package com.drdisagree.teledrive.desktop.media.player

/**
 * Which candidate players an appearance swap creates, and which of them
 * may flip into the session (PC-28).
 *
 * The flip player is NEVER the proof player. The proof player plays the
 * tiny proof media and is stopped and retired; the flip player is
 * created from the same candidate instance but stays virgin — the real
 * media is the first thing it ever plays. That separation is the fix
 * for the 1.6.8 regression: there, one player did both jobs, so the
 * proof's stop() queued a Stopped event on the very player that then
 * had to start the real media, and the start latch — which reads
 * Stopped as failure — could consume that stale event and roll the
 * swap back silently. Every Apply looked like nothing had happened.
 * With distinct players the stale event's event manager is a different
 * object's: it cannot reach the flip player's latch at all, and the
 * flip player's start verdict is exactly the real start's verdict.
 *
 * A finished session needs no proof (nothing is playing to protect),
 * so no proof player is created for it.
 */
internal class SwapPlayers<P>(
    /** Plays the proof media and is retired; null when no proof is needed. */
    val proofPlayer: P?,
    /** Virgin until the flip: the real media is its first playback. */
    val flipPlayer: P
)

/**
 * Creates the swap's players through [create]: two distinct players
 * when a proof is needed (local and streamed sessions alike), one when
 * it is not. The factory behind [create] is the candidate instance, so
 * both players carry the candidate appearance by construction.
 */
internal fun <P> assignSwapPlayers(needsProof: Boolean, create: () -> P): SwapPlayers<P> {
    val flipPlayer = create()
    val proofPlayer = if (needsProof) create() else null
    return SwapPlayers(proofPlayer = proofPlayer, flipPlayer = flipPlayer)
}
