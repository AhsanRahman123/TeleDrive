package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.entity.CacheEntryEntity
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.testing.unused
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 3 A10: the long-checklist field failure through the PRODUCTION entry
 * points — [PreviewContentResolver.resolve] (the Files pager call) and
 * [PreviewContentResolver.resolveLocalPath] (the Transfers local viewer call) —
 * not through a read helper.
 *
 * The fixture `viewer_fixtures/Checklist_52.html` is a 52-item checklist page
 * (headings, sections, native checkboxes driven by an inline script that also
 * persists to localStorage, inline CSS, in-page anchor links, one external
 * link, one download link) of ~278 KiB. Pre-fix, `readHtml` reused the
 * plain-text reader's 256 KiB cap, so the production route handed the WebView
 * a document cut off part-way down: item 52 and the end-of-checklist footer
 * never existed for the viewer, and the `truncated` flag that would have said
 * so was dropped by every HtmlPreview call site. These tests fail on that
 * code (Html text ends before CHECKLIST-ITEM-52, truncated = true) and pass
 * with the dedicated HTML budget.
 *
 * What host tests cannot prove is stated plainly: that the Android WebView
 * actually paints/scrolls the whole document and that a tap on the field
 * device no longer crashes are instrumentation/field properties. The host
 * side pins everything upstream of the WebView (the full document string
 * reaches [PreviewContent.Html] untruncated) plus the pure navigation /
 * subresource / settings policy the WebView client enforces
 * (TextPreviewsTest). The WebView-side argument is in HtmlPreview.kt.
 */
class HtmlPreviewProductionRouteTest {

    // ------------------------------------------------------------ fakes

