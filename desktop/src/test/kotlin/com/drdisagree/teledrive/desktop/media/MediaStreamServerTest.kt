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

    @Test
    fun `explicit range returns 206 with the requested window`() {
        val server = MediaStreamServer()
        try {
            val bytes = "hello world".encodeToByteArray()
            val url = server.serve("clip.mp4", "video/mp4") { FakeMediaByteSource(bytes) }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=2-5")
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 2-5/11", connection.getHeaderField("Content-Range"))
                assertArrayEquals(
                    "llo ".encodeToByteArray(),
                    connection.inputStream.use { it.readBytes() }
                )
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `suffix range returns the tail of the media`() {
        val server = MediaStreamServer()
        try {
            val bytes = "hello world".encodeToByteArray()
            val url = server.serve("clip.mp4", "video/mp4") { FakeMediaByteSource(bytes) }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=-4")
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 7-10/11", connection.getHeaderField("Content-Range"))
                assertArrayEquals(
                    "orld".encodeToByteArray(),
                    connection.inputStream.use { it.readBytes() }
                )
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `open ended range returns from the offset to the end`() {
        val server = MediaStreamServer()
        try {
            val bytes = "hello world".encodeToByteArray()
            val url = server.serve("clip.mp4", "video/mp4") { FakeMediaByteSource(bytes) }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=4-")
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 4-10/11", connection.getHeaderField("Content-Range"))
                assertArrayEquals(
                    "o world".encodeToByteArray(),
                    connection.inputStream.use { it.readBytes() }
                )
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `range end beyond the media size is clamped`() {
        val server = MediaStreamServer()
        try {
            val bytes = "hello world".encodeToByteArray()
            val url = server.serve("clip.mp4", "video/mp4") { FakeMediaByteSource(bytes) }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.setRequestProperty("Range", "bytes=9-999")
            try {
                assertEquals(206, connection.responseCode)
                assertEquals("bytes 9-10/11", connection.getHeaderField("Content-Range"))
                assertArrayEquals(
                    "ld".encodeToByteArray(),
                    connection.inputStream.use { it.readBytes() }
                )
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `head request returns headers without a body`() {
        val server = MediaStreamServer()
        try {
            val bytes = "hello world".encodeToByteArray()
            val url = server.serve("clip.mp4", "video/mp4") { FakeMediaByteSource(bytes) }

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            try {
                assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
                assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
                assertEquals(0, connection.inputStream.use { it.readBytes() }.size)
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `stop closes the active byte source`() {
        val server = MediaStreamServer()
        val source = TrackingMediaByteSource("hello world".encodeToByteArray())
        server.serve("clip.mp4", "video/mp4") { source }

        server.stop()

        assertEquals(true, source.closed)
    }

    @Test
    fun `server can serve again after a stop`() {
        val server = MediaStreamServer()
        try {
            val first = "first".encodeToByteArray()
            server.serve("one.mp4", "video/mp4") { FakeMediaByteSource(first) }
            server.stop()

            val second = "second".encodeToByteArray()
            val url = server.serve("two.mp4", "video/mp4") { FakeMediaByteSource(second) }
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
                assertArrayEquals(second, connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
        } finally {
            server.stop()
        }
    }

    @Test
    fun `sequential playback reads are coalesced into buffered fetches`() {
        // PC 1.6.27 P4(b): the server must wrap the raw source in
        // BufferedMediaByteSource (as Android's TelegramDataSource does),
        // so a sequential 1 MiB playback costs two 512 KiB source fetches,
        // not one TDLib round trip per 256 KiB server chunk.
        val server = MediaStreamServer()
        try {
            val bytes = ByteArray(1024 * 1024) { (it % 251).toByte() }
            val source = CountingMediaByteSource(bytes)
            val url = server.serve("movie.mp4", "video/mp4") { source }

            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
                assertArrayEquals(bytes, connection.inputStream.use { it.readBytes() })
            } finally {
                connection.disconnect()
            }
            assertEquals(2, source.readCalls)
        } finally {
            server.stop()
        }
    }

    private class CountingMediaByteSource(private val bytes: ByteArray) : MediaByteSource {
        @Volatile
        var readCalls = 0
            private set

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun read(position: Long, count: Int): ByteArray {
            readCalls++
            val start = position.coerceIn(0, bytes.size.toLong()).toInt()
            val end = (position + count).coerceIn(start.toLong(), bytes.size.toLong()).toInt()
            return bytes.copyOfRange(start, end)
        }

        override fun close() = Unit
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

    private class TrackingMediaByteSource(private val bytes: ByteArray) : MediaByteSource {
        @Volatile
        var closed = false
            private set

        override suspend fun size(): Long = bytes.size.toLong()

        override suspend fun read(position: Long, count: Int): ByteArray {
            val start = position.coerceIn(0, bytes.size.toLong()).toInt()
            val end = (position + count).coerceIn(start.toLong(), bytes.size.toLong()).toInt()
            return bytes.copyOfRange(start, end)
        }

        override fun close() {
            closed = true
        }
    }
}
