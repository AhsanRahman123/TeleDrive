package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
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
 * 1.6.25 item 3 — PRODUCTION-WIRING back-step test.
 *
 * The 1.6.24 pin (`DesktopArchiveBackStepTest`) composes `DesktopArchivePane`
 * ALONE and invokes the handler the pane registers into a test-owned reference.
 * Production never composes the pane alone: the pane lives inside
 * `PreviewPane` inside `DesktopPreviewScreen`'s AnimatedContent, the handler
 * is stored in the SCREEN's `archiveBackHandler` state, and the press arrives
 * either as a click on the screen's top-bar back button or as a real key event
 * through the screen's focus tree. Pranto's 1.6.24 field test showed one Back
 * press from inside a zip folder still exiting the whole viewer, so the seam
 * the old test pins is not the seam that fails. This test composes the REAL
 * `DesktopPreviewScreen` (with a real PreviewViewModel over fakes) and drives
 * both production press paths.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveProductionBackStepTest {

    @get:Rule
    val rule = createComposeRule()

    private val exits = AtomicInteger(0)

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

    private val entryData = listOf(
        "a/top.txt" to "top".toByteArray(),
        "a/b/mid.txt" to "mid".toByteArray(),
        "a/b/c/deep.txt" to "deep".toByteArray(),
        "root.txt" to "root".toByteArray()
    )

    private fun zipFile(sizeBytes: Long): DriveFile = DriveFile(
        id = "prod-back-zip",
        folderId = null,
        name = "prod.zip",
        sizeBytes = sizeBytes,
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

    private fun setProductionScreen() {
        val zipBytes = archiveZipBytes(entryData)
        val file = zipFile(zipBytes.size.toLong())
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
            private val root = File.createTempFile("archive-prod-back", "").also {
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
        val koinModule = module {
            single { MediaStreamServer() }
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

    private fun descend() {
        waitDisplayed("root.txt")
        rule.onNodeWithTag("archive-folder-a").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("archive-folder-a/b").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("archive-folder-a/b/c").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("deep.txt").assertIsDisplayed()
    }

    @Test
    fun `production top-bar back steps up one folder level and exits only at the zip root`() {
        setProductionScreen()
        descend()

        // One top-bar Back press: only the deepest folder collapses.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals("top-bar back inside the zip must not exit the viewer", 0, exits.get())
        assertFalse("deep.txt must be hidden after one back", displayed("deep.txt"))
        assertTrue("mid.txt must still show after one back", displayed("mid.txt"))

        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(0, exits.get())
        assertFalse("mid.txt must be hidden after two backs", displayed("mid.txt"))
        assertTrue("top.txt must still show after two backs", displayed("top.txt"))

        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(0, exits.get())
        assertFalse("top.txt must be hidden at the root", displayed("top.txt"))
        assertTrue("root.txt must show at the root", displayed("root.txt"))

        // At the zip root the press leaves the viewer.
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(1, exits.get())
    }

    @Test
    fun `production backspace after mouse use steps up one folder level`() {
        setProductionScreen()
        descend()

        // The mouse path a real user takes: folders were opened by clicking,
        // then Backspace is pressed on the window's focused node (the root,
        // wherever focus actually sits after mouse use).
        rule.onRoot().performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertEquals("backspace inside the zip must not exit the viewer", 0, exits.get())
        assertFalse("deep.txt must be hidden after backspace", displayed("deep.txt"))
        assertTrue("mid.txt must still show after backspace", displayed("mid.txt"))
    }
}
