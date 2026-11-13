package com.drdisagree.teledrive.desktop.media

import com.drdisagree.teledrive.core.media.MediaByteSource
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaStreamServerTest {

    @Test
    fun `old stream URL cannot read bytes from the replacement media`() {
        val server = MediaStreamServer()
        try {
            val oldBytes = "first media".encodeToByteArray()
            val newBytes = "second media".encodeToByteArray()

            val oldUrl = server.serve("first.mp4", "video/mp4") {
                FakeMediaByteSource(oldBytes)
            }
            val newUrl = server.serve("second.mp4", "video/mp4") {
                FakeMediaByteSource(newBytes)
            }

            val oldResponse = URL(oldUrl).openConnection() as HttpURLConnection
            try {
                assertEquals(HttpURLConnection.HTTP_NOT_FOUND, oldResponse.responseCode)
            } finally {
                oldResponse.disconnect()
            }

            val newResponse = URL(newUrl).openConnection() as HttpURLConnection
            try {
                assertEquals(HttpURLConnection.HTTP_OK, newResponse.responseCode)
                assertArrayEquals(newBytes, newResponse.inputStream.use { it.readBytes() })
            } finally {
                newResponse.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    private class FakeMediaByteSource(private val bytes: ByteArray) : MediaByteSource {
        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun read(position: Long, count: Int): ByteArray {
            val start = position.coerceIn(0, bytes.size.toLong()).toInt()
            val end = (position + count).coerceIn(start.toLong(), bytes.size.toLong()).toInt()
            return bytes.copyOfRange(start, end)
        }

        override fun close() = Unit
    }
}
