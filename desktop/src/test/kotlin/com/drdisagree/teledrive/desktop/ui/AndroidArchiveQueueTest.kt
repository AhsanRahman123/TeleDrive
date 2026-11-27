package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.ArchiveDownloadReporter
import com.drdisagree.teledrive.core.archive.ArchiveDownloadTracker
import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.archive.LocalArchiveService
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
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
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_archive_entry_saving
import com.drdisagree.teledrive.resources.preview_archive_entry_saved
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Ported from PC 1.6.24's DesktopArchiveQueueTest (notes 2 + 5), ViewModel
 * level: while one in-ZIP download is running, further downloads
 * (single-entry AND Download-selected batches) must be STARTABLE — they
 * queue in acceptance order and run one at a time after the running one,
 * instead of being dropped by the old `archiveEntryBusyIndex` early-return.
 * And the download-start message (the one carrying the View -> Transfers
 * action) must be emitted at acceptance time, not only at completion.
 *
 * Adaptations for this tree's divergence: the start message is this tree's
 * existing `preview_archive_entry_saving` (already a transfer message since
 * vc180 — the PC tree introduced its own `…_entry_downloading` string for
 * the same slot); the ViewModel constructor takes this tree's extra
 * LocalArchiveService; the content resolver here has no thumbnailStore
 * parameter. Two tests are added below for the second-pass fixes: MF-2
 * (a reporter whose first begin() throws must not strand the queue) and
 * MF-1's download axis (a preview tap while a download holds the
 * extraction gate does not start). MF-1's preview-held axis is NOT pinned
 * here: holding a preview extraction in flight needs the PC harness's
 * suspending coverage-wait fake, which this tree's TDLib byte source does
 * not have; the gate code itself is the ported mutex, shared by both axes.
 *
 * The first extraction is held inside the fake DownloadWriter so "while one
 * is running" is a controlled state, not a race. Transfers records must exist
 * only for work that has actually started (claim-proof report-only records,
 * reports 49/52): a queued request has no record yet.
 */
@OptIn(ExperimentalTestApi::class)
class AndroidArchiveQueueTest {

    // Only present so the Compose desktop Main dispatcher exists for
    // viewModelScope, exactly as in DesktopArchivePaneTest's environment.
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
                // Hold the first extraction open until the test releases it.
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

