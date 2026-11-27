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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * vc184 A1: the four field fixtures through the PRODUCTION Files-pager entry
 * point — [PreviewContentResolver.resolve], the exact call
 * `PreviewViewModel.contentFor` makes for every pager page — not through a
 * classification or extraction helper.
 *
 * Round 1 (vc183) pinned the `previewDispatch` helper and called the
 * extractor directly on fixture-named files, and the field route still
 * rendered "No preview — No preview for this file type" on vc183. The miss
 * lived in the seam those tests never drove: dispatch classified by the
 * DriveFile's stored MIME + name, but extraction re-derived the format from
 * the ON-DISK file's extension alone, discarding the caller's
 * classification. A byte source whose name does not end in
 * .epub/.docx/.xlsx/.pptx — the preview cache synthesises its file name, and
 * local copies keep whatever name their producer gave them — therefore
 * extracted as Failed and the pager showed the Unsupported page even though
 * dispatch had reached the document branch.
 *
 * The extension-less local-copy cases below fail on vc183 at this entry
 * point (Unsupported) and pass with the seam fix (the DriveFile's
 * mime/name now reach extraction, with ZIP content markers as a last
 * resort). The remote-route and properly-named cases pin the rest of the
 * production path end to end, and the ZIP cases pin that archive browsing
 * and the misnamed-ZIP outcome are unchanged.
 */
class PreviewProductionRouteTest {

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

    /** Serves [downloadedFile] as the completed TDLib download for any document. */
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
        java.nio.file.Files.createTempDirectory("preview-route").toFile()

    private fun fixtureBytes(resourceName: String): ByteArray =
        javaClass.getResourceAsStream("/viewer_fixtures/$resourceName")
            ?.use { it.readBytes() }
            ?: error("missing test fixture $resourceName")

    /** Writes [bytes] into [dir] under exactly [diskName] — extension-less names included. */
    private fun writeCopy(dir: File, diskName: String, bytes: ByteArray): File {
        val file = File(dir, diskName)
        file.writeBytes(bytes)
        return file
    }

