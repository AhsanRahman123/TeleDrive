package com.drdisagree.teledrive.core.archive

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZMethod
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Fixture round-trips for the local 7z/TAR readers (vc181 B10): archives are
 * built with commons-compress' own writers, then listed and extracted through
 * the readers, asserting bytes and listing-index identity (duplicate names
 * included). The 7z fixture uses the COPY method so the test needs no codec
 * beyond the reader path itself.
 */
class LocalArchiveReaderTest {

    private fun tempFile(suffix: String, bytes: ByteArray): File {
        val file = File.createTempFile("local-archive-reader-", suffix)
        file.writeBytes(bytes)
        file.deleteOnExit()
        return file
    }

    private fun buildTar(compress: Boolean, entries: List<Pair<String, ByteArray>>): ByteArray {
        val bytes = ByteArrayOutputStream()
        val sink = if (compress) GzipCompressorOutputStream(bytes) else bytes
        TarArchiveOutputStream(sink).use { tar ->
            for ((name, data) in entries) {
                val entry = TarArchiveEntry(name)
                entry.size = data.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun buildSevenZ(entries: List<Pair<String, ByteArray>>): File {
        val file = File.createTempFile("local-archive-reader-", ".7z")
        file.deleteOnExit()
        SevenZOutputFile(file).use { archive ->
            archive.setContentMethods(
                listOf(SevenZMethodConfiguration(SevenZMethod.COPY))
            )
            for ((name, data) in entries) {
                val entry = SevenZArchiveEntry()
                entry.name = name
                entry.size = data.size.toLong()
                archive.putArchiveEntry(entry)
                archive.write(data)
                archive.closeArchiveEntry()
            }
        }
        return file
    }

    private fun extractToBytes(reader: LocalArchiveReader, file: File, index: Int): ByteArray {
        val out = ByteArrayOutputStream()
        reader.extract(file, index, out)
        return out.toByteArray()
    }

    @Test
    fun `tar listing and extraction round trip with duplicate names`() {
        val entries = listOf(
            "dir/readme.txt" to "hello".toByteArray(),
            "dup.bin" to byteArrayOf(1, 2, 3),
            "dup.bin" to byteArrayOf(9, 8, 7, 6)
        )
        val file = tempFile(".tar", buildTar(compress = false, entries))
        val reader = localArchiveReader(LocalArchiveFormat.TAR)

        val listing = reader.list(file)
        assertEquals(entries.map { it.first }, listing.map { it.name })
        assertEquals(entries.map { it.second.size.toLong() }, listing.map { it.sizeBytes })

        // Listing-index identity: the second dup.bin extracts its own bytes.
        assertEquals("hello", String(extractToBytes(reader, file, 0)))
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(extractToBytes(reader, file, 1)))
        assertTrue(byteArrayOf(9, 8, 7, 6).contentEquals(extractToBytes(reader, file, 2)))
    }

    @Test
    fun `tar gz listing and extraction round trip`() {
        val entries = listOf(
            "a.txt" to "first".toByteArray(),
            "b/c.txt" to "second".toByteArray()
        )
        val file = tempFile(".tar.gz", buildTar(compress = true, entries))
        val reader = localArchiveReader(LocalArchiveFormat.TAR_COMPRESSED)

        assertEquals(listOf("a.txt", "b/c.txt"), reader.list(file).map { it.name })
        assertEquals("second", String(extractToBytes(reader, file, 1)))
    }

    @Test
    fun `seven z listing and extraction round trip with duplicate names`() {
        val entries = listOf(
            "page1.jpg" to byteArrayOf(10, 20),
            "page1.jpg" to byteArrayOf(30, 40, 50),
            "notes.txt" to "text".toByteArray()
        )
        val file = buildSevenZ(entries)
        val reader = localArchiveReader(LocalArchiveFormat.SEVEN_Z)

        val listing = reader.list(file)
        assertEquals(entries.map { it.first }, listing.map { it.name })
        assertEquals(entries.map { it.second.size.toLong() }, listing.map { it.sizeBytes })
        assertTrue(byteArrayOf(30, 40, 50).contentEquals(extractToBytes(reader, file, 1)))
        assertEquals("text", String(extractToBytes(reader, file, 2)))
    }

    @Test
    fun `extraction past the last entry is a specific not-found failure`() {
        val file = tempFile(".tar", buildTar(false, listOf("a.txt" to "x".toByteArray())))
        try {
            extractToBytes(localArchiveReader(LocalArchiveFormat.TAR), file, 5)
            fail("expected ArchiveException")
        } catch (failure: ArchiveException) {
            assertEquals(ArchiveErrorKind.ENTRY_NOT_FOUND, failure.kind)
        }
    }

    @Test
    fun `non archive bytes fail listing instead of returning garbage`() {
        val file = tempFile(".7z", "this is not an archive at all".toByteArray())
        try {
            localArchiveReader(LocalArchiveFormat.SEVEN_Z).list(file)
            fail("expected a listing failure")
        } catch (expected: Exception) {
            // Any hard failure is correct here; a silent empty/garbage listing is not.
        }
    }

    @Test
    fun `format mapping covers the resolver mime types and labels`() {
        assertEquals(
            LocalArchiveFormat.SEVEN_Z,
            LocalArchiveFormat.fromMimeType("application/x-7z-compressed")
        )
        assertEquals(LocalArchiveFormat.TAR, LocalArchiveFormat.fromMimeType("application/x-tar"))
        assertEquals(
            LocalArchiveFormat.TAR_COMPRESSED,
            LocalArchiveFormat.fromMimeType("application/x-compressed-tar")
        )
        assertEquals(null, LocalArchiveFormat.fromMimeType("application/zip"))
        for (format in LocalArchiveFormat.entries) {
            assertEquals(format, LocalArchiveFormat.fromArchiveLabel(format.archiveLabel))
        }
    }
}
