package com.drdisagree.teledrive.presentation.preview

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The zoom-stability rewrite: [pinchZoomStep] applies only one event's span ratio at a
 * time. These tests pin that behaviour (Pranto's report on the old build: the %
 * bounced 198 -> 146 -> 96 during a steady pinch, the picture slammed to the 40%
 * floor, and any zoom at or below ~100% snapped back to exactly 100% on release).
 *
 * The total-fix round adds the sensitivity damping: each event's span ratio is raised
 * to ZOOM_SENSITIVITY (0.55) before it is applied, after frame-by-frame measurement of
 * the 1:1 build showed one quick pinch sweeping 129% -> 41% in 0.45 s. The scale
 * expectations below are therefore the DAMPED values (e.g. a doubled span yields
 * 2^0.55 = 1.4641, not 2).
 *
 * The stillness round changes the ANCHORING: the zoom now scales about the stage
 * centre, never about the fingers. Frame measurement of the MX Player reference
 * showed its picture centre staying within ~2 recording px of the stage centre
 * across full-range sweeps (146% -> 40% -> 88%), with no translation when the pinch
 * midpoint moved at constant span — while the centroid-anchored build carried its
 * picture centre ~24 recording px off centre during one portrait pinch-in to 123%.
 * The old assertions that encoded centroid-following (the content point under the
 * fingers stays under the fingers; centroid movement pans one-to-one; a max-scale
 * pinch only pans) are replaced below by the centre-anchored invariant: a pinch, by
 * itself, never creates or moves a pan except by scaling an existing one about the
 * centre.
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
    fun `the content point at the screen centre stays at the screen centre`() {
        // The MX stillness invariant. The pinch happens off-centre AND its centroid
        // glides mid-step; neither may move the picture sideways. Starting panned,
        // the pan must scale by the applied ratio (1.5^0.55 = 1.2499) so the content
        // point sitting at the centre stays exactly there: before the step that
        // point is O - t/s = (500 - 60/1.5, 1000 + 30/1.5) = (460, 1020).
        val result = pinchZoomStep(
            ZoomTransform(1.5f, 60f, -30f),
            prevSpan = 100f, span = 150f,
            prevCentroid = Offset(250f, 1000f), centroid = Offset(300f, 1050f),
            width = width, height = height
        )
        assertEquals(1.8748f, result.scale, 0.001f)
        assertEquals(74.99f, result.panX, 0.01f)
        assertEquals(-37.50f, result.panY, 0.01f)
        // Cross-check with the layer's rendering model: a content point p renders at
        // O + (p - O) * s + t. The point (460, 1020) sat at the centre before the
        // step; it must still render at the centre (500, 1000) after it.
        val renderedX = 500f + (460f - 500f) * result.scale + result.panX
        val renderedY = 1000f + (1020f - 1000f) * result.scale + result.panY
        assertEquals(500f, renderedX, 0.01f)
        assertEquals(1000f, renderedY, 0.01f)
    }

    @Test
    fun `an off-centre pinch on an un-panned picture creates no pan`() {
        // Pranto's complaint, as a test: pinch about a point left of centre from an
        // un-panned state. The old centroid anchoring shifted the pan to
        // (250 - 500) * (1 - 1.4641) = +116 px so the content followed the fingers;
        // centre anchoring leaves the pan at exactly zero — the picture grows about
        // the middle of the screen and stays planted.
        val result = pinchZoomStep(
            ZoomTransform(1f, 0f, 0f),
            prevSpan = 100f, span = 200f,
            prevCentroid = Offset(250f, 1000f), centroid = Offset(250f, 1000f),
            width = width, height = height
        )
        assertEquals(1.4641f, result.scale, 0.001f)
        assertEquals(0f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
    }

    @Test
    fun `centroid movement without span change leaves the pan untouched`() {
        // The old formula reduced to t + (C' - C) here: pure finger glide dragged
        // the picture one-to-one. Centre anchoring: with no scale change the applied
        // ratio is 1 and the pan is bit-for-bit unchanged.
        val result = pinchZoomStep(
            ZoomTransform(2f, 100f, 50f),
            prevSpan = 200f, span = 200f,
            prevCentroid = Offset(400f, 900f), centroid = Offset(460f, 940f),
            width = width, height = height
        )
        assertEquals(2f, result.scale, 0.001f)
        assertEquals(100f, result.panX, 0.001f)
        assertEquals(50f, result.panY, 0.001f)
    }

    @Test
    fun `a full pinch-in with a gliding centroid never moves an un-panned picture`() {
        // Twelve +10% span steps while the centroid glides diagonally across the
        // stage — the shape of a real two-thumb pinch-in. The scale must accumulate
        // to the damped 1.10^(12 * 0.55) = 1.8757 while the pan stays exactly zero
        // after every single step: the picture grows, it never slides.
        var state = ZoomTransform(1f, 0f, 0f)
        var span = 100f
        var prevC = Offset(300f, 800f)
        repeat(12) { i ->
            val nextSpan = span * 1.10f
            val nextC = Offset(300f + (i + 1) * 20f, 800f + (i + 1) * 40f)
            state = pinchZoomStep(state, span, nextSpan, prevC, nextC, width, height)
            assertEquals(0f, state.panX, 0.001f)
            assertEquals(0f, state.panY, 0.001f)
            span = nextSpan
            prevC = nextC
        }
        assertEquals(1.8757f, state.scale, 0.01f)
    }

    @Test
    fun `pinching out shrinks an existing pan proportionally about the centre`() {
        // Centre anchoring is symmetric: zooming out from a panned state scales the
        // pan by the applied ratio (0.5^0.55 = 0.683), so the centre content point
        // stays on the centre on the way out too.
        val result = pinchZoomStep(
            ZoomTransform(2f, 120f, -80f),
            prevSpan = 200f, span = 100f,
            prevCentroid = centre, centroid = centre,
            width = width, height = height
        )
        assertEquals(1.366f, result.scale, 0.001f)
        assertEquals(81.96f, result.panX, 0.01f)
        assertEquals(-54.64f, result.panY, 0.01f)
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
        // invert the clamp range. A step landing below 100% must come out centred,
        // not throw.
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
    fun `at the maximum scale a further pinch in changes nothing`() {
        val result = pinchZoomStep(
            ZoomTransform(2.5f, 0f, 0f),
            prevSpan = 100f, span = 150f,
            prevCentroid = Offset(400f, 900f), centroid = Offset(430f, 930f),
            width = width, height = height
        )
        assertEquals(2.5f, result.scale, 0.001f)
        // The applied ratio is 1 once clamped and the centroid is never followed, so
        // the moving fingers cannot drag the picture even at the ceiling (the old
        // anchoring added the full centroid movement, (30, 30), here).
        assertEquals(0f, result.panX, 0.001f)
        assertEquals(0f, result.panY, 0.001f)
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
