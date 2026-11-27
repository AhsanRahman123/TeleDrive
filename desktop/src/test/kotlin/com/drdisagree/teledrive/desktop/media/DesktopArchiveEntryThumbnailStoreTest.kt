package com.drdisagree.teledrive.desktop.media

import com.drdisagree.teledrive.core.archive.ArchiveEntryThumbnailPolicy
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
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
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.25 item 6: the desktop store's no-read guarantees and lifecycle pins,
 * mirroring Android's ArchiveEntryThumbnailStoreTest — an oversize or non-image
 * row performs ZERO Telegram calls, a cache hit performs zero calls, an
 * eligible fetch decodes (ImageIO works on the JVM) to a Ready JPEG under the
 * policy cache key, and an externally deleted Ready file refetches (MF-2).
 */
class DesktopArchiveEntryThumbnailStoreTest {

    private class FakeFilePartDao : FilePartDao {
        override suspend fun partsOf(fileId: String): List<FilePartEntity> = emptyList()
        override suspend fun upsert(part: FilePartEntity) = throw UnsupportedOperationException()
        override suspend fun partsOfAll(fileIds: List<String>): List<FilePartEntity> =
            throw UnsupportedOperationException()
        override suspend fun countOf(fileId: String): Int = 0
        override suspend fun deleteFor(fileIds: List<String>) = throw UnsupportedOperationException()
        override suspend fun byRemoteUniqueId(uniqueId: String): FilePartEntity? =
            throw UnsupportedOperationException()
        override suspend fun byPart(fileId: String, partIndex: Int): FilePartEntity? =
            throw UnsupportedOperationException()
    }

    private class FakeDownloadWriter : DownloadWriter {
        override fun write(
            fileName: String, mimeType: String, folderPath: String?, body: (OutputStream) -> Unit
        ): String = "/Downloads/$fileName"
        override fun openPartial(
            fileName: String, mimeType: String, folderPath: String?, token: String?
        ): PartialDownload? = null
        override fun freeBytes(folderPath: String?): Long? = null
    }

    private class CountingTelegram(private val bytes: ByteArray) {
        val totalCalls = AtomicInteger(0)
        val client: TelegramClient = Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
            InvocationHandler { _, method, args ->
                totalCalls.incrementAndGet()
                val info = TelegramFileInfo(
                    fileId = 7, sizeBytes = bytes.size.toLong(), localPath = null,
                    isDownloadingCompleted = true, isDownloadingActive = false,
                    downloadOffset = 0, downloadedPrefixSize = bytes.size.toLong()
                )
                when (method.name) {
                    "resolveFile" -> info
                    "getFileInfo" -> info
                    "readFilePart" -> {
                        val offset = (args[1] as Number).toLong()
                        val count = (args[2] as Number).toLong()
                        if (offset >= bytes.size) ByteArray(0)
                        else bytes.copyOfRange(offset.toInt(), minOf(offset + count, bytes.size.toLong()).toInt())
                    }
                    "requestFileRange", "cancelFileDownload" -> Unit
                    "fileUpdates" -> flowOf(info)
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        ) as TelegramClient
    }

    private fun service(telegram: TelegramClient) = RemoteArchiveService(
        telegramClient = telegram,
        filePartDao = FakeFilePartDao(),
        downloadWriter = FakeDownloadWriter(),
        folderPathResolver = FolderPathResolver(
            unused<FolderDao>(), ActiveChannel(unused<SettingsRepository>()), unused<FolderTombstoneDao>()
        )
    )

    private fun driveFile() = DriveFile(
        id = "file-1", folderId = null, name = "bundle.zip", sizeBytes = 1234,
        mimeType = "application/zip", category = FileCategory.ARCHIVE, localPath = null,
        contentHash = null, chatId = 100L, messageId = 200L, remoteFileId = "remote-1",
        remoteUniqueId = "unique-1", backupState = BackupState.BACKED_UP, isHidden = false,
        isArchived = false, isFavorite = false, isEncrypted = false, width = null, height = null,
        durationMs = null, trashedAt = null, createdAt = 0, modifiedAt = 0, addedAt = 0
    )

