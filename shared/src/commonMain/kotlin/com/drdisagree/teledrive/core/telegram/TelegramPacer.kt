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
     * a separate conservative one-per-second lane. Server FLOOD_WAIT responses still pause every
     * Telegram mutation through the shared account-wide deadline.
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

    private suspend fun awaitEditClearance() {
        editMutex.withLock {
            while (true) {
                val now = System.currentTimeMillis()
                val floodWait = (floodUntil - now).coerceAtLeast(0L)
                val intervalWait = (EDIT_INTERVAL_MS - (now - lastEditAt)).coerceAtLeast(0L)
                val wait = maxOf(floodWait, intervalWait)
                if (wait == 0L) {
                    lastEditAt = System.currentTimeMillis()
                    break
                }
                delay(wait.milliseconds)
            }
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
        const val EDIT_INTERVAL_MS = 1_000L
    }
}
