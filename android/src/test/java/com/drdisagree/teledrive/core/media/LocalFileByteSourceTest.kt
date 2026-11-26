package com.drdisagree.teledrive.core.media

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFileByteSourceTest {

    private fun tempFileWith(bytes: ByteArray): File {
        val file = File.createTempFile("local-byte-source-", ".bin")
        file.writeBytes(bytes)
        return file
    }

    @Test
    fun `size and ranged reads return exact bytes`() = runBlocking {
        val bytes = ByteArray(10_000) { (it * 7 % 251).toByte() }
        val file = tempFileWith(bytes)
        try {
            val source = LocalFileByteSource(file)
            try {
                assertEquals(bytes.size.toLong(), source.size())
                assertArrayEquals(bytes.copyOfRange(0, 100), source.read(0, 100))
                assertArrayEquals(bytes.copyOfRange(1234, 2234), source.read(1234, 1000))
                assertArrayEquals(
                    bytes.copyOfRange(9990, 10_000),
                    source.read(9990, 10)
                )
            } finally {
                source.close()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `read straddling the end is truncated and read at the end is empty`() = runBlocking {
        val bytes = "0123456789".toByteArray()
        val file = tempFileWith(bytes)
        try {
            val source = LocalFileByteSource(file)
            try {
                assertArrayEquals("89".toByteArray(), source.read(8, 100))
                assertTrue(source.read(10, 5).isEmpty())
                assertTrue(source.read(50, 5).isEmpty())
                assertTrue(source.read(0, 0).isEmpty())
            } finally {
                source.close()
            }
        } finally {
            file.delete()
        }
    }
}
