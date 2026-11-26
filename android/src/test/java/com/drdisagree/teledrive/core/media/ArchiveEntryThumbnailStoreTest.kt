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
import java.io.RandomAccessFile
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * TelegramClient serving several archives keyed by remote file id (MF-1).
     * The FIRST read of [blockedFileId] parks on [releaseFirstRead] after
     * counting down [enteredFirstRead], so a test can hold one archive's
     * central-directory parse in flight while another archive's session is
     * opened — which closes the first archive's session underneath it. When
     * the parked read is released, the parse finishes from the buffered chunk
     * and the fetch then runs into the closed session at extract time.
     */
    private class BlockingFirstReadTelegram(
        private val bytesByRemoteId: Map<String, ByteArray>,
        private val fileIdByRemoteId: Map<String, Int>,
        private val blockedFileId: Int
    ) {
        val enteredFirstRead = CountDownLatch(1)
        val releaseFirstRead = CountDownLatch(1)
        private val firstReadSeen = AtomicBoolean(false)
        private val readsByFileId = ConcurrentHashMap<Int, AtomicInteger>()

        fun readCalls(fileId: Int): Int = readsByFileId[fileId]?.get() ?: 0

        private fun bytesFor(fileId: Int): ByteArray =
            bytesByRemoteId.entries.first { fileIdByRemoteId[it.key] == fileId }.value

        private fun info(fileId: Int, bytes: ByteArray) = TelegramFileInfo(
            fileId = fileId,
            sizeBytes = bytes.size.toLong(),
            localPath = null,
            isDownloadingCompleted = true,
            isDownloadingActive = false,
            downloadOffset = 0,
            downloadedPrefixSize = bytes.size.toLong()
        )

        val client: TelegramClient = Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "resolveFile" -> {
                        val remoteId = args[0] as String
                        info(fileIdByRemoteId.getValue(remoteId), bytesByRemoteId.getValue(remoteId))
                    }
                    "getFileInfo" -> {
                        val fileId = (args[0] as Number).toInt()
                        info(fileId, bytesFor(fileId))
                    }
                    "readFilePart" -> {
                        val fileId = (args[0] as Number).toInt()
                        readsByFileId.computeIfAbsent(fileId) { AtomicInteger(0) }.incrementAndGet()
                        if (fileId == blockedFileId && firstReadSeen.compareAndSet(false, true)) {
                            enteredFirstRead.countDown()
                            // Bounded wait: a broken test must fail, never hang CI.
                            releaseFirstRead.await(15, TimeUnit.SECONDS)
                        }
                        val bytes = bytesFor(fileId)
                        val offset = (args[1] as Number).toLong()
                        val count = (args[2] as Number).toLong()
                        if (offset >= bytes.size) ByteArray(0)
                        else bytes.copyOfRange(
                            offset.toInt(),
                            minOf(offset + count, bytes.size.toLong()).toInt()
                        )
                    }
                    "requestFileRange", "cancelFileDownload", "deleteLocalCopy" -> Unit
                    "fileUpdates" -> {
                        val fileId = (args[0] as Number).toInt()
                        flowOf(info(fileId, bytesFor(fileId)))
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        ) as TelegramClient
    }

    private fun driveFile(id: String = "file-1", remoteFileId: String = "remote-1") = DriveFile(
        id = id,
        folderId = null,
        name = "bundle.zip",
        sizeBytes = 1234,
        mimeType = "application/zip",
        category = FileCategory.ARCHIVE,
        localPath = null,
        contentHash = null,
        chatId = 100L,
        messageId = 200L,
        remoteFileId = remoteFileId,
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

    /**
     * Waits until the in-flight fetch for [key] has fully settled (MF-1: a
     * superseded fetch ends back on Placeholder, which [awaitState] cannot
     * observe). Reads the store's in-flight map reflectively — the test pins
     * lifecycle behaviour, and completion of the Deferred is the exact signal.
     */
    private fun awaitFetchSettled(store: ArchiveEntryThumbnailStore, key: String, timeoutMs: Long = 10_000) {
        val field = ArchiveEntryThumbnailStore::class.java.getDeclaredField("inFlight")
        field.isAccessible = true
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            @Suppress("UNCHECKED_CAST")
            val inFlight = field.get(store) as Map<String, Deferred<Unit>>
            val fetch = inFlight[key]
            if (fetch == null || fetch.isCompleted) return
            Thread.sleep(25)
        }
        throw AssertionError("fetch for $key never settled")
    }

    /**
     * Runs the store's throttled eviction check (MF-2). Eviction only runs on
     * every 8th successful write and host tests cannot produce a successful
     * write (bitmap stubs cannot decode), so the private check is invoked
     * directly until the throttle lets one run.
     */
    private fun runEvictionCheck(store: ArchiveEntryThumbnailStore) {
        val method = ArchiveEntryThumbnailStore::class.java
            .getDeclaredMethod("evictIfNeededThrottled")
        method.isAccessible = true
        repeat(8) { method.invoke(store) }
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

    @Test
    fun `archive switch mid-fetch does not mark the key failed and a later request retries`() {
        val cacheDir = Files.createTempDirectory("archive-thumbs-switch").toFile()
        try {
            val contentA = "archive a image bytes".toByteArray()
            val contentB = "archive b image bytes".toByteArray()
            val telegram = BlockingFirstReadTelegram(
                bytesByRemoteId = mapOf(
                    "remote-A" to storedZip(listOf("pic.jpg" to contentA)),
                    "remote-B" to storedZip(listOf("pic.jpg" to contentB))
                ),
                fileIdByRemoteId = mapOf("remote-A" to 71, "remote-B" to 72),
                blockedFileId = 71
            )
            val store = ArchiveEntryThumbnailStore.forCacheDir(cacheDir, service(telegram.client))
            val keyA = ArchiveEntryThumbnailPolicy.cacheKey(
                "file-A", 0, contentA.size.toLong(), contentA.size.toLong(), 0
            )
            val keyB = ArchiveEntryThumbnailPolicy.cacheKey(
                "file-B", 0, contentB.size.toLong(), contentB.size.toLong(), 0
            )

            // Archive A's fetch parks inside its first byte-source read, with
            // the central-directory parse (and the session mutex) in flight.
            store.request(
                keyA, driveFile(id = "file-A", remoteFileId = "remote-A"),
                entryIndex = 0, entryName = "pic.jpg", sizeBytes = contentA.size.toLong()
            )
            assertTrue(
                "archive A fetch never reached its first read",
                telegram.enteredFirstRead.await(10, TimeUnit.SECONDS)
            )

            // Opening archive B closes A's session underneath the parked fetch.
            // B's own fetch runs to completion (Failed on host: no decode), which
            // proves B's sessionFor — and therefore A's close — has happened.
            store.request(
                keyB, driveFile(id = "file-B", remoteFileId = "remote-B"),
                entryIndex = 0, entryName = "pic.jpg", sizeBytes = contentB.size.toLong()
            )
            awaitState(store, keyB)
            assertEquals(ArchiveThumbState.Failed, store.state(keyB).value)

            // Release A's read: the parse completes, then extraction hits the
            // closed session. That failure must stay retryable.
            telegram.releaseFirstRead.countDown()
            awaitFetchSettled(store, keyA)
            assertEquals(
                "superseded fetch was marked Failed",
                ArchiveThumbState.Placeholder, store.state(keyA).value
            )

            // A later request for the same key must retry — read the byte source
            // again — rather than short-circuiting on a remembered failure. (On
            // host the retry itself ends Failed at decode; the pin is that it
            // was attempted at all.)
            val readsBeforeRetry = telegram.readCalls(71)
            store.request(
                keyA, driveFile(id = "file-A", remoteFileId = "remote-A"),
                entryIndex = 0, entryName = "pic.jpg", sizeBytes = contentA.size.toLong()
            )
            awaitState(store, keyA)
            assertEquals(ArchiveThumbState.Failed, store.state(keyA).value)
            assertTrue(
                "retry after archive switch never re-read the byte source",
                telegram.readCalls(71) > readsBeforeRetry
            )
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `ready key whose cache file disappeared refetches on request`() {
        val cacheDir = Files.createTempDirectory("archive-thumbs-missing").toFile()
        try {
            val content = "fake jpeg bytes".toByteArray()
            val telegram = CountingTelegram(storedZip(listOf("pic.jpg" to content)))
            val store = ArchiveEntryThumbnailStore.forCacheDir(cacheDir, service(telegram.client))
            val key = ArchiveEntryThumbnailPolicy.cacheKey(
                "file-1", 0, content.size.toLong(), content.size.toLong(), 0
            )
            val thumbsDir = File(cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME).apply { mkdirs() }
            val cached = File(thumbsDir, "$key.jpg")
            cached.writeBytes(byteArrayOf(9, 9, 9))

            store.request(
                key, driveFile(), entryIndex = 0, entryName = "pic.jpg",
                sizeBytes = content.size.toLong()
            )
            assertTrue(store.state(key).value is ArchiveThumbState.Ready)
            assertEquals("cache hit touched telegram", 0, telegram.totalCalls.get())

            // The file vanishes while the process lives (external cache clear):
            // the stale Ready must not strand the row — the next request falls
            // through to a real fetch (Failed on host at decode, but attempted).
            assertTrue(cached.delete())
            store.request(
                key, driveFile(), entryIndex = 0, entryName = "pic.jpg",
                sizeBytes = content.size.toLong()
            )
            // The stale Ready stays visible until the refetch settles, so wait
            // on the fetch itself, not on a state change.
            awaitFetchSettled(store, key)
            assertEquals(ArchiveThumbState.Failed, store.state(key).value)
            assertTrue(
                "ready-with-missing-file never refetched",
                telegram.readCalls.get() > 0
            )
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `eviction of a ready key resets it to placeholder and a later request refetches`() {
        val cacheDir = Files.createTempDirectory("archive-thumbs-evict").toFile()
        try {
            val content = "fake jpeg bytes".toByteArray()
            val telegram = CountingTelegram(storedZip(listOf("pic.jpg" to content)))
            val store = ArchiveEntryThumbnailStore.forCacheDir(cacheDir, service(telegram.client))
            val key = ArchiveEntryThumbnailPolicy.cacheKey(
                "file-1", 0, content.size.toLong(), content.size.toLong(), 0
            )
            val thumbsDir = File(cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME).apply { mkdirs() }

            // The victim is Ready and least-recently-modified; a sparse filler
            // pushes the dir just over the 32 MiB cap, so evicting only the
            // victim brings it back under.
            val victim = File(thumbsDir, "$key.jpg")
            victim.writeBytes(byteArrayOf(9, 9, 9))
            victim.setLastModified(1_000_000L)
            val filler = File(thumbsDir, "filler.jpg")
            RandomAccessFile(filler, "rw").use { it.setLength(32L * 1024 * 1024) }
            filler.setLastModified(System.currentTimeMillis())
            assertTrue(store.state(key).value is ArchiveThumbState.Ready)

            runEvictionCheck(store)

            assertFalse("eviction did not delete the victim", victim.exists())
            assertEquals(
                "evicted Ready key kept its stale Ready state",
                ArchiveThumbState.Placeholder, store.state(key).value
            )

            // And the reset is functional: the next request refetches.
            store.request(
                key, driveFile(), entryIndex = 0, entryName = "pic.jpg",
                sizeBytes = content.size.toLong()
            )
            awaitFetchSettled(store, key)
            assertEquals(ArchiveThumbState.Failed, store.state(key).value)
            assertTrue(
                "request after eviction never refetched",
                telegram.readCalls.get() > 0
            )
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
