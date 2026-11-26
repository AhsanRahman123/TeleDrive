package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.ArchiveDownloadReporter
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
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
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
import com.drdisagree.teledrive.resources.preview_archive_entry_downloading
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Report 54 second-pass MUST-FIX regressions:
 *
 * MF-1 — a queued download must NOT start while a PREVIEW extraction is in
 * flight (and a preview must not start while a download extraction is in
 * flight): all extractions of one archive share the one TDLib window for its
 * fileId, so they must be serialised on BOTH axes.
 *
 * MF-2 — one throwing `archiveDownloadReporter.begin()` must not kill the
 * queue processor: the next queued request still runs to completion and the
 * queued count returns to 0.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveQueueGateTest {

    @get:Rule
    val rule = createComposeRule()

    // ---------------------------------------------------------------- fakes

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

    /**
     * Fake TelegramClient (same shape as [archiveFakeTelegram]) that can be
     * ARMED: while armed, every ranged read fails with the coverage-class
     * Telegram error and the file info reports an active download window at
     * the last requested offset with zero bytes landed. The byte source
     * therefore holds the extraction in its suspending 500 ms recheck loop
     * (a genuinely in-flight extraction that does NOT block any thread —
     * a latch-blocked read would stall the Main thread the ViewModel runs
     * on and mask the very concurrency this test pins). Disarming restores
     * full coverage and every held read completes.
     */
    private class GatedTelegram(private val bytes: ByteArray) {
        val firstArmedRead = CountDownLatch(1)
        private val armed = AtomicBoolean(false)
        private val windowOffset = java.util.concurrent.atomic.AtomicLong(0)

        fun arm() = armed.set(true)
        fun disarm() = armed.set(false)

        private fun info(): TelegramFileInfo =
            if (armed.get()) {
                TelegramFileInfo(
                    fileId = 7,
                    sizeBytes = bytes.size.toLong(),
                    localPath = null,
                    isDownloadingCompleted = false,
                    isDownloadingActive = true,
                    downloadOffset = windowOffset.get(),
                    downloadedPrefixSize = 0
                )
            } else {
                TelegramFileInfo(
                    fileId = 7,
                    sizeBytes = bytes.size.toLong(),
                    localPath = null,
                    isDownloadingCompleted = true,
                    isDownloadingActive = false,
                    downloadOffset = 0,
                    downloadedPrefixSize = bytes.size.toLong()
                )
            }

        val client: TelegramClient = archiveTestProxy { method, args ->
            when (method) {
                "resolveFile", "getFileInfo" -> info()
                "readFilePart" -> {
                    if (armed.get()) {
                        firstArmedRead.countDown()
                        throw TelegramException(400, "not enough downloaded bytes")
                    }
                    val offset = (args!![1] as Number).toLong()
                    val count = (args[2] as Number).toLong()
                    if (offset >= bytes.size) {
                        ByteArray(0)
                    } else {
                        bytes.copyOfRange(
                            offset.toInt(),
                            minOf(offset + count, bytes.size.toLong()).toInt()
                        )
                    }
                }
                "requestFileRange" -> {
                    windowOffset.set((args!![1] as Number).toLong())
                    Unit
                }
                "cancelFileDownload" -> Unit
                "fileUpdates" -> flowOf(info())
                else -> throw UnsupportedOperationException(method)
            }
        }
    }

    /** Reporter whose FIRST begin() blocks until released, then throws (MF-2). */
    private class ThrowingFirstBeginReporter(
        private val delegate: ArchiveDownloadReporter
    ) : ArchiveDownloadReporter {
        val firstBeginEntered = CountDownLatch(1)
        val releaseFirstBegin = CountDownLatch(1)
        private val begins = AtomicInteger(0)

        override suspend fun begin(displayName: String, sizeBytes: Long): String? {
            if (begins.incrementAndGet() == 1) {
                firstBeginEntered.countDown()
                releaseFirstBegin.await(60, TimeUnit.SECONDS)
                throw RuntimeException("simulated Room insert failure")
            }
            return delegate.begin(displayName, sizeBytes)
        }

        override fun attach(recordId: String?, job: Job) = delegate.attach(recordId, job)
        override fun progress(recordId: String?, transferredBytes: Long) =
            delegate.progress(recordId, transferredBytes)

        override suspend fun complete(recordId: String?, localPath: String) =
            delegate.complete(recordId, localPath)

        override suspend fun fail(recordId: String?, message: String?) =
            delegate.fail(recordId, message)

        override suspend fun ownerStopped(
            recordId: String?,
            cause: kotlinx.coroutines.CancellationException?
        ) = delegate.ownerStopped(recordId, cause)
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
    }

    // ------------------------------------------------------------- harness

    private class Harness(
        val viewModel: PreviewViewModel,
        val file: DriveFile,
        val transferDao: FakeTransferDao,
        val messages: CopyOnWriteArrayList<UiText>
    )

    private fun harness(
        entryData: List<Pair<String, ByteArray>>,
        telegram: TelegramClient,
        downloadWriter: GatingDownloadWriter,
        reporterOverride: ((ArchiveDownloadReporter) -> ArchiveDownloadReporter)? = null
    ): Harness {
        val zipBytes = archiveZipBytes(entryData)
        val file = DriveFile(
            id = "zip-file-gate",
            folderId = null,
            name = "gate.zip",
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
        val transferDao = FakeTransferDao()
        val tracker = ArchiveDownloadTracker(transferDao.dao, ArchiveExtractionJobs())
        val reporter: ArchiveDownloadReporter =
            reporterOverride?.invoke(tracker) ?: tracker
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
            private val root = File.createTempFile("archive-gate", "").also {
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
            archiveDownloadReporter = reporter
        )
        rule.setContent { MaterialTheme { } }
        val messages = CopyOnWriteArrayList<UiText>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.messages.collect { messages += it } }
        scope.launch { viewModel.contentFor(file).collect {} }
        waitUntil("remote listing stored") {
            viewModel.uiState.value.archiveListings.containsKey(file.id)
        }
        return Harness(viewModel, file, transferDao, messages)
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

    /** Asserts [condition] stays false for [windowMs] (a "must not start" check). */
    private fun assertStaysFalse(description: String, windowMs: Long = 2_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + windowMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) fail("Must not happen: $description")
            Thread.sleep(25)
        }
    }

    // --------------------------------------------------------------- tests

    /**
     * MF-1: a preview extraction held in flight; a download enqueued during
     * it must not START (no Transfers record — records are created only at
     * start — and no saved file, busy index still the preview's) until the
     * preview finishes; then it runs to completion.
     */
    @Test
    fun `download enqueued during a held preview extraction does not start until the preview finishes`() {
        val entryData = listOf(
            "pic.png" to ByteArray(64) { 1 },
            "two.bin" to ByteArray(64) { 2 }
        )
        val zipBytes = archiveZipBytes(entryData)
        val gated = GatedTelegram(zipBytes)
        val writer = GatingDownloadWriter()
        writer.releaseFirstWrite.countDown() // downloads themselves are not writer-gated here
        val h = harness(entryData, gated.client, writer)
        val vm = h.viewModel

        // Start the preview and hold its extraction in flight inside the
        // byte source's coverage-wait loop.
        gated.arm()
        vm.previewArchiveEntry(h.file, 0)
        assertTrue(
            "preview extraction never reached the fake Telegram read",
            gated.firstArmedRead.await(15, TimeUnit.SECONDS)
        )
        waitUntil("preview busy index") { vm.uiState.value.archiveEntryBusyIndex == 0 }

        // Enqueue a download while the preview is in flight. Its start
        // message fires at acceptance (note 5) — but the download itself
        // must NOT start: no Transfers record, nothing written, and the
        // busy index still belongs to the preview.
        vm.downloadArchiveEntry(h.file, 1)
        waitUntil("download start message at acceptance") {
            h.messages.filterIsInstance<UiText.Resource>()
                .any {
                    it.resource == Res.string.preview_archive_entry_downloading &&
                        it.args.first().toString() == "two.bin"
                }
        }
        assertStaysFalse("download started while preview extraction in flight") {
            h.transferDao.rows.values.any { it.displayName == "two.bin" } ||
                writer.writtenNames.isNotEmpty()
        }
        assertEquals(0, vm.uiState.value.archiveEntryBusyIndex)

        // Release the preview: it publishes, then the queued download runs.
        gated.disarm()
        waitUntil("preview published") { vm.uiState.value.archivePreview != null }
        waitUntil("download written after preview finished") {
            writer.writtenNames == listOf("two.bin")
        }
        waitUntil("download record completed") {
            h.transferDao.rows.values.any {
                it.displayName == "two.bin" &&
                    it.type == TransferType.ARCHIVE_DOWNLOAD &&
                    it.state == TransferState.COMPLETED
            }
        }
        waitUntil("busy cleared and queue empty") {
            vm.uiState.value.archiveEntryBusyIndex == null &&
                vm.uiState.value.archiveQueuedDownloads == 0
        }
    }

    /**
     * MF-1, symmetric axis: while a download extraction is in flight, a
     * preview tap does not start; once the download finishes, a preview is
     * immediate again.
     */
    @Test
    fun `preview does not start during a held download extraction and is immediate when idle`() {
        val entryData = listOf(
            "pic.png" to ByteArray(64) { 1 },
            "two.bin" to ByteArray(64) { 2 }
        )
        val zipBytes = archiveZipBytes(entryData)
        val writer = GatingDownloadWriter()
        val h = harness(entryData, archiveFakeTelegram(zipBytes), writer)
        val vm = h.viewModel

        vm.downloadArchiveEntry(h.file, 1)
        assertTrue(
            "download extraction never reached the writer",
            writer.firstWriteEntered.await(15, TimeUnit.SECONDS)
        )
        waitUntil("download busy index") { vm.uiState.value.archiveEntryBusyIndex == 1 }

        vm.previewArchiveEntry(h.file, 0)
        Thread.sleep(500)
        assertNull(
            "preview must not start while a download extraction is in flight",
            vm.uiState.value.archivePreview
        )
        assertEquals(1, vm.uiState.value.archiveEntryBusyIndex)

        writer.releaseFirstWrite.countDown()
        waitUntil("download written and busy cleared") {
            writer.writtenNames == listOf("two.bin") &&
                vm.uiState.value.archiveEntryBusyIndex == null
        }

        // Idle again: the same preview tap now extracts and publishes.
        vm.previewArchiveEntry(h.file, 0)
        waitUntil("preview published when idle") { vm.uiState.value.archivePreview != null }
    }

    /**
     * MF-2: the first queued request's begin() throws (after the second
     * request has been accepted behind it). The second request must still
     * run to completion and the queued count must return to 0.
     */
    @Test
    fun `throwing begin for the first queued request does not strand the second`() {
        val entryData = listOf(
            "one.bin" to ByteArray(64) { 1 },
            "two.bin" to ByteArray(64) { 2 }
        )
        val zipBytes = archiveZipBytes(entryData)
        val writer = GatingDownloadWriter()
        writer.releaseFirstWrite.countDown()
        lateinit var throwing: ThrowingFirstBeginReporter
        val h = harness(entryData, archiveFakeTelegram(zipBytes), writer) { delegate ->
            ThrowingFirstBeginReporter(delegate).also { throwing = it }
        }
        val vm = h.viewModel

        vm.downloadArchiveEntry(h.file, 0)
        assertTrue(
            "first request never reached begin()",
            throwing.firstBeginEntered.await(15, TimeUnit.SECONDS)
        )
        // Accept the second request while the first is still inside begin()
        // (processor alive), so it queues behind the doomed first request.
        vm.downloadArchiveEntry(h.file, 1)
        waitUntil("second request queued") { vm.uiState.value.archiveQueuedDownloads == 1 }

        throwing.releaseFirstBegin.countDown()

        waitUntil("second request still runs to completion") {
            writer.writtenNames == listOf("two.bin")
        }
        waitUntil("second request record completed") {
            h.transferDao.rows.values.any {
                it.displayName == "two.bin" &&
                    it.type == TransferType.ARCHIVE_DOWNLOAD &&
                    it.state == TransferState.COMPLETED
            }
        }
        waitUntil("queue count returns to 0 and busy cleared") {
            vm.uiState.value.archiveQueuedDownloads == 0 &&
                vm.uiState.value.archiveEntryBusyIndex == null
        }
    }
}
