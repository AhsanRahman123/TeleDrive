package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.transfer.BackupSessionTracker
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.repository.TransferRepositoryImpl
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.FakeTransferDao
import com.drdisagree.teledrive.testing.unused
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc183 (ITEM 1): the Transfers stop path for in-ZIP downloads, end to end —
 * repository → [ArchiveExtractionJobs] registry → extraction coroutine →
 * [ArchiveDownloadTracker] terminal writes.
 *
 * Two pre-fix defects are pinned here (both field-proven on the PC line in
 * 1.6.22/1.6.23 against this same shared code, and reproduced in a standalone
 * harness before this fix):
 *
 *  - The tracker's terminal writes were plain suspend calls that rethrew
 *    CancellationException. Once the extraction coroutine had been cancelled —
 *    by a Transfers stop or by teardown — the DAO call inside
 *    `fail`/`ownerStopped` threw BEFORE writing, leaving the record RUNNING
 *    forever ("No recent progress"): the stop appeared to do nothing.
 *    [SuspendingTransferDao] models Room's real behaviour (every DAO call
 *    genuinely suspends on Room's executor), which is what makes the abort
 *    deterministic in a host test.
 *  - A stop nobody asked for — the byte source's internal withTimeout firing
 *    (a [TimeoutCancellationException]) — was reported as a user stop
 *    (CANCELLED). It is a delivery failure and must land FAILED.
 */
class ArchiveCancelStopTest {

    private class FakeSettings(initial: UserPreferences) : SettingsRepository by unused() {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private class LenientFileDao : FileDao by unused() {
        override suspend fun byId(id: String): FileEntity? = null
        override suspend fun byIds(ids: List<String>): List<FileEntity> = emptyList()
        override suspend fun setBackupStateIfLocalOnly(id: String, state: BackupState) = Unit
        override suspend fun repairBackedUpStates(): Int = 0
        override suspend fun clearStaleQueuedStates(): Int = 0
        override suspend fun clearStaleFailedStates(): Int = 0
        override suspend fun deleteCancelledBackupEntries(): Int = 0
    }

    private class NoopScheduler : TransferScheduler {
        override fun kick(allowMetered: Boolean) = Unit
        override fun rekick(allowMetered: Boolean) = Unit
        override suspend fun pause() = Unit
        override fun resume(allowMetered: Boolean) = Unit
    }

    private class NoopSessionTracker : BackupSessionTracker {
        override suspend fun refreshActive() = Unit
        override suspend fun refresh(sessionId: String?) = Unit
    }

    /**
     * FakeTransferDao whose writes/reads genuinely suspend (a [yield] first),
     * like Room's executor hop. Without a real suspension point, a call made
     * from an already-cancelled coroutine would not throw and the pre-fix
     * terminal-write abort could not be observed in a host test.
     */
    private class SuspendingTransferDao(
        private val delegate: FakeTransferDao
    ) : TransferDao by delegate {
        override suspend fun byId(id: String): TransferEntity? {
            yield()
            return delegate.byId(id)
        }

        override suspend fun setState(id: String, state: TransferState, updatedAt: Long) {
            yield()
            delegate.setState(id, state, updatedAt)
        }

        override suspend fun setFailed(
            id: String,
            state: TransferState,
            error: String?,
            updatedAt: Long
        ) {
            yield()
            delegate.setFailed(id, state, error, updatedAt)
        }

        override suspend fun setCompleted(
            id: String,
            completedAt: Long,
            completed: TransferState
        ) {
            yield()
            delegate.setCompleted(id, completedAt, completed)
        }

        override suspend fun setLocalPath(id: String, localPath: String?) {
            yield()
            delegate.setLocalPath(id, localPath)
        }

        override suspend fun setCompletedIfRunning(
            id: String,
            localPath: String?,
            completedAt: Long,
            completed: TransferState
        ): Int {
            yield()
            return delegate.setCompletedIfRunning(id, localPath, completedAt, completed)
        }

        override suspend fun setFailedIfRunning(
            id: String,
            state: TransferState,
            error: String?,
            updatedAt: Long
        ): Int {
            yield()
            return delegate.setFailedIfRunning(id, state, error, updatedAt)
        }

        override suspend fun setStateIfRunning(
            id: String,
            state: TransferState,
            updatedAt: Long
        ): Int {
            yield()
            return delegate.setStateIfRunning(id, state, updatedAt)
        }
    }

