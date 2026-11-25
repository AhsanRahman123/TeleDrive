package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.BufferedMediaByteSource
import com.drdisagree.teledrive.core.media.FakeGrowth
import com.drdisagree.teledrive.core.media.FakeTdLibTelegramClient
import com.drdisagree.teledrive.core.media.TelegramMediaByteSource
import com.drdisagree.teledrive.core.telegram.TelegramException
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
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Report-52 harness: drives the REAL [TelegramMediaByteSource] + [BufferedMediaByteSource] +
 * [RemoteArchiveService] + [ZipArchiveReader] through the exact field sequence —
 * listEntries (session closed) then, in a FRESH session, extractEntryToTempFile /
 * extractEntry (re-list + local header + data) — against [FakeTdLibTelegramClient], which
 * models the TDLight / upstream TDLib source semantics from
 * `52-tdlight-source-findings.md` (persistent part bitmask, single re-targetable window,
 * all-or-nothing ReadFilePart gate). The same upstream gate is what Android's official
 * TDLib implements, so these tests are the Android regression argument too.
 */
class TelegramArchiveHarnessTest {

    private class HarnessFilePartDao : FilePartDao {
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

    private class HarnessDownloadWriter : DownloadWriter {
        var bytes: ByteArray? = null
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
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

    private fun service(fake: FakeTdLibTelegramClient, writer: HarnessDownloadWriter) =
        RemoteArchiveService(
            telegramClient = fake.client,
            filePartDao = HarnessFilePartDao(),
            downloadWriter = writer,
            folderPathResolver = FolderPathResolver(
                unused<FolderDao>(),
                ActiveChannel(unused<SettingsRepository>()),
                unused<FolderTombstoneDao>()
            )
        )

    private val startData = Random(11).nextBytes(200_000)
    private val middleData = ByteArray(300_000) { (it % 251).toByte() }
    private val endData = Random(23).nextBytes(150_000)
    private val fillerData = Random(37).nextBytes(1_800_000)

    private val zipBytes: ByteArray = buildZip(
        listOf(
            Triple("a-start.bin", startData, ZipEntry.STORED),
            Triple("b-filler.bin", fillerData, ZipEntry.DEFLATED),
            Triple("c-middle.txt", middleData, ZipEntry.DEFLATED),
            Triple("d-end.bin", endData, ZipEntry.STORED)
        )
    )

