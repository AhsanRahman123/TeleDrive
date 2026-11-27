package com.drdisagree.teledrive.desktop.ui

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.25 item 5: GIF eligibility for the desktop image surfaces (Files
 * viewer and in-zip viewer share ZoomablePreviewImage, which routes GIF
 * models to the Skia-Codec player) and a real decode of a real GIF.
 */
class DesktopGifImageTest {

    /** A real two-frame GIF written by the JDK's own GIF writer. */
    private fun gifBytes(frames: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("gif").next()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            writer.prepareWriteSequence(null)
            repeat(frames) {
                val image = BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
                writer.writeToSequence(javax.imageio.IIOImage(image, null, null), null)
            }
            writer.endWriteSequence()
        }
        writer.dispose()
        return out.toByteArray()
    }

    @Test
    fun `gif header detection accepts both versions and rejects others`() {
        assertTrue(hasGifHeader("GIF87a".toByteArray(Charsets.US_ASCII)))
        assertTrue(hasGifHeader("GIF89a".toByteArray(Charsets.US_ASCII)))
        assertFalse(hasGifHeader("GIF88a".toByteArray(Charsets.US_ASCII)))
        assertFalse(hasGifHeader(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))) // PNG magic
        assertFalse(hasGifHeader(ByteArray(0)))
        assertFalse(hasGifHeader("GIF".toByteArray(Charsets.US_ASCII)))
    }

    @Test
    fun `eligibility by extension header and byte model`() {
        val gif = gifBytes()
        assertTrue(isGifImageModel(gif))

        val named = File.createTempFile("anim-", ".gif")
        named.writeBytes(byteArrayOf(1, 2, 3))
        named.deleteOnExit()
        assertTrue(isGifImageModel(named))

        // A mis-named file with real GIF bytes is still a GIF.
        val misnamed = File.createTempFile("anim-", ".bin")
        misnamed.writeBytes(gif)
        misnamed.deleteOnExit()
        assertTrue(isGifImageModel(misnamed))

        val png = File.createTempFile("img-", ".png")
        png.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        png.deleteOnExit()
        assertFalse(isGifImageModel(png))
        assertFalse(isGifImageModel("not a model"))
    }

    @Test
    fun `a real gif decodes to frames with durations`() {
        val decoded = decodeGifFrames(gifBytes(frames = 2))
        assertNotNull(decoded)
        assertEquals(2, decoded!!.frames.size)
        assertEquals(2, decoded.durationsMs.size)
        assertTrue(decoded.durationsMs.all { it >= 0 })
    }

    @Test
    fun `non gif bytes do not decode`() {
        assertEquals(null, decodeGifFrames("plain text".toByteArray(Charsets.UTF_8)))
    }
}