    /** Minimal in-memory TransferDao serving only what ArchiveDownloadTracker calls. */
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
    }

    private fun zipFile(zipBytes: ByteArray): DriveFile = DriveFile(
        id = "zip-file-queue",
        folderId = null,
        name = "queue.zip",
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

    private fun waitUntil(description: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for: $description")
            }
            Thread.sleep(25)
        }
    }

    private fun startedMessages(messages: List<UiText>): List<String> =
        messages.filterIsInstance<UiText.Resource>()
            .filter { it.resource == Res.string.preview_archive_entry_saving }
            .map { it.args.first().toString() }

    private fun savedMessages(messages: List<UiText>): List<String> =
        messages.filterIsInstance<UiText.Resource>()
            .filter { it.resource == Res.string.preview_archive_entry_saved }
            .map { it.args.first().toString() }

    @Test
    fun `second single download and a batch queue behind a running download and complete in order`() {
        val zipBytes = archiveZipBytes(entryData)
        val file = zipFile(zipBytes)
        val telegram = archiveFakeTelegram(zipBytes)
        val downloadWriter = GatingDownloadWriter()
        val transferDao = FakeTransferDao()
        val tracker = ArchiveDownloadTracker(transferDao.dao, ArchiveExtractionJobs())
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
            telegram, filePartDao, downloadWriter, folderPathResolver
        )
        val storagePaths = object : AppStoragePaths {
            private val root = File.createTempFile("archive-queue", "").also {
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
            localArchiveService = LocalArchiveService(downloadWriter, folderPathResolver),
            archiveDownloadReporter = tracker
        )

        rule.setContent { MaterialTheme { } }

        val messages = CopyOnWriteArrayList<UiText>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.messages.collect { messages += it } }
        scope.launch { viewModel.contentFor(file).collect {} }

        waitUntil("remote listing stored") {
            viewModel.uiState.value.archiveListings.containsKey(file.id)
        }
        val listing = viewModel.uiState.value.archiveListings.getValue(file.id)
        assertEquals(entryData.map { it.first }, listing.map { it.name })

        // Start the first download and wait until its extraction is genuinely
        // in flight (held inside the writer) with its Transfers record begun.
        viewModel.downloadArchiveEntry(file, 0)
        assertTrue(
            "first extraction never reached the writer",
            downloadWriter.firstWriteEntered.await(15, TimeUnit.SECONDS)
        )
        waitUntil("first download busy + record begun") {
            viewModel.uiState.value.archiveEntryBusyIndex == 0 &&
                transferDao.rows.values.any { it.displayName == "one.bin" }
        }
        // Note 5: the start message for the RUNNING download is already out.
        waitUntil("start message for one.bin") { startedMessages(messages) == listOf("one.bin") }

        // Note 2: a second single-entry download is startable while the first
        // runs — it queues (depth 1), its start message fires NOW (note 5),
        // but no Transfers record exists for it yet and the busy entry is
        // still the actually-running one.
        viewModel.downloadArchiveEntry(file, 1)
        waitUntil("second download queued") {
            viewModel.uiState.value.archiveQueuedDownloads == 1
        }
        waitUntil("start message for two.bin while one.bin still running") {
            startedMessages(messages) == listOf("one.bin", "two.bin")
        }
        assertEquals(0, downloadWriter.writtenNames.size)
        assertTrue(
            "queued download must not have a Transfers record yet: ${transferDao.rows.values}",
            transferDao.rows.values.none { it.displayName == "two.bin" }
        )
        assertEquals(0, viewModel.uiState.value.archiveEntryBusyIndex)

        // A Download-selected batch is equally startable and queues behind it.
        viewModel.downloadArchiveSelection(file, setOf(2), emptySet())
        waitUntil("batch queued") { viewModel.uiState.value.archiveQueuedDownloads == 2 }
        waitUntil("start message for three.bin batch") {
            startedMessages(messages) == listOf("one.bin", "two.bin", "three.bin")
        }
        assertTrue(
            transferDao.rows.values.none { it.displayName == "three.bin" }
        )

        // Release the first extraction: everything drains serially, in
        // acceptance order, each with its own completed Transfers record.
        downloadWriter.releaseFirstWrite.countDown()
        waitUntil("all three downloads written in order") {
            downloadWriter.writtenNames == listOf("one.bin", "two.bin", "three.bin")
        }
        waitUntil("queue drained and busy cleared") {
            viewModel.uiState.value.archiveQueuedDownloads == 0 &&
                viewModel.uiState.value.archiveEntryBusyIndex == null &&
                viewModel.uiState.value.archiveBatchProgress == null
        }
        waitUntil("all records completed") {
            val completed = transferDao.rows.values.filter {
                it.type == TransferType.ARCHIVE_DOWNLOAD && it.state == TransferState.COMPLETED
            }
            completed.map { it.displayName }.sorted() == listOf("one.bin", "three.bin", "two.bin")
        }
        waitUntil("completion messages for all three") {
            savedMessages(messages).sorted() == listOf("one.bin", "three.bin", "two.bin")
        }
    }

    /** A DownloadWriter that never blocks; just records what was saved. */
    private class RecordingDownloadWriter : DownloadWriter {
        val writtenNames = CopyOnWriteArrayList<String>()

        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
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

    /** Reporter whose first begin() throws, as a failing Room insert would (MF-2). */
    private class ThrowingFirstBeginReporter : ArchiveDownloadReporter {
        private val begins = AtomicInteger(0)
        val completedNames = CopyOnWriteArrayList<String>()
        private val namesById = ConcurrentHashMap<String, String>()

        override suspend fun begin(displayName: String, sizeBytes: Long): String? {
            if (begins.incrementAndGet() == 1) {
                throw RuntimeException("simulated Room insert failure")
            }
            val id = "record-$displayName"
            namesById[id] = displayName
            return id
        }

        override fun attach(recordId: String?, job: Job) = Unit

        override fun progress(recordId: String?, transferredBytes: Long) = Unit

        override suspend fun complete(recordId: String?, localPath: String) {
            completedNames += namesById[recordId] ?: "?"
        }

        override suspend fun fail(recordId: String?, message: String?) = Unit

        // vc183 merge adaptation: ITEM 1 widened the interface with a
        // `cause` parameter (defaulted); the fake stays a no-op.
        override suspend fun ownerStopped(
            recordId: String?,
            cause: kotlinx.coroutines.CancellationException?
        ) = Unit
    }

    /** Builds a ViewModel over the in-memory fake Telegram client, like the queue test above. */
    private fun buildViewModel(
        zipBytes: ByteArray,
        downloadWriter: DownloadWriter,
        reporter: ArchiveDownloadReporter
    ): Pair<PreviewViewModel, DriveFile> {
        val file = zipFile(zipBytes)
        val telegram = archiveFakeTelegram(zipBytes)
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
            telegram, filePartDao, downloadWriter, folderPathResolver
        )
        val storagePaths = object : AppStoragePaths {
            private val root = File.createTempFile("archive-queue-extra", "").also {
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
            localArchiveService = LocalArchiveService(downloadWriter, folderPathResolver),
            archiveDownloadReporter = reporter
        )
        return viewModel to file
    }

    private fun awaitListing(viewModel: PreviewViewModel, file: DriveFile) {
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.contentFor(file).collect {} }
        waitUntil("remote listing stored") {
            viewModel.uiState.value.archiveListings.containsKey(file.id)
        }
    }

    @Test
    fun `a reporter begin failure on the first request does not strand the queued second request`() {
        val zipBytes = archiveZipBytes(entryData.take(2))
        val writer = RecordingDownloadWriter()
        val reporter = ThrowingFirstBeginReporter()
        val (viewModel, file) = buildViewModel(zipBytes, writer, reporter)
        rule.setContent { MaterialTheme { } }
        awaitListing(viewModel, file)

        // Request 1's begin() throws inside its run; request 2 is queued
        // behind it. MF-2: the failure must fail request 1 only — the
        // processor continues and request 2 still completes.
        viewModel.downloadArchiveEntry(file, 0)
        viewModel.downloadArchiveEntry(file, 1)

        waitUntil("second request completed despite the first begin failure") {
            reporter.completedNames == listOf("two.bin")
        }
        assertEquals(listOf("two.bin"), writer.writtenNames.toList())
        waitUntil("queue drained and busy cleared after the failure") {
            viewModel.uiState.value.archiveQueuedDownloads == 0 &&
                viewModel.uiState.value.archiveEntryBusyIndex == null
        }
    }

    @Test
    fun `preview tap while a download holds the extraction gate does not start a preview`() {
        val entries = listOf(
            "one.bin" to ByteArray(64) { 1 },
            "pic.jpg" to ByteArray(64) { 9 }
        )
        val zipBytes = archiveZipBytes(entries)
        val downloadWriter = GatingDownloadWriter()
        val transferDao = FakeTransferDao()
        val tracker = ArchiveDownloadTracker(transferDao.dao, ArchiveExtractionJobs())
        val (viewModel, file) = buildViewModel(zipBytes, downloadWriter, tracker)
        rule.setContent { MaterialTheme { } }
        awaitListing(viewModel, file)

        // Hold a download extraction in flight, then tap the photo entry:
        // MF-1 — the preview must NOT start (no concurrent extraction); the
        // tap is dropped, exactly as the old busy gate dropped it.
        viewModel.downloadArchiveEntry(file, 0)
        assertTrue(
            "first extraction never reached the writer",
            downloadWriter.firstWriteEntered.await(15, TimeUnit.SECONDS)
        )
        viewModel.previewArchiveEntry(file, 1)
        Thread.sleep(750)
        assertEquals(null, viewModel.uiState.value.archivePreview)
        assertEquals(0, viewModel.uiState.value.archiveQueuedDownloads)

        downloadWriter.releaseFirstWrite.countDown()
        waitUntil("download completed and busy cleared") {
            downloadWriter.writtenNames == listOf("one.bin") &&
                viewModel.uiState.value.archiveEntryBusyIndex == null
        }
        // The dropped preview does not appear later either — it was a drop,
        // not a queue.
        assertEquals(null, viewModel.uiState.value.archivePreview)
    }
}
