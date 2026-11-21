package com.drdisagree.teledrive.core.telegram

import com.drdisagree.teledrive.core.common.SafeLog
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.milliseconds

/**
 * A flood wait applies to the whole account, so every message-creating call waits here,
 * not just the one that hit it.
 */
class TelegramPacer {

    private val mutex = Mutex()
    private val editMutex = Mutex()
    private var lastEditAt = 0L
    private var tokens = BURST.toDouble()
    private var lastRefillAt = System.currentTimeMillis()

    @Volatile
    private var floodUntil = 0L

    val floodWaitSeconds: Int
        get() = ((floodUntil - System.currentTimeMillis()) / 1000L)
            .coerceAtLeast(0L)
            .toInt()

    suspend fun <T> paced(
        block: suspend () -> T,
        onWaiting: suspend (secondsLeft: Int) -> Unit = {}
    ): T {
        return try {
            awaitClearance(onWaiting)
            block()
        } catch (e: TelegramException) {
            if (e.isRateLimit) {
                recordFloodWait(e.retryAfterSeconds ?: FALLBACK_SECONDS)
            }
            throw e
        }
    }

    private suspend fun awaitClearance(onWaiting: suspend (secondsLeft: Int) -> Unit) {
        while (true) {
            val remaining = floodUntil - System.currentTimeMillis()
            if (remaining > 0) {
                awaitFloodWindow(onWaiting)
                continue
            }
            val wait = mutex.withLock { takeToken() }
            if (wait <= 0) return
            delay(wait.milliseconds)
        }
    }

    /**
     * Sleeps out the account-wide flood window in slices of at most
     * [FLOOD_TICK_MS], reporting the seconds left through [onWaiting]
     * on every slice (PC-36).
     *
     * Before PC-36 both clearance lanes slept the ENTIRE remaining
     * window in one uncapped `delay`, before any request was sent:
     * no [TelegramException] ever reached the caller, no callback
     * fired, and nothing was logged at park time, so a drain that
     * started against a pre-existing flood deadline froze the publish
     * banner at "0 of N" with a dead Sync now — the PC-35 surfaces
     * (countdown, wait-cut, budgets) all hang off a rate-limit result
     * the park prevented from existing. Now the wait is visible from
     * the first second.
     *
     * One call still never sleeps more than [MAX_FLOOD_SLEEP_MS] in
     * total: past that it throws a 429 carrying the seconds remaining,
     * and the paced catch re-arms the deadline from that remainder.
     * The long tail of the wait then belongs to the caller's own
     * retry machinery — the publish drainer's bounded budgets, a
     * transfer's retry policy — where it is visible and cancellable,
     * instead of an invisible park inside the pacer. The slices are
     * plain coroutine delays, so cancellation lands within one slice.
     */
    private suspend fun awaitFloodWindow(onWaiting: suspend (secondsLeft: Int) -> Unit) {
        var sleptMs = 0L
        var announced = false
        while (true) {
            val leftMs = floodUntil - System.currentTimeMillis()
            if (leftMs <= 0) return
            val leftSeconds = ((leftMs + 999L) / 1000L).toInt()
            if (!announced) {
                SafeLog.w(
                    TAG,
                    "Flood wait in effect: ${leftSeconds}s left; " +
                        "holding this request until it passes"
                )
                announced = true
            }
            onWaiting(leftSeconds)
            if (sleptMs >= MAX_FLOOD_SLEEP_MS) {
                throw TelegramException(
                    429,
                    "FLOOD_WAIT: retry after $leftSeconds",
                    leftSeconds
                )
            }
            val stepMs = minOf(FLOOD_TICK_MS, leftMs, MAX_FLOOD_SLEEP_MS - sleptMs)
            delay(stepMs.milliseconds)
            sleptMs += stepMs
        }
    }

