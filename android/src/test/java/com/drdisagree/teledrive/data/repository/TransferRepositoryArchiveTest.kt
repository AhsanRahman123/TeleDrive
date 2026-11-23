package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.archive.ArchiveDownloadTracker
import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.transfer.BackupSessionTracker
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.FakeTransferDao
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc179: TransferRepositoryImpl's report-only branching for ARCHIVE_DOWNLOAD rows.
 *
 * Native-interaction tripwires: TelegramClient is an [unused] proxy (any call
 * throws), FileDao is a lenient hand fake that RECORDS every call, the scheduler
 * and backup-session tracker count their invocations — so a test fails if an
 * archive branch touches any machinery it must not own.
 */
class TransferRepositoryArchiveTest {

    private class FakeSettings(initial: UserPreferences) : SettingsRepository by unused() {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    /** Lenient FileDao: syncFileStates' repair/clear/delete calls are no-ops returning 0. */
    private class LenientFileDao : FileDao by unused() {
        val calls = mutableListOf<String>()
        override suspend fun byId(id: String): FileEntity? {
            calls += "byId"
            return null
        }

        override suspend fun byIds(ids: List<String>): List<FileEntity> {
            calls += "byIds"
            return emptyList()
        }

        override suspend fun setBackupStateIfLocalOnly(id: String, state: BackupState) {
            calls += "setBackupStateIfLocalOnly"
        }

        override suspend fun repairBackedUpStates(): Int {
            calls += "repairBackedUpStates"
            return 0
        }

        override suspend fun clearStaleQueuedStates(): Int {
            calls += "clearStaleQueuedStates"
            return 0
        }

        override suspend fun clearStaleFailedStates(): Int {
            calls += "clearStaleFailedStates"
            return 0
        }

        override suspend fun deleteCancelledBackupEntries(): Int {
            calls += "deleteCancelledBackupEntries"
            return 0
        }
    }

    private class RecordingScheduler : TransferScheduler {
        var kicks = 0
        var rekicks = 0
        var pauses = 0
        var resumes = 0
        override fun kick(allowMetered: Boolean) { kicks++ }
        override fun rekick(allowMetered: Boolean) { rekicks++ }
        override suspend fun pause() { pauses++ }
        override fun resume(allowMetered: Boolean) { resumes++ }
        fun totalCalls() = kicks + rekicks + pauses + resumes
    }

    private class CountingSessionTracker : BackupSessionTracker {
        var calls = 0
        override suspend fun refreshActive() { calls++ }
        override suspend fun refresh(sessionId: String?) { calls++ }
    }

    private val dao = FakeTransferDao()
    private val fileDao = LenientFileDao()
    private val scheduler = RecordingScheduler()
    private val sessionTracker = CountingSessionTracker()
    private val jobs = ArchiveExtractionJobs()
    private val repository = TransferRepositoryImpl(
        transferDao = dao,
        fileDao = fileDao,
        telegramClient = unused<TelegramClient>(),
        transferScheduler = scheduler,
        settingsRepository = FakeSettings(UserPreferences()),
        validateUpload = ValidateUploadUseCase(),
        storageInspector = StorageInspector(unused<AppStoragePaths>()),
        backupSessionTracker = sessionTracker,
        archiveExtractionJobs = jobs
    )