    private fun pngBytes(): ByteArray {
        val image = BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
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
                zip.putNextEntry(entry); zip.write(data); zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun awaitState(store: DesktopArchiveEntryThumbnailStore, key: String): ArchiveThumbState {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val value = store.state(key).value
            if (value !is ArchiveThumbState.Placeholder) return value
            Thread.sleep(25)
        }
        return store.state(key).value
    }

    @Test
    fun `oversize and non-image rows perform zero telegram calls`() {
        val telegram = CountingTelegram(storedZip(listOf("cat.png" to pngBytes())))
        val dir = Files.createTempDirectory("thumbs-test").toFile()
        val store = DesktopArchiveEntryThumbnailStore.forCacheDir(dir, service(telegram.client))
        val file = driveFile()
        val bigKey = ArchiveEntryThumbnailPolicy.cacheKey(file.id, 0, ArchiveEntryThumbnailPolicy.MAX_ENTRY_BYTES + 1, 1, 1)
        store.request(bigKey, file, 0, "cat.png", ArchiveEntryThumbnailPolicy.MAX_ENTRY_BYTES + 1)
        assertEquals(ArchiveThumbState.Failed, store.state(bigKey).value)
        val textKey = ArchiveEntryThumbnailPolicy.cacheKey(file.id, 1, 10, 10, 1)
        store.request(textKey, file, 1, "notes.txt", 10)
        assertEquals(ArchiveThumbState.Failed, store.state(textKey).value)
        assertEquals(0, telegram.totalCalls.get())
    }

    @Test
    fun `eligible fetch becomes ready and a second request is a zero-call cache hit`() {
        val png = pngBytes()
        val telegram = CountingTelegram(storedZip(listOf("cat.png" to png)))
        val dir = Files.createTempDirectory("thumbs-test").toFile()
        val store = DesktopArchiveEntryThumbnailStore.forCacheDir(dir, service(telegram.client))
        val file = driveFile()
        val key = ArchiveEntryThumbnailPolicy.cacheKey(file.id, 0, png.size.toLong(), png.size.toLong(), 0)
        store.request(key, file, 0, "cat.png", png.size.toLong())
        val ready = awaitState(store, key)
        assertTrue("expected Ready, was $ready", ready is ArchiveThumbState.Ready)
        val callsAfterFetch = telegram.totalCalls.get()
        assertTrue(callsAfterFetch > 0)
        store.request(key, file, 0, "cat.png", png.size.toLong())
        assertEquals(callsAfterFetch, telegram.totalCalls.get())
    }

    @Test
    fun `externally deleted ready file refetches instead of stranding the row`() {
        val png = pngBytes()
        val telegram = CountingTelegram(storedZip(listOf("cat.png" to png)))
        val dir = Files.createTempDirectory("thumbs-test").toFile()
        val store = DesktopArchiveEntryThumbnailStore.forCacheDir(dir, service(telegram.client))
        val file = driveFile()
        val key = ArchiveEntryThumbnailPolicy.cacheKey(file.id, 0, png.size.toLong(), png.size.toLong(), 0)
        store.request(key, file, 0, "cat.png", png.size.toLong())
        val ready = awaitState(store, key) as ArchiveThumbState.Ready
        ready.file.delete()
        store.request(key, file, 0, "cat.png", png.size.toLong())
        val deadline = System.currentTimeMillis() + 10_000
        var refetched = false
        while (System.currentTimeMillis() < deadline) {
            val value = store.state(key).value
            if (value is ArchiveThumbState.Ready && value.file.isFile) { refetched = true; break }
            Thread.sleep(25)
        }
        assertTrue(refetched)
    }
}