    private fun buildZip(entries: List<Triple<String, ByteArray, Int>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data, method) in entries) {
                val entry = ZipEntry(name)
                entry.method = method
                if (method == ZipEntry.STORED) {
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    val crc = CRC32(); crc.update(data); entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /**
     * The exact field sequence on ONE fileId: list in one session (the service closes the
     * source), then extract in fresh sessions — preview path (temp file) for one entry and
     * download path (writer) for another.
     */
    private fun runFieldSequence(
        fake: FakeTdLibTelegramClient,
        previewEntry: String,
        previewExpected: ByteArray,
        downloadEntry: String,
        downloadExpected: ByteArray
    ) = runBlocking {
        val writer = HarnessDownloadWriter()
        val service = service(fake, writer)
        val file = driveFile()

        val listing = service.listEntries(file)
        assertEquals(
            listOf("a-start.bin", "b-filler.bin", "c-middle.txt", "d-end.bin"),
            listing.filter { !it.isDirectory }.map { it.name }
        )

        val temp = service.extractEntryToTempFile(file, previewEntry)
        try {
            assertArrayEquals(previewExpected, temp.readBytes())
        } finally {
            temp.delete()
        }

        service.extractEntry(file, downloadEntry)
        assertArrayEquals(downloadExpected, writer.bytes)
    }

    @Test
    fun `field sequence - source semantics, instant growth`() {
        val fake = FakeTdLibTelegramClient(zipBytes)
        runFieldSequence(fake, "c-middle.txt", middleData, "a-start.bin", startData)
    }

    @Test
    fun `field sequence - source semantics, delayed growth`() {
        val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.DELAYED)
        runFieldSequence(fake, "d-end.bin", endData, "c-middle.txt", middleData)
    }

    @Test
    fun `field sequence - source semantics, delayed growth, small steps`() {
        val fake = FakeTdLibTelegramClient(
            zipBytes,
            growth = FakeGrowth.DELAYED,
            growthStepBytes = 32 * 1024
        )
        runFieldSequence(fake, "a-start.bin", startData, "d-end.bin", endData)
    }

    @Test
    fun `byte source - bytes from an earlier window stay readable after re-target without a new DownloadFile`() =
        runBlocking {
            val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
            val source = TelegramMediaByteSource(
                fake.client, "remote-1", 100L, 200L, primeFromStart = false
            )
            try {
                // Window 1: the tail (like a listing).
                val tailStart = zipBytes.size - 1_000L
                assertArrayEquals(
                    zipBytes.copyOfRange(tailStart.toInt(), zipBytes.size),
                    source.read(tailStart, 1_000)
                )
                // Window 2: re-target to the start of the file.
                assertArrayEquals(zipBytes.copyOfRange(0, 4_096), source.read(0, 4_096))
                val requestsAfterRetarget = fake.rangeRequests
                // The tail bytes persist in the partial file and ReadFilePart's gate is
                // keyed to the read offset, not the current window: re-reading the tail
                // must succeed WITHOUT another DownloadFile.
                assertArrayEquals(
                    zipBytes.copyOfRange(tailStart.toInt(), zipBytes.size),
                    source.read(tailStart, 1_000)
                )
                assertEquals(
                    "earlier-window bytes must be readable without a new DownloadFile",
                    requestsAfterRetarget, fake.rangeRequests
                )
            } finally {
                source.close()
            }
        }

    @Test
    fun `byte source - slow-starting window with silent updates issues exactly one DownloadFile`() =
        runBlocking {
            // Second pass (report 52, must-fix 2 + O4(i)): fileUpdates emits NOTHING
            // during the wait (replay-0 SharedFlow behaviour) and the window's first
            // part lands only after >1 wait slice (1_200 ms > 500 ms). Pre-fix, the
            // re-issue decision read the stale pre-request snapshot and re-issued
            // the identical DownloadFile on the next failed read (each re-issue
            // also restarts this fake's slow-start delay, so the read never landed).
            val fake = FakeTdLibTelegramClient(
                zipBytes,
                growth = FakeGrowth.INSTANT,
                emitUpdatesOnlyOnChange = true,
                slowStartDelayMs = 1_200
            )
            val source = TelegramMediaByteSource(
                fake.client, "remote-1", 100L, 200L, primeFromStart = false
            )
            try {
                assertArrayEquals(zipBytes.copyOfRange(0, 4_096), source.read(0, 4_096))
                assertEquals(
                    "exactly one DownloadFile must be issued while the window is active",
                    1, fake.rangeRequests
                )
            } finally {
                source.close()
            }
        }

    @Test
    fun `byte source - range-only close does not cancel the download`() = runBlocking {
        val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
        val source = TelegramMediaByteSource(
            fake.client, "remote-1", 100L, 200L, primeFromStart = false
        )
        source.read(0, 100)
        source.close()
        assertEquals(
            "range-only sources must not cancel on close (re-targeting is the designed path)",
            0, fake.cancelCalls
        )
    }

    @Test
    fun `byte source - a stalled window fails with a typed 408 carrying the last observed state`() =
        runBlocking {
            val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.STALLED)
            val source = TelegramMediaByteSource(
                fake.client, "remote-1", 100L, 200L, primeFromStart = false
            )
            try {
                source.read(0, 100)
                fail("a stalled download window must fail, not return bytes or hang")
            } catch (expected: TelegramException) {
                assertEquals(408, expected.code)
                assertTrue(
                    "408 message must carry the last observed state, was: ${expected.message}",
                    expected.message.contains("downloadOffset") &&
                        expected.message.contains("downloadedPrefixSize") &&
                        expected.message.contains("isDownloadingActive")
                )
            } finally {
                source.close()
            }
        }

