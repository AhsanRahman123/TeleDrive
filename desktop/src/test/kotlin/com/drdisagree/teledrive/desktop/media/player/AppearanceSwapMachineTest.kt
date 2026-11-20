package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The PC-19 swap contract (report 20): an appearance Apply builds a
 * candidate LibVLC instance behind the stable PlayerSession facade and
 * flips to it ONLY when the candidate has proven itself live. These
 * tests pin the decision table of [AppearanceSwapMachine], including
 * failure injection — a candidate that never plays (settle with
 * candidateReady = false, the timeout/error outcome) must roll back and
 * leave the settled appearance untouched, because in the real session
 * the live player was never stopped or reconfigured.
 */
class AppearanceSwapMachineTest {

    private val defaultKey = emptyList<String>()
    private val customKey = listOf("--text-renderer=freetype", "--freetype-font=Roboto")
    private val otherKey = listOf("--text-renderer=freetype", "--freetype-font=Georgia")

    @Test
    fun `reverting a settled flip restores the previous key (PC-27 flip-back)`() {
        // PC-27: the candidate proved itself on the proof media and the
        // session flipped to it, but its start of the real media then
        // failed, so the old player owns the session again. The settled
        // key must follow the player that actually renders.
        val machine = AppearanceSwapMachine()
        machine.seed(defaultKey)

        val generation = machine.begin(customKey)
        assertEquals(
            AppearanceSwapMachine.Decision.FLIP,
            machine.settle(generation, candidateReady = true)
        )
        assertEquals(customKey, machine.settledKey())

        machine.revertSettled(defaultKey)
        assertEquals(defaultKey, machine.settledKey())
        assertNull(machine.pendingKey())
    }

    @Test
    fun `after a flip-back, re-applying the same appearance starts a real swap`() {
        // If the settled key were left at the reverted-from key, the
        // session's no-op check (target == settled) would swallow the
        // user's next Apply of the same appearance forever.
        val machine = AppearanceSwapMachine()
        machine.seed(defaultKey)

        val first = machine.begin(customKey)
        machine.settle(first, candidateReady = true)
        machine.revertSettled(defaultKey)

        val second = machine.begin(customKey)
        assertEquals(customKey, machine.pendingKey())
        assertEquals(
            AppearanceSwapMachine.Decision.FLIP,
            machine.settle(second, candidateReady = true)
        )
        assertEquals(customKey, machine.settledKey())
    }

    @Test
    fun `a proven candidate flips and settles its key`() {
        val machine = AppearanceSwapMachine()
        machine.seed(defaultKey)

        val generation = machine.begin(customKey)
        assertEquals(customKey, machine.pendingKey())
        assertEquals(
            AppearanceSwapMachine.Decision.FLIP,
            machine.settle(generation, candidateReady = true)
        )
        assertEquals(customKey, machine.settledKey())
        assertNull(machine.pendingKey())
    }

    @Test
    fun `a candidate that never plays rolls back and the settled key is untouched`() {
        // Failure injection: the candidate start times out (or errors) —
        // the session's outcome for settle(false). The live player's
        // appearance must remain exactly what it was.
        val machine = AppearanceSwapMachine()
        machine.seed(customKey)

        val generation = machine.begin(otherKey)
        assertEquals(
            AppearanceSwapMachine.Decision.ROLLBACK,
            machine.settle(generation, candidateReady = false)
        )
        assertEquals(customKey, machine.settledKey())
        assertNull(machine.pendingKey())
    }

    @Test
    fun `a failed swap back to the default appearance keeps the custom instance settled`() {
        val machine = AppearanceSwapMachine()
        machine.seed(customKey)

        val generation = machine.begin(defaultKey)
        assertEquals(
            AppearanceSwapMachine.Decision.ROLLBACK,
            machine.settle(generation, candidateReady = false)
        )
        assertEquals(customKey, machine.settledKey())
    }

    @Test
    fun `a superseded candidate is discarded even when it proves itself live`() {
        // Apply A, then quickly Apply B: A's late "I am playing" report
        // must never flip the session to A.
        val machine = AppearanceSwapMachine()
        machine.seed(defaultKey)

        val generationA = machine.begin(customKey)
        val generationB = machine.begin(otherKey)

        assertEquals(
            AppearanceSwapMachine.Decision.DISCARD,
            machine.settle(generationA, candidateReady = true)
        )
        assertEquals(defaultKey, machine.settledKey())

        assertEquals(
            AppearanceSwapMachine.Decision.FLIP,
            machine.settle(generationB, candidateReady = true)
        )
        assertEquals(otherKey, machine.settledKey())
    }

    @Test
    fun `a superseded candidate failure is discarded, not rolled back`() {
        val machine = AppearanceSwapMachine()
        machine.seed(defaultKey)

        val generationA = machine.begin(customKey)
        machine.begin(otherKey)

        assertEquals(
            AppearanceSwapMachine.Decision.DISCARD,
            machine.settle(generationA, candidateReady = false)
        )
        // The newer swap is still pending and owns the session.
        assertEquals(otherKey, machine.pendingKey())
    }

    @Test
    fun `settling an unknown or already settled generation is discarded`() {
        val machine = AppearanceSwapMachine()
        machine.seed(defaultKey)

        assertEquals(
            AppearanceSwapMachine.Decision.DISCARD,
            machine.settle(999L, candidateReady = true)
        )
        val generation = machine.begin(customKey)
        assertEquals(
            AppearanceSwapMachine.Decision.FLIP,
            machine.settle(generation, candidateReady = true)
        )
        // A duplicate settle of the same generation (a second latch path
        // reporting late) must not flip or roll anything back.
        assertEquals(
            AppearanceSwapMachine.Decision.DISCARD,
            machine.settle(generation, candidateReady = false)
        )
        assertEquals(customKey, machine.settledKey())
    }

    @Test
    fun `repeated applies walk the settled key deterministically`() {
        // His sequence: switch track, Apply, Apply again with another
        // change, Apply back to defaults — each proven candidate flips,
        // each failure would have left the previous key in place.
        val machine = AppearanceSwapMachine()
        machine.seed(defaultKey)

        listOf(customKey, otherKey, defaultKey, customKey).forEach { target ->
            val generation = machine.begin(target)
            assertEquals(
                AppearanceSwapMachine.Decision.FLIP,
                machine.settle(generation, candidateReady = true)
            )
            assertEquals(target, machine.settledKey())
        }
    }
}