    /**
     * MF-2 (second pass) interposing DAO: once armed for a record, a
     * repository-style PAUSED write lands at the worst possible moment for
     * the tracker — immediately after the tracker's own read of the row
     * (the pre-fix read-then-write interleave), or, if the tracker never
     * reads, immediately before its conditional terminal write. Either way
     * the PAUSED is in place before the tracker's terminal state is applied,
     * so a tracker that overwrites it fails the test.
     */
    private class PauseInterposingDao(
        private val delegate: FakeTransferDao
    ) : TransferDao by delegate {
        var pauseTargetId: String? = null
        private var pauseLanded = false

        private suspend fun landPause(id: String) {
            if (id == pauseTargetId && !pauseLanded) {
                pauseLanded = true
                delegate.setState(id, TransferState.PAUSED, 999L)
            }
        }

        override suspend fun byId(id: String): TransferEntity? {
            yield()
            val row = delegate.byId(id)
            if (row?.state == TransferState.RUNNING) landPause(id)
            return row
        }

        override suspend fun setCompletedIfRunning(
            id: String,
            localPath: String?,
            completedAt: Long,
            completed: TransferState
        ): Int {
            yield()
            landPause(id)
            return delegate.setCompletedIfRunning(id, localPath, completedAt, completed)
        }

        override suspend fun setFailedIfRunning(
            id: String,
            state: TransferState,
            error: String?,
            updatedAt: Long
        ): Int {
            yield()
            landPause(id)
            return delegate.setFailedIfRunning(id, state, error, updatedAt)
        }

        override suspend fun setStateIfRunning(
            id: String,
            state: TransferState,
            updatedAt: Long
        ): Int {
            yield()
            landPause(id)
            return delegate.setStateIfRunning(id, state, updatedAt)
        }
    }

