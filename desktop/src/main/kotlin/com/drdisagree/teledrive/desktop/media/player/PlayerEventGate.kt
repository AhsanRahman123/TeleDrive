package com.drdisagree.teledrive.desktop.media.player

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Decides which VLC player events may touch the viewer's Compose state.
 *
 * One gate lives for the whole media viewer (it is NOT recreated when the
 * player is). Two failure modes shaped this design, both from the subtitle
 * appearance Apply flow, which recreates the player from a new LibVLC
 * factory while the viewer stays on screen:
 *
 * 1. **Stale-player events.** The retired player is stopped and released
 *    asynchronously on vlcj's serial executor, so its event listener can
 *    still fire while the new player is already live. When the guard
 *    atomics were recreated together with each player, the retired listener
 *    checked its OWN stale guards — which its own teardown had left in a
 *    passing state for some events — and wrote the shared Compose state
 *    (`finished`, `playing`, position) of the new player. The gate therefore
 *    keys every event on the identity of the emitting player: only the most
 *    recently [attach]ed player's events are live, whoever fires later.
 *
 * 2. **The start-paused gate.** A player restarted paused (Apply while
 *    paused) is started with `:start-paused`; libVLC then reports the media
 *    through the paused state, and if the event gate only opens in the
 *    `playing` callback, `timeChanged` / `lengthChanged` / `paused` from
 *    the LIVE player are dropped forever — duration stays 0, the seek bar
 *    is dead and the restart seek never runs. The viewer opens the gate
 *    from the `paused` callback too, under the same identity + generation
 *    checks, so a start-paused player's events flow exactly like a playing
 *    one's.
 *
 * [detach] is deliberately conditional: on a player swap the new player is
 * attached during composition, BEFORE the old player's disposal runs, so a
 * detach that unconditionally cleared the flags could close the frame gate
 * the new player's start task had just opened. Detaching a player that is
 * no longer current is a no-op; the new player's [beginMedia] already reset
 * the flags for its own load.
 *
 * The player type is a parameter so the decision logic is unit-testable
 * without a native VLC instance.
 */
internal class PlayerEventGate<Player : Any> {

    private val currentPlayer = AtomicReference<Player?>(null)
    private val acceptsEvents = AtomicBoolean(false)
    private val acceptsFrames = AtomicBoolean(false)
    private val frameGeneration = AtomicLong(0L)
    private val expectedGeneration = AtomicLong(-1L)

    /** [player] was just created; from now on only its events are live. */
    fun attach(player: Player) {
        currentPlayer.set(player)
    }

    /**
     * [player] is being torn down. Clears the gates only when it is still
     * the current player; a newer player owns the gates by then.
     */
    fun detach(player: Player) {
        if (currentPlayer.compareAndSet(player, null)) {
            acceptsEvents.set(false)
            acceptsFrames.set(false)
            expectedGeneration.set(-1L)
        }
    }

    /** True when [player] is the live player instance. */
    fun isCurrent(player: Player): Boolean = currentPlayer.get() === player

    /**
     * A new media load starts on the current player: close both gates and
     * mint the generation that tags this load's frames and events.
     */
    fun beginMedia(): Long {
        acceptsEvents.set(false)
        acceptsFrames.set(false)
        expectedGeneration.set(-1L)
        return frameGeneration.incrementAndGet()
    }

    fun generation(): Long = frameGeneration.get()

    /** The in-flight start task has stopped the outgoing source; events for [generation] may open the gate. */
    fun expectGeneration(generation: Long) {
        expectedGeneration.set(generation)
    }

    fun clearExpected(generation: Long) {
        expectedGeneration.compareAndSet(generation, -1L)
    }

    /** True when events arriving now belong to the load [beginMedia] minted. */
    fun isExpectedGeneration(): Boolean = expectedGeneration.get() == frameGeneration.get()

    fun setAcceptsFrames(value: Boolean) {
        acceptsFrames.set(value)
    }

    fun acceptsFrames(): Boolean = acceptsFrames.get()

    /** The live player reported playing (or paused, for a start-paused load): its events may flow. */
    fun openEvents() {
        acceptsEvents.set(true)
    }

    /** Close the event gate; true when it was open, so end/error handling runs exactly once. */
    fun closeEvents(): Boolean = acceptsEvents.getAndSet(false)

    fun eventsOpen(): Boolean = acceptsEvents.get()
}
