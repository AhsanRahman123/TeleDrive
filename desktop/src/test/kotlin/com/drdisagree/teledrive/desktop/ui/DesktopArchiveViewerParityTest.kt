package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 1.6.24 notes 7 + 9, desktop archive viewers.
 *
 * UI level (fake callbacks): the in-zip image preview must expose the real
 * photo viewer's parity controls (rotate L/R, flip H/V, Edit, File info,
 * Close, prev/next pager), the pager must ask for the neighbouring DISPLAYED
 * photo, the info panel must show the entry's own metadata, and a video row
 * tap must route to the desktop video path — never to the shared tap routing.
 *
 * ViewModel level (real RemoteArchiveService over a fake TelegramClient, the
 * DesktopArchivePaneTest construction): switching photos extracts the
 * neighbour and deletes the previous temp; a video preview request produces
 * an ArchiveEntryPreview.Video whose temp holds the entry bytes; dismiss
 * deletes temps; and the SHARED tap routing for video still downloads
 * (Android's behaviour is unchanged by the desktop routing).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveViewerParityTest {

    @get:Rule
    val rule = createComposeRule()

    private fun pngBytes(width: Int, height: Int): ByteArray {
        val image = java.awt.image.BufferedImage(
            width, height, java.awt.image.BufferedImage.TYPE_INT_RGB
        )
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    private val imageBytesA by lazy { pngBytes(6, 4) }
    private val imageBytesB by lazy { pngBytes(9, 3) }
    private val videoBytes = ByteArray(96) { 3 }

    private val entryData by lazy {
        listOf(
            "pics/a.png" to imageBytesA,
            "pics/b.png" to imageBytesB,
            "clip.mp4" to videoBytes
        )
    }

    // ---------- UI level ----------

    private val uiListing = listOf(
        ArchiveEntry("a.png", 111, 100, false, modifiedAtMillis = 1_700_000_000_000),
        ArchiveEntry("b.png", 222, 200, false),
        ArchiveEntry("clip.mp4", 333, 300, false)
    )

    private fun zipFile(id: String): DriveFile = DriveFile(
        id = id,
        folderId = null,
        name = "test.zip",
        sizeBytes = 100,
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
    fun `in-zip image preview exposes parity controls, pager, info, and video rows route to the player`() {
        val tempPhoto = File.createTempFile("archive-a", ".png")
        tempPhoto.writeBytes(imageBytesA)
        val previewRequests = CopyOnWriteArrayList<Int>()
        val videoTaps = CopyOnWriteArrayList<Int>()
        val plainTaps = CopyOnWriteArrayList<Int>()
        var previewState by mutableStateOf<ArchiveEntryPreview?>(
            ArchiveEntryPreview.Image("a.png", tempPhoto.absolutePath, entryIndex = 0)
        )

        rule.setContent {
            MaterialTheme {
              ArchiveTestProviders {
                DesktopArchivePane(
                    file = zipFile("parity-zip"),
                    archive = PreviewContent.Archive(uiListing, "ZIP", remote = true),
                    busyEntryIndex = null,
                    batchProgress = null,
                    archivePreview = previewState,
                    sortField = FileSortField.NAME,
                    sortDirection = SortDirection.ASCENDING,
                    onSortChange = { _, _ -> },
                    onEntryTap = { plainTaps += it },
                    onPreviewEntry = { previewRequests += it },
                    onVideoEntryTap = { videoTaps += it },
                    onEntryDownload = {},
                    onDownloadSelection = { _, _ -> },
                    onDismissPreview = { previewState = null },
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
        }
        rule.waitForIdle()

        // The photo itself renders through the shared zoomable surface.
        rule.onNodeWithContentDescription("a.png").assertIsDisplayed()
        // Parity toolbar: rotate L/R, flip H/V, Edit, File info, Close.
        rule.onNodeWithContentDescription("Rotate counter-clockwise").assertIsDisplayed()
        rule.onNodeWithContentDescription("Rotate clockwise").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mirror horizontally").assertIsDisplayed()
        rule.onNodeWithContentDescription("Mirror vertically").assertIsDisplayed()
        rule.onNodeWithContentDescription("Edit").assertIsDisplayed()
        rule.onNodeWithContentDescription("File info").assertIsDisplayed()
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        // Pager: a.png is the first of two displayed photos — previous is
        // disabled, next is available (both are composed, as in the real viewer).
        rule.onNodeWithContentDescription("Previous file").assertIsDisplayed()
        rule.onNodeWithContentDescription("Next file").assertIsDisplayed()
        rule.onNodeWithContentDescription("Next file").performClick()
        rule.waitForIdle()
        assertEquals(listOf(1), previewRequests.toList())

        // Info: the entry's own metadata. The asserted strings are the ones
        // only the dialog shows (the row behind it shows name + size too).
        rule.onNodeWithContentDescription("File info").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Path in archive").assertIsDisplayed()
        rule.onNodeWithText("Compressed size").assertIsDisplayed()
        rule.onNodeWithText("100 B").assertIsDisplayed()
        rule.onNodeWithText("image/png").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.waitForIdle()

        // Close the preview, then tap the video row: it must reach the desktop
        // video route and NOT the shared tap routing.
        rule.runOnIdle { previewState = null }
        rule.waitForIdle()
        rule.onNodeWithText("clip.mp4").performClick()
        rule.waitForIdle()
        assertEquals(listOf(2), videoTaps.toList())
        assertTrue("video tap leaked into shared routing: $plainTaps", plainTaps.isEmpty())
        tempPhoto.delete()
    }

    // ---------- ViewModel level ----------

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

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for: $description", condition())
    }

    @Test
    fun `photo switch extracts the neighbour and deletes the old temp, video previews and dismiss cleans temps`() {
        val zipBytes = archiveZipBytes(entryData)
        val file = zipFile("vm-zip")
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
            private val root = File.createTempFile("archive-parity", "").also {
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
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.contentFor(file).collect {} }
        await("remote listing stored") { viewModel.uiState.value.archiveListings.containsKey(file.id) }
        val listing = viewModel.uiState.value.archiveListings.getValue(file.id)
        assertEquals(entryData.map { it.first }, listing.map { it.name })

        // Open the first photo.
        viewModel.previewArchiveEntry(file, 0)
        await("first image preview") {
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Image
        }
        val first = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Image
        assertEquals("a.png", first.name)
        assertEquals(0, first.entryIndex)
        assertArrayEquals(imageBytesA, File(first.path).readBytes())
        val firstTemp = File(first.path)
        assertTrue(firstTemp.exists())

        // Switch to the neighbour: new temp published, old temp deleted.
        viewModel.previewArchiveEntry(file, 1)
        await("second image preview") {
            (viewModel.uiState.value.archivePreview as? ArchiveEntryPreview.Image)
                ?.entryIndex == 1
        }
        val second = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Image
        assertEquals("b.png", second.name)
        assertArrayEquals(imageBytesB, File(second.path).readBytes())
        assertFalse("switch leaked the previous photo's temp", firstTemp.exists())
        val secondTemp = File(second.path)
        assertTrue(secondTemp.exists())

        // Dismiss deletes the current temp.
        viewModel.dismissArchivePreview()
        await("preview dismissed") { viewModel.uiState.value.archivePreview == null }
        assertFalse("dismiss leaked the photo temp", secondTemp.exists())

        // Video: the desktop route produces a Video preview over the extracted temp.
        viewModel.previewArchiveVideoEntry(file, 2)
        await("video preview") {
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Video
        }
        val video = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Video
        assertEquals("clip.mp4", video.name)
        assertArrayEquals(videoBytes, File(video.path).readBytes())
        val videoTemp = File(video.path)
        viewModel.dismissArchivePreview()
        await("video preview dismissed") { viewModel.uiState.value.archivePreview == null }
        assertFalse("dismiss leaked the video temp", videoTemp.exists())

        // The video route refuses non-video entries (no preview, nothing extracted).
        viewModel.previewArchiveVideoEntry(file, 0)
        Thread.sleep(300)
        assertTrue(viewModel.uiState.value.archivePreview == null)

        // The SHARED tap routing is untouched: a video tap still downloads
        // (this is Android's behaviour, and the desktop row's Download action).
        viewModel.onArchiveEntryTap(file, 2)
        await("shared-routing video download") { downloadWriter.bytes != null }
        assertEquals("clip.mp4", downloadWriter.fileName)
        assertArrayEquals(videoBytes, downloadWriter.bytes)
        assertTrue(viewModel.uiState.value.archivePreview == null)
    }
}
