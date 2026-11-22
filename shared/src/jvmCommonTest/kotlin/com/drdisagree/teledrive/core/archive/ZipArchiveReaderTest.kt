package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.media.MediaByteSource
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ZipArchiveReaderTest {

    /** In-memory source that records every read range and can cap bytes per read. */
    private class RecordingSource(
        private val bytes: ByteArray,
        private val maxPerRead: Int = Int.MAX_VALUE
    ) : MediaByteSource {
        val reads = mutableListOf<Pair<Long, Int>>()
        var totalRead = 0L

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun read(position: Long, count: Int): ByteArray {
            if (position >= bytes.size || count <= 0) return ByteArray(0)
            val length = minOf(count, maxPerRead, bytes.size - position.toInt())
            reads += position to length
            totalRead += length
            return bytes.copyOfRange(position.toInt(), position.toInt() + length)
        }

        override fun close() = Unit

        fun overlaps(start: Long, end: Long): Boolean =
            reads.any { (position, length) -> position < end && position + length > start }
    }

    private fun zipBytes(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, data) in entries) {
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = data.size.toLong()
                    entry.compressedSize = data.size.toLong()
                    val crc = CRC32()
                    crc.update(data)
                    entry.crc = crc.value
                }
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private suspend fun assertThrowsZip(
        expected: Class<out ZipArchiveException>,
        block: suspend () -> Unit
    ) {
        try {
            block()
            fail("Expected ${expected.simpleName}")
        } catch (failure: ZipArchiveException) {
            assertEquals(expected, failure.javaClass)
        }
    }

    @Test
    fun `listing returns names sizes methods and crc for stored and deflated entries`() = runBlocking {
        val storedData = "stored hello".toByteArray()
        val deflatedData = ByteArray(5_000) { (it % 13).toByte() }
        val bytes = zipBytes(
            "docs/readme.txt" to storedData,
            "data.bin" to deflatedData,
            stored = setOf("docs/readme.txt")
        )

        val entries = ZipArchiveReader(RecordingSource(bytes)).entries()

        assertEquals(2, entries.size)
        val readme = entries[0]
        assertEquals("docs/readme.txt", readme.name)
        assertFalse(readme.isDirectory)
        assertEquals(0, readme.method)
        assertEquals(storedData.size.toLong(), readme.sizeBytes)
        assertEquals(storedData.size.toLong(), readme.compressedBytes)
        val storedCrc = CRC32().apply { update(storedData) }.value
        assertEquals(storedCrc, readme.crc32)
        assertFalse(readme.isEncrypted)

        val data = entries[1]
        assertEquals("data.bin", data.name)
        assertEquals(8, data.method)
        assertEquals(deflatedData.size.toLong(), data.sizeBytes)
        assertTrue(data.compressedBytes < data.sizeBytes)
    }

    @Test
    fun `extract returns the exact bytes for stored and deflated entries`() = runBlocking {
        val storedData = ByteArray(3_000) { (it * 7 % 251).toByte() }
        val deflatedData = "repeat me, repeat me, repeat me".repeat(200).toByteArray()
        val bytes = zipBytes(
            "a.bin" to storedData,
            "b.txt" to deflatedData,
            stored = setOf("a.bin")
        )
        // Short reads everywhere: the reader must loop until each range is complete.
        val reader = ZipArchiveReader(RecordingSource(bytes, maxPerRead = 997))
        val entries = reader.entries()

        val outA = ByteArrayOutputStream()
        reader.extract(entries[0], outA)
        assertArrayEquals(storedData, outA.toByteArray())

        val outB = ByteArrayOutputStream()
        reader.extract(entries[1], outB)
        assertArrayEquals(deflatedData, outB.toByteArray())
    }

    @Test
    fun `corrupting one stored data byte is caught as a crc mismatch`() = runBlocking {
        val data = "integrity matters".toByteArray()
        val bytes = zipBytes("file.txt" to data, stored = setOf("file.txt"))
        val reader = ZipArchiveReader(RecordingSource(bytes))
        val entry = reader.entries().single()
        // Flip the first data byte in the underlying archive.
        val dataStart = entry.localHeaderOffset.toInt() + 30 +
            u16(bytes, entry.localHeaderOffset.toInt() + 26) +
            u16(bytes, entry.localHeaderOffset.toInt() + 28)
        bytes[dataStart] = (bytes[dataStart] + 1).toByte()

        assertThrowsZip(ZipArchiveException.CrcMismatch::class.java) {
            ZipArchiveReader(RecordingSource(bytes)).extract(entry, ByteArrayOutputStream())
        }
    }

    @Test
    fun `encrypted entries list but extraction refuses`() = runBlocking {
        val bytes = zipBytes("secret.txt" to "hidden".toByteArray())
        patchFlags(bytes, 0x0001)
        val reader = ZipArchiveReader(RecordingSource(bytes))
        val entry = reader.entries().single()
        assertTrue(entry.isEncrypted)

        assertThrowsZip(ZipArchiveException.EncryptedEntry::class.java) {
            reader.extract(entry, ByteArrayOutputStream())
        }
    }

    @Test
    fun `unsupported compression method refuses extraction`() = runBlocking {
        val bytes = zipBytes("odd.bin" to "data".toByteArray())
        patchMethod(bytes, 99)
        val reader = ZipArchiveReader(RecordingSource(bytes))
        val entry = reader.entries().single()
        assertEquals(99, entry.method)

        assertThrowsZip(ZipArchiveException.UnsupportedMethod::class.java) {
            reader.extract(entry, ByteArrayOutputStream())
        }
    }

    @Test
    fun `multi disk eocd refuses listing`() = runBlocking {
        val bytes = zipBytes("a.txt" to "a".toByteArray())
        val eocd = findSignature(bytes, 0x06054b50)
        bytes[eocd + 4] = 1 // disk number

        assertThrowsZip(ZipArchiveException.MultiDisk::class.java) {
            ZipArchiveReader(RecordingSource(bytes)).entries()
        }
    }

    @Test
    fun `non zip bytes are reported as not a zip`() = runBlocking {
        assertThrowsZip(ZipArchiveException.NotAZip::class.java) {
            ZipArchiveReader(RecordingSource("definitely not a zip file".toByteArray())).entries()
        }
    }

    @Test
    fun `damaged central directory fails cleanly as corrupt`() = runBlocking {
        val bytes = zipBytes("a.txt" to "a".toByteArray(), "b.txt" to "b".toByteArray())
        val central = findSignature(bytes, 0x02014b50)
        bytes[central + 1] = 0x7F // break the central header signature

        assertThrowsZip(ZipArchiveException.Corrupt::class.java) {
            ZipArchiveReader(RecordingSource(bytes)).entries()
        }
    }

    @Test
    fun `zip64 archive lists and extracts`() = runBlocking {
        val data = "Hello ZIP64 world".toByteArray()
        val bytes = buildZip64Archive("hello.txt", data)

        val reader = ZipArchiveReader(RecordingSource(bytes))
        val entry = reader.entries().single()
        assertEquals("hello.txt", entry.name)
        assertEquals(data.size.toLong(), entry.sizeBytes)
        assertEquals(data.size.toLong(), entry.compressedBytes)
        assertEquals(0L, entry.localHeaderOffset)

        val out = ByteArrayOutputStream()
        reader.extract(entry, out)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `archive with more than 65535 entries lists them all`() = runBlocking {
        val count = 65_540
        val bytes = buildManyEntryArchive(count)

        val entries = ZipArchiveReader(RecordingSource(bytes)).entries()

        assertEquals(count, entries.size)
        assertEquals("e000000", entries.first().name)
        assertEquals("e065539", entries.last().name)
    }

    @Test
    fun `listing reads only the tail and central directory, never a large entry's data`() = runBlocking {
        val bigData = ByteArray(3_000_000) { (it % 251).toByte() }
        val bytes = zipBytes(
            "small.txt" to "tiny".toByteArray(),
            "big.bin" to bigData,
            stored = setOf("small.txt", "big.bin")
        )
        val source = RecordingSource(bytes)
        val entries = ZipArchiveReader(source).entries()
        val big = entries.single { it.name == "big.bin" }
        val bigDataStart = big.localHeaderOffset + 30 +
            u16(bytes, big.localHeaderOffset.toInt() + 26) +
            u16(bytes, big.localHeaderOffset.toInt() + 28)
        val bigDataEnd = bigDataStart + bigData.size

        assertFalse("listing touched the large entry's data", source.overlaps(bigDataStart, bigDataEnd))
        assertTrue("listing read ${source.totalRead} bytes", source.totalRead < 200_000)
    }

    @Test
    fun `extracting a small entry never reads a large entry's data`() = runBlocking {
        val bigData = ByteArray(3_000_000) { (it % 251).toByte() }
        val bytes = zipBytes(
            "small.txt" to "tiny".toByteArray(),
            "big.bin" to bigData,
            stored = setOf("small.txt", "big.bin")
        )
        val listing = ZipArchiveReader(RecordingSource(bytes)).entries()
        val big = listing.single { it.name == "big.bin" }
        val bigDataStart = big.localHeaderOffset + 30 +
            u16(bytes, big.localHeaderOffset.toInt() + 26) +
            u16(bytes, big.localHeaderOffset.toInt() + 28)
        val bigDataEnd = bigDataStart + bigData.size

        val source = RecordingSource(bytes)
        val reader = ZipArchiveReader(source)
        val small = reader.entries().single { it.name == "small.txt" }
        val out = ByteArrayOutputStream()
        reader.extract(small, out)

        assertArrayEquals("tiny".toByteArray(), out.toByteArray())
        assertFalse("extraction touched the large entry's data", source.overlaps(bigDataStart, bigDataEnd))
    }

    // --- archive surgery helpers --------------------------------------------

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun findSignature(bytes: ByteArray, signature: Int): Int {
        val needle = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(signature).array()
        for (i in 0..bytes.size - 4) {
            if (bytes.copyOfRange(i, i + 4).contentEquals(needle)) return i
        }
        error("signature not found")
    }

    private fun patchFlags(bytes: ByteArray, flags: Int) {
        val local = findSignature(bytes, 0x04034b50)
        bytes[local + 6] = (flags and 0xFF).toByte()
        bytes[local + 7] = (flags shr 8).toByte()
        val central = findSignature(bytes, 0x02014b50)
        bytes[central + 8] = (flags and 0xFF).toByte()
        bytes[central + 9] = (flags shr 8).toByte()
    }

    private fun patchMethod(bytes: ByteArray, method: Int) {
        val local = findSignature(bytes, 0x04034b50)
        bytes[local + 8] = (method and 0xFF).toByte()
        bytes[local + 9] = (method shr 8).toByte()
        val central = findSignature(bytes, 0x02014b50)
        bytes[central + 10] = (method and 0xFF).toByte()
        bytes[central + 11] = (method shr 8).toByte()
    }

    /** A minimal hand-built ZIP64 archive: one stored entry, all 32-bit size/offset fields saturated. */
    private fun buildZip64Archive(name: String, data: ByteArray): ByteArray {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val crc = CRC32().apply { update(data) }.value
        val saturated = 0xFFFFFFFFL
        val out = ByteArrayOutputStream()
        fun w16(value: Int) {
            out.write(value and 0xFF); out.write(value shr 8)
        }
        fun w32(value: Long) {
            for (i in 0..3) out.write((value shr (8 * i)).toInt() and 0xFF)
        }
        fun w64(value: Long) {
            for (i in 0..7) out.write((value shr (8 * i)).toInt() and 0xFF)
        }

        // Local header + ZIP64 extra (real size, real compressed size) + data.
        w32(0x04034b50); w16(45); w16(0x0800); w16(0); w16(0); w16(0)
        w32(crc); w32(saturated); w32(saturated)
        w16(nameBytes.size); w16(20)
        out.write(nameBytes)
        w16(0x0001); w16(16); w64(data.size.toLong()); w64(data.size.toLong())
        out.write(data)

        val cdOffset = out.size().toLong()
        // Central header + ZIP64 extra (size, compressed, local offset).
        w32(0x02014b50); w16(45); w16(45); w16(0x0800); w16(0); w16(0); w16(0)
        w32(crc); w32(saturated); w32(saturated)
        w16(nameBytes.size); w16(28); w16(0); w16(0); w16(0); w32(0); w32(saturated)
        out.write(nameBytes)
        w16(0x0001); w16(24); w64(data.size.toLong()); w64(data.size.toLong()); w64(0)
        val cdSize = out.size() - cdOffset

        val zip64Offset = out.size().toLong()
        // ZIP64 EOCD record.
        w32(0x06064b50); w64(44); w16(45); w16(45); w32(0); w32(0)
        w64(1); w64(1); w64(cdSize); w64(cdOffset)
        // ZIP64 locator.
        w32(0x07064b50); w32(0); w64(zip64Offset); w32(1)
        // Classic EOCD with saturated fields.
        w32(0x06054b50); w16(0); w16(0); w16(0xFFFF); w16(0xFFFF)
        w32(saturated); w32(saturated); w16(0)
        return out.toByteArray()
    }

    /** [count] empty stored entries, hand-built with a ZIP64 EOCD because the count overflows u16. */
    private fun buildManyEntryArchive(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun w16(value: Int) {
            out.write(value and 0xFF); out.write(value shr 8)
        }
        fun w32(value: Long) {
            for (i in 0..3) out.write((value shr (8 * i)).toInt() and 0xFF)
        }
        fun w64(value: Long) {
            for (i in 0..7) out.write((value shr (8 * i)).toInt() and 0xFF)
        }

        val names = (0 until count).map { "e%06d".format(it).toByteArray(Charsets.UTF_8) }
        val localOffsets = LongArray(count)
        for (i in 0 until count) {
            localOffsets[i] = out.size().toLong()
            w32(0x04034b50); w16(20); w16(0x0800); w16(0); w16(0); w16(0)
            w32(0); w32(0); w32(0)
            w16(names[i].size); w16(0)
            out.write(names[i])
        }
        val cdOffset = out.size().toLong()
        for (i in 0 until count) {
            w32(0x02014b50); w16(20); w16(20); w16(0x0800); w16(0); w16(0); w16(0)
            w32(0); w32(0); w32(0)
            w16(names[i].size); w16(0); w16(0); w16(0); w16(0); w32(0); w32(localOffsets[i])
            out.write(names[i])
        }
        val cdSize = out.size() - cdOffset
        val zip64Offset = out.size().toLong()
        w32(0x06064b50); w64(44); w16(45); w16(45); w32(0); w32(0)
        w64(count.toLong()); w64(count.toLong()); w64(cdSize); w64(cdOffset)
        w32(0x07064b50); w32(0); w64(zip64Offset); w32(1)
        w32(0x06054b50); w16(0); w16(0); w16(0xFFFF); w16(0xFFFF)
        w32(0xFFFFFFFFL); w32(0xFFFFFFFFL); w16(0)
        return out.toByteArray()
    }
}
