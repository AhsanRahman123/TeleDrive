package com.drdisagree.teledrive.desktop.ui

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * PC-17 picker thumbnails: the decode must be sampled (never full-size),
 * the candidate set must be exactly what ImageIO can decode, the cache
 * must be bounded and change-sensitive, and a real image file must come
 * back downscaled — while a non-image or corrupt file falls back to the
 * icon (null) instead of failing.
 */
class PickerThumbnailsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `subsample picks the largest power of two that stays at or above the target`() {
        // 4000x3000 toward 240: /8 leaves 500x375, /16 would leave 250x187 (height below).
        assertEquals(8, pickerThumbnailSubsample(4000, 3000, 240))
        assertEquals(2, pickerThumbnailSubsample(800, 600, 240))
        assertEquals(4, pickerThumbnailSubsample(1920, 1080, 160))
        // Already small enough: no sampling.
        assertEquals(1, pickerThumbnailSubsample(200, 150, 240))
        assertEquals(1, pickerThumbnailSubsample(240, 240, 240))
    }

    @Test
    fun `subsample is safe on degenerate input`() {
        assertEquals(1, pickerThumbnailSubsample(0, 100, 240))
        assertEquals(1, pickerThumbnailSubsample(100, 0, 240))
        assertEquals(1, pickerThumbnailSubsample(100, 100, 0))
    }

    @Test
    fun `only ImageIO-decodable image extensions are candidates`() {
        assertTrue(isPickerThumbnailCandidate(File("photo.JPG")))
        assertTrue(isPickerThumbnailCandidate(File("photo.jpeg")))
        assertTrue(isPickerThumbnailCandidate(File("a.png")))
        assertTrue(isPickerThumbnailCandidate(File("a.gif")))
        assertTrue(isPickerThumbnailCandidate(File("a.bmp")))
        assertFalse(isPickerThumbnailCandidate(File("clip.mp4")))
        assertFalse(isPickerThumbnailCandidate(File("notes.txt")))
        // The JDK ships no WebP reader, so WebP is honestly excluded.
        assertFalse(isPickerThumbnailCandidate(File("a.webp")))
        assertFalse(isPickerThumbnailCandidate(File("noextension")))
    }

    @Test
    fun `cache is bounded and evicts the least recently used entry`() {
        val cache = PickerThumbnailCache<String>(capacity = 3)
        val files = (1..4).map { File("f$it.jpg") }
        files.forEachIndexed { index, file -> cache.put(file, 160, "thumb$index") }
        assertEquals(3, cache.size())
        // f1 was evicted; f2..f4 remain.
        assertNull(cache.get(files[0], 160))
        assertEquals("thumb1", cache.get(files[1], 160))
        assertEquals("thumb3", cache.get(files[3], 160))
    }

    @Test
    fun `cache key is sensitive to the decode size class`() {
        val cache = PickerThumbnailCache<String>(capacity = 10)
        val file = File("photo.jpg")
        cache.put(file, 160, "small")
        assertEquals("small", cache.get(file, 160))
        assertNull(cache.get(file, 480))
    }

    @Test
    fun `a real PNG decodes downscaled to the requested longest edge`() {
        val source = temp.newFile("big.png")
        val image = BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB)
        assertTrue(ImageIO.write(image, "png", source))

        val decoded = decodePickerThumbnailImage(source, maxDim = 100)
        assertTrue(decoded != null)
        assertEquals(100, decoded!!.width)
        assertEquals(75, decoded.height)
    }

    @Test
    fun `a small image is decoded unscaled`() {
        val source = temp.newFile("small.png")
        val image = BufferedImage(50, 40, BufferedImage.TYPE_INT_RGB)
        assertTrue(ImageIO.write(image, "png", source))

        val decoded = decodePickerThumbnailImage(source, maxDim = 160)
        assertTrue(decoded != null)
        assertEquals(50, decoded!!.width)
        assertEquals(40, decoded.height)
    }

    @Test
    fun `a corrupt or non-image file decodes to null - the icon fallback`() {
        val bogus = temp.newFile("fake.jpg")
        bogus.writeText("this is not a jpeg")
        assertNull(decodePickerThumbnailImage(bogus, maxDim = 160))
        assertNull(decodePickerThumbnailImage(File(temp.root, "missing.png"), maxDim = 160))
    }
}
