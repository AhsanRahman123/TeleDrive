package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.koin.compose.KoinApplication
import org.koin.dsl.module

/**
 * PC 1.6.26 item P5 — text/document viewer zoom on PC, driven through the
 * REAL [DesktopPreviewScreen] (production route, same harness shape as
 * [DesktopArchiveProductionBackStepTest]).
 *
 * Root cause of the gap: the shared persisted scale
 * (`UserPreferences.textPreviewScale`, exposed by
 * [PreviewViewModel.textScale]) existed all along and Android's viewer
 * uses it, but the desktop screen never collected it — every PC text
 * viewer added in 1.6.25 (EPUB/OOXML text, Markdown, plain text,
 * subtitles, JSON/XML pretty-print, all publishing PlainText) rendered at
 * one fixed size. This test pins the desktop wiring: the zoom buttons and
 * Ctrl+plus/minus/0 step the ONE shared scale, and the scale — hence the
 * rendered size — is the same after paging to another text file (a
 * Markdown file here, proving consistency across viewer flavours).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopTextZoomTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeSettingsRepository : SettingsRepository {
        val prefs = MutableStateFlow(UserPreferences())
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

    private lateinit var settings: FakeSettingsRepository

    private fun driveFile(
        id: String,
        name: String,
        mimeType: String,
        localPath: String,
        sizeBytes: Long
    ): DriveFile = DriveFile(
        id = id,
        folderId = null,
        name = name,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        category = FileCategory.DOCUMENT,
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

    private fun localFile(id: String, name: String, mimeType: String, body: String): DriveFile {
        val file = File.createTempFile(id, ".tmp")
        file.writeText(body)
        file.deleteOnExit()
        return driveFile(id, name, mimeType, file.absolutePath, body.toByteArray().size.toLong())
    }

    private fun setProductionScreen() {
        val plain = localFile("plain", "plain.txt", "text/plain", "PLAIN BODY TEXT")
        val markdown = localFile(
            "notes", "notes.md", "text/markdown", "# Heading\nMARKDOWN BODY TEXT"
        )
        val filesInOrder = listOf(plain, markdown)
        val filesById = filesInOrder.associateBy { it.id }

        val telegram = archiveFakeTelegram(ByteArray(0))
        settings = FakeSettingsRepository()
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
            private val root = File.createTempFile("text-zoom", "").also {
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
        val viewModel = PreviewViewModel(
            savedStateHandle = SavedStateHandle(mapOf("fileId" to plain.id)),
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
                            DesktopPreviewScreen(onBack = {}, viewModel = viewModel)
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

    private fun waitScale(expected: Float) {
        val deadline = System.currentTimeMillis() + 15_000
        while (true) {
            val current = runBlocking { settings.preferences.first().textPreviewScale }
            if (current == expected) return
            if (System.currentTimeMillis() > deadline) {
                throw AssertionError("Timed out waiting for text scale $expected; current=$current")
            }
            Thread.sleep(50)
            rule.waitForIdle()
        }
    }

    @Test
    fun `buttons and ctrl keys step one shared scale that survives paging between text viewers`() {
        setProductionScreen()
        waitDisplayed("PLAIN BODY TEXT")
        waitDisplayed("100%")

        // Zoom-in button: one ladder step, written to the shared preference.
        rule.onNodeWithContentDescription("Zoom in").performClick()
        waitScale(1.15f)
        waitDisplayed("115%")

        // Ctrl+plus: another step on the same scale.
        rule.onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals) }
        }
        waitScale(1.3f)
        waitDisplayed("130%")

        // Page to the Markdown file: a different viewer flavour, the SAME
        // scale — the percentage (and the rendered size) does not reset.
        rule.onRoot().performKeyInput { pressKey(Key.E) }
        waitDisplayed("MARKDOWN BODY TEXT")
        waitDisplayed("130%")
        assertEquals(1.3f, settings.prefs.value.textPreviewScale)

        // Ctrl+minus steps back down; clicking the percentage resets.
        rule.onRoot().performKeyInput {
            withKeyDown(Key.CtrlLeft) { pressKey(Key.Minus) }
        }
        waitScale(1.15f)
        rule.onNodeWithTag(TEXT_ZOOM_PERCENT_TAG).performClick()
        waitScale(1f)
        waitDisplayed("100%")

        // Zoom-out button from 100% reaches the step below.
        rule.onNodeWithContentDescription("Zoom out").performClick()
        waitScale(0.85f)
        waitDisplayed("85%")

        assertTrue(displayed("MARKDOWN BODY TEXT"))
    }
}
