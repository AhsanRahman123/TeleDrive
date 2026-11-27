package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 P5: the text-viewer zoom buttons' stepping logic — the production
 * functions the Android buttons call (PreviewScreen TextZoomButtons), which
 * step the one persisted textPreviewScale shared by every text viewer.
 */
class TextZoomTest {

    @Test
    fun `zoom in multiplies by the step factor`() {
        assertEquals(1.25f, textZoomIn(1f), 0.001f)
        assertEquals(1.5625f, textZoomIn(1.25f), 0.001f)
    }

    @Test
    fun `zoom out divides by the step factor and inverts zoom in`() {
        assertEquals(0.8f, textZoomOut(1f), 0.001f)
        assertEquals(1f, textZoomOut(textZoomIn(1f)), 0.001f)
    }

    @Test
    fun `zoom clamps at the shared min and max`() {
        assertEquals(TEXT_PREVIEW_SCALE_MAX, textZoomIn(TEXT_PREVIEW_SCALE_MAX), 0f)
        assertEquals(TEXT_PREVIEW_SCALE_MAX, textZoomIn(2.9f), 0.001f)
        assertEquals(TEXT_PREVIEW_SCALE_MIN, textZoomOut(TEXT_PREVIEW_SCALE_MIN), 0f)
        assertEquals(TEXT_PREVIEW_SCALE_MIN, textZoomOut(0.75f), 0.001f)
    }

    @Test
    fun `repeated presses reach but never pass the bounds`() {
        var scale = 1f
        repeat(30) { scale = textZoomIn(scale) }
        assertEquals(TEXT_PREVIEW_SCALE_MAX, scale, 0f)
        repeat(60) { scale = textZoomOut(scale) }
        assertEquals(TEXT_PREVIEW_SCALE_MIN, scale, 0f)
        assertTrue(scale > 0f)
    }

    @Test
    fun `clamp keeps the persisted range the pinch already used`() {
        // The pre-existing Android text pinch coerced to 0.7..3 — the shared
        // constants must keep exactly that range so old persisted values and
        // button steps agree.
        assertEquals(0.7f, TEXT_PREVIEW_SCALE_MIN, 0f)
        assertEquals(3f, TEXT_PREVIEW_SCALE_MAX, 0f)
        assertEquals(1f, clampTextPreviewScale(1f), 0f)
        assertEquals(TEXT_PREVIEW_SCALE_MIN, clampTextPreviewScale(0.1f), 0f)
        assertEquals(TEXT_PREVIEW_SCALE_MAX, clampTextPreviewScale(99f), 0f)
    }
}
