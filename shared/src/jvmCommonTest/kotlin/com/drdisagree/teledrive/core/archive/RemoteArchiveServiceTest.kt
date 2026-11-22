package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.MediaByteSource
import com.drdisagree.teledrive.core.telegram.TelegramClient
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

        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
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

    private fun service(
        dao: FakeFilePartDao = FakeFilePartDao(),
        writer: FakeDownloadWriter = FakeDownloadWriter(),
        source: MediaByteSource = ArraySource(ByteArray(0))
    ) = RemoteArchiveService(
        telegramClient = unused<TelegramClient>(),
        filePartDao = dao,
        downloadWriter = writer,
        folderPathResolver = FolderPathResolver(
            unused<FolderDao>(),
            ActiveChannel(unused<SettingsRepository>()),
            unused<FolderTombstoneDao>()
        ),
        byteSourceFactory = { source }
    )

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
