package com.drdisagree.teledrive.core.common

import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Round 11: one-line-per-phase startup timing marks, logged under the
 * `D/Startup` tag through [SafeLog] (so they are debug-gated exactly like
 * the other `D/` lines and land in teledrive.log). Each mark carries the
 * elapsed milliseconds since [markMainEntry], so a single launch's log
 * assigns every millisecond of the startup window to a named phase:
 * main entry, Koin ready, DB open, the deferred startup-block steps,
 * TDLib create/auth, VLC warm-up, window shown, Home first data.
 *
 * Marks are timing only — calling them never changes behaviour.
 */
object StartupTrace {

    private val timeSource = TimeSource.Monotonic
    private var startMark: TimeMark? = null
    private val onceKeys = mutableSetOf<String>()

    /** Anchors the clock; call as the first statement of `main()`. */
    fun markMainEntry() {
        synchronized(this) {
            if (startMark == null) startMark = timeSource.markNow()
        }
        mark("main_entry")
    }

    fun mark(phase: String) {
        SafeLog.d(TAG, "$phase elapsed=${elapsedMs()}ms")
    }

    /** Like [mark], but only the first call for a given [phase] logs. */
    fun markOnce(phase: String) {
        synchronized(this) {
            if (!onceKeys.add(phase)) return
        }
        mark(phase)
    }

    fun elapsedMs(): Long {
        val start = synchronized(this) { startMark } ?: return -1L
        return start.elapsedNow().inWholeMilliseconds
    }

    private const val TAG = "Startup"
}
