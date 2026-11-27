package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PC 1.6.26 item P5: the text-zoom ladder the desktop buttons and
 * Ctrl+wheel / Ctrl+plus/minus step through. Bounds match Android's
 * existing pinch bounds for the same persisted preference (0.7f..3f), so
 * a scale written on either platform stays valid on the other.
 */
class TextPreviewZoomTest {

    @Test
    fun `zoom in walks the ladder one step at a time and caps at the top`() {
        var scale = 1f
        val walked = mutableListOf<Float>()
        repeat(10) {
            scale = textPreviewScaleZoomIn(scale)
            walked += scale
        }
        assertEquals(
            listOf(1.15f, 1.3f, 1.5f, 1.75f, 2f, 2.5f, 3f, 3f, 3f, 3f),
            walked
        )
    }

    @Test
    fun `zoom out walks the ladder one step at a time and caps at the bottom`() {
        var scale = 1f
        val walked = mutableListOf<Float>()
        repeat(5) {
            scale = textPreviewScaleZoomOut(scale)
            walked += scale
        }
        assertEquals(listOf(0.85f, 0.7f, 0.7f, 0.7f, 0.7f), walked)
    }

    @Test
    fun `off-ladder values step to the nearest ladder step in the direction`() {
        assertEquals(1.15f, textPreviewScaleZoomIn(1.1f))
        assertEquals(1f, textPreviewScaleZoomOut(1.1f))
        assertEquals(3f, textPreviewScaleZoomIn(99f))
        assertEquals(0.7f, textPreviewScaleZoomOut(0.01f))
    }

    @Test
    fun `clamp keeps stored values inside the supported range`() {
        assertEquals(0.7f, clampTextPreviewScale(0.5f))
        assertEquals(3f, clampTextPreviewScale(4f))
        assertEquals(1.42f, clampTextPreviewScale(1.42f))
    }
}
