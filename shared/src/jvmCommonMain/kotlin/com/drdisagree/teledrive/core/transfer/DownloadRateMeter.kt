package com.drdisagree.teledrive.core.transfer

/**
 * Honest download speed for ONE execution of ONE transfer (R3 item P7;
 * the meter design is the Android vc184 A2 standard, ported to the shared
 * executor so the PC download paths measure the same way).
 *
 * Why the old figure was fake: the PC download paths fed TDLib's per-file
 * `downloadedSize` counter into the 1-second windowed [ProgressTicker].
 * That counter is cumulative for the FILE, not for this execution: when an
 * execution attaches, TDLib first reports/replays the bytes fetched before
 * the attach (earlier executions, previews/streams of the same file, its
 * own persistent download list). The ticker seeded itself from only the
 * FIRST event, so a replay that arrived over the following events — tens
 * of megabytes inside the first window — was divided by ~1 s and displayed
 * as a sustained ~34 MB/s. Four downloads started together attached and
 * replayed together, so all four rows showed the IDENTICAL figure rising
 * in lockstep, while the rows' percentages (the same counter, read
 * absolutely) moved at the real ~2 MB/s total. The round-2 harness never
 * drove this seam: it wrote speeds through the DAO directly, so "a number
 * is produced" passed while the number's provenance was never measured.
 *
 * This meter therefore never converts attach-time counter movement into
 * a speed:
 *  - a settle window ([SETTLE_MS]) after [start] keeps re-basing the
 *    meter to the latest counter value, so the attach replay is excluded
 *    by construction, however many events it arrives over;
 *  - after the settle window the published speed is the cumulative
 *    average of the bytes that moved after the settle baseline over the
 *    real elapsed time since that baseline — it can never exceed the
 *    true average rate of this execution, it is computed from THIS row's
 *    own counter only (concurrent rows cannot share a figure), and it
 *    decays toward zero when bytes stop moving;
 *  - a speed is published at most once per [PUBLISH_MS], the same write
 *    cadence the P3 persistence bound established.
 *
 * One meter per execution per row: instances share no state.
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
     * The speed to publish for a progress event carrying [bytes] at [now],
     * or null when this event should not produce a progress write at all
     * (still inside the settle window, or inside the publish cadence).
     */
    fun speedAt(bytes: Long, now: Long): Long? {
        if (!settled) {
            // Re-base on every event inside the settle window: whatever
            // the counter does here is the attach replay, not this
            // execution's throughput. The last pre-settle sample becomes
            // the baseline.
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
