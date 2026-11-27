package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.looksLikeText
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * vc186 round 3 — A1 (third strike) + X1, driven through the PRODUCTION
 * entry points only:
 *
 *  - Files route: [PreviewContentResolver.resolve], the exact call
 *    `PreviewViewModel.contentFor` makes.
 *  - In-zip route: the one-argument [extractDocumentText] call
 *    `PreviewViewModel` makes on the extracted temp file, fed with temps
 *    named exactly as `RemoteArchiveService.createEntryTempFile` names
 *    them, plus extension-less and wrong-extension variants.
 *
 * A1's device failure lived BELOW every seam the earlier rounds pinned:
 * the extractors ran on the platform XML stack (`javax.xml.parsers`),
 * which is Xerces on the desktop JDK (and in every host test) but a
 * KXmlParser-based builder on Android whose factory rejects the
 * hardening configuration outright. vc186 removes that dependency — the
 * extractors run on a pure-Kotlin parser in the shared layer — so these
 * host tests now exercise the same parsing code the phone runs. The
 * renamed/wrong-extension cases pin that neither route's outcome depends
 * on the on-disk copy's name any more.
 *
 * X1 cases pin the README family: name-trusted text dispatch, the
 * byte-sniff promotion for extension-less files on the Files route, and
 * known extension-less text names on the in-zip tap action.
 */
class ReaderRound3ProductionTest {

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
        java.nio.file.Files.createTempDirectory("reader-round3").toFile()

    private fun fixtureBytes(resourceName: String): ByteArray =
        javaClass.getResourceAsStream("/viewer_fixtures/$resourceName")
            ?.use { it.readBytes() }
            ?: error("missing test fixture $resourceName")

    private fun writeCopy(dir: File, diskName: String, bytes: ByteArray): File {
        val file = File(dir, diskName)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        return file
    }

