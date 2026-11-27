package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * P2 (1.6.26): Escape as a back key in the desktop preview, driven through
 * the REAL [DesktopPreviewScreen] exactly as
 * [DesktopArchiveProductionBackStepTest] drives Backspace — the press
 * arrives on the window's focused node after mouse use.
 *
 * Two production paths:
 *  - inside a ZIP, Escape runs the wired ladder (one folder step per
 *    press, exit only at the zip root) — it must not break the existing
 *    Backspace/Alt+Left behaviour, and it must not exit early;
 *  - for a normal file, Escape leaves the viewer, like the top-bar Back.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopPreviewEscapeBackTest {

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
            private val root = File.createTempFile("preview-escape", "").also {
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

    private fun pressEscape() {
        rule.onRoot().performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
    }

    @Test
    fun `escape inside a zip steps up one folder level and exits only at the root`() {
        val zipBytes = archiveZipBytes(
            listOf(
                "a/top.txt" to "top".toByteArray(),
                "a/b/mid.txt" to "mid".toByteArray(),
                "root.txt" to "root".toByteArray()
            )
        )
        setScreen(
            driveFile(
                id = "escape-zip",
                name = "escape.zip",
                mimeType = "application/zip",
                category = FileCategory.ARCHIVE,
                sizeBytes = zipBytes.size.toLong(),
                localPath = null
            ),
            zipBytes
        )
        waitDisplayed("root.txt")
        rule.onNodeWithTag("archive-folder-a").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("archive-folder-a/b").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("mid.txt").assertIsDisplayed()

        // One Escape: only the deepest folder collapses.
        pressEscape()
        assertEquals("escape inside the zip must not exit the viewer", 0, exits.get())
        assertFalse("mid.txt must be hidden after one escape", displayed("mid.txt"))
        assertTrue("top.txt must still show after one escape", displayed("top.txt"))

        pressEscape()
        assertEquals(0, exits.get())
        assertTrue("root.txt must show at the root", displayed("root.txt"))

        // At the zip root, Escape leaves the viewer.
        pressEscape()
        assertEquals(1, exits.get())
    }

    @Test
    fun `escape on a normal file leaves the viewer`() {
        val textFile = File.createTempFile("escape-note", ".txt").apply {
            writeText("a plain note")
        }
        setScreen(
            driveFile(
                id = "escape-text",
                name = "note.txt",
                mimeType = "text/plain",
                category = FileCategory.fromMimeType("text/plain"),
                sizeBytes = textFile.length(),
                localPath = textFile.absolutePath
            ),
            ByteArray(0)
        )
        waitDisplayed("a plain note")

        pressEscape()
        assertEquals("escape on a normal preview must leave the viewer", 1, exits.get())
    }
}
