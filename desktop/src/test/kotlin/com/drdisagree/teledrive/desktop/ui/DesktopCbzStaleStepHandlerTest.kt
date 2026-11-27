package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
 * MF-2 (PC 1.6.26 second-pass review): a disposed archive pane's W/E
 * step registration must never fire again.
 *
 * The pane registers its in-zip step handler by effect with NO
 * dispose-clear (deliberately: during a folder-page transition the
 * outgoing pane's dispose would clear the incoming pane's handler).
 * The old safety argument — "paging away is only possible from the
 * contents list, so a stale handler's preview is closed and it
 * declines" — is false for a CBZ: the screen-level edge pager buttons
 * are hidden only for .zip files (mime application/zip or a .zip
 * extension), so a .cbz keeps them while an entry preview is open.
 * Paging the folder away from the CBZ leaves the dead pane's handler
 * registered with the preview still open in the shared ViewModel, and
 * it then consumed every later W/E in the session, stepping the
 * invisible CBZ preview instead of paging the folder.
 *
 * The fix keys the registration by file on the screen side: the screen
 * invokes the handler only while the file it was registered for is the
 * currently displayed one. This test drives the REAL
 * [DesktopPreviewScreen]: open a CBZ entry preview, page the folder
 * with the edge Next button to a plain text file, then W must page the
 * folder back — pre-fix the stale handler consumes the press (its
 * captured preview is still the open one, even though the pane's
 * dispose already dismissed the ViewModel's copy) and the folder
 * never moves.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopCbzStaleStepHandlerTest {

    @get:Rule
    val rule = createComposeRule()

    private val exits = AtomicInteger(0)
    private lateinit var viewModel: PreviewViewModel

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
        sizeBytes: Long,
        mimeType: String,
        category: FileCategory,
        localPath: String?
    ): DriveFile = DriveFile(
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
        remoteFileId = "remote-$id",
        remoteUniqueId = "unique-$id",
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

    private fun setProductionScreen() {
        // Display order inside the CBZ is name order: page1.txt (0),
        // page2.txt (1). Text pages on purpose: the stale-handler
        // mechanics are the same for every entry-preview type, and the
        // text overlay keeps this test off the photo viewer's async
        // image/zoom state, which raced the recomposer in CI run
        // 46007309824 (snapshot concurrent-change crash).
        val cbzBytes = archiveZipBytes(
            listOf(
                "page1.txt" to "PAGE ONE BODY".toByteArray(),
                "page2.txt" to "PAGE TWO BODY".toByteArray()
            )
        )
        val cbz = driveFile(
            id = "comic",
            name = "comic.cbz",
            sizeBytes = cbzBytes.size.toLong(),
            mimeType = "application/vnd.comicbook+zip",
            category = FileCategory.ARCHIVE,
            localPath = null
        )
        val nextText = File.createTempFile("next", ".txt")
        nextText.writeText("NEXT FILE BODY")
        nextText.deleteOnExit()
        val next = driveFile(
            id = "next",
            name = "next.txt",
            sizeBytes = "NEXT FILE BODY".toByteArray().size.toLong(),
            mimeType = "text/plain",
            category = FileCategory.DOCUMENT,
            localPath = nextText.absolutePath
        )
        val filesInOrder = listOf(cbz, next)
        val filesById = filesInOrder.associateBy { it.id }

        val telegram = archiveFakeTelegram(cbzBytes)
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
            private val root = File.createTempFile("cbz-stale", "").also {
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
        val fileRepository = archiveTestProxy<FileRepository> { method, args ->
            when (method) {
                "getFile" -> filesById[args!![0] as String]
                "observeFiles" -> flowOf(filesInOrder)
                "observeFile" -> flowOf(filesById[args!![0] as String])
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
            savedStateHandle = SavedStateHandle(mapOf("fileId" to cbz.id)),
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
                        LocalUrlOpener provides UrlOpener { }
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

    private fun waitNotDisplayed(text: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (displayed(text)) {
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for $text to disappear")
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

    private fun press(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    /**
     * Clicks the SCREEN-level edge Next pager button. Two nodes carry the
     * "Next file" description while an in-zip image preview is open: the
     * screen's folder pager (vertically centred on the window's right
     * edge) and the image overlay's own in-zip pager (below centre).
     * The screen's is the one whose centre sits on the window's vertical
     * centre — that is the button this scenario pages the folder with.
     */
    private fun clickScreenNextPager() {
        val all = rule.onAllNodesWithContentDescription("Next file")
        val nodes = all.fetchSemanticsNodes()
        if (nodes.isEmpty()) throw AssertionError("No 'Next file' pager button found")
        val rootBounds = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val windowCenterY = (rootBounds.top + rootBounds.bottom) / 2f
        val targetIndex = nodes.indices.minBy { i ->
            val b = nodes[i].boundsInRoot
            kotlin.math.abs((b.top + b.bottom) / 2f - windowCenterY)
        }
        all[targetIndex].performClick()
        rule.waitForIdle()
    }

    @Test
    fun `after paging away from a cbz with its preview open W and E page the folder not the dead preview`() {
        setProductionScreen()
        waitDisplayed("page1.txt")
        assertTrue(displayed("page2.txt"))

        // Open the CBZ's first page: an entry preview is open at zip level.
        rule.onNodeWithText("page1.txt").performClick()
        waitPreview(0)
        assertTrue(
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Text
        )
        waitDisplayed("PAGE ONE BODY")

        // The CBZ keeps the screen-level edge pager buttons (they hide
        // only for .zip files): page the folder to the plain text file
        // WHILE the entry preview is still open.
        clickScreenNextPager()
        waitDisplayed("NEXT FILE BODY")
        // Let the page transition finish and the CBZ pane dispose; its
        // step registration is what goes stale at this point. (The
        // pane's own dispose dismisses the ViewModel's preview copy —
        // the stale HANDLER's captured preview stays open, which is
        // exactly why it keeps consuming presses pre-fix.)
        Thread.sleep(600)
        rule.waitForIdle()
        waitPreview(null)

        // E at the folder's last file moves nothing, and must not
        // resurrect or step anything in the dead CBZ.
        press(Key.E)
        Thread.sleep(400)
        rule.waitForIdle()
        assertTrue("the folder must still show the text file", displayed("NEXT FILE BODY"))
        assertEquals(
            "no in-zip preview may be open on the text file",
            null,
            viewModel.uiState.value.archivePreview
        )

        // W must page the FOLDER back to the CBZ. Pre-fix the stale
        // handler consumed this press (returning true for its captured,
        // still-open preview) and the folder never moved: NEXT FILE
        // BODY would stay on screen and this wait would time out.
        press(Key.W)
        waitNotDisplayed("NEXT FILE BODY")
        waitDisplayed("page1.txt")
        assertEquals("paging the folder must not exit the viewer", 0, exits.get())

        // Back on the CBZ, its own W/E routing is live again (the pane
        // re-registered for the now-current file): open the first page
        // and E steps the preview that is actually on screen.
        rule.onNodeWithText("page1.txt").performClick()
        waitPreview(0)
        press(Key.E)
        waitPreview(1)
        waitDisplayed("PAGE TWO BODY")
    }
}
