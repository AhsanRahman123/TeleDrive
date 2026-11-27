package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramLimits
import com.drdisagree.teledrive.core.transfer.CountingBackupSessionTracker
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.26 P6(b): queue-level verification of the PC Transfers bulk
 * controls against the production entry point — [TransferRepositoryImpl],
 * the exact object TransfersStore's Pause all / Resume all / Cancel all
 * buttons call — with a mixed queue (QUEUED + RUNNING + PAUSED + FAILED
 * engine rows) in the real Room database.
 *
 * The Android equivalents were field-proven broken this round (cancel not
 * reaching queued rows, pause not holding the queue, resume dead, fake
 * speed). PC does NOT share that code path: its bulk actions are DAO-wide
 * state writes plus scheduler pause/resume, written for 1.6.25 item 1.
 * This harness pins, per action:
 *  - pauseAll reaches QUEUED and RUNNING rows (not only the running one),
 *    pauses the scheduler (the queue HOLD), and zeroes displayed speeds;
 *  - resumeAll re-queues every PAUSED row and resumes the scheduler;
 *  - cancelAll lands QUEUED, RUNNING, PAUSED and FAILED rows CANCELLED;
 *  - speed honesty at the write path: progress writes are state-guarded
 *    to RUNNING rows, so a paused row can never accrue a fresh speed.
 */
class TransfersBulkQueueControlRepositoryTest {

    private val harness = SyncHarness()
    private val database = harness.database

    private class RecordingScheduler : TransferScheduler {
        var kicks = 0
        var rekicks = 0
        var pauses = 0
        var resumes = 0

        override fun kick(allowMetered: Boolean) {
            kicks++
        }

        override fun rekick(allowMetered: Boolean) {
            rekicks++
        }

        override suspend fun pause() {
            pauses++
        }

        override fun resume(allowMetered: Boolean) {
            resumes++
        }
    }

    private val scheduler = RecordingScheduler()

    @After
    fun tearDown() {
        harness.close()
    }

    private fun repository() = TransferRepositoryImpl(
        transferDao = database.transferDao(),
        fileDao = database.fileDao(),
        telegramClient = object : TelegramClient by unused() {
            override suspend fun getLimits() = TelegramLimits.REGULAR
        },
        transferScheduler = scheduler,
        settingsRepository = harness.settings,
        validateUpload = ValidateUploadUseCase(),
        storageInspector = StorageInspector(harness.storagePaths),
        backupSessionTracker = CountingBackupSessionTracker(
            database.transferDao(),
            database.backupDao()
        ),
        storagePaths = harness.storagePaths,
        archiveExtractionJobs = ArchiveExtractionJobs()
    )

    private suspend fun engineRow(
        id: String,
        type: TransferType,
        state: TransferState,
        speed: Long = 0L,
        transferred: Long = 0L
    ) {
        database.transferDao().upsert(
            TransferEntity(
                id = id,
                type = type,
                fileId = null,
                displayName = "$id.bin",
                localPath = null,
                chatId = null,
                messageId = null,
                remoteFileId = null,
                sizeBytes = 100_000,
                transferredBytes = transferred,
                speedBytesPerSecond = speed,
                state = state,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    private suspend fun row(id: String) = database.transferDao().byId(id)

    private suspend fun seedMixedQueue() {
        engineRow("dl-queued", TransferType.DOWNLOAD, TransferState.QUEUED)
        engineRow(
            "dl-running", TransferType.DOWNLOAD, TransferState.RUNNING,
            speed = 5_000, transferred = 40_000
        )
        engineRow(
            "up-running", TransferType.UPLOAD, TransferState.RUNNING,
            speed = 7_500, transferred = 10_000
        )
        engineRow("up-paused", TransferType.UPLOAD, TransferState.PAUSED)
        engineRow("dl-failed", TransferType.DOWNLOAD, TransferState.FAILED)
    }

    @Test
    fun `pauseAll reaches queued and running rows, holds the scheduler and zeroes speeds`() =
        runBlocking {
            seedMixedQueue()
            repository().pauseAll()

            assertEquals(TransferState.PAUSED, row("dl-queued")?.state)
            assertEquals(TransferState.PAUSED, row("dl-running")?.state)
            assertEquals(TransferState.PAUSED, row("up-running")?.state)
            assertEquals(TransferState.PAUSED, row("up-paused")?.state)
            // A failed row is not paused — it stays failed.
            assertEquals(TransferState.FAILED, row("dl-failed")?.state)
            // The queue hold: the scheduler itself was paused, so the
            // drainer cannot start the next queued row behind the pause.
            assertEquals(1, scheduler.pauses)
            // Displayed-speed honesty: no row keeps a stale rate.
            assertEquals(0L, row("dl-running")?.speedBytesPerSecond)
            assertEquals(0L, row("up-running")?.speedBytesPerSecond)
        }

    @Test
    fun `resumeAll requeues every paused row and resumes the scheduler`() = runBlocking {
        seedMixedQueue()
        val repo = repository()
        repo.pauseAll()
        repo.resumeAll()

        assertEquals(TransferState.QUEUED, row("dl-queued")?.state)
        assertEquals(TransferState.QUEUED, row("dl-running")?.state)
        assertEquals(TransferState.QUEUED, row("up-running")?.state)
        assertEquals(TransferState.QUEUED, row("up-paused")?.state)
        assertEquals(TransferState.FAILED, row("dl-failed")?.state)
        assertTrue("resumeAll must resume the scheduler", scheduler.resumes >= 1)
        // Speeds stay zeroed until real progress is measured again.
        assertEquals(0L, row("dl-running")?.speedBytesPerSecond)
    }

    @Test
    fun `cancelAll lands queued running paused and failed rows cancelled`() = runBlocking {
        seedMixedQueue()
        repository().cancelAll()

        for (id in listOf("dl-queued", "dl-running", "up-running", "up-paused", "dl-failed")) {
            assertEquals("cancelAll must reach $id", TransferState.CANCELLED, row(id)?.state)
            assertEquals(0L, row(id)?.speedBytesPerSecond)
        }
    }

    @Test
    fun `progress writes are state-guarded so a paused row cannot accrue speed`() = runBlocking {
        seedMixedQueue()
        val repo = repository()
        repo.pauseAll()

        // The executor's only progress write path (TransferDao.updateProgress)
        // is guarded to RUNNING rows: writing to the now-PAUSED row is a no-op.
        database.transferDao().updateProgress("dl-running", 90_000, 9_999, 2)
        assertEquals(40_000L, row("dl-running")?.transferredBytes)
        assertEquals(0L, row("dl-running")?.speedBytesPerSecond)

        // The same write lands on a genuinely RUNNING row.
        engineRow(
            "dl-live", TransferType.DOWNLOAD, TransferState.RUNNING,
            speed = 0L, transferred = 0L
        )
        database.transferDao().updateProgress("dl-live", 2_000, 2_000, 3)
        assertEquals(2_000L, row("dl-live")?.transferredBytes)
        assertEquals(2_000L, row("dl-live")?.speedBytesPerSecond)
    }
}
