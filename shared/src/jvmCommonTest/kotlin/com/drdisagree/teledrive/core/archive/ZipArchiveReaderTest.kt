package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.media.MediaByteSource
import java.io.ByteArrayOutputStream
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

    private class InMemorySource(private val bytes: ByteArray) : MediaByteSource {
        override suspend fun size(): Long = bytes.size.toLong()
        override suspend fun read(position: Long, count: Int): ByteArray {
            if (position >= bytes.size) return ByteArray(0)
            val end = minOf(position + count, bytes.size.toLong()).toInt()
            return bytes.copyOfRange(position.toInt(), end)
        }
        override fun close() = Unit
    }

    /** Records every range actually fetched, for the range-discipline assertions. */
    private class RecordingSource(private val bytes: ByteArray) : MediaByteSource {
        val ranges = mutableListOf<LongRange>()
        var totalRead = 0L
        override suspend fun size(): Long = bytes.size.toLong()
        override suspend fun read(position: Long, count: Int): ByteArray {
            if (position >= bytes.size) return ByteArray(0)
            val end = minOf(position + count, bytes.size.toLong()).toInt()
            ranges += position until end.toLong()
            totalRead += (end - position)
            return bytes.copyOfRange(position.toInt(), end)
        }
        override fun close() = Unit
        fun readOverlaps(range: LongRange): Boolean =
            ranges.any { it.first <= range.last && range.first <= it.last }
    }

    private fun zipOf(entries: List<Triple<String, ByteArray, Int>>): ByteArray {
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

    private fun reader(bytes: ByteArray) = ZipArchiveReader(InMemorySource(bytes))

    private fun expectArchiveError(kind: ArchiveErrorKind, block: () -> Unit) {
        try {
            block()
            fail("Expected ArchiveException($kind)")
        } catch (failure: ArchiveException) {
            assertEquals(kind, failure.kind)
        }
    }

    @Test
    fun `listing reports stored and deflated entries`() = runBlocking {
        val stored = "stored contents".toByteArray()
        val deflated = ByteArray(5_000) { (it % 17).toByte() }
        val bytes = zipOf(
            listOf(
                Triple("a/stored.txt", stored, ZipEntry.STORED),
                Triple("b/deflated.bin", deflated, ZipEntry.DEFLATED),
                Triple("a/", ByteArray(0), ZipEntry.DEFLATED)
            )
        )
        val entries = reader(bytes).entries()
        assertEquals(3, entries.size)
        val byName = entries.associateBy { it.name }
        assertEquals(stored.size.toLong(), byName.getValue("a/stored.txt").sizeBytes)
        assertEquals(0, byName.getValue("a/stored.txt").method)
        assertEquals(8, byName.getValue("b/deflated.bin").method)
        assertEquals(deflated.size.toLong(), byName.getValue("b/deflated.bin").sizeBytes)
        assertTrue(byName.getValue("b/deflated.bin").compressedBytes < deflated.size)
        assertTrue(byName.getValue("a/").isDirectory)
        assertFalse(byName.getValue("a/stored.txt").isEncrypted)
        val crc = CRC32(); crc.update(stored)
        assertEquals(crc.value, byName.getValue("a/stored.txt").crc32)
    }

    @Test
    fun `extraction returns the exact bytes for stored and deflated entries`() = runBlocking {
        val stored = ByteArray(123_456) { (it * 31 % 251).toByte() }
        val deflated = "repeat me ".repeat(10_000).toByteArray()
        val bytes = zipOf(
            listOf(
                Triple("stored.bin", stored, ZipEntry.STORED),
                Triple("deflated.txt", deflated, ZipEntry.DEFLATED)
            )
        )
        val archive = reader(bytes)
        val entries = archive.entries().associateBy { it.name }
        val storedOut = ByteArrayOutputStream()
        archive.extract(entries.getValue("stored.bin"), storedOut)
        assertArrayEquals(stored, storedOut.toByteArray())
        val deflatedOut = ByteArrayOutputStream()
        archive.extract(entries.getValue("deflated.txt"), deflatedOut)
        assertArrayEquals(deflated, deflatedOut.toByteArray())
    }

    @Test
    fun `zip64 archive lists and extracts`() = runBlocking {
        val content = "hello zip64 world".toByteArray()
        val bytes = buildZip64(content)
        val archive = reader(bytes)
        val entries = archive.entries()
        assertEquals(1, entries.size)
        assertEquals("hello.txt", entries[0].name)
        assertEquals(content.size.toLong(), entries[0].sizeBytes)
        assertEquals(content.size.toLong(), entries[0].compressedBytes)
        val out = ByteArrayOutputStream()
        archive.extract(entries[0], out)
        assertArrayEquals(content, out.toByteArray())
    }

    @Test
    fun `more than 65535 entries are listed`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            repeat(66_000) { index ->
                zip.putNextEntry(ZipEntry("f%05d.txt".format(index)))
                zip.closeEntry()
            }
        }
        val entries = reader(out.toByteArray()).entries()
        assertEquals(66_000, entries.size)
        assertEquals("f00000.txt", entries.first().name)
        assertEquals("f65999.txt", entries.last().name)
    }

    @Test
    fun `crc mismatch is detected`() = runBlocking {
        val content = "integrity matters".toByteArray()
        val bytes = zipOf(listOf(Triple("data.txt", content, ZipEntry.STORED)))
        val at = indexOf(bytes, content)
        assertTrue(at >= 0)
        bytes[at] = (bytes[at] + 1).toByte()
        val archive = reader(bytes)
        val entry = archive.entries().single()
        expectArchiveError(ArchiveErrorKind.CRC_MISMATCH) {
            runBlocking { archive.extract(entry, ByteArrayOutputStream()) }
        }
    }

    @Test
    fun `encrypted flag entry lists but extraction refuses`() = runBlocking {
        val bytes = zipOf(listOf(Triple("secret.txt", "shh".toByteArray(), ZipEntry.STORED)))
        setFlagBit0(bytes)
        val archive = reader(bytes)
        val entry = archive.entries().single()
        assertTrue(entry.isEncrypted)
        expectArchiveError(ArchiveErrorKind.ENCRYPTED_ENTRY) {
            runBlocking { archive.extract(entry, ByteArrayOutputStream()) }
        }
    }

    @Test
    fun `unsupported compression method refuses extraction`() = runBlocking {
        val bytes = zipOf(listOf(Triple("odd.txt", "data".toByteArray(), ZipEntry.STORED)))
        // Local header method at +8, central header method at +10.
        patchU16(bytes, findSig(bytes, 0x04034b50) + 8, 99)
        patchU16(bytes, findSig(bytes, 0x02014b50) + 10, 99)
        val archive = reader(bytes)
        val entry = archive.entries().single()
        assertEquals(99, entry.method)
        expectArchiveError(ArchiveErrorKind.UNSUPPORTED_METHOD) {
            runBlocking { archive.extract(entry, ByteArrayOutputStream()) }
        }
    }

    @Test
    fun `multi disk archive is refused`() = runBlocking {
        val bytes = zipOf(listOf(Triple("a.txt", "a".toByteArray(), ZipEntry.STORED)))
        val eocd = findSig(bytes, 0x06054b50)
        patchU16(bytes, eocd + 4, 1)
        expectArchiveError(ArchiveErrorKind.MULTI_DISK) {
            runBlocking { reader(bytes).entries() }
        }
    }

    @Test
    fun `truncated and garbage files fail with a typed error`() = runBlocking {
        val bytes = zipOf(listOf(Triple("a.txt", "hello".toByteArray(), ZipEntry.STORED)))
        expectArchiveError(ArchiveErrorKind.NOT_A_ZIP) {
            runBlocking { reader(bytes.copyOf(bytes.size - 10)).entries() }
        }
        expectArchiveError(ArchiveErrorKind.NOT_A_ZIP) {
            runBlocking { reader("this is not a zip file at all, just text".toByteArray()).entries() }
        }
        // Central directory signature smashed: still a typed ArchiveException, never a crash.
        val damaged = bytes.copyOf()
        val central = findSig(damaged, 0x02014b50)
        damaged[central] = 0
        try {
            reader(damaged).entries()
            fail("Expected ArchiveException")
        } catch (expected: ArchiveException) {
            assertEquals(ArchiveErrorKind.CORRUPT, expected.kind)
        }
    }

    /** Serves at most [maxPerRead] bytes per read, like a ranged Telegram source can. */
    private class ShortReadSource(
        private val bytes: ByteArray,
        private val maxPerRead: Int
    ) : MediaByteSource {
        override suspend fun size(): Long = bytes.size.toLong()
        override suspend fun read(position: Long, count: Int): ByteArray {
            if (position >= bytes.size) return ByteArray(0)
            val end = minOf(position + minOf(count, maxPerRead), bytes.size.toLong()).toInt()
            return bytes.copyOfRange(position.toInt(), end)
        }
        override fun close() = Unit
    }

    @Test(timeout = 15_000)
    fun `deflate stream ending before the recorded compressed size is corrupt, not a hang`() =
        runBlocking {
            // The central directory overstates the compressed size by 50 bytes. With short
            // ranged reads the deflate stream finishes while recorded bytes remain: that must be
            // a Corrupt error. Completing at all (inside the test timeout) proves there is no
            // inflater spin.
            val data = ByteArray(400) { (it % 23).toByte() }
            val bytes = zipOf(listOf(Triple("entry.bin", data, ZipEntry.DEFLATED)))
            val central = findSig(bytes, 0x02014b50)
            val recorded = (bytes[central + 20].toLong() and 0xFF) or
                ((bytes[central + 21].toLong() and 0xFF) shl 8) or
                ((bytes[central + 22].toLong() and 0xFF) shl 16) or
                ((bytes[central + 23].toLong() and 0xFF) shl 24)
            patchU32(bytes, central + 20, recorded + 50)
            val archive = ZipArchiveReader(ShortReadSource(bytes, maxPerRead = 64))
            val entry = archive.entries().single()
            expectArchiveError(ArchiveErrorKind.CORRUPT) {
                runBlocking { archive.extract(entry, ByteArrayOutputStream()) }
            }
        }

    @Test(timeout = 60_000)
    fun `valid deflated entry larger than one chunk extracts through tiny reads`() = runBlocking {
        // ~2 MiB of seeded-random data barely compresses, so the deflated entry's compressed
        // size stays well above the reader's 512 KiB chunk. Serving it 8 bytes at a time
        // forces hundreds of thousands of chunk boundaries, including boundaries inside the
        // deflate block header and mid-symbol, where inflate() legitimately returns 0 with
        // needsInput() set. The old loop treated that as Corrupt; the fixed loop must feed
        // the next chunk and produce the exact original bytes.
        val data = ByteArray(2 * 1024 * 1024)
        java.util.Random(20261122L).nextBytes(data)
        val bytes = zipOf(listOf(Triple("big.bin", data, ZipEntry.DEFLATED)))
        val archive = ZipArchiveReader(ShortReadSource(bytes, maxPerRead = 8))
        val entry = archive.entries().single()
        assertTrue(
            "compressed size ${entry.compressedBytes} must exceed one 512 KiB chunk",
            entry.compressedBytes > 512 * 1024
        )
        val out = ByteArrayOutputStream()
        archive.extract(entry, out)
        assertArrayEquals(data, out.toByteArray())
    }

    @Test
    fun `listing a zip with a large entry reads only a small fraction`() = runBlocking {
        val large = ByteArray(2 * 1024 * 1024) { (it % 253).toByte() }
        val small = "tiny".toByteArray()
        val bytes = zipOf(
            listOf(
                Triple("large.bin", large, ZipEntry.STORED),
                Triple("small.txt", small, ZipEntry.STORED)
            )
        )
        val source = RecordingSource(bytes)
        val entries = ZipArchiveReader(source).entries()
        assertEquals(2, entries.size)
        assertTrue(
            "listing read ${source.totalRead} of ${bytes.size} bytes",
            source.totalRead < bytes.size / 10
        )
        // The tail window itself may overlap the last entry bytes before the central
        // directory - the discipline that matters is the total volume read, asserted above.
    }

    @Test
    fun `extracting a small entry never reads the large entry data`() = runBlocking {
        val large = ByteArray(2 * 1024 * 1024) { (it % 253).toByte() }
        val small = "tiny payload".toByteArray()
        val bytes = zipOf(
            listOf(
                Triple("large.bin", large, ZipEntry.STORED),
                Triple("dir/small.txt", small, ZipEntry.STORED)
            )
        )
        val listing = reader(bytes).entries()
        val largeEntry = listing.first { it.name == "large.bin" }
        val largeData = localDataStart(bytes, largeEntry.localHeaderOffset).let {
            it until it + large.size
        }
        val source = RecordingSource(bytes)
        val archive = ZipArchiveReader(source)
        val smallEntry = archive.entries().first { it.name == "dir/small.txt" }
        source.ranges.clear(); source.totalRead = 0
        val out = ByteArrayOutputStream()
        archive.extract(smallEntry, out)
        assertArrayEquals(small, out.toByteArray())
        assertFalse("extraction read the large entry's data", source.readOverlaps(largeData))
        assertTrue(source.totalRead < large.size)
    }

    // -- hand-built archives and byte patching ---------------------------------------------

    private fun buildZip64(content: ByteArray): ByteArray {
        val name = "hello.txt".toByteArray(Charsets.UTF_8)
        val crc = CRC32(); crc.update(content)
        val out = ByteArrayOutputStream()
        fun w16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
        fun w32(v: Long) { for (i in 0 until 4) out.write((v shr (8 * i)).toInt() and 0xFF) }
        fun w64(v: Long) { for (i in 0 until 8) out.write((v shr (8 * i)).toInt() and 0xFF) }

        val localOffset = 0L
        // Local header with saturated sizes + ZIP64 extra (size, compressed).
        w32(0x04034b50); w16(45); w16(0); w16(0); w16(0); w16(0)
        w32(crc.value); w32(0xFFFF_FFFFL); w32(0xFFFF_FFFFL)
        w16(name.size); w16(20)
        out.write(name)
        w16(0x0001); w16(16); w64(content.size.toLong()); w64(content.size.toLong())
        out.write(content)

        val cdOffset = out.size().toLong()
        // Central header with saturated sizes/offset + ZIP64 extra (size, compressed, offset).
        w32(0x02014b50); w16(45); w16(45); w16(0); w16(0); w16(0); w16(0)
        w32(crc.value); w32(0xFFFF_FFFFL); w32(0xFFFF_FFFFL)
        w16(name.size); w16(28); w16(0); w16(0); w16(0); w32(0); w32(0xFFFF_FFFFL)
        out.write(name)
        w16(0x0001); w16(24); w64(content.size.toLong()); w64(content.size.toLong()); w64(localOffset)
        val cdSize = out.size().toLong() - cdOffset

        val zip64Offset = out.size().toLong()
        w32(0x06064b50); w64(44); w16(45); w16(45); w32(0); w32(0)
        w64(1); w64(1); w64(cdSize); w64(cdOffset)

        w32(0x07064b50); w32(0); w64(zip64Offset); w32(1)

        w32(0x06054b50); w16(0); w16(0); w16(0xFFFF); w16(0xFFFF)
        w32(0xFFFF_FFFFL); w32(0xFFFF_FFFFL); w16(0)
        return out.toByteArray()
    }

    private fun setFlagBit0(bytes: ByteArray) {
        val local = findSig(bytes, 0x04034b50)
        bytes[local + 6] = (bytes[local + 6].toInt() or 1).toByte()
        val central = findSig(bytes, 0x02014b50)
        bytes[central + 8] = (bytes[central + 8].toInt() or 1).toByte()
    }

    private fun localDataStart(bytes: ByteArray, localHeaderOffset: Long): Long {
        val at = localHeaderOffset.toInt()
        val nameLength = (bytes[at + 26].toInt() and 0xFF) or ((bytes[at + 27].toInt() and 0xFF) shl 8)
        val extraLength = (bytes[at + 28].toInt() and 0xFF) or ((bytes[at + 29].toInt() and 0xFF) shl 8)
        return localHeaderOffset + 30 + nameLength + extraLength
    }

    private fun findSig(bytes: ByteArray, sig: Long): Int {
        val b0 = (sig and 0xFF).toByte(); val b1 = ((sig shr 8) and 0xFF).toByte()
        val b2 = ((sig shr 16) and 0xFF).toByte(); val b3 = ((sig shr 24) and 0xFF).toByte()
        for (i in 0..bytes.size - 4) {
            if (bytes[i] == b0 && bytes[i + 1] == b1 && bytes[i + 2] == b2 && bytes[i + 3] == b3) return i
        }
        fail("signature not found")
        return -1
    }

    private fun patchU32(bytes: ByteArray, at: Int, value: Long) {
        for (i in 0 until 4) bytes[at + i] = ((value shr (8 * i)) and 0xFF).toByte()
    }

    private fun patchU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    // ---- 1.6.23 failure visibility (report 52) ----

    @Test
    fun `telegram failure during data read names the telegram error instead of damaged archive`() = runBlocking {
        val data = "hello data".toByteArray()
        val bytes = zipOf(listOf(Triple("x.bin", data, ZipEntry.STORED)))
        val entry = reader(bytes).entries().single()
        val failing = object : MediaByteSource {
            override suspend fun size(): Long = bytes.size.toLong()
            override suspend fun read(position: Long, count: Int): ByteArray {
                if (position == 0L) {
                    return bytes.copyOfRange(0, minOf(count, bytes.size))
                }
                throw com.drdisagree.teledrive.core.telegram.TelegramException(
                    400, "telegram boom xyz"
                )
            }
            override fun close() = Unit
        }
        try {
            ZipArchiveReader(failing).extract(entry, ByteArrayOutputStream())
            fail("expected ArchiveException")
        } catch (failure: ArchiveException) {
            assertTrue(
                "message must name the telegram error, was: ${failure.message}",
                failure.message!!.contains("400") && failure.message.contains("telegram boom xyz")
            )
            assertFalse(
                "a telegram failure must not present as a damaged archive: ${failure.message}",
                failure.message.contains("is damaged and cannot be extracted")
            )
        }
    }
}
