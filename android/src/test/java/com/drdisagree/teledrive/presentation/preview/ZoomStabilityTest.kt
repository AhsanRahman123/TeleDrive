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
 *
 * The total-fix round adds the sensitivity damping: each event's span ratio is raised
 * to ZOOM_SENSITIVITY (0.55) before it is applied, after frame-by-frame measurement of
 * the 1:1 build showed one quick pinch sweeping 129% -> 41% in 0.45 s. The scale
 * expectations below are therefore the DAMPED values (e.g. a doubled span yields
 * 2^0.55 = 1.4641, not 2); every structural assertion — anchoring, zero pan at the
 * centre, drift-free accumulation, the below-100% pan pin — is unchanged.
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
        // Damped: 1.5^0.55 = 1.2499.
        assertEquals(1.2499f, result.scale, 0.001f)
        assertEquals(0f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
    }

    @Test
    fun `the content point under the fingers stays under the fingers`() {
        // Pinch about a point left of centre: doubling the span applies the damped
        // factor 2^0.55 = 1.4641, and the pan must shift so the same content point
        // stays at (250, 1000): panX = (250 - 500) * (1 - 1.4641) = 116.03.
        val result = pinchZoomStep(
            ZoomTransform(1f, 0f, 0f),
            prevSpan = 100f, span = 200f,
            prevCentroid = Offset(250f, 1000f), centroid = Offset(250f, 1000f),
            width = width, height = height
        )
        assertEquals(1.4641f, result.scale, 0.001f)
        assertEquals(116.03f, result.panX, 0.01f)
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
        // Ten +5% span steps, each damped by the sensitivity exponent, must land on
        // 1.05^(10 * 0.55) = 1.3079 — the damped steps still telescope exactly, so
        // nothing is lost or invented along the way — anchored at the centre so the
        // pan stays exactly zero throughout.
        var state = ZoomTransform(1f, 0f, 0f)
        var span = 100f
        repeat(10) {
            val nextSpan = span * 1.05f
            state = pinchZoomStep(state, span, nextSpan, centre, centre, width, height)
            span = nextSpan
        }
        assertEquals(1.3079f, state.scale, 0.01f)
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
        // Damped: 0.5^0.55 = 0.683 — still below 100%, so the pan pin is what matters.
        assertEquals(0.683f, result.scale, 0.001f)
        assertEquals(0f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
    }

    @Test
    fun `span steps are damped by the sensitivity gain`() {
        // The total-fix regression: at the old 1:1 gain a single firm pinch swept the
        // whole zoom range. Even the largest single-event steps the glitch filter lets
        // through must now be damped: a x2 span step applies 2^0.55 = 1.4641, and a
        // x0.5 span step 0.5^0.55 = 0.683 — the fingers move the zoom at a reduced
        // rate in both directions.
        val out = pinchZoomStep(
            ZoomTransform(1f, 0f, 0f),
            prevSpan = 100f, span = 200f,
            prevCentroid = centre, centroid = centre,
            width = width, height = height
        )
        assertEquals(1.4641f, out.scale, 0.001f)
        val into = pinchZoomStep(
            ZoomTransform(1f, 0f, 0f),
            prevSpan = 200f, span = 100f,
            prevCentroid = centre, centroid = centre,
            width = width, height = height
        )
        assertEquals(0.683f, into.scale, 0.001f)
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
