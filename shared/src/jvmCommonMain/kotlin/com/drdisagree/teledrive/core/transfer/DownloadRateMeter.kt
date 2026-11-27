package com.drdisagree.teledrive.core.transfer

/**
 * Honest download speed for one execution of one transfer (vc184 A2).
 *
 * The byte counter behind a download is TDLib's per-file `downloadedSize`. That
 * counter is NOT this transfer's own throughput: when an execution attaches,
 * TDLib first replays the file's current state — bytes fetched before the
 * attach, by an earlier execution, by a preview/stream of the same file, or by
 * TDLib's own persistent download list. The old windowed ticker divided
 * whatever the counter did in a one-second window by that window, so a replay
 * jump of tens of megabytes inside one window displayed as a sustained
 * 23–37 MB/s on a connection moving ~100 B/s, and the row's percentage jumped
 * by the replayed amount as if it had just been downloaded.
 *
 * This meter therefore never converts attach-time counter movement into a
 * speed:
 *  - a settle window ([SETTLE_MS]) after [start] keeps re-basing the meter to
 *    the latest counter value, so the attach replay is excluded by
 *    construction;
 *  - after the settle window the published speed is the cumulative average of
 *    the bytes that moved after the settle baseline over the real elapsed
 *    time since that baseline — it can never exceed the true average rate of
 *    this execution, and it decays toward zero when bytes stop moving;
 *  - a speed is published at most once per [PUBLISH_MS], the same write
 *    cadence the P3 persistence bound established.
 */
class DownloadRateMeter {

    private var attachAt = 0L
    private var baseBytes = 0L
    private var baseAt = 0L
    private var settled = false
    private var lastPublishAt = 0L

    fun start(bytes: Long, now: Long) {
        attachAt = now
        baseBytes = bytes
        baseAt = now
        settled = false
        lastPublishAt = 0L
    }

    /**
     * The speed to publish for a progress event carrying [bytes] at [now], or
     * null when this event should not produce a progress write at all (still
     * inside the settle window, or inside the publish cadence).
     */
    fun speedAt(bytes: Long, now: Long): Long? {
        if (!settled) {
            // Re-base on every event inside the settle window: whatever the
            // counter does here is the attach replay, not this execution's
            // throughput. The last pre-settle sample becomes the baseline.
            baseBytes = bytes
            baseAt = now
            if (now - attachAt < SETTLE_MS) return null
            settled = true
            lastPublishAt = now
            return 0L
        }
        if (now - lastPublishAt < PUBLISH_MS) return null
        lastPublishAt = now
        val elapsed = now - baseAt
        if (elapsed <= 0L) return 0L
        val moved = (bytes - baseBytes).coerceAtLeast(0L)
        return moved * 1_000L / elapsed
    }

    private companion object {
        const val SETTLE_MS = 1_000L
        const val PUBLISH_MS = 1_000L
    }
}