    private val fakeDao = FakeTransferDao()
    private val dao = SuspendingTransferDao(fakeDao)
    private val jobs = ArchiveExtractionJobs()
    private val tracker = ArchiveDownloadTracker(dao, jobs)
    private val repository = TransferRepositoryImpl(
        transferDao = dao,
        fileDao = LenientFileDao(),
        telegramClient = unused<TelegramClient>(),
        transferScheduler = NoopScheduler(),
        settingsRepository = FakeSettings(UserPreferences()),
        validateUpload = ValidateUploadUseCase(),
        storageInspector = StorageInspector(unused<AppStoragePaths>()),
        backupSessionTracker = NoopSessionTracker(),
        archiveExtractionJobs = jobs
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * An extraction coroutine shaped like PreviewViewModel's: work that only
     * ends by cancellation, with the tracker told about the stop from the
     * coroutine's own catch block (the only place that knows it stopped).
     */
    private fun extraction(recordId: String, chunks: AtomicInteger? = null) = scope.async {
        try {
            if (chunks == null) {
                delay(60_000)
            } else {
                repeat(10_000) {
                    delay(20)
                    chunks.incrementAndGet()
                }
            }
        } catch (failure: CancellationException) {
            tracker.ownerStopped(recordId, failure)
            throw failure
        }
    }

    @Test
    fun `cancel stops an actively progressing extraction and lands CANCELLED`() = runBlocking {
        val chunks = AtomicInteger(0)
        val id = tracker.begin("song.mp3", 100L)!!
        val job = extraction(id, chunks)
        tracker.attach(id, job)
        delay(150)
        assertTrue(chunks.get() > 0)

        repository.cancel(id)
        job.join()

        assertEquals(TransferState.CANCELLED, fakeDao.byId(id)!!.state)
        val stoppedAt = chunks.get()
        delay(200)
        assertEquals("extraction kept producing after cancel", stoppedAt, chunks.get())
    }

    @Test
    fun `repeated cancel on an in-flight archive download is harmless`() = runBlocking {
        val id = tracker.begin("song.mp3", 100L)!!
        val job = extraction(id)
        tracker.attach(id, job)
        delay(100)

        // The user mashing Cancel: several taps in flight at once, then more
        // taps after the record has landed (terminal -> history delete ->
        // absent). None of them may throw, whatever order they land in.
        (1..5).map { async { repository.cancel(id) } }.awaitAll()
        job.join()
        repository.cancel(id)
        repository.cancel(id)

        assertTrue(job.isCancelled)
        val row = fakeDao.byId(id)
        assertTrue(
            "row should be CANCELLED or deleted, was ${row?.state}",
            row == null || row.state == TransferState.CANCELLED
        )
    }

    @Test
    fun `pause stops an in-flight archive extraction and the record stays PAUSED`() =
        runBlocking {
            val id = tracker.begin("song.mp3", 100L)!!
            val job = extraction(id)
            tracker.attach(id, job)
            delay(100)

            repository.pause(id)
            job.join()

            assertTrue(job.isCancelled)
            // The extraction's ownerStopped must not overwrite the PAUSED the
            // repository wrote (its terminal writes stay RUNNING-guarded).
            assertEquals(TransferState.PAUSED, fakeDao.byId(id)!!.state)
        }

    @Test
    fun `cancelAll stops an in-flight archive extraction and lands CANCELLED`() = runBlocking {
        val id = tracker.begin("song.mp3", 100L)!!
        val job = extraction(id)
        tracker.attach(id, job)
        delay(100)

        repository.cancelAll()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(TransferState.CANCELLED, fakeDao.byId(id)!!.state)
    }

    @Test
    fun `tracker fail write lands even when the extraction coroutine is already cancelled`() =
        runBlocking {
            val id = tracker.begin("song.mp3", 100L)!!
            // Teardown-style stop: the extraction coroutine is cancelled by its
            // owner, NOT through the registry or the repository, so the
            // tracker's write from the catch block is the only terminal write.
            val job = scope.async {
                try {
                    delay(60_000)
                } catch (failure: CancellationException) {
                    tracker.fail(id, "boom")
                    throw failure
                }
            }
            delay(100)
            job.cancel()
            runCatching { job.await() }

            val row = fakeDao.byId(id)!!
            assertEquals(TransferState.FAILED, row.state)
            assertEquals("boom", row.errorMessage)
        }

    @Test
    fun `tracker ownerStopped write lands even when the extraction coroutine is already cancelled`() =
        runBlocking {
            val id = tracker.begin("song.mp3", 100L)!!
            val job = extraction(id)
            delay(100)
            job.cancel()
            runCatching { job.await() }

            assertEquals(TransferState.CANCELLED, fakeDao.byId(id)!!.state)
        }

    @Test
    fun `registry stop lands CANCELLED through ownerStopped and consumes the mark`() =
        runBlocking {
            val id = tracker.begin("song.mp3", 100L)!!
            val job = extraction(id)
            tracker.attach(id, job)
            delay(100)

            // Only the registry stop (no repository state write first): the
            // record is still RUNNING when the extraction reports its stop, so
            // ownerStopped itself must land it CANCELLED.
            assertTrue(jobs.cancel(id))
            job.join()

            assertEquals(TransferState.CANCELLED, fakeDao.byId(id)!!.state)
            assertFalse(jobs.wasCancelledViaRegistry(id))
        }

    @Test
    fun `internal timeout lands FAILED with the timeout message, never CANCELLED`() =
        runBlocking {
            val id = tracker.begin("song.mp3", 100L)!!
            // The byte source's own download-window deadline fires: a
            // TimeoutCancellationException nobody asked for is a failure.
            val job = scope.async {
                try {
                    withTimeout(60) { delay(60_000) }
                } catch (failure: TimeoutCancellationException) {
                    tracker.ownerStopped(id, failure)
                    throw failure
                }
            }
            runCatching { job.await() }

            val row = fakeDao.byId(id)!!
            assertEquals(TransferState.FAILED, row.state)
            assertEquals(
                "Telegram did not deliver the file in time. Try again.",
                row.errorMessage
            )
        }

    @Test
    fun `pause landing between tracker read and timeout write leaves the row PAUSED`() =
        runBlocking {
            // MF-2 (second pass): the internal-timeout branch of ownerStopped
            // races a concurrent Pause tap. The interposing DAO lands the
            // repository's PAUSED after the tracker's read (pre-fix shape) or
            // just before its conditional write (post-fix shape); the FAILED
            // must NOT overwrite it and the pause must not appear ignored.
            val interposingFake = FakeTransferDao()
            val interposingDao = PauseInterposingDao(interposingFake)
            val interposingTracker = ArchiveDownloadTracker(
                interposingDao, ArchiveExtractionJobs()
            )
            val id = interposingTracker.begin("song.mp3", 100L)!!
            interposingDao.pauseTargetId = id

            val job = scope.async {
                try {
                    withTimeout(50) { delay(60_000) }
                } catch (failure: TimeoutCancellationException) {
                    interposingTracker.ownerStopped(id, failure)
                    throw failure
                }
            }
            runCatching { job.await() }

            assertEquals(TransferState.PAUSED, interposingFake.byId(id)!!.state)
        }

    @Test
    fun `pause landing just before tracker complete leaves the row PAUSED with no local path`() =
        runBlocking {
            // MF-2, complete() axis: the same interleave must not let a
            // COMPLETED (with its saved path) overwrite a just-written PAUSED.
            val interposingFake = FakeTransferDao()
            val interposingDao = PauseInterposingDao(interposingFake)
            val interposingTracker = ArchiveDownloadTracker(
                interposingDao, ArchiveExtractionJobs()
            )
            val id = interposingTracker.begin("song.mp3", 100L)!!
            interposingDao.pauseTargetId = id

            interposingTracker.complete(id, "/Downloads/song.mp3")

            val row = interposingFake.byId(id)!!
            assertEquals(TransferState.PAUSED, row.state)
            assertEquals(null, row.localPath)
        }

    @Test
    fun `tracker complete write lands from an already cancelled coroutine`() =
        runBlocking {
            // MF-2 must not reopen the original vc183 defect: the conditional
            // terminal write still runs under NonCancellable, so a write
            // issued from a cancelled extraction coroutine (through the
            // genuinely-suspending DAO) still lands.
            val id = tracker.begin("song.mp3", 100L)!!
            val job = scope.async {
                try {
                    delay(60_000)
                } catch (failure: CancellationException) {
                    tracker.complete(id, "/Downloads/song.mp3")
                    throw failure
                }
            }
            delay(100)
            job.cancel()
            runCatching { job.await() }

            val row = fakeDao.byId(id)!!
            assertEquals(TransferState.COMPLETED, row.state)
            assertEquals("/Downloads/song.mp3", row.localPath)
        }

    @Test
    fun `cancel on a terminal or absent archive record never throws`() = runBlocking {
        val id = tracker.begin("song.mp3", 100L)!!
        tracker.complete(id, "/Downloads/song.mp3")
        assertEquals(TransferState.COMPLETED, fakeDao.byId(id)!!.state)

        repository.cancel(id) // terminal: history delete, as for any record
        assertEquals(null, fakeDao.byId(id))
        repository.cancel(id) // absent now: a no-op
        repository.cancel("never-existed")
        repository.pause("never-existed")
        Unit
    }
}
