package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.FilePartialDownload
import com.drdisagree.teledrive.core.files.Hashing
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.repository.SyncHarness
import com.drdisagree.teledrive.testing.unused
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PartDownloaderTest {

    private val harness = SyncHarness()
    private val parts = harness.database.filePartDao()
    private val workDir: File = Files.createTempDirectory("parts").toFile()
    private val destination = File(workDir, "out").apply { mkdirs() }
    private val contents = listOf(ByteArray(300) { 1 }, ByteArray(300) { 2 }, ByteArray(50) { 3 })
    private val telegram = PartTelegram(contents, workDir)

    @After
    fun tearDown() {
        harness.close()
        workDir.deleteRecursively()
    }

    @Test
    fun `parts are written straight into the destination and freed one by one`() = runBlocking {
        recordParts()
        val target = FilePartialDownload.open(destination, "big.vhdx", null)!!

        downloader().download("f1", encrypted = false, target = target).toList()

        assertArrayEquals(contents.reduce { a, b -> a + b }, File(target.token).readBytes())
        assertEquals(listOf("r0", "r1", "r2"), telegram.freed)
        assertEquals("big.vhdx", File(target.finish()!!).name)
    }

    @Test
    fun `a resumed download skips the parts already written and drops a half part`() = runBlocking {
        recordParts()
        val first = FilePartialDownload.open(destination, "big.vhdx", null)!!
        first.append { it.write(contents[0]); it.write(ByteArray(120) { 9 }) }

        val resumed = FilePartialDownload.open(destination, "big.vhdx", first.token)!!
        downloader().download("f1", encrypted = false, target = resumed).toList()

        assertEquals(listOf("r1", "r2"), telegram.downloaded)
        assertArrayEquals(contents.reduce { a, b -> a + b }, File(resumed.token).readBytes())
    }

    @Test
    fun `a corrupted part is rejected without removing previously verified parts`() = runBlocking {
        recordParts()
        telegram.corruptParts += 1
        val target = FilePartialDownload.open(destination, "big.vhdx", null)!!

        val failure = runCatching {
            downloader().download("f1", encrypted = false, target = target).toList()
        }.exceptionOrNull()

        assertTrue(failure is PartIntegrityException)
        assertArrayEquals(contents[0], File(target.token).readBytes())
        assertEquals(listOf("r0", "r1"), telegram.freed)
    }

    @Test
    fun `a name already taken gets a free one`() {
        File(destination, "big.vhdx").writeText("old")

        val target = FilePartialDownload.open(destination, "big.vhdx", null)!!

        assertTrue(File(target.token).name.startsWith("big (1).vhdx"))
    }

    private suspend fun recordParts() {
        var offset = 0L
        contents.forEachIndexed { index, bytes ->
            parts.upsert(
                FilePartEntity(
                    fileId = "f1",
                    partIndex = index,
                    chatId = 1,
                    messageId = index.toLong(),
                    remoteFileId = "r$index",
                    remoteUniqueId = "u$index",
                    plainOffset = offset,
                    plainSize = bytes.size.toLong(),
                    storedSize = bytes.size.toLong(),
                    uploadedAt = 1,
                    plainHash = Hashing.sha256(java.io.ByteArrayInputStream(bytes))
                )
            )
            offset += bytes.size
        }
    }

    private fun downloader() = PartDownloader(
        telegramClient = telegram,
        filePartDao = parts,
        streamCrypto = StreamCrypto(),
        wrappedKeyRepository = unused(),
        dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }
    )

    private class PartTelegram(
        private val contents: List<ByteArray>,
        private val dir: File
    ) : TelegramClient by unused() {
        val downloaded = mutableListOf<String>()
        val freed = mutableListOf<String>()
        val corruptParts = mutableSetOf<Int>()

        override fun downloadDocument(
            remoteFileId: String,
            chatId: Long?,
            messageId: Long?
        ): Flow<TelegramDownloadEvent> {
            downloaded += remoteFileId
            val index = remoteFileId.removePrefix("r").toInt()
            val bytes = contents[index].copyOf()
            if (index in corruptParts && bytes.isNotEmpty()) {
                bytes[0] = (bytes[0].toInt() xor 0xFF).toByte()
            }
            val file = File(dir, "tdlib-$remoteFileId").apply { writeBytes(bytes) }
            return flowOf(TelegramDownloadEvent.Completed(file.absolutePath, file.length()))
        }

        override suspend fun deleteLocalCopy(remoteFileId: String) {
            freed += remoteFileId
            File(dir, "tdlib-$remoteFileId").delete()
        }
    }
}