    private suspend fun insert(
        id: String,
        type: TransferType,
        state: TransferState,
        error: String? = null
    ) {
        dao.upsert(
            com.drdisagree.teledrive.data.local.entity.TransferEntity(
                id = id,
                type = type,
                fileId = null,
                displayName = id,
                localPath = null,
                chatId = null,
                messageId = null,
                remoteFileId = null,
                sizeBytes = 100,
                state = state,
                errorMessage = error,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    private fun extractionJob(): Job =
        CoroutineScope(Dispatchers.Default).launch { delay(60_000) }

    @Test
    fun `pause on a running archive record pauses it, cancels its job, touches nothing native`() =
        runBlocking {
            insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
            val job = extractionJob()
            jobs.register("arc", job)

            repository.pause("arc")

            assertEquals(TransferState.PAUSED, dao.byId("arc")!!.state)
            assertTrue(job.isCancelled)
            assertTrue("fileDao touched: ${fileDao.calls}", fileDao.calls.isEmpty())
            assertEquals(0, scheduler.totalCalls())
            assertEquals(0, sessionTracker.calls)
        }

    @Test
    fun `cancel on a running archive record cancels it and its job`() = runBlocking {
        insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
        val job = extractionJob()
        jobs.register("arc", job)

        repository.cancel("arc")

        assertEquals(TransferState.CANCELLED, dao.byId("arc")!!.state)
        assertTrue(job.isCancelled)
        assertTrue("fileDao touched: ${fileDao.calls}", fileDao.calls.isEmpty())
        assertEquals(0, sessionTracker.calls)
    }

    @Test
    fun `cancel on a completed archive record deletes the history row`() = runBlocking {
        insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.COMPLETED)
        repository.cancel("arc")
        assertNull(dao.byId("arc"))
    }

    @Test
    fun `resume on a paused archive record fails it with the cant-resume message, never queued`() =
        runBlocking {
            insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.PAUSED)

            repository.resume("arc")

            val row = dao.byId("arc")!!
            assertEquals(TransferState.FAILED, row.state)
            assertEquals(
                "A paused archive download can't be resumed. Download the file again from its ZIP.",
                row.errorMessage
            )
            assertEquals(0, scheduler.totalCalls())
        }

    @Test
    fun `resume on a failed archive record is a no-op`() = runBlocking {
        insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.FAILED, error = "boom")
        repository.resume("arc")
        val row = dao.byId("arc")!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals("boom", row.errorMessage)
    }

    @Test
    fun `retry on a failed archive record leaves it unchanged`() = runBlocking {
        insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.FAILED, error = "boom")
        repository.retry("arc")
        val row = dao.byId("arc")!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals("boom", row.errorMessage)
        assertEquals(0, scheduler.totalCalls())
    }

    @Test
    fun `recoverOrphanedTransfers fails running archive rows and requeues running downloads`() =
        runBlocking {
            insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
            insert("dl", TransferType.DOWNLOAD, TransferState.RUNNING)

            repository.recoverOrphanedTransfers()

            val archive = dao.byId("arc")!!
            assertEquals(TransferState.FAILED, archive.state)
            assertEquals(
                "Interrupted — the app stopped while this file was being extracted from its ZIP.",
                archive.errorMessage
            )
            assertEquals(TransferState.QUEUED, dao.byId("dl")!!.state)
        }

    @Test
    fun `pauseAll pauses a running archive record and cancels its job`() = runBlocking {
        insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
        val job = extractionJob()
        jobs.register("arc", job)

        repository.pauseAll()

        assertEquals(TransferState.PAUSED, dao.byId("arc")!!.state)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `cancelAll cancels a running archive record and cancels its job`() = runBlocking {
        insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
        val job = extractionJob()
        jobs.register("arc", job)

        repository.cancelAll()

        assertEquals(TransferState.CANCELLED, dao.byId("arc")!!.state)
        assertTrue(job.isCancelled)
    }

    @Test
    fun `setPriority on a running archive record leaves priority and state unchanged`() =
        runBlocking {
            insert("arc", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
            val before = dao.byId("arc")!!

            repository.setPriority("arc", 99)

            val row = dao.byId("arc")!!
            assertEquals(before.priority, row.priority)
            assertEquals(TransferState.RUNNING, row.state)
        }

    @Test
    fun `archive records appear in the Transfers sections the view model observes`() =
        runBlocking {
            val tracker = ArchiveDownloadTracker(dao, jobs)
            val id = tracker.begin("from-zip.txt", 42L)!!

            val active = repository.observeSection(TransferSection.ACTIVE, 100).first()
            assertTrue(active.any { it.id == id && it.displayName == "from-zip.txt" })

            tracker.complete(id, "/Downloads/TeleDrive/from-zip.txt")

            val completed = repository.observeSection(TransferSection.COMPLETED, 100).first()
            val task = completed.firstOrNull { it.id == id }
            assertTrue(task != null)
            assertEquals("from-zip.txt", task!!.displayName)
            assertEquals("/Downloads/TeleDrive/from-zip.txt", task.localPath)
        }
}
