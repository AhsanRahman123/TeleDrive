package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.BufferedMediaByteSource
import com.drdisagree.teledrive.core.media.FakeCancelVariant
import com.drdisagree.teledrive.core.media.FakeGrowth
import com.drdisagree.teledrive.core.media.FakeRangeVariant
import com.drdisagree.teledrive.core.media.FakeReadVariant
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Report-51 harness: drives the REAL [TelegramMediaByteSource] + [BufferedMediaByteSource] +
 * [RemoteArchiveService] + [ZipArchiveReader] through the exact field sequence —
 * listEntries (session closed with CancelDownloadFile) then, in a FRESH session,
 * extractEntryToTempFile / extractEntry (re-list + local header + data) — against a fake
 * TelegramClient that emulates TDLib download-window semantics (see
 * [FakeTdLibTelegramClient]) instead of pretending the file is already downloaded.
 *
 * The field pattern being chased (1.6.21): listing works everywhere on desktop, every
 * extraction out of a ZIP fails fast with the generic "Could not open archive", one
 * extraction hung at 0%. Android, with the same Kotlin layers over official TDLib, works.
 */
class TelegramArchiveHarnessTest {

    // ----- service fakes (same shapes as RemoteArchiveServiceTest, harness-local) -----

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

    // ----- a multi-MB zip: stored at the start, deflate in the middle, stored at the end -----

    private val startData = Random(11).nextBytes(200_000)
    private val middleData = ByteArray(300_000) { (it % 251).toByte() }
    private val endData = Random(23).nextBytes(150_000)
    // Random (incompressible) filler so the DEFLATE entry stays large on disk too:
    // the archive must be genuinely multi-MB for the middle/end offsets to exist.
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
     * The exact field sequence: list in one session (the service closes the source, which
     * cancels the Telegram download), then extract in a fresh session — preview path
     * (temp file) for one entry and download path (writer) for another.
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
        assertTrue("listing session must have been closed via cancel", fake.cancelCalls >= 1)

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
    fun `field sequence - V1 documented semantics, R1 restart, C1 prefix kept, instant growth`() {
        val fake = FakeTdLibTelegramClient(zipBytes)
        runFieldSequence(fake, "c-middle.txt", middleData, "a-start.bin", startData)
    }

    @Test
    fun `field sequence - V1, R1, C2 prefix discarded, delayed growth`() {
        val fake = FakeTdLibTelegramClient(
            zipBytes,
            cancelVariant = FakeCancelVariant.C2_PREFIX_DISCARDED,
            growth = FakeGrowth.DELAYED
        )
        runFieldSequence(fake, "d-end.bin", endData, "c-middle.txt", middleData)
    }

    @Test
    fun `field sequence - V3 empty reads on uncovered ranges`() {
        val fake = FakeTdLibTelegramClient(
            zipBytes,
            readVariant = FakeReadVariant.V3_EMPTY,
            growth = FakeGrowth.DELAYED
        )
        runFieldSequence(fake, "a-start.bin", startData, "d-end.bin", endData)
    }

    @Test
    fun `field sequence - V2 covered-subrange reads must not corrupt or fail extraction`() {
        // V2 is the prime desktop suspect: a ReadFilePart that hands back whatever part of
        // the download window it has — short, and misaligned when the request starts before
        // the window — instead of erroring like documented TDLib. The old byte source
        // accepted ANY non-empty result as the requested bytes, so a read for a local
        // header at the start of the file could silently receive central-directory bytes.
        val fake = FakeTdLibTelegramClient(
            zipBytes,
            readVariant = FakeReadVariant.V2_COVERED_SUBRANGE,
            growth = FakeGrowth.DELAYED
        )
        runFieldSequence(fake, "c-middle.txt", middleData, "d-end.bin", endData)
    }

    @Test
    fun `byte source - V2 misaligned read for an uncovered position returns the requested bytes`() =
        runBlocking {
            val fake = FakeTdLibTelegramClient(
                zipBytes,
                readVariant = FakeReadVariant.V2_COVERED_SUBRANGE,
                growth = FakeGrowth.INSTANT
            )
            val source = BufferedMediaByteSource(
                TelegramMediaByteSource(fake.client, "remote-1", 100L, 200L, primeFromStart = false)
            )
            try {
                // Prime the window at the tail (like a listing), then jump to the start.
                val tailStart = zipBytes.size - 1_000L
                assertArrayEquals(
                    zipBytes.copyOfRange(tailStart.toInt(), zipBytes.size),
                    source.read(tailStart, 1_000)
                )
                assertArrayEquals(zipBytes.copyOfRange(0, 4_096), source.read(0, 4_096))
                assertArrayEquals(
                    zipBytes.copyOfRange(1_000_000, 1_004_096),
                    source.read(1_000_000, 4_096)
                )
            } finally {
                source.close()
            }
        }

    @Test
    fun `byte source - R2 range request ignored while a window is active still reaches a position behind the window`() =
        runBlocking {
            val fake = FakeTdLibTelegramClient(
                zipBytes,
                rangeVariant = FakeRangeVariant.R2_IGNORED_WHILE_ACTIVE,
                growth = FakeGrowth.DELAYED,
                growthStepBytes = 256 * 1024
            )
            val source = TelegramMediaByteSource(
                fake.client, "remote-1", 100L, 200L, primeFromStart = false
            )
            try {
                // Start a window in the middle; it is still active (not at EOF) when covered.
                assertArrayEquals(
                    zipBytes.copyOfRange(1_000_000, 1_000_100),
                    source.read(1_000_000, 100)
                )
                // Jump behind the active window: the re-target request is ignored (R2), so
                // the only routes to coverage are the window finishing and a re-request.
                assertArrayEquals(zipBytes.copyOfRange(0, 100), source.read(0, 100))
                assertTrue("R2 must have ignored at least one request", fake.ignoredRangeRequests >= 1)
            } finally {
                source.close()
            }
        }

    @Test
    fun `byte source - a stalled window fails with a typed TelegramException, not a timeout cancellation`() =
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
            } finally {
                source.close()
            }
        }
}
