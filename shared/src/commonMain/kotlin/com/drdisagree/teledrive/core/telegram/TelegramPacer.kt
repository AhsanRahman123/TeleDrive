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

    suspend fun <T> paced(block: suspend () -> T): T {
        awaitClearance()
        return try {
            block()
        } catch (e: TelegramException) {
            if (e.isRateLimit) {
                recordFloodWait(e.retryAfterSeconds ?: FALLBACK_SECONDS)
            }
            throw e
        }
    }

    private suspend fun awaitClearance() {
        while (true) {
            val remaining = floodUntil - System.currentTimeMillis()
            if (remaining > 0) {
                delay(remaining.milliseconds)
                continue
            }
            val wait = mutex.withLock { takeToken() }
            if (wait <= 0) return
            delay(wait.milliseconds)
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
    suspend fun <T> pacedEdit(block: suspend () -> T): T {
        awaitEditClearance()
        return try {
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
    private suspend fun awaitEditClearance() {
        while (true) {
            val waitMs = editMutex.withLock {
                val now = System.currentTimeMillis()
                val floodWait = (floodUntil - now).coerceAtLeast(0L)
                val intervalWait = (EDIT_INTERVAL_MS - (now - lastEditAt)).coerceAtLeast(0L)
                val wait = maxOf(floodWait, intervalWait)
                if (wait == 0L) lastEditAt = now
                wait
            }
            if (waitMs == 0L) return
            delay(waitMs.milliseconds)
        }
    }

    fun recordFloodWait(seconds: Int) {
        val until = System.currentTimeMillis() + (seconds + 1) * 1000L
        if (until > floodUntil) {
            floodUntil = until
            SafeLog.w(TAG, "Flood wait for ${seconds}s, holding every request")
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
    }
}
