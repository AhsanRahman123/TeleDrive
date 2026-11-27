package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.ArchiveDownloadTracker
import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 1.6.25 item 1, queue half: Transfers Cancel all / Pause all must reach the
 * PreviewViewModel's in-ZIP download queue. On 1.6.24 the bulk actions only
 * stopped the one registered extraction job, and the queue treated that as a
 * per-record stop and started the next queued request — Pause all paused one
 * file and the next started anyway; a Download-selected batch ran to
 * completion after Cancel all.
 *
 * The bulk actions are simulated here EXACTLY as TransferRepositoryImpl now
 * performs their archive parts (same shared ArchiveExtractionJobs registry,
 * same order — see ArchiveBulkQueueControlRepositoryTest for the repository
 * half against the real DAO):
 *  - Cancel all: jobs.requestBulkCancel(), archive rows -> CANCELLED,
 *    active jobs cancelled.
 *  - Pause all: jobs.holdQueue(), running row -> PAUSED, its job cancelled.
 *  - Resume all: jobs.releaseQueue().
 * A per-record cancel (row -> CANCELLED + only its job cancelled, no bulk
 * signal) must keep its old semantics: only that step is skipped.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveBulkControlTest {

    @get:Rule
    val rule = createComposeRule()

    private val entryData = listOf(
        "one.bin" to ByteArray(64) { 1 },
        "two.bin" to ByteArray(64) { 2 },
        "three.bin" to ByteArray(64) { 3 }
    )

    private class GatingDownloadWriter : DownloadWriter {
        val writtenNames = CopyOnWriteArrayList<String>()
        val firstWriteEntered = CountDownLatch(1)
        val releaseFirstWrite = CountDownLatch(1)
        private val writes = AtomicInteger(0)

        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
            if (writes.incrementAndGet() == 1) {
                firstWriteEntered.countDown()
                releaseFirstWrite.await(60, TimeUnit.SECONDS)
            }
            val out = ByteArrayOutputStream()
            body(out)
            writtenNames += fileName
            return "/Downloads/$fileName"
        }

        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
        ): PartialDownload? = null

        override fun freeBytes(folderPath: String?): Long? = null
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val prefs = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            prefs.value = transform(prefs.value)
        }

        override suspend fun getTelegramCredentials(): TelegramCredentials? = null
        override suspend fun setTelegramCredentials(credentials: TelegramCredentials) = Unit
        override suspend fun hasStoredTelegramCredentials(): Boolean = false
        override suspend fun clearTelegramCredentials() = Unit
    }

    private class FakeTransferDao {
        val rows = ConcurrentHashMap<String, TransferEntity>()
        val dao: TransferDao = archiveTestProxy { method, args ->
            when (method) {
                "upsert" -> {
                    val entity = args!![0] as TransferEntity
                    rows[entity.id] = entity
                    Unit
                }
                "byId" -> rows[args!![0] as String]
                "setLocalPath" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row -> row.copy(localPath = args[1] as String?) }
                    Unit
                }
                "setCompleted" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row ->
                        row.copy(
                            state = TransferState.COMPLETED,
                            transferredBytes = row.sizeBytes,
                            completedAt = args[1] as Long
                        )
                    }
                    Unit
                }
                "updateProgress" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row ->
                        row.copy(transferredBytes = args[1] as Long)
                    }
                    Unit
                }
                "setFailed" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row ->
                        row.copy(state = args[1] as TransferState, errorMessage = args[2] as String?)
                    }
                    Unit
                }
                "setState" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row -> row.copy(state = args[1] as TransferState) }
                    Unit
                }
                else -> throw UnsupportedOperationException(method)
            }
        }

        fun rowNamed(name: String): TransferEntity? = rows.values.firstOrNull { it.displayName == name }

        /** The DAO half of TransferRepositoryImpl.cancelAll for archive rows. */
        fun bulkCancelArchiveRows() {
            rows.values.forEach { row ->
                if (row.state in setOf(
                        TransferState.QUEUED, TransferState.RUNNING,
                        TransferState.PAUSED, TransferState.FAILED
                    )
                ) {
                    rows[row.id] = row.copy(state = TransferState.CANCELLED)
                }
            }
        }
    }

    private class Fixture(
        val viewModel: PreviewViewModel,
        val file: DriveFile,
        val writer: GatingDownloadWriter,
        val dao: FakeTransferDao,
        val jobs: ArchiveExtractionJobs
    )

    private fun fixture(): Fixture {
        val zipBytes = archiveZipBytes(entryData)
        val file = DriveFile(
            id = "zip-file-bulk",
            folderId = null,
            name = "bulk.zip",
            sizeBytes = zipBytes.size.toLong(),
            mimeType = "application/zip",
            category = FileCategory.ARCHIVE,
            localPath = null,
            contentHash = null,
            chatId = 1L,
            messageId = 2L,
            remoteFileId = "remote-1",
            remoteUniqueId = "unique-1",
            backupState = BackupState.BACKED_UP,
            isHidden = false,
            isArchived = false,
            isFavorite = false,
            isEncrypted = false,
            width = null,
            height = null,
            durationMs = null,
            trashedAt = null,
            createdAt = 0L,
            modifiedAt = 0L,
            addedAt = 0L
        )
        val telegram = archiveFakeTelegram(zipBytes)
        val writer = GatingDownloadWriter()
        val dao = FakeTransferDao()
        val jobs = ArchiveExtractionJobs()
        val tracker = ArchiveDownloadTracker(dao.dao, jobs)
        val settings = FakeSettingsRepository()
        val filePartDao = archiveTestProxy<FilePartDao> { method, _ ->
            when (method) {
                "partsOf" -> emptyList<FilePartEntity>()
                "countOf" -> 0
                else -> throw UnsupportedOperationException(method)
            }
        }
        val folderPathResolver = FolderPathResolver(
            archiveTestProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
            ActiveChannel(settings),
            archiveTestProxy<FolderTombstoneDao> { m, _ -> throw UnsupportedOperationException(m) }
        )
        val remoteArchiveService = RemoteArchiveService(
            telegram, filePartDao, writer, folderPathResolver
        )
        val storagePaths = object : AppStoragePaths {
            private val root = File.createTempFile("archive-bulk", "").also {
                it.delete()
                it.mkdirs()
            }
            override val filesDir: File = File(root, "files").also { it.mkdirs() }
            override val cacheDir: File = File(root, "cache").also { it.mkdirs() }
            override val externalCacheDir: File? = null
            override val externalStorageRoot: File? = null
        }
        val resolver = PreviewContentResolver(
            storagePaths = storagePaths,
            telegramClient = telegram,
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = archiveTestProxy<WrappedKeyRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            cacheDao = archiveTestProxy<CacheDao> { m, _ -> throw UnsupportedOperationException(m) },
            filePartDao = filePartDao,
            settingsRepository = settings,
            thumbnailStore = archiveTestProxy<ThumbnailStore> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            dispatchers = DefaultDispatcherProvider(),
            remoteArchiveService = remoteArchiveService
        )
        val fileRepository = archiveTestProxy<FileRepository> { method, _ ->
            when (method) {
                "getFile" -> file
                "observeFiles" -> flowOf(listOf(file))
                "observeFile" -> flowOf(file)
                "getFolder" -> null
                else -> throw UnsupportedOperationException(method)
            }
        }
        val transferRepository = archiveTestProxy<TransferRepository> { method, _ ->
            when (method) {
                "observeActiveForFile" -> flowOf(null)
                else -> throw UnsupportedOperationException(method)
            }
        }
        val viewModel = PreviewViewModel(
            savedStateHandle = SavedStateHandle(mapOf("fileId" to file.id)),
            fileRepository = fileRepository,
            trashRepository = archiveTestProxy<TrashRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            transferRepository = transferRepository,
            settingsRepository = settings,
            contentResolver = resolver,
            remoteArchiveService = remoteArchiveService,
            archiveDownloadReporter = tracker
        )
        rule.setContent { MaterialTheme { } }
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.contentFor(file).collect {} }
        waitUntil("remote listing stored") {
            viewModel.uiState.value.archiveListings.containsKey(file.id)
        }
        return Fixture(viewModel, file, writer, dao, jobs)
    }

    private fun waitUntil(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for: $description")
            }
            Thread.sleep(25)
        }
    }

    /** Starts a 3-entry batch and waits until step 1 is genuinely in flight. */
    private fun startBatchHeldAtFirstStep(f: Fixture) {
        f.viewModel.downloadArchiveSelection(f.file, setOf(0, 1, 2), emptySet())
        assertTrue(
            "first extraction never reached the writer",
            f.writer.firstWriteEntered.await(15, TimeUnit.SECONDS)
        )
        waitUntil("first batch record begun") { f.dao.rowNamed("one.bin") != null }
    }

    @Test
    fun `cancel all aborts the running batch and drops queued requests`() {
        val f = fixture()
        startBatchHeldAtFirstStep(f)
        // A further single download queues behind the batch.
        f.viewModel.downloadArchiveEntry(f.file, 0)
        waitUntil("single queued behind batch") {
            f.viewModel.uiState.value.archiveQueuedDownloads == 1
        }

        // Transfers Cancel all, archive parts in repository order.
        f.jobs.requestBulkCancel()
        f.dao.bulkCancelArchiveRows()
        f.jobs.activeIds().forEach { f.jobs.cancel(it) }
        // Repeated press: harmless.
        f.jobs.requestBulkCancel()
        f.writer.releaseFirstWrite.countDown()

        waitUntil("queue drained after cancel all") {
            f.viewModel.uiState.value.archiveQueuedDownloads == 0 &&
                f.viewModel.uiState.value.archiveEntryBusyIndex == null &&
                f.viewModel.uiState.value.archiveBatchProgress == null
        }
        // Give any wrongly-continuing work a chance to betray itself.
        Thread.sleep(750)
        assertEquals(
            "no further entry may be written after Cancel all",
            listOf("one.bin"),
            f.writer.writtenNames.toList()
        )
        assertEquals(
            TransferState.CANCELLED,
            f.dao.rowNamed("one.bin")?.state
        )
        assertTrue(
            "steps that never started must have no record: ${f.dao.rows.values}",
            f.dao.rowNamed("two.bin") == null && f.dao.rowNamed("three.bin") == null
        )
    }

    @Test
    fun `pause all holds the queue until resume all, running record stays paused`() {
        val f = fixture()
        startBatchHeldAtFirstStep(f)

        // Transfers Pause all, archive parts in repository order.
        f.jobs.holdQueue()
        val running = f.dao.rowNamed("one.bin")!!
        f.dao.rows[running.id] = running.copy(state = TransferState.PAUSED)
        f.jobs.cancel(running.id)
        // Repeated press: harmless.
        f.jobs.holdQueue()
        f.writer.releaseFirstWrite.countDown()

        // The batch must park: step 2 does not start while held.
        Thread.sleep(1_000)
        assertTrue(
            "step 2 must not start while pause-all holds the queue",
            f.dao.rowNamed("two.bin") == null
        )
        assertEquals(listOf("one.bin"), f.writer.writtenNames.toList())
        assertEquals(TransferState.PAUSED, f.dao.rowNamed("one.bin")?.state)

        // Transfers Resume all: the hold releases and the QUEUED steps run;
        // the interrupted record itself stays PAUSED (its own resume keeps
        // the can't-resume contract, pinned at the repository level).
        f.jobs.releaseQueue()
        waitUntil("remaining steps complete after resume all") {
            f.dao.rowNamed("two.bin")?.state == TransferState.COMPLETED &&
                f.dao.rowNamed("three.bin")?.state == TransferState.COMPLETED
        }
        assertEquals(
            listOf("one.bin", "two.bin", "three.bin"),
            f.writer.writtenNames.toList()
        )
        assertEquals(TransferState.PAUSED, f.dao.rowNamed("one.bin")?.state)
    }

    @Test
    fun `per-record cancel still skips only its own batch step`() {
        val f = fixture()
        startBatchHeldAtFirstStep(f)

        // Per-record cancel from Transfers: row CANCELLED + only its job
        // stopped, NO bulk signal — the batch continues with the next step.
        val running = f.dao.rowNamed("one.bin")!!
        f.dao.rows[running.id] = running.copy(state = TransferState.CANCELLED)
        f.jobs.cancel(running.id)
        f.writer.releaseFirstWrite.countDown()

        waitUntil("batch completes the remaining steps") {
            f.dao.rowNamed("two.bin")?.state == TransferState.COMPLETED &&
                f.dao.rowNamed("three.bin")?.state == TransferState.COMPLETED
        }
        assertEquals(TransferState.CANCELLED, f.dao.rowNamed("one.bin")?.state)
        assertEquals(
            listOf("one.bin", "two.bin", "three.bin"),
            f.writer.writtenNames.toList()
        )
    }

    @Test
    fun `cancel all while the queue is held by pause all still drops everything`() {
        val f = fixture()
        startBatchHeldAtFirstStep(f)

        // Pause all first…
        f.jobs.holdQueue()
        val running = f.dao.rowNamed("one.bin")!!
        f.dao.rows[running.id] = running.copy(state = TransferState.PAUSED)
        f.jobs.cancel(running.id)
        f.writer.releaseFirstWrite.countDown()
        Thread.sleep(500)
        assertTrue(f.dao.rowNamed("two.bin") == null)

        // …then Cancel all: it releases the hold, and the parked batch must
        // observe the epoch change and abort instead of resuming.
        f.jobs.requestBulkCancel()
        f.dao.bulkCancelArchiveRows()
        f.jobs.activeIds().forEach { f.jobs.cancel(it) }

        waitUntil("queue drained after cancel-during-pause") {
            f.viewModel.uiState.value.archiveQueuedDownloads == 0 &&
                f.viewModel.uiState.value.archiveEntryBusyIndex == null &&
                f.viewModel.uiState.value.archiveBatchProgress == null
        }
        Thread.sleep(500)
        assertEquals(listOf("one.bin"), f.writer.writtenNames.toList())
        assertEquals(TransferState.CANCELLED, f.dao.rowNamed("one.bin")?.state)
        assertTrue(f.dao.rowNamed("two.bin") == null && f.dao.rowNamed("three.bin") == null)
    }
}
