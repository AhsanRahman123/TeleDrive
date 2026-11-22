package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.testing.unused
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteArchiveServiceTest {

    private class FakeFilePartDao(private val parts: List<FilePartEntity>) : FilePartDao {
        override suspend fun partsOf(fileId: String): List<FilePartEntity> = parts
        override suspend fun upsert(part: FilePartEntity) = throw UnsupportedOperationException()
        override suspend fun partsOfAll(fileIds: List<String>): List<FilePartEntity> =
            throw UnsupportedOperationException()
        override suspend fun countOf(fileId: String): Int = parts.size
        override suspend fun deleteFor(fileIds: List<String>) = throw UnsupportedOperationException()
        override suspend fun byRemoteUniqueId(uniqueId: String): FilePartEntity? =
            throw UnsupportedOperationException()
        override suspend fun byPart(fileId: String, partIndex: Int): FilePartEntity? =
            throw UnsupportedOperationException()
    }

    private class FakeDownloadWriter : DownloadWriter {
        var fileName: String? = null
        var folderPath: String? = null
        var bytes: ByteArray? = null
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
            this.fileName = fileName
            this.folderPath = folderPath
            val out = ByteArrayOutputStream()
            body(out)
            bytes = out.toByteArray()
            return "/Downloads/TeleDrive/$fileName"
        }
        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
        ): PartialDownload? = null
        override fun freeBytes(folderPath: String?): Long? = null
    }

    /** Minimal TelegramClient serving one in-memory file as already fully downloaded. */
    private fun fakeTelegram(bytes: ByteArray): TelegramClient {
        val info = TelegramFileInfo(
            fileId = 7,
            sizeBytes = bytes.size.toLong(),
            localPath = null,
            isDownloadingCompleted = true,
            isDownloadingActive = false,
            downloadOffset = 0,
            downloadedPrefixSize = bytes.size.toLong()
        )
        val handler = InvocationHandler { _, method, args ->
            when (method.name) {
                "resolveFile" -> info
                "getFileInfo" -> info
                "readFilePart" -> {
                    val offset = (args[1] as Number).toLong()
                    val count = (args[2] as Number).toLong()
                    if (offset >= bytes.size) ByteArray(0)
                    else bytes.copyOfRange(
                        offset.toInt(),
                        minOf(offset + count, bytes.size.toLong()).toInt()
                    )
                }
                "requestFileRange", "cancelFileDownload" -> Unit
                "fileUpdates" -> flowOf(info)
                else -> throw UnsupportedOperationException(method.name)
            }
        }
        return Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
            handler
        ) as TelegramClient
    }

    /**
     * TelegramClient proxy that serves one in-memory file and records how it was asked for data:
     * every requestFileRange call and every cancel (which is how a closed byte source shows up).
     */
    private class RecordingTelegram(
        private val bytes: ByteArray,
        private val completed: Boolean
    ) {
        val rangeRequests = mutableListOf<Pair<Long, Long>>()
        var cancelCalls = 0

        val client: TelegramClient = Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
            InvocationHandler { _, method, args ->
                val info = TelegramFileInfo(
                    fileId = 7,
                    sizeBytes = bytes.size.toLong(),
                    localPath = null,
                    isDownloadingCompleted = completed,
                    isDownloadingActive = false,
                    downloadOffset = 0,
                    downloadedPrefixSize = if (completed) bytes.size.toLong() else 0
                )
                when (method.name) {
                    "resolveFile" -> info
                    "getFileInfo" -> info
                    "readFilePart" -> {
                        val offset = (args[1] as Number).toLong()
                        val count = (args[2] as Number).toLong()
                        if (offset >= bytes.size) ByteArray(0)
                        else bytes.copyOfRange(
                            offset.toInt(),
                            minOf(offset + count, bytes.size.toLong()).toInt()
                        )
                    }
                    "requestFileRange" -> {
                        rangeRequests += (args[1] as Number).toLong() to (args[2] as Number).toLong()
                        Unit
                    }
                    "cancelFileDownload" -> { cancelCalls++; Unit }
                    "fileUpdates" -> flowOf(info)
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        ) as TelegramClient
    }

    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                entry.method = ZipEntry.STORED
                entry.size = data.size.toLong()
                entry.compressedSize = data.size.toLong()
                val crc = CRC32(); crc.update(data); entry.crc = crc.value
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun driveFile(
        encrypted: Boolean = false,
        chatId: Long? = 100L,
        messageId: Long? = 200L,
        remoteFileId: String? = "remote-1"
    ) = DriveFile(
        id = "file-1",
        folderId = null,
        name = "bundle.zip",
        sizeBytes = 1234,
        mimeType = "application/zip",
        category = FileCategory.ARCHIVE,
        localPath = null,
        contentHash = null,
        chatId = chatId,
        messageId = messageId,
        remoteFileId = remoteFileId,
        remoteUniqueId = "unique-1",
        backupState = BackupState.BACKED_UP,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        isEncrypted = encrypted,
        width = null,
        height = null,
        durationMs = null,
        trashedAt = null,
        createdAt = 0,
        modifiedAt = 0,
        addedAt = 0
    )

    private fun service(
        telegram: TelegramClient,
        parts: List<FilePartEntity> = emptyList(),
        writer: DownloadWriter = FakeDownloadWriter()
    ) = RemoteArchiveService(
        telegramClient = telegram,
        filePartDao = FakeFilePartDao(parts),
        downloadWriter = writer,
        folderPathResolver = FolderPathResolver(
            unused<FolderDao>(),
            ActiveChannel(unused<SettingsRepository>()),
            unused<FolderTombstoneDao>()
        )
    )

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        outer@ for (i in 0..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun part() = FilePartEntity(
        fileId = "file-1", partIndex = 0, chatId = 100L, messageId = 200L,
        remoteFileId = "part-remote", remoteUniqueId = "part-unique",
        plainOffset = 0, plainSize = 100, storedSize = 100, uploadedAt = 0
    )

    private fun expectKind(kind: ArchiveErrorKind, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("Expected ArchiveException($kind)")
        } catch (failure: ArchiveException) {
            assertEquals(kind, failure.kind)
        }
    }

    @Test
    fun `encrypted zip is refused before any telegram read`() {
        expectKind(ArchiveErrorKind.ENCRYPTED_FILE) {
            service(unused<TelegramClient>()).listEntries(driveFile(encrypted = true))
        }
    }

    @Test
    fun `drive split zip is refused with the split explanation`() {
        expectKind(ArchiveErrorKind.SPLIT_FILE) {
            service(unused<TelegramClient>(), parts = listOf(part())).listEntries(driveFile())
        }
        expectKind(ArchiveErrorKind.SPLIT_FILE) {
            service(unused<TelegramClient>(), parts = listOf(part()))
                .extractEntry(driveFile(), "a.txt")
        }
    }

    @Test
    fun `zip without a remote copy is refused`() {
        expectKind(ArchiveErrorKind.NO_REMOTE_COPY) {
            service(unused<TelegramClient>())
                .listEntries(driveFile(chatId = null, messageId = null, remoteFileId = null))
        }
    }

    @Test
    fun `remote zip lists and extracts one entry through the download writer`() = runBlocking {
        val content = "just this one file".toByteArray()
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zip ->
            val entry = ZipEntry("dir/hello.txt")
            entry.method = ZipEntry.STORED
            entry.size = content.size.toLong()
            entry.compressedSize = content.size.toLong()
            val crc = CRC32(); crc.update(content); entry.crc = crc.value
            zip.putNextEntry(entry)
            zip.write(content)
            zip.closeEntry()
        }
        val zipBytes = zipOut.toByteArray()
        val writer = FakeDownloadWriter()
        val remote = service(fakeTelegram(zipBytes), writer = writer)

        val entries = remote.listEntries(driveFile())
        assertEquals(listOf("dir/hello.txt"), entries.map { it.name })

        val saved = remote.extractEntry(driveFile(), "dir/hello.txt")
        assertEquals("/Downloads/TeleDrive/hello.txt", saved)
        assertEquals("hello.txt", writer.fileName)
        assertEquals("", writer.folderPath)
        assertArrayEquals(content, writer.bytes)
    }

    @Test
    fun `archive access never primes a whole file download from offset zero`() = runBlocking {
        // Not fully downloaded: the old byte-source priming would issue an unbounded
        // requestFileRange(0, 0) here. Ranged archive reads must never do that.
        val telegram = RecordingTelegram(
            storedZip(listOf("a.txt" to "abc".toByteArray())),
            completed = false
        )
        val remote = service(telegram.client)
        remote.listEntries(driveFile())
        remote.extractEntry(driveFile(), "a.txt")
        assertTrue(
            "unbounded offset-0 range requested: ${telegram.rangeRequests}",
            telegram.rangeRequests.none { it.first == 0L && it.second == 0L }
        )
    }

    @Test
    fun `duplicate names extract their own bytes by occurrence`() = runBlocking {
        val zipBytes = storedZip(
            listOf(
                "dup.txt" to "first-content".toByteArray(),
                "dup.txt" to "second-content".toByteArray()
            )
        )
        val writer = FakeDownloadWriter()
        val remote = service(fakeTelegram(zipBytes), writer = writer)
        remote.extractEntry(driveFile(), "dup.txt", occurrence = 0)
        assertArrayEquals("first-content".toByteArray(), writer.bytes)
        remote.extractEntry(driveFile(), "dup.txt", occurrence = 1)
        assertArrayEquals("second-content".toByteArray(), writer.bytes)
    }

    @Test
    fun `crc corrupted entry never reaches the download writer and closes the source`() {
        val content = "integrity matters".toByteArray()
        val zipBytes = storedZip(listOf("data.txt" to content))
        val at = zipBytes.indexOf(content)
        assertTrue(at >= 0)
        zipBytes[at] = (zipBytes[at] + 1).toByte()
        val telegram = RecordingTelegram(zipBytes, completed = true)
        val writer = FakeDownloadWriter()
        expectKind(ArchiveErrorKind.CRC_MISMATCH) {
            service(telegram.client, writer = writer).extractEntry(driveFile(), "data.txt")
        }
        assertEquals(null, writer.fileName)
        assertTrue("byte source was not closed", telegram.cancelCalls > 0)
    }

    @Test
    fun `extracting a missing entry reports entry not found`() {
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zip ->
            zip.putNextEntry(ZipEntry("present.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        expectKind(ArchiveErrorKind.ENTRY_NOT_FOUND) {
            service(fakeTelegram(zipOut.toByteArray())).extractEntry(driveFile(), "absent.txt")
        }
    }
}