    // ----- fake-level pins of the source semantics themselves -----

    @Test
    fun `fake - ReadFilePart gate is all-or-nothing with the exact 400 message`() = runBlocking {
        val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
        // Nothing downloaded yet: exact source error.
        try {
            fake.client.readFilePart(FakeTdLibTelegramClient.FILE_ID, 0, 100)
            fail("uncovered read must throw")
        } catch (expected: TelegramException) {
            assertEquals(400, expected.code)
            assertEquals(
                "There is not enough downloaded bytes in the file to read",
                expected.message
            )
        }
        // count=0 probe never fails on coverage and returns the contiguous prefix (empty).
        assertEquals(0, fake.client.readFilePart(FakeTdLibTelegramClient.FILE_ID, 0, 0).size)
        // Download only the first 100 bytes (part-aligned expansion fetches whole parts).
        fake.client.requestFileRange(FakeTdLibTelegramClient.FILE_ID, 0, 100)
        val probe = fake.client.readFilePart(FakeTdLibTelegramClient.FILE_ID, 0, 0)
        assertTrue("part-aligned overshoot expected, probe=${probe.size}", probe.size >= 100)
        // A read extending past the ready parts still fails whole, never short.
        try {
            fake.client.readFilePart(
                FakeTdLibTelegramClient.FILE_ID, 0, zipBytes.size.toLong()
            )
            fail("partially covered read must throw, not short-read")
        } catch (expected: TelegramException) {
            assertEquals(400, expected.code)
        }
    }

    @Test
    fun `fake - limited window terminates inactive and uncompleted with prefix at least the limit`() =
        runBlocking {
            val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
            fake.client.requestFileRange(FakeTdLibTelegramClient.FILE_ID, 1_000_000, 1_000)
            val info = fake.client.getFileInfo(FakeTdLibTelegramClient.FILE_ID)
            assertFalse(info.isDownloadingActive)
            assertFalse("a limit>0 download never completes the file", info.isDownloadingCompleted)
            assertEquals(1_000_000L, info.downloadOffset)
            assertTrue(
                "prefix must reach at least the limit (part overshoot allowed): ${info.downloadedPrefixSize}",
                info.downloadedPrefixSize >= 1_000
            )
            // Re-target re-bases the reported prefix instantly, even though the earlier
            // window's bytes are still in the partial file.
            fake.client.requestFileRange(FakeTdLibTelegramClient.FILE_ID, 0, 100)
            val rebased = fake.client.getFileInfo(FakeTdLibTelegramClient.FILE_ID)
            assertEquals(0L, rebased.downloadOffset)
            assertTrue(rebased.downloadedPrefixSize < 1_000_000L)
            // ...and the earlier window's bytes are still readable via the gate.
            assertArrayEquals(
                zipBytes.copyOfRange(1_000_000, 1_001_000),
                fake.client.readFilePart(FakeTdLibTelegramClient.FILE_ID, 1_000_000, 1_000)
            )
        }

    @Test
    fun `fake - offset plus limit past EOF wraps and fetches the file start`() = runBlocking {
        val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
        val tailStart = zipBytes.size - 500L
        fake.client.requestFileRange(
            FakeTdLibTelegramClient.FILE_ID, tailStart, 2_000
        )
        // The wrapped part of the window fetched the beginning of the file.
        assertArrayEquals(
            zipBytes.copyOfRange(0, 1_000),
            fake.client.readFilePart(FakeTdLibTelegramClient.FILE_ID, 0, 1_000)
        )
    }

    @Test
    fun `fake - limit zero completes the whole file`() = runBlocking {
        val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
        fake.client.requestFileRange(FakeTdLibTelegramClient.FILE_ID, 0, 0)
        val info = fake.client.getFileInfo(FakeTdLibTelegramClient.FILE_ID)
        assertTrue(info.isDownloadingCompleted)
        assertFalse(info.isDownloadingActive)
    }
}
