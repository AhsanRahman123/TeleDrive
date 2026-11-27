package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramLimits
import com.drdisagree.teledrive.core.transfer.CountingBackupSessionTracker
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from PC 1.6.24 (field note 3, and the note-6 path wiring) to the
 * Android tree, at the repository level with the real Room DAO. Adapted only
 * where the trees diverged: this tree's TransferRepositoryImpl constructor
 * takes no storagePaths parameter (the paths are already inside
 * StorageInspector here).
 *
 *  - Selected FAILED records can be REMOVED (deleteTransfers deletes
 *    terminal rows and leaves live rows alone).
 *  - Remove-all-failed deletes exactly the FAILED rows.
 *  - Bulk retry dispatches per record type: engine-backed rows re-queue;
 *    report-only ARCHIVE_DOWNLOAD rows — which retain only a display name
 *    and size, no archive/entry identity — are counted as skipped and left
 *    FAILED, never re-queued and never silently re-failed.
 *  - A completed row's Open with path: a regular download resolves the
 *    saved path from its file row, and a completed ARCHIVE_DOWNLOAD row
 *    carries the path its reporter wrote at completion.
 */
class TransfersBulkFailedTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val paths = harness.storagePaths

    @After
    fun tearDown() {
        harness.close()
    }

    private fun repository(transferDao: TransferDao = database.transferDao()) =
        TransferRepositoryImpl(
        transferDao = transferDao,
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
        storageInspector = StorageInspector(paths),
        backupSessionTracker = CountingBackupSessionTracker(
            database.transferDao(),
            database.backupDao()
        ),
        archiveExtractionJobs = ArchiveExtractionJobs()
    )

    private suspend fun row(
        id: String,
        type: TransferType,
        state: TransferState,
        fileId: String? = null,
        localPath: String? = null
    ) {
        database.transferDao().upsert(
            TransferEntity(
                id = id,
                type = type,
                fileId = fileId,
                displayName = "$id.bin",
                localPath = localPath,
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

    @Test
    fun `deleteTransfers removes failed records and leaves live rows alone`() = runBlocking {
        row("failed-download", TransferType.DOWNLOAD, TransferState.FAILED)
        row("failed-archive", TransferType.ARCHIVE_DOWNLOAD, TransferState.FAILED)
        row("cancelled-upload", TransferType.UPLOAD, TransferState.CANCELLED)
        row("running-download", TransferType.DOWNLOAD, TransferState.RUNNING)
        row("queued-upload", TransferType.UPLOAD, TransferState.QUEUED)

        repository().deleteTransfers(
            listOf(
                "failed-download", "failed-archive", "cancelled-upload",
                "running-download", "queued-upload", "missing-id"
            )
        )

        assertNull(stateOf("failed-download"))
        assertNull(stateOf("failed-archive"))
        assertNull(stateOf("cancelled-upload"))
        assertEquals(TransferState.RUNNING, stateOf("running-download"))
        assertEquals(TransferState.QUEUED, stateOf("queued-upload"))
    }

    @Test
    fun `removeAllFailed deletes exactly the failed rows`() = runBlocking {
        row("failed-download", TransferType.DOWNLOAD, TransferState.FAILED)
        row("failed-archive", TransferType.ARCHIVE_DOWNLOAD, TransferState.FAILED)
        row("completed", TransferType.DOWNLOAD, TransferState.COMPLETED)
        row("paused", TransferType.DOWNLOAD, TransferState.PAUSED)

        val removed = repository().removeAllFailed()

        assertEquals(2, removed)
        assertNull(stateOf("failed-download"))
        assertNull(stateOf("failed-archive"))
        assertEquals(TransferState.COMPLETED, stateOf("completed"))
        assertEquals(TransferState.PAUSED, stateOf("paused"))
    }

    @Test
    fun `retryFailedTransfers requeues engine rows and skips archive rows`() = runBlocking {
        row("failed-download", TransferType.DOWNLOAD, TransferState.FAILED)
        row("failed-upload", TransferType.UPLOAD, TransferState.FAILED)
        row("failed-archive", TransferType.ARCHIVE_DOWNLOAD, TransferState.FAILED)
        row("paused-download", TransferType.DOWNLOAD, TransferState.PAUSED)

        val outcome = repository().retryFailedTransfers(
            listOf("failed-download", "failed-upload", "failed-archive", "paused-download")
        )

        assertEquals(2, outcome.requeued)
        assertEquals(1, outcome.skippedArchiveDownloads)
        assertEquals(TransferState.QUEUED, stateOf("failed-download"))
        assertEquals(TransferState.QUEUED, stateOf("failed-upload"))
        // The archive row is untouched: still FAILED with its original error,
        // not re-queued for an engine that would refuse it.
        val archiveRow = database.transferDao().byId("failed-archive")
        assertEquals(TransferState.FAILED, archiveRow?.state)
        assertEquals(TransferState.PAUSED, stateOf("paused-download"))
    }

    @Test
    fun `retryAllFailed covers every failed row in the table`() = runBlocking {
        row("failed-download", TransferType.DOWNLOAD, TransferState.FAILED)
        row("failed-archive", TransferType.ARCHIVE_DOWNLOAD, TransferState.FAILED)
        row("completed", TransferType.DOWNLOAD, TransferState.COMPLETED)

        val outcome = repository().retryAllFailed()

        assertEquals(1, outcome.requeued)
        assertEquals(1, outcome.skippedArchiveDownloads)
        assertEquals(TransferState.QUEUED, stateOf("failed-download"))
        assertEquals(TransferState.FAILED, stateOf("failed-archive"))
        assertEquals(TransferState.COMPLETED, stateOf("completed"))
    }

    /**
     * MF-3 interposing DAO: models a concurrent retry that WINS the gap
     * inside removeAllFailed — after the FAILED snapshot is read, the target
     * row is re-queued (QUEUED, as retry() would leave it) before the delete
     * runs. A remove-all that deletes from its stale snapshot removes a live
     * record and fails this test; one that re-reads each row under
     * workerControlLock before deleting leaves it alone.
     */
    private class RequeueAfterFailedSnapshotDao(
        private val delegate: TransferDao,
        private val targetId: String
    ) : TransferDao by delegate {
        override fun observeFailed(limit: Int): Flow<List<TransferEntity>> = flow {
            val snapshot = delegate.observeFailed(limit).first()
            delegate.byId(targetId)?.let { row ->
                delegate.upsert(
                    row.copy(
                        state = TransferState.QUEUED,
                        transferredBytes = 0,
                        errorMessage = null,
                        updatedAt = 2
                    )
                )
            }
            emit(snapshot)
        }
    }

    @Test
    fun `removeAllFailed does not delete a row re-queued after the failed snapshot`() =
        runBlocking {
            row("requeued-row", TransferType.DOWNLOAD, TransferState.FAILED)
            row("still-failed", TransferType.DOWNLOAD, TransferState.FAILED)

            val removed = repository(
                RequeueAfterFailedSnapshotDao(database.transferDao(), "requeued-row")
            ).removeAllFailed()

            // The retry won: the re-queued record survives, still QUEUED, and
            // the removed count covers only the row actually deleted.
            assertEquals(1, removed)
            assertEquals(TransferState.QUEUED, stateOf("requeued-row"))
            assertNull(stateOf("still-failed"))
        }

    @Test
    fun `racing retryAllFailed and removeAllFailed never delete a requeued row`() =
        runBlocking {
            // MF-3 overlap, launched as TransfersViewModel launches bulk
            // actions — separate coroutines from two quick taps. Whichever
            // order the lock serialises them in, the outcomes must partition
            // the failed rows exactly: a row is re-queued XOR removed, never
            // both (counted as requeued AND deleted while live).
            row("race-1", TransferType.DOWNLOAD, TransferState.FAILED)
            row("race-2", TransferType.UPLOAD, TransferState.FAILED)
            val repo = repository()

            val retryDeferred = async { repo.retryAllFailed() }
            val removeDeferred = async { repo.removeAllFailed() }
            val outcome = retryDeferred.await()
            val removed = removeDeferred.await()

            assertEquals(0, outcome.skippedArchiveDownloads)
            assertEquals(2, outcome.requeued + removed)
            listOf("race-1", "race-2").forEach { id ->
                val state = stateOf(id)
                assertTrue(
                    "$id must be QUEUED (retry won) or deleted (remove won), was $state",
                    state == TransferState.QUEUED || state == null
                )
            }
            // The requeue count matches the rows that actually survived queued.
            val survivedQueued = listOf("race-1", "race-2").count {
                stateOf(it) == TransferState.QUEUED
            }
            assertEquals(outcome.requeued, survivedQueued)
        }

    @Test
    fun `completed rows expose the saved path for Open with`() = runBlocking {
        // A regular download: the transfer row's own localPath is null; the
        // saved path lives on the file row and must be resolved from it.
        database.fileDao().upsert(
            FileEntity(
                id = "file-1",
                folderId = null,
                name = "movie.mp4",
                sizeBytes = 100,
                mimeType = "video/mp4",
                category = FileCategory.VIDEO,
                localPath = "/downloads/movie.mp4",
                contentHash = null,
                chatId = TEST_CHAT,
                messageId = null,
                remoteFileId = "remote-1",
                remoteUniqueId = null,
                backupState = BackupState.NONE,
                partCount = 1,
                createdAt = 1,
                modifiedAt = 1,
                addedAt = 1
            )
        )
        row("done-download", TransferType.DOWNLOAD, TransferState.COMPLETED, fileId = "file-1")
        // An archive download: fileId is null by design; the reporter wrote
        // the saved path onto the transfer row itself at completion.
        row(
            "done-archive", TransferType.ARCHIVE_DOWNLOAD, TransferState.COMPLETED,
            localPath = "/downloads/song.mp3"
        )

        val completed = repository().observeSection(TransferSection.COMPLETED, 200).first()
        val byId = completed.associateBy { it.id }

        assertEquals("/downloads/movie.mp4", byId["done-download"]?.localPath)
        assertEquals("/downloads/song.mp3", byId["done-archive"]?.localPath)
    }
}
