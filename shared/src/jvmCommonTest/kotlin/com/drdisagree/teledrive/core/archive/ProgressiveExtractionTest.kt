package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.FakeGrowth
import com.drdisagree.teledrive.core.media.FakeTdLibTelegramClient
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
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PC 1.6.27 item N4: progressive in-zip extraction, driven through the REAL
 * [RemoteArchiveService] + [ZipArchiveReader] + ranged byte source against
 * [FakeTdLibTelegramClient] (the report-52 TDLib semantics model).
 *
 * The field failure these tests pin: previously the player/viewer was
 * handed NOTHING until an entry had been produced 100% and CRC-verified —
 * a 500 MB video meant waiting for the whole entry. Now the start decision
 * must fire with only a prefix on disk, the fetch must proceed in order
 * (the order TDLib's contiguous-prefix ReadFilePart gate rewards), an
 * MP4 whose moov sits after its mdat must be reported as delayed instead
 * of silently stalling, the central directory must be parsed once per
 * archive instead of once per switch, and a verification failure must
 * still delete the temp file even after an early start was handed out.
 */
class ProgressiveExtractionTest {

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
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String = "/Downloads/$fileName"
        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
        ): PartialDownload? = null
        override fun freeBytes(folderPath: String?): Long? = null
    }

    private fun service(fake: FakeTdLibTelegramClient) = RemoteArchiveService(
        telegramClient = fake.client,
        filePartDao = FakeFilePartDao(),
        downloadWriter = FakeDownloadWriter(),
        folderPathResolver = FolderPathResolver(
            unused<FolderDao>(),
            ActiveChannel(unused<SettingsRepository>()),
            unused<FolderTombstoneDao>()
        )
    )

    private fun driveFile(sizeBytes: Long) = DriveFile(
        id = "file-1",
        folderId = null,
        name = "bundle.zip",
        sizeBytes = sizeBytes,
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

    /** Central-directory offset, read from the EOCD record at the file tail. */
    private fun centralDirectoryOffset(zip: ByteArray): Long {
        val eocd = zip.size - 22
        fun u32(at: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = v or ((zip[at + i].toLong() and 0xFF) shl (8 * i))
            return v
        }
        require(u32(eocd) == 0x06054b50L) { "test zip has no plain EOCD" }
        return u32(eocd + 16)
    }

    private fun mp4Box(type: String, payload: ByteArray): ByteArray {
        val size = 8 + payload.size
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        payload.copyInto(out, 8)
        return out
    }

    @Test
    fun `start decision fires with only a prefix produced, then the entry completes verified`() =
        runBlocking {
            val videoData = Random(41).nextBytes(3 * 1024 * 1024)
            val zipBytes = buildZip(
                listOf(
                    Triple("movie.mkv", videoData, ZipEntry.DEFLATED),
                    Triple("notes.txt", ByteArray(2_000) { 5 }, ZipEntry.DEFLATED)
                )
            )
            // Slow but progressing window: the full entry takes many growth
            // steps, so a start decision observed mid-flight is meaningful.
            val fake = FakeTdLibTelegramClient(
                zipBytes,
                growth = FakeGrowth.DELAYED,
                growthStepBytes = 64 * 1024,
                growthStepIntervalMs = 20
            )
            val service = service(fake)
            val file = driveFile(zipBytes.size.toLong())

            val decisionSeen = CompletableDeferred<ProgressiveStartDecision>()
            val producedAtDecision = CompletableDeferred<Long>()
            val tempLengthAtDecision = CompletableDeferred<Long>()
            var latestProduced = 0L

            val extraction = async {
                service.extractEntryProgressively(
                    file = file,
                    entryName = "movie.mkv",
                    occurrence = 0,
                    onStartDecision = { temp, decision ->
                        if (!decisionSeen.isCompleted) {
                            producedAtDecision.complete(latestProduced)
                            tempLengthAtDecision.complete(temp.length())
                            decisionSeen.complete(decision)
                        }
                    },
                    onProgress = { produced -> latestProduced = produced }
                )
            }

            val decision = withTimeout(60_000) { decisionSeen.await() }
            assertEquals(ProgressiveStartDecision.StartNow, decision)
            val produced = withTimeout(5_000) { producedAtDecision.await() }
            val tempLength = withTimeout(5_000) { tempLengthAtDecision.await() }
            // The whole point of N4: the player is handed the file with only
            // a prefix present — not after 100% of a multi-MB entry.
            assertTrue(
                "decision produced $produced of ${videoData.size} bytes",
                produced >= ProgressiveStartPolicy.DEFAULT_PREFIX_BYTES
            )
            assertTrue(
                "decision produced $produced of ${videoData.size} bytes",
                produced < videoData.size.toLong()
            )
            assertTrue(
                "temp file at decision held $tempLength bytes",
                tempLength in ProgressiveStartPolicy.DEFAULT_PREFIX_BYTES until
                    videoData.size.toLong()
            )
            assertTrue("extraction must still be running at decision time", extraction.isActive)

            val temp = withTimeout(60_000) { extraction.await() }
            try {
                assertArrayEquals(videoData, temp.readBytes())
            } finally {
                temp.delete()
            }
        }

    @Test
    fun `entry data is fetched in ascending order and no other entry is touched`() = runBlocking {
        val videoData = Random(43).nextBytes(2 * 1024 * 1024)
        val fillerData = Random(44).nextBytes(1024 * 1024)
        val zipBytes = buildZip(
            listOf(
                Triple("movie.mkv", videoData, ZipEntry.DEFLATED),
                Triple("filler.bin", fillerData, ZipEntry.DEFLATED)
            )
        )
        val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
        val service = service(fake)
        val file = driveFile(zipBytes.size.toLong())
        val listing = service.listEntries(file)
        val filler = listing.first { it.name == "filler.bin" }
        val cdOffset = centralDirectoryOffset(zipBytes)
        fake.rangeRequestList.clear()

        val temp = service.extractEntryProgressively(
            file = file,
            entryName = "movie.mkv",
            occurrence = 0,
            onStartDecision = { _, _ -> }
        )
        try {
            assertArrayEquals(videoData, temp.readBytes())
        } finally {
            temp.delete()
        }

        val requests = fake.rangeRequestList.map { it.first }
        // Directory ranges live at/after the tail window start; everything
        // below it in this extraction is the entry's local header + data.
        val tailStart = zipBytes.size - 65_600L
        val dataRequests = requests.filter { it < tailStart }
        assertTrue("expected data range requests, got $requests", dataRequests.isNotEmpty())
        assertEquals(
            "entry data must be fetched in ascending (in-order) offsets: $dataRequests",
            dataRequests.sorted(),
            dataRequests
        )
        // Whole-ZIP download is forbidden: not one request may touch the
        // neighbouring entry's bytes.
        assertTrue(
            "a request touched the filler entry's range: $requests",
            requests.none { it >= filler.localHeaderOffset && it < cdOffset }
        )
    }

    @Test
    fun `moov-at-end mp4 reports delayed before completion and still completes verified`() =
        runBlocking {
            val payload = Random(47).nextBytes(600 * 1024)
            val mp4Data = mp4Box("ftyp", ByteArray(16) { 1 }) +
                mp4Box("mdat", payload) +
                mp4Box("moov", ByteArray(64) { 2 })
            val zipBytes = buildZip(listOf(Triple("clip.mp4", mp4Data, ZipEntry.STORED)))
            val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
            val service = service(fake)
            val file = driveFile(zipBytes.size.toLong())

            val decisions = mutableListOf<Pair<ProgressiveStartDecision, Long>>()
            var latestProduced = 0L
            val temp = service.extractEntryProgressively(
                file = file,
                entryName = "clip.mp4",
                occurrence = 0,
                onStartDecision = { _, decision ->
                    decisions += decision to latestProduced
                },
                onProgress = { produced -> latestProduced = produced }
            )
            try {
                assertArrayEquals(mp4Data, temp.readBytes())
            } finally {
                temp.delete()
            }
            assertEquals("exactly one start decision must fire", 1, decisions.size)
            val (decision, producedAtDecision) = decisions.single()
            assertTrue(
                "expected DelayedUntilComplete, got $decision",
                decision is ProgressiveStartDecision.DelayedUntilComplete
            )
            assertTrue(
                "delayed verdict must arrive before the entry is complete " +
                    "($producedAtDecision of ${mp4Data.size})",
                producedAtDecision < mp4Data.size.toLong()
            )
        }

    @Test
    fun `central directory is parsed once across listing and later extractions`() = runBlocking {
        val oneData = Random(53).nextBytes(700 * 1024)
        val twoData = Random(59).nextBytes(700 * 1024)
        val zipBytes = buildZip(
            listOf(
                Triple("one.mkv", oneData, ZipEntry.DEFLATED),
                Triple("two.mkv", twoData, ZipEntry.DEFLATED)
            )
        )
        val fake = FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT)
        val service = service(fake)
        val file = driveFile(zipBytes.size.toLong())
        service.listEntries(file)

        val tailStart = zipBytes.size - 65_600L
        fake.rangeRequestList.clear()
        val tempTwo = service.extractEntryToTempFile(file, "two.mkv")
        try {
            assertArrayEquals(twoData, tempTwo.readBytes())
        } finally {
            tempTwo.delete()
        }
        assertTrue(
            "second extraction re-read the directory: ${fake.rangeRequestList}",
            fake.rangeRequestList.none { it.first >= tailStart }
        )

        fake.rangeRequestList.clear()
        val tempOne = service.extractEntryProgressively(
            file = file,
            entryName = "one.mkv",
            occurrence = 0,
            onStartDecision = { _, _ -> }
        )
        try {
            assertArrayEquals(oneData, tempOne.readBytes())
        } finally {
            tempOne.delete()
        }
        assertTrue(
            "progressive extraction re-read the directory: ${fake.rangeRequestList}",
            fake.rangeRequestList.none { it.first >= tailStart }
        )
    }

    @Test
    fun `verification failure after an early start still deletes the temp file`() = runBlocking {
        val videoData = Random(61).nextBytes(2 * 1024 * 1024)
        val zipBytes = buildZip(listOf(Triple("movie.mkv", videoData, ZipEntry.DEFLATED)))
        // Corrupt one byte near the END of the entry's compressed data: the
        // start decision fires from the intact prefix, then length/CRC
        // verification must fail. The position is computed from the real
        // listing + local header (a blind flip near the central directory
        // could land in the data descriptor ZipOutputStream appends after
        // the compressed data, which the reader never reads).
        val probeService = service(FakeTdLibTelegramClient(zipBytes, growth = FakeGrowth.INSTANT))
        val probeFile = driveFile(zipBytes.size.toLong())
        val entryInfo = probeService.listEntries(probeFile).first { it.name == "movie.mkv" }
        val headerAt = entryInfo.localHeaderOffset.toInt()
        fun u16(at: Int): Int =
            (zipBytes[at].toInt() and 0xFF) or ((zipBytes[at + 1].toInt() and 0xFF) shl 8)
        val dataStart = headerAt + 30 + u16(headerAt + 26) + u16(headerAt + 28)
        val corruptAt = (dataStart + entryInfo.compressedBytes - 64).toInt()
        val corrupted = zipBytes.copyOf()
        corrupted[corruptAt] = (corrupted[corruptAt].toInt() xor 0xFF).toByte()
        val fake = FakeTdLibTelegramClient(corrupted, growth = FakeGrowth.INSTANT)
        val service = service(fake)
        val file = driveFile(corrupted.size.toLong())

        val decisionSeen = CompletableDeferred<ProgressiveStartDecision>()
        val tempSeen = CompletableDeferred<File>()
        try {
            service.extractEntryProgressively(
                file = file,
                entryName = "movie.mkv",
                occurrence = 0,
                onStartDecision = { temp, decision ->
                    if (!decisionSeen.isCompleted) {
                        tempSeen.complete(temp)
                        decisionSeen.complete(decision)
                    }
                }
            )
            fail("corrupt entry must not extract successfully")
        } catch (expected: ArchiveException) {
            // Expected: CRC_MISMATCH or CORRUPT, depending on where the
            // flipped byte lands in the deflate stream.
        }
        assertEquals(
            ProgressiveStartDecision.StartNow,
            withTimeout(5_000) { decisionSeen.await() }
        )
        val temp = withTimeout(5_000) { tempSeen.await() }
        assertFalse("failed viewing temp must be deleted", temp.exists())
    }
}
