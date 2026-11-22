package com.drdisagree.teledrive.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-39 ETA estimator: every scenario is driven by synthetic timestamps,
 * so the window math is asserted exactly — no sleeping, no flakiness.
 * Snapshots mimic the store's ~1 Hz sampling of cumulative completed
 * totals.
 */
class TransferEtaEstimatorTest {

    private val mb = 1_000_000L

    /** Feeds [seconds] of snapshots at 1 Hz, completing [bytesPerSecond] throughout. */
    private fun feedSteady(
        estimator: TransferEtaEstimator,
        fromSecond: Int,
        seconds: Int,
        bytesPerSecond: Long,
        filesPerSecond: Long,
        startFiles: Long = 0,
        startBytes: Long = 0
    ) {
        for (s in 0..seconds) {
            estimator.onSnapshot(
                atMs = (fromSecond + s) * 1_000L,
                completedFiles = startFiles + filesPerSecond * s,
                completedBytes = startBytes + bytesPerSecond * s
            )
        }
    }

    @Test
    fun `steady rate gives a steady estimate`() {
        val estimator = TransferEtaEstimator()
        // 10 seconds at 2 MB/s and 2 files/s.
        feedSteady(estimator, 0, 10, bytesPerSecond = 2 * mb, filesPerSecond = 2)

        val eta = estimator.estimate(
            nowMs = 10_000,
            remainingFiles = 20,
            remainingBytes = 20 * mb,
            running = 2
        )

        // Both rates agree: 20 MB at 2 MB/s = 10 s; 20 files at 2/s = 10 s.
        assertEquals(EtaEstimate.Ready(10), eta)
    }

    @Test
    fun `before enough measured progress the answer is estimating`() {
        val estimator = TransferEtaEstimator()
        estimator.onSnapshot(0, 0, 0)
        estimator.onSnapshot(1_000, 1, mb)

        // Span is only one second: too early to trust any rate.
        assertEquals(
            EtaEstimate.Estimating,
            estimator.estimate(nowMs = 1_000, remainingFiles = 5, remainingBytes = 5 * mb, running = 1)
        )
    }

    @Test
    fun `bursty small files with zero speed gaps still produce a sane estimate`() {
        val estimator = TransferEtaEstimator()
        // The field pattern: a burst of tiny completions, a long 0 B/s
        // gap while the next files are set up, another burst. 200 KB
        // files; bursts of 10 files at t=0..2s, t=20..22s, t=40..42s.
        var files = 0L
        var bytes = 0L
        for (second in 0..42) {
            if (second % 20 <= 2) {
                files += 5
                bytes += 5 * 200_000L
            }
            estimator.onSnapshot(second * 1_000L, files, bytes)
        }

        val eta = estimator.estimate(
            nowMs = 42_000,
            remainingFiles = 100,
            remainingBytes = 100 * 200_000L,
            running = 2
        )

        // 40 files / 8 MB completed across the 42 s window span (first
        // snapshot to last): ~0.95 files/s, so 100 more files take
        // ~105 s — finite, plausible, and nowhere near the infinity an
        // instantaneous 0 B/s reading would produce mid-gap.
        assertTrue("expected Ready, was $eta", eta is EtaEstimate.Ready)
        val seconds = (eta as EtaEstimate.Ready).seconds
        assertTrue("eta $seconds should be within 85..125 s", seconds in 85..125)
    }

