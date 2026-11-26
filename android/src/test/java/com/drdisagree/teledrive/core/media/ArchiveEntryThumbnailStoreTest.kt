package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.archive.ArchiveEntryThumbnailPolicy
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.files.DownloadWriter
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
import java.io.File
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc182 item 6: the store's no-read guarantees. A cache hit and an oversize entry
 * must not touch the Telegram byte source at all; an eligible fetch must clean
 * its extraction temp up even when decoding fails (host tests cannot decode —
 * the Android bitmap stubs fail — so a fetch here always ends in Failed, which
 * is exactly the cleanup path being pinned).
 */
class ArchiveEntryThumbnailStoreTest {

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

    /** TelegramClient serving one in-memory file as fully downloaded, counting every call. */
    private class CountingTelegram(private val bytes: ByteArray) {
        val totalCalls = AtomicInteger(0)
        val readCalls = AtomicInteger(0)

        val client: TelegramClient = Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
            InvocationHandler { _, method, args ->
                totalCalls.incrementAndGet()
                val info = TelegramFileInfo(
                    fileId = 7,
                    sizeBytes = bytes.size.toLong(),
                    localPath = null,
                    isDownloadingCompleted = true,
                    isDownloadingActive = false,
                    downloadOffset = 0,
                    downloadedPrefixSize = bytes.size.toLong()
                )
                when (method.name) {
                    "resolveFile", "getFileInfo" -> info
                    "readFilePart" -> {
                        readCalls.incrementAndGet()
                        val offset = (args[1] as Number).toLong()
                        val count = (args[2] as Number).toLong()
                        if (offset >= bytes.size) ByteArray(0)
                        else bytes.copyOfRange(
                            offset.toInt(),
                            minOf(offset + count, bytes.size.toLong()).toInt()
                        )
                    }
                    "requestFileRange", "cancelFileDownload", "deleteLocalCopy" -> Unit
                    "fileUpdates" -> flowOf(info)
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        ) as TelegramClient
    }

    private fun driveFile() = DriveFile(
        id = "file-1",
        folderId = null,
        name = "bundle.zip",
        sizeBytes = 1234,
        mimeType = "application/zip",
        category = FileCategory.ARCHIVE,
        localPath = null,
        contentHash = null,
        chatId = 100L,
        messageId = 200L,
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
        createdAt = 0,
        modifiedAt = 0,
        addedAt = 0
    )

    private fun service(telegram: TelegramClient) = RemoteArchiveService(
        telegramClient = telegram,
        filePartDao = FakeFilePartDao(),
        downloadWriter = unused<DownloadWriter>(),
        folderPathResolver = FolderPathResolver(
            unused<FolderDao>(),
            ActiveChannel(unused<SettingsRepository>()),
            unused<FolderTombstoneDao>()
        )
    )

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

    private fun awaitState(store: ArchiveEntryThumbnailStore, key: String, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (store.state(key).value != ArchiveThumbState.Placeholder) return
            Thread.sleep(25)
        }
    }

    @Test
    fun `cache hit performs zero telegram calls`() {
        val cacheDir = Files.createTempDirectory("archive-thumbs-hit").toFile()
        try {
            val telegram = CountingTelegram(storedZip(listOf("pic.jpg" to byteArrayOf(1, 2, 3))))
            val store = ArchiveEntryThumbnailStore.forCacheDir(cacheDir, service(telegram.client))
            val key = ArchiveEntryThumbnailPolicy.cacheKey("file-1", 0, 3, 3, 0)
            val thumbsDir = File(cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME).apply { mkdirs() }
            File(thumbsDir, "$key.jpg").writeBytes(byteArrayOf(9, 9, 9))

            store.request(key, driveFile(), entryIndex = 0, entryName = "pic.jpg", sizeBytes = 3)

            assertTrue(store.state(key).value is ArchiveThumbState.Ready)
            assertEquals("cache hit touched telegram", 0, telegram.totalCalls.get())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `oversize entry performs zero telegram calls and is marked failed`() {
        val cacheDir = Files.createTempDirectory("archive-thumbs-big").toFile()
        try {
            val telegram = CountingTelegram(storedZip(listOf("big.jpg" to byteArrayOf(1))))
            val store = ArchiveEntryThumbnailStore.forCacheDir(cacheDir, service(telegram.client))
            val oversize = ArchiveEntryThumbnailPolicy.MAX_ENTRY_BYTES + 1
            val key = ArchiveEntryThumbnailPolicy.cacheKey("file-1", 0, oversize, oversize, 0)

            store.request(key, driveFile(), entryIndex = 0, entryName = "big.jpg", sizeBytes = oversize)

            assertEquals(ArchiveThumbState.Failed, store.state(key).value)
            assertEquals("oversize entry touched telegram", 0, telegram.totalCalls.get())
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `eligible fetch reads the entry and always deletes its extraction temp`() {
        val cacheDir = Files.createTempDirectory("archive-thumbs-fetch").toFile()
        try {
            val content = "fake jpeg bytes".toByteArray()
            val telegram = CountingTelegram(storedZip(listOf("pic.jpg" to content)))
            val store = ArchiveEntryThumbnailStore.forCacheDir(cacheDir, service(telegram.client))
            val key = ArchiveEntryThumbnailPolicy.cacheKey(
                "file-1", 0, content.size.toLong(), content.size.toLong(), 0
            )

            store.request(
                key, driveFile(), entryIndex = 0, entryName = "pic.jpg",
                sizeBytes = content.size.toLong()
            )
            awaitState(store, key)

            // Host bitmap stubs cannot decode, so the fetch ends in Failed — but it
            // must have read the entry, produced no cache file, and left no temp.
            assertEquals(ArchiveThumbState.Failed, store.state(key).value)
            assertTrue("fetch never read the byte source", telegram.readCalls.get() > 0)
            val thumbsDir = File(cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME)
            val leftovers = thumbsDir.listFiles()?.map { it.name } ?: emptyList()
            assertTrue("extraction temp left behind: $leftovers", leftovers.none { it.startsWith("extract-") })
            assertTrue("failed fetch wrote a cache file: $leftovers", leftovers.none { it.endsWith(".jpg") })
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
