package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * PC-28: the appearance swap's player assignment. The regression being
 * pinned: in 1.6.8 ONE player both played the proof media (and was
 * stopped) and then had to start the real media, so the proof's queued
 * Stopped event could be consumed by the real start's latch as a
 * failure verdict and every Apply silently rolled back. The flip
 * player must therefore always be a distinct, virgin player; the proof
 * player is disposable.
 */
class SwapPlayersTest {

    @Test
    fun `a swap that needs proof creates two distinct players`() {
        var next = 0
        val players = assignSwapPlayers(needsProof = true) { Any().also { next++ } }

        assertEquals(2, next)
        assertNotSame(players.proofPlayer, players.flipPlayer)
    }

    @Test
    fun `a finished session creates only the flip player`() {
        var next = 0
        val players = assignSwapPlayers(needsProof = false) { Any().also { next++ } }

        assertEquals(1, next)
        assertNull(players.proofPlayer)
    }

    @Test
    fun `the flip player is created first so a proof failure never strands it`() {
        val created = mutableListOf<String>()
        val players = assignSwapPlayers(needsProof = true) {
            val name = if (created.isEmpty()) "first" else "second"
            created += name
            name
        }

        assertSame("first", players.flipPlayer)
        assertSame("second", players.proofPlayer)
    }
}
