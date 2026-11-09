package com.drdisagree.teledrive.core.transfer

/**
 * Averages each two-second interval and eases into the last reading, reducing short progress bursts.
 */
class ProgressTicker(
    private val intervalMs: Long = INTERVAL_MS,
    private val smoothing: Double = SMOOTHING
) {

    private var lastBytes = 0L
    private var lastTick = 0L
    private var speed = 0L

    fun start(bytes: Long, now: Long) {
        lastBytes = bytes
        lastTick = now
        speed = 0
    }

    fun tick(bytes: Long, now: Long): Long? {
        val elapsed = now - lastTick
        if (elapsed < intervalMs) return null

        val moved = bytes - lastBytes
        val measured = if (moved > 0) moved * 1000 / elapsed else 0
        speed = if (speed <= 0) {
            measured
        } else {
            (measured * smoothing + speed * (1 - smoothing)).toLong()
        }
        lastBytes = bytes
        lastTick = now
        return speed
    }

    private companion object {
        const val INTERVAL_MS = 2_000L
        const val SMOOTHING = 0.35
    }
}