    private fun driveFile(
        name: String,
        mimeType: String,
        category: FileCategory,
        sizeBytes: Long,
        localPath: String? = null,
        remote: Boolean = false
    ) = DriveFile(
        id = "file-$name",
        folderId = null,
        name = name,
        sizeBytes = sizeBytes,
        mimeType = mimeType,
        category = category,
        localPath = localPath,
        contentHash = null,
        chatId = if (remote) 100L else null,
        messageId = if (remote) 200L else null,
        remoteFileId = if (remote) "remote-1" else null,
        remoteUniqueId = if (remote) "unique-1" else null,
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

    private fun assertDocumentText(content: PreviewContent, vararg markers: String) {
        assertTrue(
            "expected extracted document text, was $content",
            content is PreviewContent.PlainText
        )
        val text = (content as PreviewContent.PlainText).text
        for (marker in markers) {
            assertTrue("extracted text should contain \"$marker\": $text", text.contains(marker))
        }
    }

    /**
     * The vc183 field failure at the production entry point: the DriveFile is
     * a document (by stored MIME and by name), but the on-disk copy the
     * resolver is handed carries NO extension, so vc183's extraction — keyed
     * on the disk name alone — failed and the pager showed Unsupported.
     */
    private fun extensionLessLocalCase(
        fixtureName: String,
        mimeType: String,
        category: FileCategory,
        vararg markers: String
    ) {
        val root = freshRoot()
        val bytes = fixtureBytes(fixtureName)
        val diskCopy = writeCopy(root, "blob-no-extension", bytes)
        val file = driveFile(
            name = fixtureName,
            mimeType = mimeType,
            category = category,
            sizeBytes = bytes.size.toLong(),
            localPath = diskCopy.absolutePath
        )
        assertDocumentText(resolveLast(resolver(root, FakeTelegramClient(diskCopy)), file), *markers)
    }

    /** The remote production path: no local copy, the bytes arrive via fetchToCache. */
    private fun remoteCase(fixtureName: String, mimeType: String, vararg markers: String) {
        val root = freshRoot()
        val bytes = fixtureBytes(fixtureName)
        val tdlibCopy = writeCopy(root, fixtureName, bytes)
        val file = driveFile(
            name = fixtureName,
            mimeType = mimeType,
            category = FileCategory.DOCUMENT,
            sizeBytes = bytes.size.toLong(),
            localPath = null,
            remote = true
        )
        assertDocumentText(resolveLast(resolver(root, FakeTelegramClient(tdlibCopy)), file), *markers)
    }

    // --------------------------------------- extension-less local copies (the field miss)

    @Test
    fun `production route - minimal epub with extension-less on-disk copy renders its text`() =
        extensionLessLocalCase(
            "Viewer_Test.epub", EPUB_MIME, FileCategory.DOCUMENT,
            "Chapter 1 — It opens", "the EPUB viewer works"
        )

    @Test
    fun `production route - minimal docx with extension-less on-disk copy renders its text`() =
        extensionLessLocalCase(
            "Viewer_Test.docx", DOCX_MIME, FileCategory.DOCUMENT,
            "Viewer Test Document", "the Office viewer works"
        )

    @Test
    fun `production route - full epub with extension-less on-disk copy renders its text`() =
        extensionLessLocalCase(
            "Classical_Conditioning.epub", EPUB_MIME, FileCategory.DOCUMENT,
            "Chapter 1 — What Classical Conditioning Is", "Ivan Pavlov"
        )

    @Test
    fun `production route - full docx with extension-less on-disk copy renders its text`() =
        extensionLessLocalCase(
            "Classical_Conditioning.docx", DOCX_MIME, FileCategory.DOCUMENT,
            "Classical Conditioning — How Learning by Association Works and Helps", "Ivan Pavlov"
        )

    @Test
    fun `production route - epub stored as zip mime with extension-less copy renders its text`() =
        extensionLessLocalCase(
            "Classical_Conditioning.epub", "application/zip", FileCategory.ARCHIVE,
            "Chapter 1 — What Classical Conditioning Is", "Ivan Pavlov"
        )

    @Test
    fun `production route - docx stored as zip mime with extension-less copy renders its text`() =
        extensionLessLocalCase(
            "Classical_Conditioning.docx", "application/zip", FileCategory.ARCHIVE,
            "Classical Conditioning — How Learning by Association Works and Helps", "Ivan Pavlov"
        )

    // ------------------------------------------------------------ remote route

    @Test
    fun `production route - remote minimal epub renders its text`() =
        remoteCase("Viewer_Test.epub", EPUB_MIME, "Chapter 1 — It opens", "the EPUB viewer works")

    @Test
    fun `production route - remote minimal docx renders its text`() =
        remoteCase("Viewer_Test.docx", DOCX_MIME, "Viewer Test Document", "the Office viewer works")

    @Test
    fun `production route - remote full epub renders its text`() =
        remoteCase(
            "Classical_Conditioning.epub", EPUB_MIME,
            "Chapter 1 — What Classical Conditioning Is", "Ivan Pavlov"
        )

    @Test
    fun `production route - remote full docx renders its text`() =
        remoteCase(
            "Classical_Conditioning.docx", DOCX_MIME,
            "Classical Conditioning — How Learning by Association Works and Helps", "Ivan Pavlov"
        )

    // ------------------------------------------------------------ unchanged neighbours

    @Test
    fun `production route - properly named local docx still renders its text`() {
        val root = freshRoot()
        val bytes = fixtureBytes("Viewer_Test.docx")
        val diskCopy = writeCopy(root, "Viewer_Test.docx", bytes)
        val file = driveFile(
            name = "Viewer_Test.docx",
            mimeType = DOCX_MIME,
            category = FileCategory.DOCUMENT,
            sizeBytes = bytes.size.toLong(),
            localPath = diskCopy.absolutePath
        )
        assertDocumentText(
            resolveLast(resolver(root, FakeTelegramClient(diskCopy)), file),
            "Viewer Test Document", "the Office viewer works"
        )
    }

    @Test
    fun `production route - a real zip still opens as an archive`() {
        val root = freshRoot()
        val zipFile = File(root, "bundle.zip")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hi".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val file = driveFile(
            name = "bundle.zip",
            mimeType = "application/zip",
            category = FileCategory.ARCHIVE,
            sizeBytes = zipFile.length(),
            localPath = zipFile.absolutePath
        )
        val content = resolveLast(resolver(root, FakeTelegramClient(zipFile)), file)
        assertTrue("a real ZIP must stay an archive listing, was $content",
            content is PreviewContent.Archive)
        assertTrue(
            (content as PreviewContent.Archive).entries.any { it.name == "hello.txt" }
        )
    }

    @Test
    fun `production route - a plain zip named epub stays unsupported`() {
        val root = freshRoot()
        val zipFile = File(root, "blob-no-extension")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("hello.txt"))
            zip.write("hi".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val file = driveFile(
            name = "fake.epub",
            mimeType = EPUB_MIME,
            category = FileCategory.DOCUMENT,
            sizeBytes = zipFile.length(),
            localPath = zipFile.absolutePath
        )
        val content = resolveLast(resolver(root, FakeTelegramClient(zipFile)), file)
        assertTrue(
            "a plain ZIP with no document markers must stay Unsupported, was $content",
            content is PreviewContent.Unsupported
        )
    }

    private companion object {
        const val EPUB_MIME = "application/epub+zip"
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    }
}
