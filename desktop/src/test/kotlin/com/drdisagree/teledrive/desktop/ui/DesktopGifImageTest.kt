package com.drdisagree.teledrive.desktop.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    // ------------------------------------------------------------------
    // MF-1: compositing of optimised (sub-rectangle / disposal) GIFs.
    //
    // The JDK writer above emits full-canvas frames only, so it can
    // never produce the dependent frames real optimised GIFs consist
    // of. The fixture below is therefore written byte-by-byte with a
    // minimal GIF89a encoder (fixed 9-bit LZW codes: a Clear code, the
    // frame's palette indices, EOI — the dictionary can never outgrow
    // the 9-bit width at these frame sizes).
    // ------------------------------------------------------------------

    private data class FixtureFrame(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val disposal: Int,
        val pixels: IntArray
    )

    /** Palette indices used by [optimisedGifBytes]. */
    private companion object {
        const val IDX_RED = 1
        const val IDX_GREEN = 2
        const val IDX_BLUE = 3
        const val DISPOSAL_KEEP = 1
        const val DISPOSAL_RESTORE_BG = 2
    }

    /**
     * A 6×4 GIF whose later frames are sub-rectangle deltas:
     * frame 1 paints the whole canvas red (keep), frame 2 paints a
     * 2×2 green patch at (2,1) with restore-to-background disposal,
     * frame 3 paints a 2×1 blue patch at (4,2) (keep). Composited,
     * frame 3 must be red everywhere except frame 2's patch rect
     * (cleared back to transparent by its disposal) and its own blue
     * patch.
     */
    private fun optimisedGifBytes(): ByteArray {
        val frames = listOf(
            FixtureFrame(0, 0, 6, 4, DISPOSAL_KEEP, IntArray(24) { IDX_RED }),
            FixtureFrame(2, 1, 2, 2, DISPOSAL_RESTORE_BG, IntArray(4) { IDX_GREEN }),
            FixtureFrame(4, 2, 2, 1, DISPOSAL_KEEP, IntArray(2) { IDX_BLUE })
        )
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v and 0xFF)
        fun u16(v: Int) {
            u8(v)
            u8(v shr 8)
        }
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        u16(6) // logical width
        u16(4) // logical height
        u8(0xF7) // global colour table, 8-bit colour resolution, 256 entries
        u8(0) // background colour index
        u8(0) // aspect ratio
        repeat(256) { index ->
            when (index) {
                IDX_RED -> {
                    u8(255); u8(0); u8(0)
                }
                IDX_GREEN -> {
                    u8(0); u8(255); u8(0)
                }
                IDX_BLUE -> {
                    u8(0); u8(0); u8(255)
                }
                else -> {
                    u8(0); u8(0); u8(0)
                }
            }
        }
        for (frame in frames) {
            // Graphics Control Extension: disposal in bits 2..4, no transparency.
            u8(0x21); u8(0xF9); u8(4)
            u8(frame.disposal shl 2)
            u16(10) // delay 100 ms
            u8(0) // transparent colour index (unused)
            u8(0) // block terminator
            // Image Descriptor.
            u8(0x2C)
            u16(frame.left); u16(frame.top); u16(frame.width); u16(frame.height)
            u8(0) // no local colour table
            // LZW image data: min code size 8, codes are 9 bits,
            // LSB-first: Clear (256), pixel indices, EOI (257).
            u8(8)
            val packed = ByteArrayOutputStream()
            var bitBuffer = 0
            var bitCount = 0
            fun emit(code: Int) {
                bitBuffer = bitBuffer or (code shl bitCount)
                bitCount += 9
                while (bitCount >= 8) {
                    packed.write(bitBuffer and 0xFF)
                    bitBuffer = bitBuffer ushr 8
                    bitCount -= 8
                }
            }
            emit(256)
            frame.pixels.forEach { emit(it) }
            emit(257)
            if (bitCount > 0) packed.write(bitBuffer and 0xFF)
            val data = packed.toByteArray()
            var offset = 0
            while (offset < data.size) {
                val chunk = minOf(255, data.size - offset)
                u8(chunk)
                out.write(data, offset, chunk)
                offset += chunk
            }
            u8(0) // sub-block terminator
        }
        u8(0x3B) // trailer
        return out.toByteArray()
    }

    private data class ParsedFrame(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val disposal: Int
    )

    /** Walks a GIF's blocks and returns each image descriptor + its GCE disposal. */
    private fun parseGifFrames(bytes: ByteArray): List<ParsedFrame> {
        var pos = 6 // header
        fun u8(): Int = bytes[pos++].toInt() and 0xFF
        fun u16(): Int {
            val v = (bytes[pos].toInt() and 0xFF) or ((bytes[pos + 1].toInt() and 0xFF) shl 8)
            pos += 2
            return v
        }
        fun skipSubBlocks() {
            while (true) {
                val size = u8()
                if (size == 0) return
                pos += size
            }
        }
        u16(); u16() // logical size
        val packed = u8()
        u8(); u8() // background, aspect
        if (packed and 0x80 != 0) pos += 3 * (1 shl ((packed and 0x07) + 1))
        val frames = ArrayList<ParsedFrame>()
        var disposal = 0
        while (pos < bytes.size) {
            when (u8()) {
                0x3B -> return frames
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) {
                        val size = u8()
                        val gcePacked = u8()
                        disposal = (gcePacked shr 2) and 0x07
                        pos += size - 1 // delay + transparent index
                        u8() // terminator
                    } else {
                        skipSubBlocks()
                    }
                }
                0x2C -> {
                    val left = u16(); val top = u16(); val w = u16(); val h = u16()
                    val descPacked = u8()
                    if (descPacked and 0x80 != 0) pos += 3 * (1 shl ((descPacked and 0x07) + 1))
                    u8() // LZW min code size
                    skipSubBlocks()
                    frames += ParsedFrame(left, top, w, h, disposal)
                }
                else -> error("unexpected GIF block at ${pos - 1}")
            }
        }
        return frames
    }

    @Test
    fun `the optimised fixture really has sub-rectangle disposal frames`() {
        val parsed = parseGifFrames(optimisedGifBytes())
        assertEquals(3, parsed.size)
        assertEquals(ParsedFrame(0, 0, 6, 4, DISPOSAL_KEEP), parsed[0])
        assertEquals(ParsedFrame(2, 1, 2, 2, DISPOSAL_RESTORE_BG), parsed[1])
        assertEquals(ParsedFrame(4, 2, 2, 1, DISPOSAL_KEEP), parsed[2])
    }

    @Test
    fun `dependent frames composite onto the required frame`() {
        val decoded = decodeGifFrames(optimisedGifBytes())
        assertNotNull(decoded)
        assertEquals(3, decoded!!.frames.size)

        val frame2 = decoded.frames[1].toPixelMap()
        // The green delta lands on the red base, not on an empty canvas.
        assertEquals(Color.Red, frame2[0, 0])
        assertEquals(Color.Red, frame2[5, 3])
        assertEquals(Color.Green, frame2[2, 1])
        assertEquals(Color.Green, frame2[3, 2])
        assertEquals(Color.Red, frame2[4, 1])
    }

    @Test
    fun `restore to background clears the disposed frame rectangle`() {
        val decoded = decodeGifFrames(optimisedGifBytes())
        assertNotNull(decoded)
        val frame3 = decoded!!.frames[2].toPixelMap()
        // Frame 2's green patch is gone: its rect was restored to
        // background (transparent) before frame 3 was drawn.
        assertEquals(0f, frame3[2, 1].alpha, 0f)
        assertEquals(0f, frame3[3, 2].alpha, 0f)
        // The rest of frame 1's red canvas survives, plus frame 3's patch.
        assertEquals(Color.Red, frame3[0, 0])
        assertEquals(Color.Red, frame3[5, 0])
        assertEquals(Color.Blue, frame3[4, 2])
        assertEquals(Color.Blue, frame3[5, 2])
    }

    // ------------------------------------------------------------------
    // MF-2: pixel / input budgets.
    // ------------------------------------------------------------------

    @Test
    fun `animation over the pixel budget falls back to a static first frame`() {
        val bytes = optimisedGifBytes() // 6x4 canvas, 3 frames = 72 px total
        val decoded = decodeGifFrames(bytes, maxTotalPixels = 48)
        assertNotNull(decoded)
        assertEquals(1, decoded!!.frames.size)
        assertEquals(1, decoded.durationsMs.size)
        // The static frame is the fully composited first frame.
        assertEquals(Color.Red, decoded.frames[0].toPixelMap()[3, 2])
    }

    @Test
    fun `animation within the pixel budget keeps every frame`() {
        val decoded = decodeGifFrames(optimisedGifBytes(), maxTotalPixels = 72)
        assertNotNull(decoded)
        assertEquals(3, decoded!!.frames.size)
    }

    @Test
    fun `a canvas over the pixel budget does not decode`() {
        assertNull(decodeGifFrames(optimisedGifBytes(), maxTotalPixels = 23))
    }

    @Test
    fun `input over the byte bound does not decode`() {
        val bytes = optimisedGifBytes()
        assertNull(decodeGifFrames(bytes, maxInputBytes = bytes.size - 1))
        assertNotNull(decodeGifFrames(bytes, maxInputBytes = bytes.size))
    }
}