    private fun takeToken(): Long {
        val now = System.currentTimeMillis()
        val elapsed = (now - lastRefillAt).coerceAtLeast(0)
        lastRefillAt = now
        tokens = (tokens + elapsed * TOKENS_PER_MS).coerceAtMost(BURST.toDouble())
        if (tokens >= 1.0) {
            tokens -= 1.0
            return 0
        }
        return ((1.0 - tokens) / TOKENS_PER_MS).toLong().coerceAtLeast(MIN_WAIT_MS)
    }

    /**
     * Caption edits update existing Telegram messages rather than creating new ones, so they use
     * a separate lane whose starts are spaced [EDIT_INTERVAL_MS] apart — up to four edits a
     * second, matching the publish outbox's four in-flight publishes. Server FLOOD_WAIT
     * responses still pause every Telegram mutation through the shared account-wide deadline.
     */
    suspend fun <T> pacedEdit(
        block: suspend () -> T,
        onWaiting: suspend (secondsLeft: Int) -> Unit = {}
    ): T {
        return try {
            awaitEditClearance(onWaiting)
            block()
        } catch (e: TelegramException) {
            if (e.isRateLimit) recordFloodWait(e.retryAfterSeconds ?: FALLBACK_SECONDS)
            throw e
        }
    }

    /**
     * The wait is computed under the lock but slept OUTSIDE it (PC-35).
     * This used to delay inside [editMutex], so one caller's flood
     * sleep serialised every other caller behind the mutex for the
     * whole flood window — a publisher could sit in this sleep while
     * the other publish slots queued behind it before a single edit
     * was attempted. Now each waiter sleeps in parallel and re-checks
     * the shared deadline afterwards (so a flood that is extended —
     * or expires — while sleeping is honoured promptly), and edit
     * starts stay spaced [EDIT_INTERVAL_MS] apart because the start
     * stamp is still taken under the lock.
     */
    private suspend fun awaitEditClearance(onWaiting: suspend (secondsLeft: Int) -> Unit) {
        while (true) {
            // PC-36: a flood window is slept through awaitFloodWindow
            // (ticking, capped) instead of being folded into the single
            // uncapped delay below; only the sub-second edit spacing
            // still sleeps here, exactly as before.
            if (floodUntil - System.currentTimeMillis() > 0) {
                awaitFloodWindow(onWaiting)
                continue
            }
            val waitMs = editMutex.withLock {
                val now = System.currentTimeMillis()
                val intervalWait = (EDIT_INTERVAL_MS - (now - lastEditAt)).coerceAtLeast(0L)
                if (intervalWait == 0L) lastEditAt = now
                intervalWait
            }
            if (waitMs == 0L) return
            delay(waitMs.milliseconds)
        }
    }

    fun recordFloodWait(seconds: Int) {
        // PC-36: clamp the stored deadline. The retry-after is parsed
        // from TDLib's error text with no bound of its own, so one
        // absurd value could otherwise set a multi-hour account-wide
        // deadline that every paced caller then honours. Past the
        // clamp the calls simply go out, meet the real remaining
        // limit, and record it again.
        val effective = seconds.coerceAtMost(MAX_RECORDED_FLOOD_SECONDS)
        if (effective < seconds) {
            SafeLog.w(TAG, "Clamping a ${seconds}s flood wait to ${effective}s")
        }
        val until = System.currentTimeMillis() + (effective + 1) * 1000L
        if (until > floodUntil) {
            floodUntil = until
            SafeLog.w(TAG, "Flood wait for ${effective}s, holding every request")
        }
    }

    private companion object {
        const val TAG = "TelegramPacer"
        const val PER_MINUTE = 20
        const val BURST = 5
        const val TOKENS_PER_MS = PER_MINUTE / 60_000.0
        const val MIN_WAIT_MS = 50L
        const val FALLBACK_SECONDS = 5
        const val EDIT_INTERVAL_MS = 250L
        const val FLOOD_TICK_MS = 1_000L
        const val MAX_FLOOD_SLEEP_MS = 30_000L
        const val MAX_RECORDED_FLOOD_SECONDS = 900
    }
}
