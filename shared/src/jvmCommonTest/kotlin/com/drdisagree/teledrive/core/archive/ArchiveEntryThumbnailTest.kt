package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.media.MediaByteSource
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc182 item 6: the pure seams of on-demand archive-entry thumbnails — eligibility,
 * cache keys, and the one-parse-per-session guarantee the per-row path relies on.
 */
class ArchiveEntryThumbnailTest {

    private fun info(
        name: String = "photos/cat.jpg",
        isDirectory: Boolean = false,
        sizeBytes: Long = 100_000,
        compressedBytes: Long = 90_000,
        method: Int = ArchiveEntryThumbnailPolicy.METHOD_DEFLATE,
        isEncrypted: Boolean = false
    ) = ZipEntryInfo(
        name = name,
        isDirectory = isDirectory,
        sizeBytes = sizeBytes,
        compressedBytes = compressedBytes,
        method = method,
        crc32 = 0,
        localHeaderOffset = 0,
        isEncrypted = isEncrypted,
        modifiedAtMillis = 1234
    )

    // ---- eligibility ----

    @Test
    fun `stored and deflated images are eligible`() {
        assertTrue(ArchiveEntryThumbnailPolicy.isEligible(info(method = ArchiveEntryThumbnailPolicy.METHOD_STORED)))
        assertTrue(ArchiveEntryThumbnailPolicy.isEligible(info(method = ArchiveEntryThumbnailPolicy.METHOD_DEFLATE)))
        assertTrue(ArchiveEntryThumbnailPolicy.isEligible(info(name = "a/b/pic.PNG")))
        assertTrue(ArchiveEntryThumbnailPolicy.isEligible(info(name = "pic.webp")))
    }

    @Test
    fun `directories and non-images are not eligible`() {
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(name = "photos/", isDirectory = true)))
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(name = "notes.txt")))
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(name = "clip.mp4")))
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(name = "noextension")))
    }

    @Test
    fun `svg entries are skipped`() {
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(name = "icon.svg")))
        assertFalse(ArchiveEntryThumbnailPolicy.isImageEntryName("icon.svgz"))
    }

    @Test
    fun `size cap boundary is inclusive at twenty mebibytes`() {
        val max = ArchiveEntryThumbnailPolicy.MAX_ENTRY_BYTES
        assertTrue(ArchiveEntryThumbnailPolicy.isEligible(info(sizeBytes = max)))
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(sizeBytes = max + 1)))
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(sizeBytes = 0)))
        // The listing-level pre-check agrees, so oversize rows never open a session.
        assertTrue(ArchiveEntryThumbnailPolicy.isEligibleByListing("a.jpg", max, isDirectory = false))
        assertFalse(ArchiveEntryThumbnailPolicy.isEligibleByListing("a.jpg", max + 1, isDirectory = false))
    }

    @Test
    fun `encrypted entries and unsupported methods are not eligible`() {
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(isEncrypted = true)))
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(method = 9))) // BZIP2
        assertFalse(ArchiveEntryThumbnailPolicy.isEligible(info(method = 14))) // LZMA
    }

    // ---- decode size (vc183 item 6) ----

    @Test
    fun `decode target is pinned at 256 pixels for the bigger slot`() {
        // The rendered slot grew 40dp -> 56dp in vc183; the decode target grew
        // with it but stays at this small end so decode cost (and fling-scroll
        // smoothness, the praised vc182 baseline) does not regress.
        assertEquals(256, ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX)
    }

    // ---- cache keys ----

    @Test
    fun `cache key is stable for the same entry`() {
        val a = ArchiveEntryThumbnailPolicy.cacheKey("file-1", 3, 100, 90, 1234)
        val b = ArchiveEntryThumbnailPolicy.cacheKey("file-1", 3, 100, 90, 1234)
        assertEquals(a, b)
    }

    @Test
    fun `duplicate names get distinct keys via the listing index`() {
        val first = ArchiveEntryThumbnailPolicy.cacheKey("file-1", 3, 100, 90, 1234)
        val second = ArchiveEntryThumbnailPolicy.cacheKey("file-1", 4, 100, 90, 1234)
        assertNotEquals(first, second)
    }

    @Test
    fun `changed entry metadata or archive yields a new key`() {
        val base = ArchiveEntryThumbnailPolicy.cacheKey("file-1", 3, 100, 90, 1234)
        assertNotEquals(base, ArchiveEntryThumbnailPolicy.cacheKey("file-1", 3, 101, 90, 1234))
        assertNotEquals(base, ArchiveEntryThumbnailPolicy.cacheKey("file-1", 3, 100, 91, 1234))
        assertNotEquals(base, ArchiveEntryThumbnailPolicy.cacheKey("file-1", 3, 100, 90, 1235))
        assertNotEquals(base, ArchiveEntryThumbnailPolicy.cacheKey("file-2", 3, 100, 90, 1234))
    }

    // ---- session: one central-directory parse for N extractions ----

    private class CountingSource(private val bytes: ByteArray) : MediaByteSource {
        var reads = 0
        override suspend fun size(): Long = bytes.size.toLong()
        override suspend fun read(position: Long, count: Int): ByteArray {
            reads++
            if (position >= bytes.size) return ByteArray(0)
            val end = minOf(position + count, bytes.size.toLong()).toInt()
            return bytes.copyOfRange(position.toInt(), end)
        }
        override fun close() = Unit
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
                zip.putNextEntry(entry)
                zip.write(data)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `one session parses the central directory once for many extractions`() = runBlocking {
        val first = "first image bytes".toByteArray()
        val second = "second image bytes".toByteArray()
        val third = "third image bytes".toByteArray()
        val zip = storedZip(
            listOf("a.jpg" to first, "b.jpg" to second, "c.jpg" to third)
        )
        val source = CountingSource(zip)
        val session = ArchiveThumbnailSession(source)
        try {
            val entries = session.entries()
            assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), entries.map { it.name })
            val readsAfterFirstParse = source.reads
            assertTrue("listing read nothing", readsAfterFirstParse > 0)

            // A second listing is served from the session cache: zero new reads.
            assertEquals(entries, session.entries())
            assertEquals(readsAfterFirstParse, source.reads)

            // Extractions read only their own entry ranges and return verified bytes.
            val outB = File.createTempFile("thumb-session-b", ".bin")
            val outC = File.createTempFile("thumb-session-c", ".bin")
            try {
                session.extractEntry(entries[1], outB)
                session.extractEntry(entries[2], outC)
                assertArrayEquals(second, outB.readBytes())
                assertArrayEquals(third, outC.readBytes())
            } finally {
                outB.delete()
                outC.delete()
            }
            // Extraction must not have re-parsed: total reads stay far below a
            // second full listing (tail + central directory) per extraction.
            assertTrue(
                "extractions re-parsed the directory: ${source.reads} reads",
                source.reads <= readsAfterFirstParse + 4
            )
        } finally {
            session.close()
            session.close() // close is idempotent
        }
    }
}
