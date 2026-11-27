package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 item P3 — pins [NegotiatedVideoFormat], the tracker behind
 * the callback surface's negotiation log. The log is the field evidence
 * for the first-play wrong-resolution report: it must record each
 * DISTINCT size libVLC negotiates (so a first play at a smaller size
 * than its replay is visible), must not spam on repeats of the same
 * size, and must never record an invalid size.
 */
class NegotiatedVideoFormatTest {

    @Test
    fun `first valid negotiation is a change and is recorded`() {
        val format = NegotiatedVideoFormat()
        assertNull(format.current())
        assertTrue(format.record(1920, 1080))
        assertEquals(1920 to 1080, format.current())
    }

    @Test
    fun `repeating the same size is not a change`() {
        val format = NegotiatedVideoFormat()
        assertTrue(format.record(1280, 720))
        assertFalse(format.record(1280, 720))
        assertEquals(1280 to 720, format.current())
    }

    @Test
    fun `a renegotiation to a different size is a change`() {
        // The first-play signature this diagnostic exists to catch: a
        // first play negotiating small, a replay negotiating full size.
        val format = NegotiatedVideoFormat()
        assertTrue(format.record(640, 360))
        assertTrue(format.record(1920, 1080))
        assertEquals(1920 to 1080, format.current())
    }

    @Test
    fun `invalid sizes are ignored and never recorded`() {
        val format = NegotiatedVideoFormat()
        assertFalse(format.record(0, 1080))
        assertFalse(format.record(1920, 0))
        assertFalse(format.record(-1, -1))
        assertNull(format.current())
        assertTrue(format.record(1920, 1080))
        assertFalse(format.record(0, 0))
        assertEquals(1920 to 1080, format.current())
    }
}
