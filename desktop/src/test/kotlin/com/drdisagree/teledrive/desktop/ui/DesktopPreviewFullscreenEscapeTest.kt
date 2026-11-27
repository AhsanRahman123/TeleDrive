package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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
import com.drdisagree.teledrive.presentation.preview.ArchiveEntryPreview
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * MF-1 (PC 1.6.26 second-pass review): Escape while an in-zip media entry
 * is playing fullscreen must exit fullscreen — the player's own Escape —
 * and must NOT run the preview screen's back ladder.
 *
 * The bug: the Scaffold's onPreviewKeyEvent ran stepBack() on Escape
 * whenever an archive pane was composed. Key dispatch runs the preview
 * phase root-to-leaf, so the Scaffold consumed the press before
 * DesktopMediaPlayer's Escape-exits-fullscreen handler ever saw it: the
 * in-zip preview closed and the window stayed fullscreen over the zip
 * list.
 *
 * Driven through the REAL [DesktopPreviewScreen] with a fake
 * [FullscreenController]. The test environment has no VLC, so the in-zip
 * video preview composes its open-externally fallback instead of the
 * player; what this test pins is the SCREEN's half of the contract —
 * while the controller reports fullscreen, the screen's Escape routing
 * (preview phase and bubble phase alike) must not step the ladder and
 * must not toggle fullscreen itself (the player owns the toggle). The
 * test then flips the fake controller off — exactly what the player's
 * handler does when it consumes the press — and pins that the NEXT
 * Escape runs the ladder again.
 *
 * Delta re-review residual: the MF-1 decline must be scoped to an in-zip
 * MEDIA preview actually being open. Fullscreen is a window-level state
 * also enterable via F11 with no player composed (zip contents list, or
 * a text entry preview — the VLC-unavailable fallback and image/PDF
 * previews share the shape); there, declining left Escape completely
 * inert. The two regression tests below pin that with the fake
 * controller ON and NO media preview open, Escape runs the back ladder
 * exactly as baseline: it closes an open entry preview, and at the zip
 * root it leaves the viewer.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPreviewFullscreenEscapeTest {

    @get:Rule
    val rule = createComposeRule()

    private val exits = AtomicInteger(0)
    private lateinit var viewModel: PreviewViewModel

    @Volatile
    private var fullscreen = false

    private val fullscreenController = FullscreenController(
        isFullscreen = { fullscreen },
        toggle = { fullscreen = !fullscreen },
        setFullscreen = { fullscreen = it }
    )

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
        sizeBytes: Long,
        localPath: String?
    ) = DriveFile(
        id = id,
        folderId = null,
        name = name,
        sizeBytes = sizeBytes,
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

    private fun setScreen(file: DriveFile, telegramBytes: ByteArray) {
        val telegram = archiveFakeTelegram(telegramBytes)
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
            private val root = File.createTempFile("fullscreen-escape", "").also {
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
        viewModel = PreviewViewModel(
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
        // Global startKoin/stopKoin, same rationale as
        // DesktopPreviewEscapeBackTest (KoinApplication contexts can leak
        // between test classes sharing one JVM).
        runCatching { stopKoin() }
        startKoin { modules(koinModule) }
        rule.setContent {
            MaterialTheme {
                ArchiveTestProviders {
                    CompositionLocalProvider(
                        LocalUrlOpener provides UrlOpener { },
                        LocalFullscreenController provides fullscreenController
                    ) {
                        DesktopPreviewScreen(
                            onBack = { exits.incrementAndGet() },
                            viewModel = viewModel
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun displayed(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    private fun waitDisplayed(text: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!displayed(text)) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for display of: $text")
            }
            Thread.sleep(50)
            rule.waitForIdle()
        }
    }

    /** Polls the real ViewModel until the in-zip preview is [entryIndex] (null = closed) and idle. */
    private fun waitPreview(entryIndex: Int?) {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            val state = viewModel.uiState.value
            val current = state.archivePreview?.entryIndex
            if (current == entryIndex && state.archiveEntryBusyIndex == null) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError(
                    "Timed out waiting for archive preview entry $entryIndex; " +
                        "current=$current busy=${state.archiveEntryBusyIndex}"
                )
            }
            Thread.sleep(50)
            rule.waitForIdle()
        }
    }

    private fun pressEscape() {
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
    }

    @Test
    fun `escape while fullscreen does not step the back ladder and the next escape does`() {
        // Display order inside the zip is name order: clip.mp4 (0), note.txt (1).
        val zipBytes = archiveZipBytes(
            listOf(
                "clip.mp4" to ByteArray(96) { 1 },
                "note.txt" to "NOTE ENTRY BODY".toByteArray()
            )
        )
        setScreen(
            driveFile(
                id = "fullscreen-zip",
                name = "media.zip",
                mimeType = "application/zip",
                category = FileCategory.ARCHIVE,
                sizeBytes = zipBytes.size.toLong(),
                localPath = null
            ),
            zipBytes
        )
        waitDisplayed("clip.mp4")

        // Open the in-zip video entry: the media preview is composed.
        rule.onNodeWithText("clip.mp4").performClick()
        waitPreview(0)
        assertTrue(
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Video
        )

        // The player is fullscreen now (its own state, reported through
        // the controller the screen reads).
        fullscreen = true

        // Escape: the screen must NOT step the back ladder (the in-zip
        // preview stays open, the viewer does not exit) and must NOT
        // toggle fullscreen itself — in production the player's own
        // Escape handler consumes this press and exits fullscreen.
        pressEscape()
        assertEquals("escape while fullscreen must not exit the viewer", 0, exits.get())
        assertEquals(
            "escape while fullscreen must not close the in-zip preview",
            0,
            viewModel.uiState.value.archivePreview?.entryIndex
        )
        assertTrue(
            "the screen must leave the fullscreen toggle to the player",
            fullscreen
        )

        // The player's handler has now consumed an Escape and exited
        // fullscreen (what its toggle does in production).
        fullscreen = false

        // The next Escape runs the ladder again: it closes the in-zip
        // preview back to the zip's contents list, without exiting.
        pressEscape()
        waitPreview(null)
        assertEquals(0, exits.get())
        waitDisplayed("note.txt")

        // And at the zip root, Escape leaves the viewer as before.
        pressEscape()
        assertEquals(1, exits.get())
    }

    @Test
    fun `escape while fullscreen with no preview open steps the ladder at the contents list`() {
        // Residual regression: fullscreen entered via F11 (window-level)
        // with NO in-zip preview open at all — no player exists to own
        // the press, so Escape must run the ladder: at the zip root that
        // leaves the viewer. On the unscoped MF-1 code both handlers
        // decline and this Escape does nothing (exits stays 0).
        val zipBytes = archiveZipBytes(
            listOf(
                "clip.mp4" to ByteArray(96) { 1 },
                "note.txt" to "NOTE ENTRY BODY".toByteArray()
            )
        )
        setScreen(
            driveFile(
                id = "fullscreen-zip-list",
                name = "media.zip",
                mimeType = "application/zip",
                category = FileCategory.ARCHIVE,
                sizeBytes = zipBytes.size.toLong(),
                localPath = null
            ),
            zipBytes
        )
        waitDisplayed("clip.mp4")
        assertEquals(null, viewModel.uiState.value.archivePreview)

        fullscreen = true

        pressEscape()
        assertEquals(
            "escape while fullscreen at the contents list must leave the viewer",
            1,
            exits.get()
        )
        assertTrue(
            "the screen must not toggle fullscreen itself",
            fullscreen
        )
    }

    @Test
    fun `escape while fullscreen with a text entry preview open closes the preview`() {
        // Residual regression, entry-preview shape: a TEXT entry preview
        // composes no media player, so even while the window is
        // fullscreen (F11) Escape must run the ladder — closing the
        // entry preview back to the contents list, then leaving the
        // viewer at the root. On the unscoped MF-1 code both Escapes
        // do nothing.
        val zipBytes = archiveZipBytes(
            listOf(
                "clip.mp4" to ByteArray(96) { 1 },
                "note.txt" to "NOTE ENTRY BODY".toByteArray()
            )
        )
        setScreen(
            driveFile(
                id = "fullscreen-zip-text",
                name = "media.zip",
                mimeType = "application/zip",
                category = FileCategory.ARCHIVE,
                sizeBytes = zipBytes.size.toLong(),
                localPath = null
            ),
            zipBytes
        )
        waitDisplayed("note.txt")

        // Display order inside the zip is name order: clip.mp4 (0), note.txt (1).
        rule.onNodeWithText("note.txt").performClick()
        waitPreview(1)
        assertTrue(
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Text
        )

        fullscreen = true

        // Escape closes the text preview back to the contents list,
        // without exiting the viewer and without toggling fullscreen.
        pressEscape()
        waitPreview(null)
        assertEquals(0, exits.get())
        assertTrue(fullscreen)
        waitDisplayed("clip.mp4")

        // And at the zip root, Escape leaves the viewer, still fullscreen.
        pressEscape()
        assertEquals(1, exits.get())
    }
}
