package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.MediaByteSource
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
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RemoteArchiveServiceTest {

    private class FakeFilePartDao(var parts: List<FilePartEntity> = emptyList()) : FilePartDao {
        override suspend fun upsert(part: FilePartEntity) = Unit
        override suspend fun partsOf(fileId: String): List<FilePartEntity> = parts
        override suspend fun partsOfAll(fileIds: List<String>): List<FilePartEntity> = parts
        override suspend fun countOf(fileId: String): Int = parts.size
        override suspend fun deleteFor(fileIds: List<String>) = Unit
        override suspend fun byRemoteUniqueId(uniqueId: String): FilePartEntity? = null
        override suspend fun byPart(fileId: String, partIndex: Int): FilePartEntity? = null
    }

    private class FakeDownloadWriter : DownloadWriter {
        var savedName: String? = null
        var savedFolderPath: String? = null
        var savedBytes: ByteArray? = null
        var writeCalls = 0

        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
            writeCalls++
            val out = ByteArrayOutputStream()
            body(out)
            savedName = fileName
            savedFolderPath = folderPath
            savedBytes = out.toByteArray()
            return "/downloads/$fileName"
        }

        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
        ): PartialDownload? = null

        override fun freeBytes(folderPath: String?): Long? = null
    }

    private class ArraySource(private val bytes: ByteArray) : MediaByteSource {
        var closed = false
        override suspend fun size(): Long = bytes.size.toLong()
        override suspend fun read(position: Long, count: Int): ByteArray {
            if (position >= bytes.size) return ByteArray(0)
            val end = minOf(position + count, bytes.size.toLong()).toInt()
            return bytes.copyOfRange(position.toInt(), end)
        }

        override fun close() {
            closed = true
        }
    }

    /**
     * In-memory Telegram file for the A1 range-contract test. Records every
     * requestFileRange call; a requested range becomes readable (from its offset to EOF,
     * matching limit = 0 semantics) and readFilePart returns nothing outside the
     * downloaded window, like TDLib.
     */
    private class RecordingTelegramClient(private val bytes: ByteArray) :
        TelegramClient by unused() {
        val rangeRequests = mutableListOf<Pair<Long, Long>>()
        private var windowStart = -1L
        private val windowEnd: Long get() = if (windowStart >= 0) bytes.size.toLong() else -1L

        private fun info() = TelegramFileInfo(
            fileId = 99,
            sizeBytes = bytes.size.toLong(),
            localPath = null,
            isDownloadingCompleted = false,
            isDownloadingActive = windowStart >= 0,
            downloadOffset = if (windowStart >= 0) windowStart else 0L,
            downloadedPrefixSize = if (windowStart >= 0) windowEnd - windowStart else 0L
        )

        override suspend fun resolveFile(remoteFileId: String): TelegramFileInfo = info()

        override suspend fun resolveFile(
            remoteFileId: String,
            chatId: Long?,
            messageId: Long?
        ): TelegramFileInfo = info()

        override suspend fun getFileInfo(fileId: Int): TelegramFileInfo = info()

        override suspend fun requestFileRange(fileId: Int, offset: Long, limit: Long) {
            rangeRequests += offset to limit
            windowStart = offset
        }

        override suspend fun readFilePart(fileId: Int, offset: Long, count: Long): ByteArray {
            if (windowStart < 0 || offset < windowStart || offset >= windowEnd) {
                return ByteArray(0)
            }
            val end = minOf(offset + count, windowEnd)
            return bytes.copyOfRange(offset.toInt(), end.toInt())
        }

        override suspend fun cancelFileDownload(fileId: Int) = Unit

        override fun fileUpdates(fileId: Int): Flow<TelegramFileInfo> = emptyFlow()
    }

    private fun driveFile(
        encrypted: Boolean = false,
        remote: Boolean = true
    ) = DriveFile(
        id = "zip-1",
        folderId = null,
        name = "bundle.zip",
        sizeBytes = 100,
        mimeType = "application/zip",
        category = FileCategory.ARCHIVE,
        localPath = null,
        contentHash = null,
        chatId = if (remote) 42L else null,
        messageId = if (remote) 7L else null,
        remoteFileId = if (remote) "remote-id" else null,
        remoteUniqueId = null,
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

    private fun resolver() = FolderPathResolver(
        unused<FolderDao>(),
        ActiveChannel(unused<SettingsRepository>()),
        unused<FolderTombstoneDao>()
    )

    private fun service(
        dao: FakeFilePartDao = FakeFilePartDao(),
        writer: FakeDownloadWriter = FakeDownloadWriter(),
        source: MediaByteSource = ArraySource(ByteArray(0))
    ) = RemoteArchiveService(
        telegramClient = unused<TelegramClient>(),
        filePartDao = dao,
        downloadWriter = writer,
        folderPathResolver = resolver(),
        byteSourceFactory = { source }
    )

    /** Service using the production default byte-source factory (real TelegramMediaByteSource). */
    private fun serviceWithDefaultFactory(
        client: TelegramClient,
        writer: FakeDownloadWriter = FakeDownloadWriter()
    ) = RemoteArchiveService(
        telegramClient = client,
        filePartDao = FakeFilePartDao(),
        downloadWriter = writer,
        folderPathResolver = resolver()
    )

    /** Hand-built zip of STORED entries; unlike ZipOutputStream it allows duplicate names. */
    private fun storedZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        fun w16(value: Int) {
            out.write(value and 0xFF); out.write(value shr 8)
        }
        fun w32(value: Long) {
            for (i in 0..3) out.write((value shr (8 * i)).toInt() and 0xFF)
        }
        val centrals = mutableListOf<ByteArray>()
        for ((name, data) in entries) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val crc = java.util.zip.CRC32().apply { update(data) }.value
            val localOffset = out.size().toLong()
            w32(0x04034b50); w16(20); w16(0x0800); w16(0); w16(0); w16(0)
            w32(crc); w32(data.size.toLong()); w32(data.size.toLong())
            w16(nameBytes.size); w16(0)
            out.write(nameBytes)
            out.write(data)
            val central = ByteArrayOutputStream()
            fun c16(value: Int) {
                central.write(value and 0xFF); central.write(value shr 8)
            }
            fun c32(value: Long) {
                for (i in 0..3) central.write((value shr (8 * i)).toInt() and 0xFF)
            }
            c32(0x02014b50); c16(20); c16(20); c16(0x0800); c16(0); c16(0); c16(0)
            c32(crc); c32(data.size.toLong()); c32(data.size.toLong())
            c16(nameBytes.size); c16(0); c16(0); c16(0); c16(0); c32(0); c32(localOffset)
            central.write(nameBytes)
            centrals += central.toByteArray()
        }
        val cdOffset = out.size().toLong()
        centrals.forEach { out.write(it) }
        val cdSize = out.size() - cdOffset
        w32(0x06054b50); w16(0); w16(0); w16(entries.size); w16(entries.size)
        w32(cdSize); w32(cdOffset); w16(0)
        return out.toByteArray()
    }

    private suspend fun assertRefusal(fragment: String, block: suspend () -> Unit) {
        try {
            block()
            fail("Expected a refusal mentioning \"$fragment\"")
        } catch (failure: RemoteArchiveRefusalException) {
            assertTrue(
                "message was: ${failure.message}",
                failure.message!!.contains(fragment, ignoreCase = true)
            )
        }
    }

    @Test
    fun `encrypted files are refused before any reading`() = runBlocking {
        assertRefusal("encrypted") {
            service().listEntries(driveFile(encrypted = true))
        }
    }

    @Test
    fun `files split by pranto drive are refused with the split explanation`() = runBlocking {
        val part = FilePartEntity(
            fileId = "zip-1",
            partIndex = 0,
            chatId = 42L,
            messageId = 7L,
            remoteFileId = "part-remote",
            remoteUniqueId = null,
            plainOffset = 0,
            plainSize = 50,
            storedSize = 50,
            uploadedAt = 0
        )
        assertRefusal("split") {
            service(dao = FakeFilePartDao(listOf(part))).listEntries(driveFile())
        }
    }

    @Test
    fun `files without an uploaded copy are refused`() = runBlocking {
        assertRefusal("uploaded") {
            service().listEntries(driveFile(remote = false))
        }
    }

    @Test
    fun `list and extract work end to end over an in-memory remote copy`() = runBlocking {
        val content = "from telegram, with ranges".toByteArray()
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zip ->
            zip.putNextEntry(ZipEntry("inner/notes.txt"))
            zip.write(content)
            zip.closeEntry()
        }
        val source = ArraySource(zipOut.toByteArray())
        val writer = FakeDownloadWriter()
        val service = service(writer = writer, source = source)

        val entries = service.listEntries(driveFile())
        assertEquals(listOf("inner/notes.txt"), entries.map { it.name })

        val savedPath = service.extractEntry(driveFile(), "inner/notes.txt")
        assertEquals("/downloads/notes.txt", savedPath)
        assertEquals("notes.txt", writer.savedName)
        assertArrayEquals(content, writer.savedBytes)
        assertTrue(source.closed)
    }

    @Test
    fun `archive byte source never primes a download from offset zero`() = runBlocking {
        // Big stored entry pushes the file well past the 65,600-byte tail window, so the
        // tail/central-directory region starts far from offset 0.
        val bigData = ByteArray(150_000) { (it % 251).toByte() }
        val smallData = "small entry contents".toByteArray()
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zip ->
            val big = ZipEntry("big.bin")
            big.method = ZipEntry.STORED
            big.size = bigData.size.toLong()
            big.compressedSize = bigData.size.toLong()
            big.crc = java.util.zip.CRC32().apply { update(bigData) }.value
            zip.putNextEntry(big)
            zip.write(bigData)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("small.txt"))
            zip.write(smallData)
            zip.closeEntry()
        }
        val bytes = zipOut.toByteArray()
        val tailStart = bytes.size - 65_600L
        assertTrue("test archive must exceed the tail window", tailStart > 0)

        val listClient = RecordingTelegramClient(bytes)
        val entries = serviceWithDefaultFactory(listClient).listEntries(driveFile())
        assertEquals(listOf("big.bin", "small.txt"), entries.map { it.name })
        assertTrue("expected at least one range request", listClient.rangeRequests.isNotEmpty())
        assertEquals(
            "first range request must target the tail/CD region, not offset 0",
            tailStart,
            listClient.rangeRequests.first().first
        )

        val extractClient = RecordingTelegramClient(bytes)
        val writer = FakeDownloadWriter()
        val savedPath = serviceWithDefaultFactory(extractClient, writer)
            .extractEntry(driveFile(), "small.txt")
        assertEquals("/downloads/small.txt", savedPath)
        assertArrayEquals(smallData, writer.savedBytes)
        assertEquals(
            "extract must also start with the tail/CD region, not a prime from offset 0",
            tailStart,
            extractClient.rangeRequests.first().first
        )
    }

    @Test
    fun `duplicate entry names extract their respective contents by occurrence`() = runBlocking {
        val first = "first copy".toByteArray()
        val second = "second copy, different bytes".toByteArray()
        val bytes = storedZip(
            listOf(
                "dup.txt" to first,
                "other.txt" to "unrelated".toByteArray(),
                "dup.txt" to second
            )
        )
        val writer = FakeDownloadWriter()
        val service = service(writer = writer, source = ArraySource(bytes))

        service.extractEntry(driveFile(), "dup.txt", occurrence = 0)
        assertArrayEquals(first, writer.savedBytes)

        service.extractEntry(driveFile(), "dup.txt", occurrence = 1)
        assertArrayEquals(second, writer.savedBytes)
    }

    @Test
    fun `crc corrupted entry never reaches the download writer and closes the source`() =
        runBlocking {
            val data = "integrity matters".toByteArray()
            val bytes = storedZip(listOf("file.txt" to data))
            // Stored entry at offset 0: data starts after the 30-byte local header + name.
            val nameLength = (bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0xFF) shl 8)
            val extraLength = (bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0xFF) shl 8)
            bytes[30 + nameLength + extraLength] =
                (bytes[30 + nameLength + extraLength] + 1).toByte()

            val source = ArraySource(bytes)
            val writer = FakeDownloadWriter()
            val service = service(writer = writer, source = source)

            try {
                service.extractEntry(driveFile(), "file.txt")
                fail("Expected CrcMismatch")
            } catch (failure: ZipArchiveException.CrcMismatch) {
                // expected
            }
            assertEquals(0, writer.writeCalls)
            assertTrue(source.closed)
        }

    @Test
    fun `extracting a missing entry reports it plainly`() = runBlocking {
        val zipOut = ByteArrayOutputStream()
        ZipOutputStream(zipOut).use { zip ->
            zip.putNextEntry(ZipEntry("present.txt"))
            zip.write("x".toByteArray())
            zip.closeEntry()
        }
        val service = service(source = ArraySource(zipOut.toByteArray()))

        try {
            service.extractEntry(driveFile(), "absent.txt")
            fail("Expected EntryNotFound")
        } catch (failure: ZipArchiveException.EntryNotFound) {
            assertTrue(failure.message!!.contains("absent.txt"))
        }
    }
}
