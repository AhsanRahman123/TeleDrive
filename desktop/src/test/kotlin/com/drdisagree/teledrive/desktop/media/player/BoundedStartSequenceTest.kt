package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 N2 — the volume-ramp fix lives in the ORDERING of
 * [boundedStartSequence]: the viewer's beforePlay hook (which applies the
 * user's stored volume/mute to the native player) must run BEFORE play()
 * is invoked, so the volume is in force when the new media's audio output
 * is created and the first audible moment already plays at the stored
 * volume. Before the hook existed, volume was only applied by a Compose
 * effect after the playing event (+250 ms, + an executor queue wait
 * behind the start itself), so every start opened at libVLC's default
 * volume and then jumped.
 *
 * The sequence core is driven with recording fakes; the native half
 * (vlcj's MediaPlayer is a class and cannot be faked without natives) is
 * the thin [startMediaBounded] wrapper around it.
 */
class BoundedStartSequenceTest {

    @Test
    fun `beforePlay runs before play, and play before the bounded await`() {
        val order = mutableListOf<String>()
        val started = boundedStartSequence(
            beforePlay = { order += "beforePlay" },
            play = { order += "play"; true },
            awaitStarted = { order += "await"; true },
            timeoutMs = 123L
        )
        assertTrue(started)
        assertEquals(listOf("beforePlay", "play", "await"), order)
    }

    @Test
    fun `the timeout is passed to the await unchanged`() {
        var seenTimeout: Long? = null
        boundedStartSequence(
            beforePlay = {},
            play = { true },
            awaitStarted = { seenTimeout = it; true },
            timeoutMs = 10_000L
        )
        assertEquals(10_000L, seenTimeout)
    }

    @Test
    fun `a failing beforePlay never blocks the start`() {
        var playCalled = false
        val started = boundedStartSequence(
            beforePlay = { throw IllegalStateException("volume apply failed") },
            play = { playCalled = true; true },
            awaitStarted = { true },
            timeoutMs = 1_000L
        )
        assertTrue(playCalled)
        assertTrue(started)
    }

    @Test
    fun `a refused play fails the start without awaiting`() {
        var awaitCalled = false
        val started = boundedStartSequence(
            beforePlay = {},
            play = { false },
            awaitStarted = { awaitCalled = true; true },
            timeoutMs = 1_000L
        )
        assertFalse(started)
        assertFalse(awaitCalled)
    }

    @Test
    fun `an await timeout fails the start - the P4 wedge bound is preserved`() {
        val started = boundedStartSequence(
            beforePlay = {},
            play = { true },
            awaitStarted = { false },
            timeoutMs = 1_000L
        )
        assertFalse(started)
    }
}