    @Test
    fun `a stall decays the estimate to estimating and resuming revives it`() {
        val estimator = TransferEtaEstimator()
        // 10 s of steady progress (5 MB/s), then a 200 s stall: flat
        // snapshots keep arriving, as the store's ticker guarantees.
        feedSteady(estimator, 0, 10, bytesPerSecond = 5 * mb, filesPerSecond = 5)
        for (s in 11..210) {
            estimator.onSnapshot(s * 1_000L, completedFiles = 50, completedBytes = 50 * mb)
        }

        // The whole window is now stall: no progress to measure.
        assertEquals(
            EtaEstimate.Estimating,
            estimator.estimate(
                nowMs = 210_000,
                remainingFiles = 40,
                remainingBytes = 40 * mb,
                running = 2
            )
        )

        // Work resumes at the same rate; after a few seconds the
        // estimate is back, based on the post-stall window.
        feedSteady(
            estimator, 211, 6,
            bytesPerSecond = 5 * mb, filesPerSecond = 5,
            startFiles = 50, startBytes = 50 * mb
        )
        val eta = estimator.estimate(
            nowMs = 216_000,
            remainingFiles = 40,
            remainingBytes = 40 * mb,
            running = 2
        )
        assertTrue("expected Ready after resume, was $eta", eta is EtaEstimate.Ready)
    }

    @Test
    fun `near completion collapses to under a minute`() {
        val estimator = TransferEtaEstimator()
        feedSteady(estimator, 0, 10, bytesPerSecond = mb, filesPerSecond = 1)

        val eta = estimator.estimate(
            nowMs = 10_000,
            remainingFiles = 1,
            remainingBytes = 300_000,
            running = 1
        )

        assertEquals(EtaEstimate.Ready(1), eta)
        assertEquals("<1 min left", formatEta(eta))
    }

    @Test
    fun `nothing running or nothing remaining hides the estimate`() {
        val estimator = TransferEtaEstimator()
        feedSteady(estimator, 0, 10, bytesPerSecond = mb, filesPerSecond = 1)

        assertEquals(
            EtaEstimate.Hidden,
            estimator.estimate(nowMs = 10_000, remainingFiles = 0, remainingBytes = 0, running = 0)
        )
        assertEquals(
            "queued but paused (nothing running) hides too",
            EtaEstimate.Hidden,
            estimator.estimate(nowMs = 10_000, remainingFiles = 9, remainingBytes = 9 * mb, running = 0)
        )
        assertNull(formatEta(EtaEstimate.Hidden))
    }

    @Test
    fun `a totals drop re-baselines instead of measuring a negative rate`() {
        val estimator = TransferEtaEstimator()
        feedSteady(estimator, 0, 10, bytesPerSecond = mb, filesPerSecond = 1)
        // The user clears the finished list: cumulative totals restart.
        estimator.onSnapshot(11_000, completedFiles = 0, completedBytes = 0)
        estimator.onSnapshot(12_000, completedFiles = 1, completedBytes = mb)

        // Only the post-clear samples exist (1 s span): estimating, not
        // a nonsense rate differenced across the clear.
        assertEquals(
            EtaEstimate.Estimating,
            estimator.estimate(nowMs = 12_000, remainingFiles = 5, remainingBytes = 5 * mb, running = 1)
        )
    }

    @Test
    fun `overall eta is the slower direction, never the sum`() {
        assertEquals(
            EtaEstimate.Ready(600),
            overallEta(EtaEstimate.Ready(120), EtaEstimate.Ready(600))
        )
        assertEquals(
            EtaEstimate.Ready(120),
            overallEta(EtaEstimate.Ready(120), EtaEstimate.Hidden)
        )
        assertEquals(
            EtaEstimate.Estimating,
            overallEta(EtaEstimate.Ready(120), EtaEstimate.Estimating)
        )
        assertEquals(
            EtaEstimate.Hidden,
            overallEta(EtaEstimate.Hidden, EtaEstimate.Hidden)
        )
    }

    @Test
    fun `formatting follows the agreed shapes`() {
        assertEquals("<1 min left", formatEtaSeconds(0))
        assertEquals("<1 min left", formatEtaSeconds(59))
        assertEquals("~1 min left", formatEtaSeconds(60))
        assertEquals("~2 min left", formatEtaSeconds(119))
        assertEquals("~1 h left", formatEtaSeconds(3_600))
        assertEquals("~2 h 15 min left", formatEtaSeconds(8_100))
        assertEquals("~49 h left", formatEtaSeconds(49 * 3_600L))
        assertEquals("estimating…", formatEta(EtaEstimate.Estimating))
    }
}
