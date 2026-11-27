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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.25 item 1, repository half: Transfers Pause all / Cancel all must
 * write the bulk queue control in the shared [ArchiveExtractionJobs]
 * registry — the archive viewer's download queue reads it from there,
 * because queued in-ZIP requests have no Transfers row and no registered
 * job for the bulk actions to reach (the 1.6.24 field failure: Pause all
 * paused only the running file, Cancel all cancelled none of the batch).
 *
 * Pinned here with the real Room DAO and a real registry-held extraction
 * job:
 *  - pauseAll holds the queue AND pauses/stops the running archive row;
 *  - resumeAll releases the hold but leaves the paused archive row PAUSED,
 *    and resuming that row individually keeps the can't-resume contract
 *    (FAILED with the plain message);
 *  - cancelAll bumps the bulk-cancel epoch, releases any hold, and lands
 *    every archive row CANCELLED with its job stopped;
 *  - repeated presses are harmless.
 */
class ArchiveBulkQueueControlRepositoryTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val jobs = ArchiveExtractionJobs()

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
        transferScheduler = object : TransferScheduler {
            override fun kick(allowMetered: Boolean) = Unit
            override fun rekick(allowMetered: Boolean) = Unit
        },
        settingsRepository = harness.settings,
        validateUpload = ValidateUploadUseCase(),
        storageInspector = StorageInspector(harness.storagePaths),
        backupSessionTracker = CountingBackupSessionTracker(
            database.transferDao(),
            database.backupDao()
        ),
        storagePaths = harness.storagePaths,
        archiveExtractionJobs = jobs
    )

    private suspend fun archiveRow(id: String, state: TransferState) {
        database.transferDao().upsert(
            TransferEntity(
                id = id,
                type = TransferType.ARCHIVE_DOWNLOAD,
                fileId = null,
                displayName = "$id.bin",
                localPath = null,
                chatId = null,
                messageId = null,
                remoteFileId = null,
                sizeBytes = 100,
                transferredBytes = 0,
                state = state,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    private suspend fun stateOf(id: String): TransferState? =
        database.transferDao().byId(id)?.state

    /** Registers a never-finishing extraction job under [id], as the tracker does. */
    private fun runningJob(id: String): Job {
        val job = CoroutineScope(Dispatchers.Default).launch { CompletableDeferred<Unit>().await() }
        jobs.register(id, job)
        return job
    }

    @Test
    fun `pauseAll holds the archive queue and pauses the running archive row`() = runBlocking {
        archiveRow("arch-running", TransferState.RUNNING)
        val job = runningJob("arch-running")

        repository().pauseAll()

        assertEquals(TransferState.PAUSED, stateOf("arch-running"))
        assertTrue("pauseAll must stop the running extraction job", job.isCancelled)
        assertTrue("pauseAll must hold the archive download queue", jobs.queueHeld())

        // Repeated press: harmless, still held, row still PAUSED.
        repository().pauseAll()
        assertTrue(jobs.queueHeld())
        assertEquals(TransferState.PAUSED, stateOf("arch-running"))
        job.cancel()
    }

    @Test
    fun `resumeAll releases the hold and the paused archive row keeps the cant-resume contract`() =
        runBlocking {
            archiveRow("arch-running", TransferState.RUNNING)
            val job = runningJob("arch-running")
            val repository = repository()

            repository.pauseAll()
            repository.resumeAll()

            assertFalse("resumeAll must release the queue hold", jobs.queueHeld())
            assertEquals(
                "the bulk-paused archive row itself stays PAUSED",
                TransferState.PAUSED,
                stateOf("arch-running")
            )

            // Resuming THAT row individually: the established contract —
            // FAILED with the plain can't-resume message.
            repository.resume("arch-running")
            val row = database.transferDao().byId("arch-running")
            assertEquals(TransferState.FAILED, row?.state)
            assertEquals(
                "A paused archive download can't be resumed. " +
                    "Download the file again from its ZIP.",
                row?.errorMessage
            )
            job.cancel()
        }

    @Test
    fun `cancelAll bumps the epoch, releases the hold and cancels archive rows and jobs`() =
        runBlocking {
            archiveRow("arch-running", TransferState.RUNNING)
            archiveRow("arch-paused", TransferState.PAUSED)
            val job = runningJob("arch-running")
            val repository = repository()
            jobs.holdQueue()
            val epochBefore = jobs.bulkCancelEpoch()

            repository.cancelAll()

            assertTrue(
                "cancelAll must bump the bulk-cancel epoch",
                jobs.bulkCancelEpoch() > epochBefore
            )
            assertFalse("cancelAll must release a bulk-pause hold", jobs.queueHeld())
            assertEquals(TransferState.CANCELLED, stateOf("arch-running"))
            assertEquals(TransferState.CANCELLED, stateOf("arch-paused"))
            assertTrue("cancelAll must stop the running extraction job", job.isCancelled)

            // Repeated press: harmless.
            repository.cancelAll()
            assertEquals(TransferState.CANCELLED, stateOf("arch-running"))
            job.cancel()
        }
}
