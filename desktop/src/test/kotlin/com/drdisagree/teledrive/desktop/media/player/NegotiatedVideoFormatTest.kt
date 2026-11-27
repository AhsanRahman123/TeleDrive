package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 N3 — the negotiated-format tracker behind the video surface's
 * negotiation log. The first-play pixelation mechanism lives inside
 * libVLC's native video-output negotiation, which no unit test can
 * observe; what IS testable is that the surface records (and would log)
 * each distinct negotiated size exactly once, so a field log can compare
 * the size a first play negotiated against the size its replay
 * negotiated for the same file.
 */
class NegotiatedVideoFormatTest {

    @Test
    fun `no size is current before the first negotiation`() {
        assertNull(NegotiatedVideoFormat().current())
    }

    @Test
    fun `the first valid negotiation is a change and becomes current`() {
        val format = NegotiatedVideoFormat()
        assertTrue(format.record(1920, 1080))
        assertEquals(1920 to 1080, format.current())
    }

    @Test
    fun `repeating the same size is not a change`() {
        val format = NegotiatedVideoFormat()
        format.record(1920, 1080)
        assertFalse(format.record(1920, 1080))
        assertEquals(1920 to 1080, format.current())
    }

    @Test
    fun `a different size is a change - the first-play versus replay divergence`() {
        val format = NegotiatedVideoFormat()
        assertTrue(format.record(640, 360))
        assertTrue(format.record(1920, 1080))
        assertEquals(1920 to 1080, format.current())
    }

    @Test
    fun `invalid sizes are ignored and never become current`() {
        val format = NegotiatedVideoFormat()
        assertFalse(format.record(0, 1080))
        assertFalse(format.record(1920, 0))
        assertFalse(format.record(-1, -1))
        assertNull(format.current())
        format.record(1280, 720)
        assertFalse(format.record(0, 0))
        assertEquals(1280 to 720, format.current())
    }
}
