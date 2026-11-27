package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.ArchiveDownloadTracker
import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramClient
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
import com.drdisagree.teledrive.desktop.media.DesktopArchiveEntryThumbnailStore
import com.drdisagree.teledrive.desktop.media.MediaStreamServer
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.platform.LocalUrlOpener
import com.drdisagree.teledrive.presentation.platform.UrlOpener
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module

/**
 * R3 item P1: the Download action in the REAL [DesktopPreviewScreen] for
 * a remote-backed media file that already has a local copy — the
 * Gallery-album field case (the top bar showed only trash/Info/⋮ and
 * the ⋮ menu had no Download, because both surfaces gated the action on
 * `!hasLocalCopy`). Every viewer route converges on this screen with
 * the same DriveFile, so the screen-level pin covers the routes; the
 * full category × copy-state matrix is pinned in
 * [ViewerChromePolicyTest]. Tapping must route to the SAME enqueue the
 * viewer has always used (PreviewViewModel.download →
 * TransferRepository.enqueueDownload, the round-2 background transfer) —
 * the fake repository records the call, and the viewer must stay on
 * screen afterwards (the view is not replaced by the transfer).
 *
 * The headless scene has no VLC, so a local media file composes its
 * open-externally fallback instead of the player; what is pinned here
 * is the top bar's action construction and routing, the seam that
 * dropped the action.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopViewerDownloadButtonTest {

    @get:Rule
    val rule = createComposeRule()

    private val enqueued = CopyOnWriteArrayList<String>()

    @After
    fun tearDown() {
        runCatching { stopKoin() }
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
                "setLocalPath" -> Unit
                "setCompleted" -> Unit
                "updateProgress" -> Unit
                "setFailed" -> Unit
                "setState" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row -> row.copy(state = args[1] as TransferState) }
                    Unit
                }
                else -> throw UnsupportedOperationException(method)
            }
        }
    }

    private fun driveFile(
        id: String,
        name: String,
        mimeType: String,
        category: FileCategory,
        localPath: String?
    ) = DriveFile(
        id = id,
        folderId = null,
        name = name,
        sizeBytes = 10,
        mimeType = mimeType,
        category = category,
        localPath = localPath,
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

    private fun setScreen(file: DriveFile) {
        val telegram = archiveFakeTelegram(ByteArray(0))
        val settings = FakeSettingsRepository()
        val transferDao = FakeTransferDao()
        val tracker = ArchiveDownloadTracker(transferDao.dao, ArchiveExtractionJobs())

        val downloadWriter = object : DownloadWriter {
            override fun write(
                fileName: String,
                mimeType: String,
                folderPath: String?,
                body: (OutputStream) -> Unit
            ): String {
                val out = ByteArrayOutputStream()
                body(out)
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
            private val root = File.createTempFile("viewer-download", "").also {
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
        val transferRepository = archiveTestProxy<TransferRepository> { method, args ->
            when (method) {
                "observeActiveForFile" -> flowOf(null)
                "enqueueDownload" -> {
                    enqueued += args!![0] as String
                    AppResult.Success("transfer-1")
                }
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
        val koinModule = module {
            single { MediaStreamServer() }
            single { DesktopArchiveEntryThumbnailStore(storagePaths, remoteArchiveService) }
            single<TelegramClient> { telegram }
            single { StreamCrypto() }
            single<WrappedKeyRepository> {
                archiveTestProxy<WrappedKeyRepository> { m, _ ->
                    throw UnsupportedOperationException(m)
                }
            }
        }
        // Global startKoin/stopKoin, not the KoinApplication composable —
        // see DesktopPreviewEscapeBackTest for the cross-class leak this
        // avoids.
        runCatching { stopKoin() }
        startKoin { modules(koinModule) }
        rule.setContent {
            MaterialTheme {
                ArchiveTestProviders {
                    CompositionLocalProvider(
                        LocalUrlOpener provides UrlOpener { }
                    ) {
                        DesktopPreviewScreen(
                            onBack = {},
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun downloadNodes() =
        rule.onAllNodesWithContentDescription("Download").fetchSemanticsNodes()

    private fun waitFor(description: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (
            rule.onAllNodesWithContentDescription(description)
                .fetchSemanticsNodes().isEmpty()
        ) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for '$description'")
            }
            Thread.sleep(50)
            rule.waitForIdle()
        }
    }

    @Test
    fun `video with a local copy shows Download and tapping it enqueues the background download`() {
        val local = File.createTempFile("gallery-video", ".webm").apply {
            writeBytes(ByteArray(16))
        }
        setScreen(
            driveFile(
                id = "gallery-video",
                name = "clip.webm",
                mimeType = "video/webm",
                category = FileCategory.VIDEO,
                localPath = local.absolutePath
            )
        )
        waitFor("Download")
        rule.onNodeWithContentDescription("Download").assertIsDisplayed()

        rule.onNodeWithContentDescription("Download").performClick()
        rule.waitForIdle()
        assertEquals(
            "the tap must route to the viewer's existing enqueueDownload",
            listOf("gallery-video"),
            enqueued.toList()
        )
        // The viewer itself is still on screen after the tap: the
        // download runs in the background, the view is not replaced.
        rule.onNodeWithContentDescription("Download").assertIsDisplayed()
    }

    @Test
    fun `photo with a local copy shows Download in the top bar`() {
        val local = File.createTempFile("gallery-photo", ".jpg").apply {
            writeBytes(ByteArray(16))
        }
        setScreen(
            driveFile(
                id = "gallery-photo",
                name = "photo.jpg",
                mimeType = "image/jpeg",
                category = FileCategory.IMAGE,
                localPath = local.absolutePath
            )
        )
        waitFor("Download")
        rule.onNodeWithContentDescription("Download").assertIsDisplayed()
    }

    @Test
    fun `audio with a local copy shows Download in the top bar`() {
        val local = File.createTempFile("song", ".mp3").apply {
            writeBytes(ByteArray(16))
        }
        setScreen(
            driveFile(
                id = "song-file",
                name = "song.mp3",
                mimeType = "audio/mpeg",
                category = FileCategory.AUDIO,
                localPath = local.absolutePath
            )
        )
        waitFor("Download")
        rule.onNodeWithContentDescription("Download").assertIsDisplayed()
    }

    @Test
    fun `document with a local copy gains no top bar Download`() {
        // Scope pin: the P1 relaxation is media-only. A document that
        // already has a local copy keeps the pre-fix behaviour.
        val local = File.createTempFile("doc", ".txt").apply {
            writeText("a plain note")
        }
        setScreen(
            driveFile(
                id = "doc-file",
                name = "note.txt",
                mimeType = "text/plain",
                category = FileCategory.DOCUMENT,
                localPath = local.absolutePath
            )
        )
        waitFor("File info")
        assertTrue(
            "a non-media file with a local copy must not gain a Download action",
            downloadNodes().isEmpty()
        )
    }
}
