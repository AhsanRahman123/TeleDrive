package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.compose.KoinApplication
import org.koin.dsl.module

/**
 * PC 1.6.26 item P3 — the two-level switching rule, driven through the REAL
 * [DesktopPreviewScreen] with a real [PreviewViewModel] (the production
 * route, same harness shape as [DesktopArchiveProductionBackStepTest]).
 *
 * The folder holds three mixed files: first.txt, bundle.zip, last.txt.
 * The rule: the viewer switches within the level you are in.
 *  - Folder level: W/E move through the folder's files; landing on the zip
 *    SHOWS ITS CONTENTS LIST (a zip is stepped onto like a folder, never
 *    paged past, never played).
 *  - Zip level: opening an entry enters the zip; W/E then move ONLY within
 *    that zip's entries, across all entry types (text, image, text, audio
 *    here, in display order).
 *  - Back/Esc exits to folder level and switching resumes from the zip's
 *    own position.
 *
 * The 1.6.25 bug this regresses: the screen's W/E handler moved the OUTER
 * folder index even while an entry preview was open, so W/E inside a zip
 * jumped zip-to-zip (here: out of the zip to last.txt) instead of stepping
 * to the next entry.
 *
 * A second defect this round's first CI run proved (diagnostic probe: the
 * overlay's Next BUTTON stepped text->audio while the E KEY did not): only
 * the in-zip IMAGE overlay requested keyboard focus, so stepping onto a
 * text/audio/video/PDF entry disposed the focused surface and left
 * nothing focused — W/E went dead until the next mouse click. Every
 * in-zip overlay now requests focus on open/step, like the image overlay
 * always did; the text->audio key step below pins it.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopTwoLevelSwitchingTest {

    @get:Rule
    val rule = createComposeRule()

    private val exits = AtomicInteger(0)
    private lateinit var viewModel: PreviewViewModel

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

    private val imageBytes: ByteArray by lazy {
        val image = java.awt.image.BufferedImage(6, 4, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        javax.imageio.ImageIO.write(image, "png", out)
        out.toByteArray()
    }

    // Display order inside the zip is NAME order (the pane's default sort):
    // alpha.txt (0), beta.png (1), delta.txt (2), gamma.mp3 (3) — the zip is
    // built in that same order so listing indices read naturally.
    private val entryData by lazy {
        listOf(
            "alpha.txt" to "ALPHA ENTRY BODY".toByteArray(),
            "beta.png" to imageBytes,
            "delta.txt" to "DELTA ENTRY BODY".toByteArray(),
            "gamma.mp3" to ("ID3".toByteArray() + ByteArray(64) { 7 })
        )
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

    private fun localTextFile(id: String, name: String, body: String): DriveFile {
        val file = File.createTempFile(id, ".txt")
        file.writeText(body)
        file.deleteOnExit()
        return driveFile(
            id = id,
            name = name,
            sizeBytes = body.toByteArray().size.toLong(),
            mimeType = "text/plain",
            category = FileCategory.DOCUMENT,
            localPath = file.absolutePath
        )
    }

    private fun setProductionScreen() {
        val zipBytes = archiveZipBytes(entryData)
        val first = localTextFile("first", "first.txt", "FIRST FILE BODY")
        val zip = driveFile(
            id = "bundle",
            name = "bundle.zip",
            sizeBytes = zipBytes.size.toLong(),
            mimeType = "application/zip",
            category = FileCategory.ARCHIVE,
            localPath = null
        )
        val last = localTextFile("last", "last.txt", "LAST FILE BODY")
        val filesInOrder = listOf(first, zip, last)
        val filesById = filesInOrder.associateBy { it.id }

        val telegram = archiveFakeTelegram(zipBytes)
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
            private val root = File.createTempFile("two-level", "").also {
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
            savedStateHandle = SavedStateHandle(mapOf("fileId" to zip.id)),
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
        rule.setContent {
            MaterialTheme {
                KoinApplication(application = { modules(koinModule) }) {
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

    @Test
    fun `folder level pages mixed files and landing on the zip shows its contents list`() {
        setProductionScreen()

        // The viewer opened on the zip: folder-level landing on a zip shows
        // its contents list — the zip is a stop, not something paged past.
        waitDisplayed("alpha.txt")
        assertTrue(displayed("beta.png"))
        assertTrue(displayed("delta.txt"))
        assertTrue(displayed("gamma.mp3"))

        // Folder level: E moves to the NEXT folder file after the zip.
        // (A press on the top-bar title first — the mouse user's window
        // focus, which the screen's pointer handling reclaims to the
        // Scaffold the W/E routing lives on.)
        rule.onNodeWithText("bundle.zip").performClick()
        rule.waitForIdle()
        press(Key.E)
        try {
            waitDisplayed("LAST FILE BODY")
        } catch (failure: AssertionError) {
            // DIAGNOSTIC PROBE (temporary): dump what the screen actually
            // shows and what the ViewModel holds, so the next run says
            // whether the press was lost, the index moved elsewhere, or
            // the content never resolved.
            val tree = rule.onRoot().printToString()
            throw AssertionError(
                "PROBE: alpha=${displayed("alpha.txt")} first=${displayed("FIRST FILE BODY")} " +
                    "gamma=${displayed("gamma.mp3")} exits=${exits.get()} " +
                    "files=${viewModel.uiState.value.files.map { it.name }} " +
                    "preview=${viewModel.uiState.value.archivePreview} " +
                    "tree=${tree.take(1500)}"
            )
        }
        assertEquals("folder-level E must not exit the viewer", 0, exits.get())

        // W returns to the zip, and its contents list is what shows again.
        press(Key.W)
        waitDisplayed("alpha.txt")
        waitNotDisplayed("LAST FILE BODY")

        // W again reaches the file before the zip.
        press(Key.W)
        waitDisplayed("FIRST FILE BODY")

        // And E steps back ONTO the zip (list), not past it.
        press(Key.E)
        waitDisplayed("alpha.txt")
        assertEquals(0, exits.get())
    }

    @Test
    fun `zip level switching stays inside the zip across all entry types`() {
        setProductionScreen()
        waitDisplayed("alpha.txt")

        // Opening an entry enters zip level.
        rule.onNodeWithText("alpha.txt").performClick()
        waitPreview(0)
        waitDisplayed("ALPHA ENTRY BODY")

        // E steps to the image entry — pre-fix this press left the zip and
        // opened last.txt in the outer folder sequence.
        press(Key.E)
        waitPreview(1)
        assertTrue(
            "stepping inside the zip must not reach the outer folder",
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Image
        )
        assertFalse(displayed("LAST FILE BODY"))

        // E steps image -> text, then text -> audio: every type is a stop.
        press(Key.E)
        waitPreview(2)
        waitDisplayed("DELTA ENTRY BODY")

        press(Key.E)
        waitPreview(3)
        assertTrue(
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Audio
        )

        // The end of the zip's sequence is a hard stop: E does not fall out
        // into the folder.
        press(Key.E)
        Thread.sleep(400)
        rule.waitForIdle()
        waitPreview(3)
        assertFalse(displayed("LAST FILE BODY"))

        // W walks back through the same entries, all types, and stops at
        // the first entry instead of leaving the zip.
        press(Key.W)
        waitPreview(2)
        press(Key.W)
        waitPreview(1)
        press(Key.W)
        waitPreview(0)
        press(Key.W)
        Thread.sleep(400)
        rule.waitForIdle()
        waitPreview(0)
        assertFalse(displayed("FIRST FILE BODY"))
        assertEquals(0, exits.get())
    }

    @Test
    fun `escape and back exit to folder level and switching resumes at the zip position`() {
        setProductionScreen()
        waitDisplayed("alpha.txt")

        // Enter zip level, step once, then Escape: back at folder level,
        // the zip's contents list showing.
        rule.onNodeWithText("alpha.txt").performClick()
        waitPreview(0)
        press(Key.E)
        waitPreview(1)
        press(Key.Escape)
        waitPreview(null)
        waitDisplayed("alpha.txt")
        assertEquals("Escape from an entry must not exit the viewer", 0, exits.get())

        // Switching now resumes from the ZIP's position: E goes to the file
        // after the zip, W comes back onto the zip.
        press(Key.E)
        waitDisplayed("LAST FILE BODY")
        press(Key.W)
        waitDisplayed("delta.txt")

        // Same exit through the top-bar Back button (the back ladder's
        // CLOSE_PREVIEW step, unchanged by this round).
        rule.onNodeWithText("delta.txt").performClick()
        waitPreview(2)
        waitDisplayed("DELTA ENTRY BODY")
        rule.onNodeWithContentDescription("Back").performClick()
        waitPreview(null)
        waitDisplayed("alpha.txt")
        assertEquals(0, exits.get())

        press(Key.E)
        waitDisplayed("LAST FILE BODY")
        assertEquals(0, exits.get())
    }
}
