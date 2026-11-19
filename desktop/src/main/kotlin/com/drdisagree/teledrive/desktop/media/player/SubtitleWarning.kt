package com.drdisagree.teledrive.desktop.media.player

import java.util.concurrent.ConcurrentHashMap

/**
 * Claims a key at most once per process. The player composable is recreated
 * for every preview, so any "already shown" state kept in composition would
 * reset per video; warning policy that must hold for the whole session lives
 * at process scope instead.
 */
internal class OncePerSession {
    private val claimed = ConcurrentHashMap.newKeySet<String>()

    /** True the first time [key] is claimed, false on every later claim. */
    fun claim(key: String): Boolean = claimed.add(key)
}

/**
 * Display policy for the "subtitle appearance could not be applied" warning.
 *
 * The warning is a transient heads-up, never a fixture: it may appear at
 * most once per cause per app session, and once shown it dismisses itself
 * after [DISPLAY_DURATION_MS]. An earlier build rendered it for as long as
 * the failure flag was set, which — with the failure firing on every video —
 * left it stuck on screen permanently.
 */
internal object SubtitleWarningPolicy {

    /** How long the warning stays up before it dismisses itself. */
    const val DISPLAY_DURATION_MS: Long = 6_000

    const val CAUSE_FACTORY_CREATION_FAILED: String = "subtitle-factory-creation-failed"

    private val oncePerSession = OncePerSession()

    /** True if the warning for [cause] may be shown now (first time only). */
    fun claim(cause: String): Boolean = oncePerSession.claim(cause)
}
