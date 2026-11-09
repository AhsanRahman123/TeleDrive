package com.drdisagree.teledrive.core.transfer

/**
 * Measures bytes moved during a fixed window. A window with no new bytes reports zero
 * immediately; old throughput is never carried forward as if the transfer were still moving.
 */
class ProgressTicker(
    private val intervalMs: Long = INTERVAL_MS
) {

    private var lastBytes = 0L
    private var lastTick = 0L

    fun start(bytes: Long, now: Long) {
        lastBytes = bytes
        lastTick = now
    }

    fun tick(bytes: Long, now: Long): Long? {
        val elapsed = now - lastTick
        if (elapsed < intervalMs) return null

        val moved = bytes - lastBytes
        val measured = if (moved > 0L && elapsed > 0L) {
            (moved.toDouble() * 1_000.0 / elapsed.toDouble())
                .coerceAtMost(Long.MAX_VALUE.toDouble())
                .toLong()
        } else {
            0L
        }

        // Always advance the baseline after a completed window. This also safely re-seeds
        // the meter if TDLib resets its local downloaded-byte counter after a retry.
        lastBytes = bytes
        lastTick = now
        return measured
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
    }
}
