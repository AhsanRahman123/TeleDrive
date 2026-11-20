package com.drdisagree.teledrive.desktop.media.player

/**
 * Decision logic for an appearance instance swap (PC-19, report 20).
 *
 * Subtitle appearance is LibVLC **instance** configuration: it can only be
 * delivered by building a new LibVLC instance (the per-media-option route
 * was proven inert on the user's PC in 1.6.3, and libVLC exposes no live
 * variable API — see report 20's research). Applying an appearance
 * therefore swaps the native player behind the stable [PlayerSession]
 * facade. This machine decides, for every candidate, whether it may
 * become the live player:
 *
 * - A candidate is built and started **without touching the live player**.
 * - Only when the candidate reports it is playing (or paused, for a
 *   start-paused start) may the session [Decision.FLIP] to it.
 * - If the candidate never starts (error, timeout, factory failure), the
 *   decision is [Decision.ROLLBACK]: the candidate is discarded and the
 *   live player keeps the session, untouched.
 * - A newer Apply supersedes an in-flight one: the older candidate's
 *   settle is answered [Decision.DISCARD] whatever its outcome, so a
 *   stale candidate can never flip in over a newer request.
 *
 * The machine is deliberately free of vlcj types so the whole decision
 * table — including failure injection (a candidate that never plays) —
 * is unit-testable without a native VLC instance. Keys are the exact
 * LibVLC factory-argument lists the instances are built with, so "the
 * appearance changed" and "the instance config changed" are the same
 * question, asked once.
 */
internal class AppearanceSwapMachine {

    enum class Decision { FLIP, ROLLBACK, DISCARD }

    private var nextGeneration = 0L
    private var pendingGeneration = -1L
    private var pendingKey: List<String> = emptyList()
    private var settledKey: List<String> = emptyList()

    /** The factory-argument list the live player was built with. */
    @Synchronized
    fun settledKey(): List<String> = settledKey

    /** The factory-argument list a swap is currently building, if any. */
    @Synchronized
    fun pendingKey(): List<String>? =
        if (pendingGeneration >= 0L) pendingKey else null

    /** Records the argument list the initial player was built with. */
    @Synchronized
    fun seed(key: List<String>) {
        settledKey = key
    }

    /**
     * A new swap starts for [key]. Any previously pending swap is
     * superseded: its generation will never match again, so its settle
     * is answered [Decision.DISCARD].
     */
    @Synchronized
    fun begin(key: List<String>): Long {
        pendingGeneration = ++nextGeneration
        pendingKey = key
        return pendingGeneration
    }

    /**
     * A flip that settled (the candidate became the live player) had to
     * be undone because the candidate then failed to start the session's
     * real media (PC-27 flip-then-start): the previous player owns the
     * session again, so [key] — the argument list IT was built with — is
     * the settled one once more. Without this, the machine would believe
     * the new appearance is live while the old instance is rendering,
     * and a repeat Apply of the same appearance would be swallowed as a
     * no-op against a key no live instance actually has.
     */
    @Synchronized
    fun revertSettled(key: List<String>) {
        settledKey = key
    }

    /**
     * The candidate for [generation] has either proven itself live
     * ([candidateReady] = it reported playing/paused) or failed (error,
     * timeout, factory failure). Only the currently pending generation
     * can flip or roll back; anything else is discarded.
     */
    @Synchronized
    fun settle(generation: Long, candidateReady: Boolean): Decision {
        if (generation != pendingGeneration) return Decision.DISCARD
        val key = pendingKey
        pendingGeneration = -1L
        pendingKey = emptyList()
        return if (candidateReady) {
            settledKey = key
            Decision.FLIP
        } else {
            Decision.ROLLBACK
        }
    }
}
