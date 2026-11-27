package com.drdisagree.teledrive.desktop.media

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
 * PC 1.6.27 item N4, settled-page priority: while an in-zip preview
 * extraction is running, archive-row thumbnail fetches must not compete
 * for the archive file's single TDLib download window (report 52 §1b).
 * A request made under priority performs ZERO Telegram calls and stays
 * Placeholder (never Failed); when priority clears, the queued request
 * is replayed and completes normally.
 */
class DesktopArchiveThumbnailPriorityTest {

    private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java)
    ) { _, method, _ -> throw UnsupportedOperationException(method.name) } as T

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
        ) as TelegramClient
    }

    private fun service(telegram: TelegramClient) = RemoteArchiveService(
        telegramClient = telegram,
        filePartDao = FakeFilePartDao(),
        downloadWriter = FakeDownloadWriter(),
        folderPathResolver = FolderPathResolver(
            unused<FolderDao>(), ActiveChannel(unused<SettingsRepository>()),
            unused<FolderTombstoneDao>()
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

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for: $description", condition())
    }

    @Test
    fun `thumbnail requests wait out preview priority with zero telegram calls, then replay`() {
        val png = pngBytes()
        val zipBytes = storedZip(listOf("pic.png" to png))
        val telegram = CountingTelegram(zipBytes)
        val cacheDir = Files.createTempDirectory("thumb-priority").toFile()
        try {
            val store = DesktopArchiveEntryThumbnailStore.forCacheDir(
                cacheDir, service(telegram.client)
            )
            val key = "priority-key"
            store.setPreviewPriorityActive(true)
            assertTrue(store.isPreviewPriorityActive())

            store.request(key, driveFile(), 0, "pic.png", png.size.toLong())
            assertEquals(1, store.pendingRequestCount())
            assertEquals(
                "no Telegram call may happen while priority is active",
                0, telegram.totalCalls.get()
            )
            assertEquals(ArchiveThumbState.Placeholder, store.state(key).value)

            store.setPreviewPriorityActive(false)
            assertEquals(0, store.pendingRequestCount())
            await("queued thumbnail completes after priority clears") {
                store.state(key).value is ArchiveThumbState.Ready
            }
            assertTrue(telegram.totalCalls.get() > 0)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun `releasing a queued request drops it instead of replaying it`() {
        val png = pngBytes()
        val zipBytes = storedZip(listOf("pic.png" to png))
        val telegram = CountingTelegram(zipBytes)
        val cacheDir = Files.createTempDirectory("thumb-priority-release").toFile()
        try {
            val store = DesktopArchiveEntryThumbnailStore.forCacheDir(
                cacheDir, service(telegram.client)
            )
            val key = "priority-release-key"
            store.setPreviewPriorityActive(true)
            store.request(key, driveFile(), 0, "pic.png", png.size.toLong())
            assertEquals(1, store.pendingRequestCount())
            store.release(key)
            assertEquals(0, store.pendingRequestCount())
            store.setPreviewPriorityActive(false)
            // Give a wrongly-replayed request a chance to run, then prove
            // nothing was fetched and the row is still a plain placeholder.
            Thread.sleep(300)
            assertEquals(0, telegram.totalCalls.get())
            assertEquals(ArchiveThumbState.Placeholder, store.state(key).value)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
