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

        /**
         * Mirrors DesktopTransferScheduler's latch exactly: pause() sets
         * globallyPaused, resume() clears it, and kick() returns early while
         * it is set (vc184 MF-1's isPauseHeld seam).
         */
        var pauseHeld = false

        /** Simulated drain claim; kick/resume reach it only when not held. */
        var onDrain: (() -> Unit)? = null

        override fun kick(allowMetered: Boolean) {
            kicks++
            if (!pauseHeld) onDrain?.invoke()
        }

        override fun rekick(allowMetered: Boolean) {
            rekicks++
            if (!pauseHeld) onDrain?.invoke()
        }

        override suspend fun pause() {
            pauses++
            pauseHeld = true
        }

        override fun resume(allowMetered: Boolean) {
            resumes++
            pauseHeld = false
            onDrain?.invoke()
        }

        override fun isPauseHeld(): Boolean = pauseHeld
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

    private suspend fun downloadableFile(id: String) {
        database.fileDao().upsert(
            com.drdisagree.teledrive.data.local.entity.FileEntity(
                id = id,
                folderId = null,
                name = "$id.bin",
                sizeBytes = 100_000,
                mimeType = "application/octet-stream",
                category = com.drdisagree.teledrive.domain.model.FileCategory.OTHER,
                localPath = null,
                contentHash = null,
                chatId = 100L,
                messageId = 200L,
                remoteFileId = "remote-$id",
                remoteUniqueId = "unique-$id",
                backupState = com.drdisagree.teledrive.domain.model.BackupState.BACKED_UP,
                createdAt = 1,
                modifiedAt = 1,
                addedAt = 1
            )
        )
    }

    private suspend fun downloadRow(id: String, fileId: String, state: TransferState) {
        database.transferDao().upsert(
            TransferEntity(
                id = id,
                type = TransferType.DOWNLOAD,
                fileId = fileId,
                displayName = "$id.bin",
                localPath = null,
                chatId = 100L,
                messageId = 200L,
                remoteFileId = "remote-$fileId",
                sizeBytes = 100_000,
                transferredBytes = 0,
                speedBytesPerSecond = 0,
                state = state,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    /**
     * vc184 MF-1 combined (desktop port of the Android TransferQueueControlsTest
     * case): Pause all, then a Download tap that dedupes onto a PAUSED row.
     * Before MF-1 the repair called resume(id), whose scheduler.resume() is
     * the GLOBAL latch release on desktop (it clears globallyPaused), so the
     * hold would silently drop and the whole queue would restart. The drain
     * claim itself is simulated (onDrain completes QUEUED rows); the
     * repository, the DAO and the latch semantics under test are production.
     */
    @Test
    fun `enqueue onto a paused row during pause all keeps the hold and drains only on resume all`() =
        runBlocking {
            downloadableFile("f-target")
            downloadableFile("f-other")
            downloadRow("dl-target", "f-target", TransferState.QUEUED)
            downloadRow("dl-other", "f-other", TransferState.QUEUED)
            val repo = repository()

            // The simulated drain: claiming completes every QUEUED row.
            val drained = mutableListOf<String>()
            scheduler.onDrain = {
                runBlocking {
                    for (id in listOf("dl-target", "dl-other")) {
                        if (row(id)?.state == TransferState.QUEUED) {
                            database.transferDao()
                                .setState(id, TransferState.COMPLETED, 9)
                            drained += id
                        }
                    }
                    database.transferDao().unfinishedIdsForFiles(listOf("f-fresh"))
                        .firstOrNull()?.let { freshId ->
                            if (row(freshId)?.state == TransferState.QUEUED) {
                                database.transferDao()
                                    .setState(freshId, TransferState.COMPLETED, 9)
                                drained += freshId
                            }
                        }
                }
            }

            repo.pauseAll()
            assertTrue("pauseAll must latch the scheduler hold", scheduler.pauseHeld)
            assertEquals(TransferState.PAUSED, row("dl-target")?.state)
            assertEquals(TransferState.PAUSED, row("dl-other")?.state)

            // A fresh download enqueued during the hold waits QUEUED.
            downloadableFile("f-fresh")
            val fresh = repo.enqueueDownload("f-fresh")
            assertTrue(fresh is com.drdisagree.teledrive.core.common.AppResult.Success)
            val freshId = (fresh as com.drdisagree.teledrive.core.common.AppResult.Success).value
            assertEquals(TransferState.QUEUED, row(freshId)?.state)

            // The A3 dedupe repair on the PAUSED target row, during the hold.
            val repaired = repo.enqueueDownload("f-target")
            assertEquals(
                com.drdisagree.teledrive.core.common.AppResult.Success("dl-target"),
                repaired
            )

            // The repair still happened: the target row is runnable again...
            assertEquals(TransferState.QUEUED, row("dl-target")?.state)
            // ...but the hold is intact, the global resume was never reached,
            // and nothing started: not the target, not the other rows.
            assertTrue("pause hold must survive the enqueue repair", scheduler.pauseHeld)
            assertTrue("scheduler must still report the hold", scheduler.isPauseHeld())
            assertEquals(0, scheduler.resumes)
            assertTrue("the held repair must kick (dropped while held)", scheduler.kicks >= 1)
            assertTrue("nothing may drain during the hold: $drained", drained.isEmpty())
            assertEquals(TransferState.QUEUED, row("dl-target")?.state)
            assertEquals(TransferState.PAUSED, row("dl-other")?.state)
            assertEquals(TransferState.QUEUED, row(freshId)?.state)

            // Resume all releases the hold and the whole queue drains.
            repo.resumeAll()
            assertTrue("resumeAll must release the hold", !scheduler.pauseHeld)
            assertTrue(scheduler.resumes >= 1)
            assertEquals(TransferState.COMPLETED, row("dl-target")?.state)
            assertEquals(TransferState.COMPLETED, row("dl-other")?.state)
            assertEquals(TransferState.COMPLETED, row(freshId)?.state)
        }
}
