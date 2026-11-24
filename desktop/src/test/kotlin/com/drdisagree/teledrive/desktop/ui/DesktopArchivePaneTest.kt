package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.preview.ArchiveEntryPreview
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Composed wiring tests for the desktop ZIP viewer (1.6.21 defect 1): the REAL
 * [DesktopArchivePane] composed with a REAL [PreviewViewModel] whose archive listing
 * and extractions are served by a REAL [RemoteArchiveService] over a fake
 * TelegramClient holding an in-memory ZIP — the same construction
 * `RemoteArchiveServiceTest` uses for the service. Clicking entries must produce the
 * shared in-archive previews (image / audio / text), route video to a tracked
 * download, and never emit a failure message for the preview clicks. The field
 * failure this regresses: entry clicks surfaced the raw Telegram-layer text
 * "File is not inside the cache" and nothing opened.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchivePaneTest {

    @get:Rule
    val rule = createComposeRule()

    private val imageBytes: ByteArray by lazy {
        val image = java.awt.image.BufferedImage(6, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        out.toByteArray()
    }
    private val audioBytes = "ID3".toByteArray() + ByteArray(64) { 7 }
    private val textBytes = "hello from inside the zip".toByteArray(Charsets.UTF_8)
    private val videoBytes = ByteArray(96) { 3 }

    private val entryData = listOf(
        "pic.png" to imageBytes,
        "song.mp3" to audioBytes,
        "notes.txt" to textBytes,
        "clip.mp4" to videoBytes
    )

    private class RecordingDownloadWriter : DownloadWriter {
        @Volatile var fileName: String? = null
        @Volatile var bytes: ByteArray? = null
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
            this.fileName = fileName
            val out = ByteArrayOutputStream()
            body(out)
            bytes = out.toByteArray()
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
        id = "zip-file-1",
        folderId = null,
        name = "test.zip",
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

    @Test
    fun `entry clicks preview image audio and text, video downloads with a transfers record`() {
        val zipBytes = archiveZipBytes(entryData)
        val file = zipFile(zipBytes)
        val telegram = archiveFakeTelegram(zipBytes)
        val downloadWriter = RecordingDownloadWriter()
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
            private val root = File.createTempFile("archive-pane", "").also {
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

        val messages = CopyOnWriteArrayList<UiText>()
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.messages.collect { messages += it } }
        // Drive the real resolver path so the ViewModel stores the remote listing,
        // exactly as PreviewPane does through contentFor.
        scope.launch { viewModel.contentFor(file).collect {} }

        rule.waitUntil(10_000) { viewModel.uiState.value.archiveListings.containsKey(file.id) }
        val listing = viewModel.uiState.value.archiveListings.getValue(file.id)
        assertEquals(entryData.map { it.first }, listing.map { it.name })

        rule.setContent {
            val vmState by viewModel.uiState.collectAsState()
            val sort by viewModel.archiveSort.collectAsState()
            MaterialTheme {
                DesktopArchivePane(
                    file = file,
                    archive = PreviewContent.Archive(listing, "ZIP", remote = true),
                    busyEntryIndex = vmState.archiveEntryBusyIndex,
                    batchProgress = vmState.archiveBatchProgress,
                    archivePreview = vmState.archivePreview,
                    sortField = sort.first,
                    sortDirection = sort.second,
                    onSortChange = { field, direction -> viewModel.setArchiveSort(field, direction) },
                    onEntryTap = { viewModel.onArchiveEntryTap(file, it) },
                    onEntryDownload = { viewModel.downloadArchiveEntry(file, it) },
                    onDownloadSelection = { entries, folders ->
                        viewModel.downloadArchiveSelection(file, entries, folders)
                    },
                    onDismissPreview = { viewModel.dismissArchivePreview() },
                    preferredAudioLanguage = "",
                    preferredSubtitleLanguage = "",
                    onPreferredAudioLanguage = {},
                    onPreferredSubtitleLanguage = {},
                    backgroundPlayback = false,
                    onBackgroundPlaybackChange = {},
                    snackbarHostState = remember { SnackbarHostState() }
                )
            }
        }
        rule.waitForIdle()
        entryData.forEach { (name, _) -> rule.onNodeWithText(name).assertIsDisplayed() }

        // Image entry: extracted to a temp file and shown in the image overlay.
        rule.onNodeWithText("pic.png").performClick()
        rule.waitUntil(10_000) { viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Image }
        val imagePreview = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Image
        assertEquals("pic.png", imagePreview.name)
        assertArrayEquals(imageBytes, File(imagePreview.path).readBytes())
        viewModel.dismissArchivePreview()
        rule.waitUntil(5_000) { viewModel.uiState.value.archivePreview == null }

        // Audio entry: extracted to a temp file for the desktop player path.
        rule.onNodeWithText("song.mp3").performClick()
        rule.waitUntil(10_000) { viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Audio }
        val audioPreview = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Audio
        assertEquals("song.mp3", audioPreview.name)
        assertArrayEquals(audioBytes, File(audioPreview.path).readBytes())
        viewModel.dismissArchivePreview()
        rule.waitUntil(5_000) { viewModel.uiState.value.archivePreview == null }

        // Text entry: read in place in the read-only overlay.
        rule.onNodeWithText("notes.txt").performClick()
        rule.waitUntil(10_000) { viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Text }
        val textPreview = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Text
        assertEquals("hello from inside the zip", textPreview.text)
        rule.onNodeWithText("hello from inside the zip").assertIsDisplayed()
        viewModel.dismissArchivePreview()
        rule.waitUntil(5_000) { viewModel.uiState.value.archivePreview == null }

        // None of the three preview clicks emitted any message (failure or otherwise).
        assertTrue(
            "preview clicks emitted messages: $messages",
            messages.isEmpty()
        )

        // Video entry: never previews; it downloads through the tracked path, which
        // also proves defect 3's wiring end to end (an ARCHIVE_DOWNLOAD record that
        // completes in the Transfers history store).
        rule.onNodeWithText("clip.mp4").performClick()
        rule.waitUntil(10_000) { downloadWriter.bytes != null }
        assertEquals("clip.mp4", downloadWriter.fileName)
        assertArrayEquals(videoBytes, downloadWriter.bytes)
        assertTrue(
            "video click produced a preview",
            viewModel.uiState.value.archivePreview == null
        )
        rule.waitUntil(10_000) {
            transferDao.rows.values.any {
                it.type == TransferType.ARCHIVE_DOWNLOAD &&
                    it.displayName == "clip.mp4" &&
                    it.state == TransferState.COMPLETED &&
                    it.localPath == "/Downloads/clip.mp4"
            }
        }
    }
}
