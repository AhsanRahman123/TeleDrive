package com.drdisagree.teledrive.presentation

import com.drdisagree.teledrive.presentation.preview.nextSlideshowIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreviewSlideshowTest {

    @Test
    fun `advances to the next image`() {
        assertEquals(1, nextSlideshowIndex(currentIndex = 0, fileCount = 3))
        assertEquals(2, nextSlideshowIndex(currentIndex = 1, fileCount = 3))
    }

    @Test
    fun `stops at the final image instead of wrapping`() {
        assertNull(nextSlideshowIndex(currentIndex = 2, fileCount = 3))
    }

    @Test
    fun `single image and empty sequences cannot advance`() {
        assertNull(nextSlideshowIndex(currentIndex = 0, fileCount = 1))
        assertNull(nextSlideshowIndex(currentIndex = 0, fileCount = 0))
    }

    @Test
    fun `invalid indices cannot advance`() {
        assertNull(nextSlideshowIndex(currentIndex = -1, fileCount = 3))
        assertNull(nextSlideshowIndex(currentIndex = 3, fileCount = 3))
    }
}
