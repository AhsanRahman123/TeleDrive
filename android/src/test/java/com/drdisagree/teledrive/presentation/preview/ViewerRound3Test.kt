package com.drdisagree.teledrive.presentation.preview

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc186 round 3 (A3/A7/A8/A11): the pure logic behind the viewer fixes —
 * the tap-to-toggle state flip every player surface now routes through,
 * the linear header step + enablement the Previous/Next buttons use, the
 * text pinch's settle-into-scale commit, and the PDF zoom/clamp math the
 * gesture handler spends drags with. Gesture consumption itself is a
 * pointer-input behaviour and is argued in the round report, not here.
 */
class ViewerRound3Test {

    // ---- A3: tap-to-toggle ----

    @Test
    fun `a tap hides visible controls and the next tap shows them again`() {
        assertFalse(toggledControlsVisible(true))
        assertTrue(toggledControlsVisible(false))
        // Two taps return to the starting state on every surface.
        assertTrue(toggledControlsVisible(toggledControlsVisible(true)))
        assertFalse(toggledControlsVisible(toggledControlsVisible(false)))
    }

    // ---- A11: header step target ----

    @Test
    fun `header step moves one page in the counter sequence`() {
        assertEquals(3, viewerStepTarget(currentPage = 4, pageCount = 47, delta = -1))
        assertEquals(5, viewerStepTarget(currentPage = 4, pageCount = 47, delta = 1))
    }

    @Test
    fun `header step is a no-op at the ends instead of wrapping`() {
        assertNull(viewerStepTarget(currentPage = 0, pageCount = 47, delta = -1))
        assertNull(viewerStepTarget(currentPage = 46, pageCount = 47, delta = 1))
        assertNull(viewerStepTarget(currentPage = 0, pageCount = 1, delta = 1))
        assertNull(viewerStepTarget(currentPage = 0, pageCount = 0, delta = 1))
        assertNull(viewerStepTarget(currentPage = 4, pageCount = 47, delta = 0))
    }

    // ---- A11: header enablement mirrors the step ----

    @Test
    fun `header buttons are enabled exactly where a step exists`() {
        for (count in 1..8) {
            for (page in 0 until count) {
                assertEquals(
                    viewerStepTarget(page, count, -1) != null,
                    viewerHasPreviousFile(page)
                )
                assertEquals(
                    viewerStepTarget(page, count, 1) != null,
                    viewerHasNextFile(page, count)
                )
            }
        }
        assertFalse(viewerHasNextFile(0, 0))
        assertFalse(viewerHasPreviousFile(0))
        assertTrue(viewerHasNextFile(0, 2))
    }

    // ---- A7: text pinch settle ----

    @Test
    fun `text pinch settles by committing the transient factor once`() {
        assertEquals(1f, settledTextScale(1f, 1f), 0f)
        assertEquals(2f, settledTextScale(1f, 2f), 0.001f)
        assertEquals(1.5f, settledTextScale(3f, 0.5f), 0.001f)
    }

    @Test
    fun `text pinch settle clamps to the shared persisted range`() {
        assertEquals(TEXT_PREVIEW_SCALE_MAX, settledTextScale(2f, 4f), 0f)
        assertEquals(TEXT_PREVIEW_SCALE_MIN, settledTextScale(1f, 0.1f), 0f)
        // A settle never produces a scale the buttons/persistence reject.
        assertEquals(
            clampTextPreviewScale(1.3f * 1.4f),
            settledTextScale(1.3f, 1.4f),
            0f
        )
    }

    // ---- A7/A8: PDF zoom target + offset clamp ----

    @Test
    fun `pdf zoom target multiplies and clamps to the viewer range`() {
        assertEquals(1f, pdfZoomTarget(1f, 1f), 0f)
        assertEquals(2f, pdfZoomTarget(1f, 2f), 0.001f)
        assertEquals(1f, pdfZoomTarget(2f, 0.1f), 0f)
        assertEquals(6f, pdfZoomTarget(4f, 4f), 0f)
    }

    @Test
    fun `pdf offset is locked at zero while unzoomed`() {
        val viewport = IntSize(1000, 2000)
        assertEquals(Offset.Zero, clampPdfOffset(Offset(300f, -500f), 1f, viewport))
        assertEquals(Offset.Zero, clampPdfOffset(Offset(-9999f, 9999f), 1f, viewport))
    }

    @Test
    fun `pdf offset clamps at the zoomed page edge`() {
        val viewport = IntSize(1000, 2000)
        // At scale 2 the layer may move at most half the viewport per axis.
        assertEquals(
            Offset(500f, -1000f),
            clampPdfOffset(Offset(900f, -4000f), 2f, viewport)
        )
        // Inside the range the offset passes through untouched — this is
        // what makes a zoomed pan "spend" the drag in the A8 handler, while
        // a pan already at the edge returns the current offset unchanged
        // and the handler leaves that drag for the pager.
        assertEquals(
            Offset(120f, -80f),
            clampPdfOffset(Offset(120f, -80f), 2f, viewport)
        )
        val atEdge = clampPdfOffset(Offset(500f, 0f), 2f, viewport)
        assertEquals(atEdge, clampPdfOffset(atEdge + Offset(50f, 0f), 2f, viewport))
    }
}
