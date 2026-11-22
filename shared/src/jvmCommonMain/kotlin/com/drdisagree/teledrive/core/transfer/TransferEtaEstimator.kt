package com.drdisagree.teledrive.core.transfer

import kotlin.math.ceil
import kotlin.math.max

/**
 * PC-39 (1.6.17): estimated time remaining for one transfer direction,
 * computed from a MEASURED rolling rate — never from the instantaneous
 * speed. The field reality is 0 B/s between small files, so an
 * instant-speed ETA would flicker between infinity and nonsense.
 *
 * The feed is cumulative completed work (files + bytes, differenced from
 * the transfers table by SQL aggregate), sampled about once a second by
 * the caller. The rate is the work completed inside a trailing window
 * ([WINDOW_MS]) divided by the window's actual span:
 *
 * - Bytes AND files both produce an estimate and the larger wins. For
 *   big files the byte rate binds; for thousands of tiny files the byte
 *   rate alone would claim the queue is nearly free (the bytes are
 *   trivial — the per-file cost is the queue), while the file rate
 *   measures exactly that cost.
 * - A stall decays honestly: flat snapshots keep arriving, old progress
 *   slides out of the window, the measured rate falls, and once the
 *   window holds no progress at all the answer becomes
 *   [EtaEstimate.Estimating] instead of an absurd number.
 * - Until the window holds enough measured progress (span at least
 *   [MIN_SPAN_MS] and at least one completion), the answer is also
 *   [EtaEstimate.Estimating] — the UI shows "estimating…", not a guess.
 * - Nothing to do, or nothing actually running (paused / idle), is
 *   [EtaEstimate.Hidden]: the ETA disappears rather than freezing.
 *
 * Because the rate is measured, the estimate automatically reflects
 * whatever concurrency the user has dialled in — nothing here knows or
 * predicts from the setting.
 *
 * Not thread-safe by design: one estimator per direction, driven from a
 * single flow collector.
 */
class TransferEtaEstimator {

    private data class Snapshot(
        val atMs: Long,
        val completedFiles: Long,
        val completedBytes: Long
    )

    private val snapshots = ArrayDeque<Snapshot>()

    /**
     * Records the direction's cumulative completed totals at [atMs].
     * A long gap (UI unsubscribed, machine asleep) or a totals DROP
     * (finished list cleared, counters restarted) re-baselines the
     * window instead of letting stale samples poison the rate.
     */
    fun onSnapshot(atMs: Long, completedFiles: Long, completedBytes: Long) {
        val last = snapshots.lastOrNull()
        if (last != null) {
            if (atMs <= last.atMs) return
            if (atMs - last.atMs > GAP_RESET_MS ||
                completedFiles < last.completedFiles ||
                completedBytes < last.completedBytes
            ) {
                snapshots.clear()
            }
        }
        snapshots.addLast(Snapshot(atMs, completedFiles, completedBytes))
        prune(atMs)
    }

    fun estimate(
        nowMs: Long,
        remainingFiles: Long,
        remainingBytes: Long,
        running: Long
    ): EtaEstimate {
        if (remainingFiles <= 0L || running <= 0L) return EtaEstimate.Hidden
        prune(nowMs)
        val first = snapshots.firstOrNull() ?: return EtaEstimate.Estimating
        val last = snapshots.lastOrNull() ?: return EtaEstimate.Estimating
        val spanMs = last.atMs - first.atMs
        if (spanMs < MIN_SPAN_MS) return EtaEstimate.Estimating

        val doneFiles = last.completedFiles - first.completedFiles
        val doneBytes = last.completedBytes - first.completedBytes
        if (doneFiles <= 0L && doneBytes <= 0L) return EtaEstimate.Estimating

        val spanSeconds = spanMs / 1000.0
        var etaSeconds = 0.0
        if (remainingBytes > 0L) {
            // Bytes remain but no byte ever completed in the window: the
            // byte rate is unmeasurable, so say so instead of dividing
            // by a file rate that knows nothing about byte volume.
            if (doneBytes <= 0L) return EtaEstimate.Estimating
            etaSeconds = max(etaSeconds, remainingBytes / (doneBytes / spanSeconds))
        }
        if (doneFiles > 0L) {
            etaSeconds = max(etaSeconds, remainingFiles / (doneFiles / spanSeconds))
        }
        return EtaEstimate.Ready(ceil(etaSeconds).toLong().coerceAtLeast(0L))
    }

    private fun prune(nowMs: Long) {
        val cutoff = nowMs - WINDOW_MS
        while (snapshots.size > 1 && snapshots.first().atMs < cutoff) {
            snapshots.removeFirst()
        }
    }

    companion object {
        const val WINDOW_MS = 180_000L
        const val MIN_SPAN_MS = 3_000L
        const val GAP_RESET_MS = 10_000L
    }
}

/** What the UI may show for one direction (or the combined queue). */
sealed interface EtaEstimate {
    /** No work running: show nothing. */
    object Hidden : EtaEstimate

    /** Work is running but the measured rate is not trustworthy yet. */
    object Estimating : EtaEstimate

    /** A measured estimate, in seconds. */
    data class Ready(val seconds: Long) : EtaEstimate
}

/**
 * The overall ETA of two directions running in PARALLEL pools: the queue
 * is done when the slower direction is done, so the overall estimate is
 * the MAX of the two — never the sum. A direction with no work imposes
 * no constraint; a working direction that is still estimating makes the
 * overall estimate unknown too.
 */
fun overallEta(upload: EtaEstimate, download: EtaEstimate): EtaEstimate {
    val active = listOf(upload, download).filter { it != EtaEstimate.Hidden }
    if (active.isEmpty()) return EtaEstimate.Hidden
    if (active.any { it == EtaEstimate.Estimating }) return EtaEstimate.Estimating
    return EtaEstimate.Ready(active.maxOf { (it as EtaEstimate.Ready).seconds })
}

/**
 * The ETA's display text, or null when it should not be shown at all.
 * Always labelled an estimate; under a minute collapses to "<1 min".
 */
fun formatEta(estimate: EtaEstimate): String? = when (estimate) {
    EtaEstimate.Hidden -> null
    EtaEstimate.Estimating -> "estimating…"
    is EtaEstimate.Ready -> formatEtaSeconds(estimate.seconds)
}

fun formatEtaSeconds(totalSeconds: Long): String {
    if (totalSeconds < 60L) return "<1 min left"
    val totalMinutes = (totalSeconds + 59L) / 60L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "~$hours h $minutes min left"
        hours > 0L -> "~$hours h left"
        else -> "~$totalMinutes min left"
    }
}