    private class FakeSettings(initial: UserPreferences) : SettingsRepository by unused() {
        private val state = MutableStateFlow(initial)
        override val preferences: Flow<UserPreferences> = state
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            state.value = transform(state.value)
        }
    }

    private class FakeCacheDao : CacheDao by unused() {
        override suspend fun upsert(entry: CacheEntryEntity) = Unit
        override suspend fun touch(path: String, accessedAt: Long) = Unit
    }

    private class FakeFilePartDao : FilePartDao by unused() {
        override suspend fun partsOf(fileId: String): List<FilePartEntity> = emptyList()
    }

    private class FakeKeys : WrappedKeyRepository by unused()

    private class FakeDownloadWriter : DownloadWriter by unused()

    private class FakeTelegramClient(
        private val downloadedFile: File
    ) : TelegramClient by unused() {
        override fun downloadDocument(
            remoteFileId: String,
            chatId: Long?,
            messageId: Long?,
            queuePriority: Int
        ): Flow<TelegramDownloadEvent> = flowOf(
            TelegramDownloadEvent.Completed(downloadedFile.absolutePath, downloadedFile.length())
        )
    }

    private class FakeStoragePaths(root: File) : AppStoragePaths {
        override val filesDir: File = File(root, "files").apply { mkdirs() }
        override val cacheDir: File = File(root, "cache").apply { mkdirs() }
        override val externalCacheDir: File? = null
        override val externalStorageRoot: File? = null
    }

    private object TestDispatchers : DispatcherProvider {
        override val main: CoroutineDispatcher = Dispatchers.Unconfined
        override val io: CoroutineDispatcher = Dispatchers.Unconfined
        override val default: CoroutineDispatcher = Dispatchers.Unconfined
    }

    private fun resolver(root: File, telegram: TelegramClient): PreviewContentResolver =
        PreviewContentResolver(
            storagePaths = FakeStoragePaths(root),
            telegramClient = telegram,
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = FakeKeys(),
            cacheDao = FakeCacheDao(),
            filePartDao = FakeFilePartDao(),
            settingsRepository = FakeSettings(UserPreferences()),
            dispatchers = TestDispatchers,
            remoteArchiveService = RemoteArchiveService(
                telegramClient = telegram,
                filePartDao = FakeFilePartDao(),
                downloadWriter = FakeDownloadWriter(),
                folderPathResolver = FolderPathResolver(
                    unused<FolderDao>(),
                    ActiveChannel(unused<SettingsRepository>()),
                    unused<FolderTombstoneDao>()
                )
            )
        )

    // ------------------------------------------------------------ helpers

    private fun freshRoot(): File =
        java.nio.file.Files.createTempDirectory("html-preview-route").toFile()

    private fun fixtureBytes(): ByteArray =
        javaClass.getResourceAsStream("/viewer_fixtures/Checklist_52.html")
            ?.use { it.readBytes() }
            ?: error("missing test fixture Checklist_52.html")

    private fun driveFile(
        name: String,
        mimeType: String,
        category: FileCategory,
        sizeBytes: Long,
        localPath: String?
    ) = DriveFile(
        id = "file-$name",
        folderId = null,
        name = name,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        category = category,
        localPath = localPath,
        contentHash = null,
        chatId = null,
        messageId = null,
        remoteFileId = null,
        remoteUniqueId = null,
        backupState = BackupState.BACKED_UP,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        isEncrypted = false,
        width = null,
        height = null,
        durationMs = null,
        trashedAt = null,
        createdAt = 0,
        modifiedAt = 0,
        addedAt = 0
    )

    private fun assertCompleteChecklist(content: PreviewContent, fixtureBytes: ByteArray) {
        assertTrue("expected PreviewContent.Html, was $content", content is PreviewContent.Html)
        val html = content as PreviewContent.Html
        assertFalse(
            "a ~278 KiB checklist must arrive whole, not truncated at the old text cap",
            html.truncated
        )
        assertEquals(
            "the viewer must receive the whole document, exactly as decoded from disk",
            String(fixtureBytes, Charsets.UTF_8), html.text
        )
        assertTrue("first item missing", html.text.contains("CHECKLIST-ITEM-01"))
        assertTrue(
            "item 52 sits past the old 256 KiB cap and must reach the viewer",
            html.text.contains("CHECKLIST-ITEM-52")
        )
        assertTrue(
            "the end-of-document footer must reach the viewer",
            html.text.contains("END-OF-CHECKLIST")
        )
        assertTrue(
            "the page's inline script must reach the viewer (its checkboxes are script-driven)",
            html.text.contains("function toggleItem")
        )
    }

    // ------------------------------------------------------------ tests

    @Test
    fun `fixture really is a long page past the old plain-text cap`() {
        val bytes = fixtureBytes()
        assertTrue(
            "fixture (${bytes.size} bytes) must exceed the 256 KiB plain-text cap " +
                "or it cannot reproduce the field truncation",
            bytes.size > 256 * 1024
        )
        assertTrue(
            "fixture must stay far below the HTML budget or the route tests stop proving completeness",
            bytes.size < PreviewContentResolver.HTML_PREVIEW_LIMIT
        )
    }

    @Test
    fun `production route - long checklist html arrives at the viewer complete`() {
        val root = freshRoot()
        val bytes = fixtureBytes()
        val diskCopy = File(root, "Checklist_52.html").apply { writeBytes(bytes) }
        val file = driveFile(
            name = "Checklist_52.html",
            mimeType = "text/html",
            category = FileCategory.DOCUMENT,
            sizeBytes = bytes.size.toLong(),
            localPath = diskCopy.absolutePath
        )
        val content = runBlocking {
            resolver(root, FakeTelegramClient(diskCopy)).resolve(file).toList().last()
        }
        assertCompleteChecklist(content, bytes)
    }

    @Test
    fun `local path route - long checklist html arrives at the viewer complete`() {
        val root = freshRoot()
        val bytes = fixtureBytes()
        val diskCopy = File(root, "Checklist_52.html").apply { writeBytes(bytes) }
        val content = runBlocking {
            resolver(root, FakeTelegramClient(diskCopy))
                .resolveLocalPath(diskCopy.absolutePath, "Checklist_52.html")
                .toList().last()
        }
        assertCompleteChecklist(content, bytes)
    }

    @Test
    fun `html read budget equals the remote auto-fetch cap for text`() {
        assertEquals(
            PreviewContentResolver.AUTO_PREVIEW_LIMIT,
            PreviewContentResolver.HTML_PREVIEW_LIMIT
        )
    }

    @Test
    fun `neighbour unchanged - a plain text file past the text cap still truncates there`() {
        val root = freshRoot()
        // Pure ASCII, so byte count and decoded char count agree exactly.
        val bytes = "a".repeat(300 * 1024).toByteArray(Charsets.UTF_8)
        val diskCopy = File(root, "notes.txt").apply { writeBytes(bytes) }
        val file = driveFile(
            name = "notes.txt",
            mimeType = "text/plain",
            category = FileCategory.DOCUMENT,
            sizeBytes = bytes.size.toLong(),
            localPath = diskCopy.absolutePath
        )
        val content = runBlocking {
            resolver(root, FakeTelegramClient(diskCopy)).resolve(file).toList().last()
        }
        assertTrue("expected PlainText, was $content", content is PreviewContent.PlainText)
        val text = content as PreviewContent.PlainText
        assertTrue("plain text keeps its 256 KiB truncation flag", text.truncated)
        assertEquals(256 * 1024, text.text.length)
    }
}