    private fun driveFile(
        name: String,
        mimeType: String,
        category: FileCategory,
        sizeBytes: Long,
        localPath: String? = null
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

    private fun resolveLast(resolver: PreviewContentResolver, file: DriveFile): PreviewContent =
        runBlocking { resolver.resolve(file).toList().last() }

    private fun assertPlainText(content: PreviewContent, vararg markers: String) {
        assertTrue("expected PlainText, was $content", content is PreviewContent.PlainText)
        val text = (content as PreviewContent.PlainText).text
        for (marker in markers) {
            assertTrue("text should contain \"$marker\": $text", text.contains(marker))
        }
    }

    private fun localResolve(
        driveName: String,
        mimeType: String,
        category: FileCategory,
        diskName: String,
        bytes: ByteArray
    ): PreviewContent {
        val root = freshRoot()
        val diskCopy = writeCopy(root, diskName, bytes)
        val file = driveFile(
            name = driveName,
            mimeType = mimeType,
            category = category,
            sizeBytes = bytes.size.toLong(),
            localPath = diskCopy.absolutePath
        )
        return resolveLast(resolver(root, FakeTelegramClient(diskCopy)), file)
    }

    // --------------------------------------- A1: Files route, hostile on-disk names

    @Test
    fun `files route - epub with wrong-extension on-disk copy renders its text`() {
        val bytes = fixtureBytes("Classical_Conditioning.epub")
        val content = localResolve(
            "Classical_Conditioning.epub", EPUB_MIME, FileCategory.DOCUMENT,
            "download.bin", bytes
        )
        assertPlainText(content, "Chapter 1 — What Classical Conditioning Is", "Ivan Pavlov")
    }

    @Test
    fun `files route - docx with wrong-extension on-disk copy renders its text`() {
        val bytes = fixtureBytes("Classical_Conditioning.docx")
        val content = localResolve(
            "Classical_Conditioning.docx", DOCX_MIME, FileCategory.DOCUMENT,
            "download.dat", bytes
        )
        assertPlainText(
            content,
            "Classical Conditioning — How Learning by Association Works and Helps",
            "Ivan Pavlov"
        )
    }

    @Test
    fun `files route - epub with generic stored mime and extension-less copy renders`() {
        // Nothing classifies this file except its name (.epub) and, below
        // that, its ZIP content markers: stored MIME is generic, category
        // OTHER, and the on-disk copy has no extension at all.
        val bytes = fixtureBytes("Viewer_Test.epub")
        val content = localResolve(
            "Viewer_Test.epub", "application/octet-stream", FileCategory.OTHER,
            "blob-no-extension", bytes
        )
        assertPlainText(content, "Chapter 1 — It opens", "the EPUB viewer works")
    }

    @Test
    fun `files route - docx with generic stored mime and extension-less copy renders`() {
        val bytes = fixtureBytes("Viewer_Test.docx")
        val content = localResolve(
            "Viewer_Test.docx", "application/octet-stream", FileCategory.OTHER,
            "blob-no-extension", bytes
        )
        assertPlainText(content, "Viewer Test Document", "the Office viewer works")
    }

    // --------------------------------------- A1: in-zip route (production temp entry point)

    /** The exact call PreviewViewModel makes for a PREVIEW_DOCUMENT entry. */
    private fun inZipExtract(tempName: String, bytes: ByteArray): DocumentExtraction {
        val root = freshRoot()
        val temp = writeCopy(root, tempName, bytes)
        return extractDocumentText(temp)
    }

    private fun assertExtractionText(out: DocumentExtraction, vararg markers: String) {
        assertTrue("expected extracted text, was $out", out is DocumentExtraction.Text)
        val text = (out as DocumentExtraction.Text).text
        for (marker in markers) {
            assertTrue("extracted text should contain \"$marker\"", text.contains(marker))
        }
    }

    @Test
    fun `in-zip route - production-named epub temp extracts its text`() {
        val out = inZipExtract(
            "zip-entry-Classical_Conditioning-482910.epub",
            fixtureBytes("Classical_Conditioning.epub")
        )
        assertExtractionText(out, "Chapter 1 — What Classical Conditioning Is", "Ivan Pavlov")
    }

    @Test
    fun `in-zip route - production-named docx temp extracts its text`() {
        val out = inZipExtract(
            "zip-entry-Classical_Conditioning-482910.docx",
            fixtureBytes("Classical_Conditioning.docx")
        )
        assertExtractionText(
            out,
            "Classical Conditioning — How Learning by Association Works and Helps"
        )
    }

    @Test
    fun `in-zip route - extension-less temp with epub bytes extracts by content`() {
        val out = inZipExtract(
            "zip-entry-blob-112233.tmp",
            fixtureBytes("Viewer_Test.epub")
        )
        assertExtractionText(out, "Chapter 1 — It opens", "the EPUB viewer works")
    }

    @Test
    fun `in-zip route - extension-less temp with docx bytes extracts by content`() {
        val out = inZipExtract(
            "zip-entry-blob-445566.tmp",
            fixtureBytes("Viewer_Test.docx")
        )
        assertExtractionText(out, "Viewer Test Document", "the Office viewer works")
    }

    @Test
    fun `in-zip route - minimal fixtures extract through the temp entry point`() {
        assertExtractionText(
            inZipExtract("zip-entry-Viewer_Test-1.epub", fixtureBytes("Viewer_Test.epub")),
            "Chapter 2 — Second chapter reached"
        )
        assertExtractionText(
            inZipExtract("zip-entry-Viewer_Test-1.docx", fixtureBytes("Viewer_Test.docx")),
            "Second paragraph"
        )
    }

    // --------------------------------------- A1: the shared XML parser itself

    @Test
    fun `parser - fixture container resolves the opf path`() {
        val container = fixtureBytes("Classical_Conditioning.epub").let { epubBytes ->
            java.util.zip.ZipInputStream(epubBytes.inputStream()).use { zip ->
                var entry = zip.nextEntry
                var found: ByteArray? = null
                while (entry != null) {
                    if (entry.name == "META-INF/container.xml") {
                        found = zip.readBytes()
                        break
                    }
                    entry = zip.nextEntry
                }
                found ?: error("container.xml missing from fixture")
            }
        }
        val root = parseXmlToTree(container)
        assertTrue("container.xml must parse", root != null)
        assertEquals(
            "OEBPS/content.opf",
            root!!.allByLocalName("rootfile").firstOrNull()?.attribute("full-path")
        )
    }

    @Test
    fun `parser - doctype entity is never expanded and cdata stays literal`() {
        val xml = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <w:document xmlns:w="urn:x"><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p>
            <w:p><w:r><w:t><![CDATA[a < b & c]]></w:t></w:r></w:p></w:document>"""
        val text = docxToText(xml.toByteArray())
        assertFalse(text, text.contains("root:"))
        assertTrue(text, text.contains("a < b & c"))
    }

    @Test
    fun `parser - numeric character references and predefined entities decode`() {
        val xml = """<w:document xmlns:w="urn:x"><w:p><w:r><w:t>Tom &#x27;s &amp; Jerry&#8217;s</w:t></w:r></w:p></w:document>"""
        assertEquals("Tom 's & Jerry’s", docxToText(xml.toByteArray()))
    }

    @Test
    fun `parser - malformed xml yields no tree and no text`() {
        assertNull(parseXmlToTree("this is not xml at all".toByteArray()))
        assertNull(parseXmlToTree("<w:document><w:p>".toByteArray()))
        assertEquals("", docxToText("this is not xml at all".toByteArray()))
    }

    @Test
    fun `parser - utf16 encoded document xml extracts`() {
        val xml = """<w:document xmlns:w="urn:x"><w:p><w:r><w:t>Unicode body</w:t></w:r></w:p></w:document>"""
        val bytes = xml.toByteArray(Charsets.UTF_16LE)
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        assertEquals("Unicode body", docxToText(bom + bytes))
    }

    // --------------------------------------- X1: README family, Files route

    @Test
    fun `dispatch - text names are trusted when the stored mime is generic`() {
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "notes.txt")
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "README.md")
        )
        // Extension-less stays UNSUPPORTED at the pure dispatch: only the
        // byte sniff in fromLocal may promote it, never a guess.
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "README")
        )
        // Neighbours unchanged.
        assertEquals(
            PreviewDispatch.ARCHIVE,
            previewDispatch("application/zip", FileCategory.ARCHIVE, "archive.zip")
        )
    }

    @Test
    fun `files route - extension-less README opens in the text viewer`() {
        val bytes = "# Project\n\nRead me first.\n".toByteArray(Charsets.UTF_8)
        val content = localResolve(
            "README", "application/octet-stream", FileCategory.OTHER,
            "README", bytes
        )
        assertPlainText(content, "Read me first.")
    }

    @Test
    fun `files route - README md with generic mime opens in the text viewer`() {
        val bytes = "# Title\n\nSome **markdown** body.\n".toByteArray(Charsets.UTF_8)
        val content = localResolve(
            "README.md", "application/octet-stream", FileCategory.OTHER,
            "README.md", bytes
        )
        assertPlainText(content, "Some **markdown** body.")
    }

    @Test
    fun `files route - extension-less binary stays unsupported`() {
        val bytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00, 0x14, 0x00, 0x06, 0x00)
        val content = localResolve(
            "blob", "application/octet-stream", FileCategory.OTHER,
            "blob", bytes
        )
        assertTrue(
            "a typeless binary must keep the Unsupported page, was $content",
            content is PreviewContent.Unsupported
        )
    }

    @Test
    fun `text sniff - plain and utf8 text pass, binary fails`() {
        assertTrue(looksLikeText("plain ascii\nwith lines\n".toByteArray(Charsets.UTF_8)))
        assertTrue(looksLikeText("বাংলা লেখা\n".toByteArray(Charsets.UTF_8)))
        assertTrue(looksLikeText(ByteArray(0)))
        assertFalse(looksLikeText(byteArrayOf(0x41, 0x00, 0x42)))
        assertFalse(looksLikeText(byteArrayOf(0x41, 0x01, 0x42)))
    }

    // --------------------------------------- X1: README family, in-zip route

    private fun entry(name: String) = ArchiveEntry(
        name = name,
        sizeBytes = 128,
        compressedBytes = 100,
        isDirectory = false
    )

    @Test
    fun `in-zip tap - README family previews as text`() {
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("README"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("docs/README"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("README.md"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("notes.txt"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("LICENSE"))
        )
    }

    @Test
    fun `in-zip tap - neighbours unchanged`() {
        assertEquals(
            ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("firmware"))
        )
        assertEquals(
            ArchiveEntryTapAction.PLAY_AUDIO, archiveEntryTapAction(entry("song.mp3"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_DOCUMENT,
            archiveEntryTapAction(entry("Classical_Conditioning.epub"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_IMAGE, archiveEntryTapAction(entry("photo.png"))
        )
    }

    private companion object {
        const val EPUB_MIME = "application/epub+zip"
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
