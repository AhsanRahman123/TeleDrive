package com.drdisagree.teledrive.presentation

import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.presentation.preview.PreviewSequence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewSequenceSlideshowTest {

    @Test
    fun `slideshow launch is disabled for ordinary previews`() {
        assertFalse(PreviewSequence().routeFor("file-id").startSlideshow)
    }

    @Test
    fun `slideshow launch intent is carried into image preview route`() {
        val route = PreviewSequence(
            categories = listOf(FileCategory.IMAGE),
            startSlideshow = true
        ).routeFor("photo-id")

        assertEquals("photo-id", route.fileId)
        assertEquals(FileCategory.IMAGE.name, route.categories)
        assertTrue(route.startSlideshow)
    }
}
