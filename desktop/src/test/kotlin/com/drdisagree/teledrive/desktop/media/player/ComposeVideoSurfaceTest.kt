package com.drdisagree.teledrive.desktop.media.player

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComposeVideoSurfaceTest {

    @Test
    fun `each published frame receives independent pixel storage`() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val firstFrame = copyVideoFramePixels(source, rowBytes = 4, height = 2)
        requireNotNull(firstFrame)

        source.put(0, 99)
        val secondFrame = copyVideoFramePixels(source, rowBytes = 4, height = 2)
        requireNotNull(secondFrame)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), firstFrame)
        assertArrayEquals(byteArrayOf(99, 2, 3, 4, 5, 6, 7, 8), secondFrame)
    }

    @Test
    fun `incomplete VLC frames are rejected instead of published`() {
        val truncated = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4, 5, 6, 7))

        assertNull(copyVideoFramePixels(truncated, rowBytes = 4, height = 2))
    }

    @Test
    fun `invalid dimensions are rejected`() {
        val source = ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4))

        assertNull(copyVideoFramePixels(source, rowBytes = 0, height = 1))
        assertNull(copyVideoFramePixels(source, rowBytes = 4, height = 0))
    }
}
