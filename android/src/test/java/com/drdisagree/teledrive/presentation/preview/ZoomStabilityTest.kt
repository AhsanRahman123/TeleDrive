package com.drdisagree.teledrive.presentation.preview

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The zoom-stability rewrite: [pinchZoomStep] applies only one event's span ratio at a
 * time and anchors the pan so the content point under the fingers stays under the
 * fingers. These tests pin that behaviour (Pranto's report on the old build: the %
 * bounced 198 -> 146 -> 96 during a steady pinch, the picture slammed to the 40%
 * floor, and any zoom at or below ~100% snapped back to exactly 100% on release).
 */
class ZoomStabilityTest {

    private val width = 1000
    private val height = 2000
    private val centre = Offset(500f, 1000f)

    @Test
    fun `pinching about the centre scales without panning`() {
        val result = pinchZoomStep(
            ZoomTransform(1f, 0f, 0f),
            prevSpan = 100f, span = 150f,
            prevCentroid = centre, centroid = centre,
            width = width, height = height
        )
        assertEquals(1.5f, result.scale, 0.001f)
        assertEquals(0f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
    }

    @Test
    fun `the content point under the fingers stays under the fingers`() {
        // Pinch about a point left of centre: doubling the span doubles the scale,
        // and the pan must shift so the same content point stays at (250, 1000).
        val result = pinchZoomStep(
            ZoomTransform(1f, 0f, 0f),
            prevSpan = 100f, span = 200f,
            prevCentroid = Offset(250f, 1000f), centroid = Offset(250f, 1000f),
            width = width, height = height
        )
        assertEquals(2f, result.scale, 0.001f)
        assertEquals(250f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
        // Cross-check with the layer's rendering model: a content point p renders at
        // O + (p - O) * s + t. Before the step, the point under (250, 1000) was
        // (250, 1000) itself (scale 1, no pan); after the step it must still render
        // at (250, 1000).
        val renderedX = 500f + (250f - 500f) * result.scale + result.panX
        val renderedY = 1000f + (1000f - 1000f) * result.scale + result.panY
        assertEquals(250f, renderedX, 0.001f)
        assertEquals(1000f, renderedY, 0.001f)
    }

    @Test
    fun `centroid movement without span change pans one to one at any scale`() {
        val result = pinchZoomStep(
            ZoomTransform(2f, 100f, 50f),
            prevSpan = 200f, span = 200f,
            prevCentroid = Offset(400f, 900f), centroid = Offset(460f, 940f),
            width = width, height = height
        )
        assertEquals(2f, result.scale, 0.001f)
        assertEquals(160f, result.panX, 0.001f)
        assertEquals(90f, result.panY, 0.001f)
    }

    @Test
    fun `many small steps accumulate without drifting`() {
        // Ten +5% steps must land on 1.05^10 = 1.6289, anchored at the centre so the
        // pan stays exactly zero throughout.
        var state = ZoomTransform(1f, 0f, 0f)
        var span = 100f
        repeat(10) {
            val nextSpan = span * 1.05f
            state = pinchZoomStep(state, span, nextSpan, centre, centre, width, height)
            span = nextSpan
        }
        assertEquals(1.6289f, state.scale, 0.01f)
        assertEquals(0f, state.panX, 0.001f)
        assertEquals(0f, state.panY, 0.001f)
    }

    @Test
    fun `zooming out below 100 percent pins the pan to zero instead of throwing`() {
        // The original crash trap: below 1x the raw pan half-extents go negative and
        // invert the clamp range. An anchored step that would push the shrunken
        // picture sideways must land centred, not throw.
        val result = pinchZoomStep(
            ZoomTransform(1f, 0f, 0f),
            prevSpan = 200f, span = 100f,
            prevCentroid = Offset(250f, 1000f), centroid = Offset(250f, 1000f),
            width = width, height = height
        )
        assertEquals(0.5f, result.scale, 0.001f)
        assertEquals(0f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
    }

    @Test
    fun `at the maximum scale a further pinch in only pans`() {
        val result = pinchZoomStep(
            ZoomTransform(2.5f, 0f, 0f),
            prevSpan = 100f, span = 150f,
            prevCentroid = Offset(400f, 900f), centroid = Offset(430f, 930f),
            width = width, height = height
        )
        assertEquals(2.5f, result.scale, 0.001f)
        // The applied ratio is 1 once clamped, so anchoring reduces to following the
        // centroid exactly: pan += (30, 30), no phantom drift.
        assertEquals(30f, result.panX, 0.001f)
        assertEquals(30f, result.panY, 0.001f)
    }

    @Test
    fun `an impossible single event span jump is ignored`() {
        val before = ZoomTransform(1.2f, 10f, -20f)
        val result = pinchZoomStep(
            before,
            prevSpan = 100f, span = 400f,
            prevCentroid = centre, centroid = centre,
            width = width, height = height
        )
        assertEquals(before, result)
    }

    @Test
    fun `a zero previous span is ignored`() {
        val before = ZoomTransform(1.2f, 10f, -20f)
        val result = pinchZoomStep(
            before,
            prevSpan = 0f, span = 100f,
            prevCentroid = centre, centroid = centre,
            width = width, height = height
        )
        assertEquals(before, result)
    }
}
