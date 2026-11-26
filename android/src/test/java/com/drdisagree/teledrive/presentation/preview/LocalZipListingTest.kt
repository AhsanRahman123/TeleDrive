package com.drdisagree.teledrive.presentation.preview

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression tests for the vc179 "Could not open archive" failure on locally stored
 * ZIPs. The local listing path used zip4j alone, and zip4j throws
 * ZipException("Unknown compression method") while parsing the central directory
 * when ANY entry uses a method it does not implement — Deflate64 (9), BZip2 (12),
 * LZMA (14) or PPMd (98), all offered by ZArchiver's 7-Zip engine when creating
 * ZIPs. The resolver converted that throw into the generic failure message, so a
 * single such entry made a valid archive unopenable. The fix keeps zip4j first and
 * falls back to [localZipEntriesViaReader]; these tests pin both halves.
 */
class LocalZipListingTest {

    /** Builds a one-entry ZIP, then rewrites the entry's method fields (local +8, central +10). */
    private fun zipWithMethod(method: Int): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            val entry = ZipEntry("media.bin")
            entry.method = ZipEntry.STORED
            val data = "payload".toByteArray()
            entry.size = data.size.toLong()
            entry.compressedSize = data.size.toLong()
            val crc = java.util.zip.CRC32(); crc.update(data); entry.crc = crc.value
            zip.putNextEntry(entry)
            zip.write(data)
            zip.closeEntry()
        }
        val bytes = out.toByteArray()
        patchU16(bytes, findSig(bytes, 0x04034b50) + 8, method)
        patchU16(bytes, findSig(bytes, 0x02014b50) + 10, method)
        return bytes
    }

    private fun tempZip(bytes: ByteArray): File {
        val file = File.createTempFile("local-zip-listing-", ".zip")
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `zip4j refuses a bzip2-method archive but the reader fallback lists it`() = runBlocking {
        val file = tempZip(zipWithMethod(12))
        try {
            // The old behaviour, kept here as the reproduced failure: zip4j cannot
            // even list this valid archive.
            try {
                ZipFile(file).fileHeaders
                fail("zip4j was expected to refuse the BZip2-method archive")
            } catch (expected: ZipException) {
                assertTrue(expected.message.orEmpty().contains("Unknown compression method"))
            }
            val entries = localZipEntriesViaReader(file.absolutePath)
            assertEquals(1, entries.size)
            assertEquals("media.bin", entries[0].name)
            assertEquals("payload".length.toLong(), entries[0].sizeBytes)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `reader fallback lists deflate64 lzma and ppmd archives zip4j refuses`() = runBlocking {
        for (method in listOf(9, 14, 98)) {
            val file = tempZip(zipWithMethod(method))
            try {
                try {
                    ZipFile(file).fileHeaders
                    fail("zip4j was expected to refuse method $method")
                } catch (expected: ZipException) {
                    // Reproduced old failure for this method.
                }
                val entries = localZipEntriesViaReader(file.absolutePath)
                assertEquals(listOf("media.bin"), entries.map { it.name })
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun `reader fallback listing matches zip4j on a plain archive`() = runBlocking {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("dir/"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("dir/note.txt"))
            zip.write("hello".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("photo.jpg"))
            zip.write(ByteArray(2048) { (it % 13).toByte() })
            zip.closeEntry()
        }
        val file = tempZip(out.toByteArray())
        try {
            val viaZip4j = ZipFile(file).fileHeaders.map {
                Triple(it.fileName, it.uncompressedSize, it.isDirectory)
            }
            val viaReader = localZipEntriesViaReader(file.absolutePath).map {
                Triple(it.name, it.sizeBytes, it.isDirectory)
            }
            assertEquals(viaZip4j, viaReader)
        } finally {
            file.delete()
        }
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

    private fun patchU16(bytes: ByteArray, at: Int, value: Int) {
        bytes[at] = (value and 0xFF).toByte()
        bytes[at + 1] = ((value shr 8) and 0xFF).toByte()
    }
}
