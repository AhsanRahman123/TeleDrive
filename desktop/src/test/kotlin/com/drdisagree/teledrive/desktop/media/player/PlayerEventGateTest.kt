package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event gate behind the desktop player's Compose state. Regression
 * coverage for PC-11: after a subtitle-appearance Apply recreates the
 * player, events from the retired player must never pass, the live player's
 * events must pass, and a start-paused restart must be able to open the
 * gate from its paused report.
 */
class PlayerEventGateTest {

    private class FakePlayer(val name: String)

    @Test
    fun `only the most recently attached player is current`() {
        val gate = PlayerEventGate<FakePlayer>()
        val first = FakePlayer("first")
        val second = FakePlayer("second")

        gate.attach(first)
        assertTrue(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))

        gate.attach(second)
        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun `events from a retired player stay rejected after the swap`() {
        val gate = PlayerEventGate<FakePlayer>()
        val retired = FakePlayer("retired")
        val live = FakePlayer("live")

        gate.attach(retired)
        val retiredGeneration = gate.beginMedia()
        gate.expectGeneration(retiredGeneration)
        gate.openEvents()
        assertTrue(gate.eventsOpen())

        // The Apply swap: the new player is attached (during composition)
        // before the retired player's disposal runs.
        gate.attach(live)
        gate.detach(retired)

        assertFalse(gate.isCurrent(retired))
        assertTrue(gate.isCurrent(live))
    }

    @Test
    fun `detaching a stale player does not close the live player's gates`() {
        val gate = PlayerEventGate<FakePlayer>()
        val retired = FakePlayer("retired")
        val live = FakePlayer("live")

        gate.attach(retired)
        gate.attach(live)
        val generation = gate.beginMedia()
        gate.expectGeneration(generation)
        gate.setAcceptsFrames(true)
        gate.openEvents()

        // Late disposal of the retired player must not touch the live state.
        gate.detach(retired)

        assertTrue(gate.isCurrent(live))
        assertTrue(gate.acceptsFrames())
        assertTrue(gate.eventsOpen())
        assertTrue(gate.isExpectedGeneration())
    }

    @Test
    fun `detaching the current player closes its gates`() {
        val gate = PlayerEventGate<FakePlayer>()
        val only = FakePlayer("only")

        gate.attach(only)
        gate.beginMedia()
        gate.setAcceptsFrames(true)
        gate.openEvents()

        gate.detach(only)

        assertFalse(gate.isCurrent(only))
        assertFalse(gate.acceptsFrames())
        assertFalse(gate.eventsOpen())
        assertFalse(gate.isExpectedGeneration())
    }

    @Test
    fun `begin media closes the gates and mints a fresh generation`() {
        val gate = PlayerEventGate<FakePlayer>()
        gate.attach(FakePlayer("player"))
        val first = gate.beginMedia()
        gate.expectGeneration(first)
        gate.openEvents()
        assertTrue(gate.isExpectedGeneration())

        val second = gate.beginMedia()

        assertTrue(second > first)
        assertEquals(second, gate.generation())
        assertFalse(gate.eventsOpen())
        assertFalse(gate.acceptsFrames())
        // The previous load's generation is no longer the expected one.
        assertFalse(gate.isExpectedGeneration())
        gate.expectGeneration(second)
        assertTrue(gate.isExpectedGeneration())
        // A stale clear for the old generation cannot clear the new one.
        gate.clearExpected(first)
        assertTrue(gate.isExpectedGeneration())
    }

    @Test
    fun `the gate can be opened from a paused report for a start-paused load`() {
        // The paused-restart path: no playing report arrives, so the paused
        // handler opens the gate under the same identity + generation checks
        // the playing handler uses. This models exactly that decision.
        val gate = PlayerEventGate<FakePlayer>()
        val live = FakePlayer("live")
        gate.attach(live)
        val generation = gate.beginMedia()
        gate.expectGeneration(generation)

        val pausedReportAccepted = gate.isCurrent(live) && gate.isExpectedGeneration()
        if (pausedReportAccepted) gate.openEvents()

        assertTrue(pausedReportAccepted)
        assertTrue(gate.eventsOpen())
    }

    @Test
    fun `close events fires exactly once`() {
        val gate = PlayerEventGate<FakePlayer>()
        gate.attach(FakePlayer("player"))
        gate.beginMedia()
        gate.openEvents()

        assertTrue(gate.closeEvents())
        assertFalse(gate.closeEvents())
        assertFalse(gate.eventsOpen())
    }
}
