package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 item P2 — the volume-ramp fix lives in the ORDERING of
 * [boundedStartSequence]: the viewer's beforePlay hook (which applies
 * the user's current volume/mute to the native player) must run BEFORE
 * play() is invoked, so the volume is already set when the new media's
 * audio output is created and the first audible moment plays at the
 * user's volume. Before the hook existed, the only application was a
 * Compose effect after the playing event (+250 ms delay per attempt,
 * queued behind the start on the session executor), so every start
 * opened at libVLC's own volume and jumped when the effect landed.
 */
class BoundedStartSequenceTest {

    @Test
    fun `beforePlay runs before play and the await decides the result`() {
        val order = mutableListOf<String>()
        val started = boundedStartSequence(
            beforePlay = { order += "beforePlay" },
            play = { order += "play"; true },
            awaitStarted = { timeout ->
                order += "await"
                assertEquals(1234L, timeout)
                true
            },
            timeoutMs = 1234L
        )
        assertTrue(started)
        assertEquals(listOf("beforePlay", "play", "await"), order)
    }

    @Test
    fun `a failing beforePlay never blocks the start`() {
        val started = boundedStartSequence(
            beforePlay = { throw IllegalStateException("audio api unavailable") },
            play = { true },
            awaitStarted = { true },
            timeoutMs = 10L
        )
        assertTrue(started)
    }

    @Test
    fun `a failed play call reports failure without awaiting`() {
        var awaited = false
        val started = boundedStartSequence(
            beforePlay = {},
            play = { false },
            awaitStarted = { awaited = true; true },
            timeoutMs = 10L
        )
        assertFalse(started)
        assertFalse(awaited)
    }

    @Test
    fun `a start that never reports started times out as failure`() {
        val started = boundedStartSequence(
            beforePlay = {},
            play = { true },
            awaitStarted = { false },
            timeoutMs = 10L
        )
        assertFalse(started)
    }
}
