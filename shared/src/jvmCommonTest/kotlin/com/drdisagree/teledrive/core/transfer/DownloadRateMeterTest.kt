package com.drdisagree.teledrive.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 item N7 — ground-truth tests for the PC per-row download rate
 * meter. Each test drives the meter with a controlled byte stream at a
 * KNOWN rate (samples generated arithmetically from the rate, never from
 * the meter) and asserts the published rate ≈ that rate. The streams
 * include the field failure's ingredient — TDLib's attach replay, where
 * the per-file counter jumps by tens of megabytes the execution did not
 * download — which the old 1 s windowed ProgressTicker displayed as a
 * sustained ~34 MB/s on every simultaneously attached row.
 *
 * The meter under test is the exact component TransferExecutor's download
 * paths feed with each row's own TDLib counter (the same counter the
 * row's percentage is computed from), one meter per execution per row.
 */
class DownloadRateMeterTest {

    /**
     * Feeds [meter] a stream moving at exactly [rateBytesPerSecond] from
     * [startBytes], one sample every [stepMs], from [fromMs] to [toMs];
     * returns the last non-null published speed.
     */
    private fun driveAtRate(
        meter: DownloadRateMeter,
        startBytes: Long,
        rateBytesPerSecond: Long,
        fromMs: Long,
        toMs: Long,
        stepMs: Long = 100L
    ): Long? {
        var last: Long? = null
        var now = fromMs
        while (now <= toMs) {
            val bytes = startBytes + rateBytesPerSecond * (now - fromMs) / 1_000L
            meter.speedAt(bytes, now)?.let { last = it }
            now += stepMs
        }
        return last
    }

    @Test
    fun `known rate - a stream at exactly 2 MB per second publishes about 2 MB per second`() {
        val meter = DownloadRateMeter()
        // Attach replay: TDLib's counter already holds 50 MB this execution
        // did not download; the first event reports it wholesale.
        meter.start(bytes = 50_000_000, now = 0)

        val published = driveAtRate(
            meter,
            startBytes = 50_000_000,
            rateBytesPerSecond = 2_000_000,
            fromMs = 0,
            toMs = 10_000
        )!!

        // Cumulative average of the post-settle movement: within 5% of the
        // driven rate, and nowhere near the old fake tens-of-MB/s figures.
        assertTrue(
            "published $published must be within 5% of the driven 2,000,000 B/s",
            published in 1_900_000..2_100_000
        )
    }

    @Test
    fun `known rate - a stream at exactly 500 KB per second publishes about 500 KB per second`() {
        val meter = DownloadRateMeter()
        meter.start(bytes = 0, now = 0)

        val published = driveAtRate(
            meter,
            startBytes = 0,
            rateBytesPerSecond = 500_000,
            fromMs = 0,
            toMs = 10_000
        )!!

        assertTrue(
            "published $published must be within 5% of the driven 500,000 B/s",
            published in 475_000..525_000
        )
    }

    @Test
    fun `attach replay inside the settle window never becomes a speed`() {
        val meter = DownloadRateMeter()
        meter.start(bytes = 0, now = 0)

        // TDLib replays its current counter: +34 MB in 150 ms that this
        // execution did not download (the field recording's 34.4 MB/s).
        assertNull(meter.speedAt(bytes = 34_000_000, now = 150))
        assertNull(meter.speedAt(bytes = 34_400_000, now = 900))
        // The first post-settle sample publishes 0, not the replay rate.
        assertEquals(0L, meter.speedAt(bytes = 34_600_000, now = 1_200))
    }

    @Test
    fun `two concurrent rows at different known rates publish different speeds summing near the total`() {
        // The field scenario, inverted: four rows showed ONE identical
        // number. Here two rows attach at the same instant, each with its
        // own replay jump, then move at different known rates — 1.5 MB/s
        // and 0.5 MB/s, a 2 MB/s total like the field's real traffic.
        val rowA = DownloadRateMeter()
        val rowB = DownloadRateMeter()
        rowA.start(bytes = 34_000_000, now = 0)
        rowB.start(bytes = 12_000_000, now = 0)

        val speedA = driveAtRate(
            rowA,
            startBytes = 34_000_000,
            rateBytesPerSecond = 1_500_000,
            fromMs = 0,
            toMs = 10_000
        )!!
        val speedB = driveAtRate(
            rowB,
            startBytes = 12_000_000,
            rateBytesPerSecond = 500_000,
            fromMs = 0,
            toMs = 10_000
        )!!

        assertNotEquals(
            "concurrent rows at different rates must not show the same speed",
            speedA, speedB
        )
        assertTrue(
            "row A published $speedA, expected within 5% of 1,500,000 B/s",
            speedA in 1_425_000..1_575_000
        )
        assertTrue(
            "row B published $speedB, expected within 5% of 500,000 B/s",
            speedB in 475_000..525_000
        )
        assertTrue(
            "row speeds ${speedA + speedB} must sum near the 2,000,000 B/s total",
            speedA + speedB in 1_900_000..2_100_000
        )
    }

    @Test
    fun `speed decays toward zero when bytes stop moving`() {
        val meter = DownloadRateMeter()
        meter.start(bytes = 0, now = 0)
        meter.speedAt(bytes = 30_600_000, now = 1_200)

        val moving = meter.speedAt(bytes = 31_600_000, now = 2_200)!!
        val stalled = meter.speedAt(bytes = 31_600_000, now = 3_200)!!
        assertTrue(stalled < moving)
        assertEquals(500_000L, stalled)
    }

    @Test
    fun `a counter restart never reports a negative or inflated speed`() {
        val meter = DownloadRateMeter()
        meter.start(bytes = 0, now = 0)
        meter.speedAt(bytes = 30_600_000, now = 1_200)

        // TDLib re-added the file and its counter restarted near zero.
        assertEquals(0L, meter.speedAt(bytes = 5_000_000, now = 2_200))
    }

    @Test
    fun `publishes at most once per second after settling`() {
        val meter = DownloadRateMeter()
        meter.start(bytes = 0, now = 0)
        meter.speedAt(bytes = 1_000, now = 1_200)

        assertNull(meter.speedAt(bytes = 2_000, now = 1_500))
        assertNull(meter.speedAt(bytes = 3_000, now = 2_100))
        assertEquals(2_000L, meter.speedAt(bytes = 3_000, now = 2_200))
    }
}
