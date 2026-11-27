package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.transfer.BackupSessionTracker
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.FakeTransferDao
import com.drdisagree.teledrive.testing.unused
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * vc184 A3: a download request that dedupes onto an existing unfinished row
 * must leave a GENUINELY RUNNABLE transfer behind. Before the fix,
 * [TransferRepositoryImpl.enqueueDownload] reported the existing row as
 * "queued" success without touching it — a PAUSED row sat paused forever
 * while the caller (e.g. the video viewer's Download button) believed a
 * background download was running, and a QUEUED row whose worker had already
 * exited was never kicked again. These tests drive the production entry
 * point against the shared fake DAO and a recording scheduler.
 */
class EnqueueDownloadRepairTest {

    @get:Rule
    val folder = TemporaryFolder()

    private class FakeSettings(initial: UserPreferences) : SettingsRepository by unused() {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    /** Serves the one downloadable file; every other FileDao call is a tripwire. */
    private class OneFileDao(private val entity: FileEntity) : FileDao by unused() {
        override suspend fun byId(id: String): FileEntity? = entity.takeIf { it.id == id }
        override suspend fun setBackupStateIfLocalOnly(id: String, state: BackupState) = Unit
        override suspend fun repairBackedUpStates(): Int = 0
        override suspend fun clearStaleQueuedStates(): Int = 0
        override suspend fun clearStaleFailedStates(): Int = 0
        override suspend fun deleteCancelledBackupEntries(): Int = 0
    }

    private class RecordingScheduler : TransferScheduler {
        var kicks = 0
        var resumes = 0

        /** Mirrors the production schedulers' latch: resume() clears it. */
        var pauseHeld = false
        override fun kick(allowMetered: Boolean) { kicks++ }
        override fun rekick(allowMetered: Boolean) { resumes++ }
        override suspend fun pause() { pauseHeld = true }
        override fun resume(allowMetered: Boolean) { resumes++; pauseHeld = false }
        override fun isPauseHeld(): Boolean = pauseHeld
    }

    private class CountingSessionTracker : BackupSessionTracker {
        var calls = 0
        override suspend fun refreshActive() { calls++ }
        override suspend fun refresh(sessionId: String?) { calls++ }
    }

    private val dao = FakeTransferDao()
    private val scheduler = RecordingScheduler()

    private fun fileEntity() = FileEntity(
        id = "file-1",
        folderId = null,
        name = "movie.mp4",
        sizeBytes = 100,
        mimeType = "video/mp4",
        category = FileCategory.VIDEO,
        localPath = null,
        contentHash = null,
        chatId = 100L,
        messageId = 200L,
        remoteFileId = "remote-1",
        remoteUniqueId = "unique-1",
        backupState = BackupState.BACKED_UP,
        createdAt = 1,
        modifiedAt = 1,
        addedAt = 1
    )

    private fun repository(): TransferRepositoryImpl {
        val root = folder.newFolder()
        val paths = object : AppStoragePaths {
            override val filesDir: File = root
            override val cacheDir: File = root
            override val externalCacheDir: File? = null
            override val externalStorageRoot: File? = null
        }
        return TransferRepositoryImpl(
            transferDao = dao,
            fileDao = OneFileDao(fileEntity()),
            telegramClient = unused<TelegramClient>(),
            transferScheduler = scheduler,
            settingsRepository = FakeSettings(UserPreferences()),
            validateUpload = ValidateUploadUseCase(),
            storageInspector = StorageInspector(paths),
            backupSessionTracker = CountingSessionTracker(),
            storagePaths = paths,
            archiveExtractionJobs = ArchiveExtractionJobs()
        )
    }

    private suspend fun insertExisting(state: TransferState) {
        dao.upsert(
            TransferEntity(
                id = "existing",
                type = TransferType.DOWNLOAD,
                fileId = "file-1",
                displayName = "movie.mp4",
                localPath = null,
                chatId = 100L,
                messageId = null,
                remoteFileId = null,
                sizeBytes = 100,
                state = state,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    @Test
    fun `a paused existing download is resumed, not just reported as queued`() = runBlocking {
        insertExisting(TransferState.PAUSED)
        val repository = repository()

        val result = repository.enqueueDownload("file-1")

        assertEquals(AppResult.Success("existing"), result)
        assertEquals(TransferState.QUEUED, dao.byId("existing")!!.state)
        assertTrue("scheduler must be resumed for the revived row", scheduler.resumes >= 1)
    }

    @Test
    fun `a paused existing download during the pause hold is re-queued without releasing the hold`() =
        runBlocking {
            insertExisting(TransferState.PAUSED)
            scheduler.pauseHeld = true
            val repository = repository()

            val result = repository.enqueueDownload("file-1")

            assertEquals(AppResult.Success("existing"), result)
            // The A3 repair still happened: the row is runnable again...
            assertEquals(TransferState.QUEUED, dao.byId("existing")!!.state)
            // ...but the global resume was never reached, so the Pause-all
            // hold (MF-1) is untouched; the wake is a kick, which production
            // schedulers drop while held.
            assertEquals(0, scheduler.resumes)
            assertTrue("re-queue must kick (dropped while held in production)", scheduler.kicks >= 1)
            assertTrue(scheduler.pauseHeld)
        }

    @Test
    fun `a queued existing download re-kicks the worker`() = runBlocking {
        insertExisting(TransferState.QUEUED)
        val repository = repository()

        val result = repository.enqueueDownload("file-1")

        assertEquals(AppResult.Success("existing"), result)
        assertEquals(TransferState.QUEUED, dao.byId("existing")!!.state)
        assertTrue("worker must be kicked again for the waiting row", scheduler.kicks >= 1)
    }

    @Test
    fun `a running existing download is left alone`() = runBlocking {
        insertExisting(TransferState.RUNNING)
        val repository = repository()

        val result = repository.enqueueDownload("file-1")

        assertEquals(AppResult.Success("existing"), result)
        assertEquals(TransferState.RUNNING, dao.byId("existing")!!.state)
        assertEquals(0, scheduler.kicks)
        assertEquals(0, scheduler.resumes)
    }

    @Test
    fun `a fresh download inserts a queued row and kicks the worker`() = runBlocking {
        val repository = repository()

        val result = repository.enqueueDownload("file-1")

        assertTrue(result is AppResult.Success)
        val id = (result as AppResult.Success).value
        val row = dao.byId(id)!!
        assertEquals(TransferType.DOWNLOAD, row.type)
        assertEquals(TransferState.QUEUED, row.state)
        assertEquals("file-1", row.fileId)
        assertTrue("fresh insert must kick the worker", scheduler.kicks >= 1)
    }
}
