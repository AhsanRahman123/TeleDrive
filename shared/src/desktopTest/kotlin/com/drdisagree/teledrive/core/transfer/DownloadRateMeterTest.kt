package com.drdisagree.teledrive.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 P7 ground truth for [DownloadRateMeter]: the meter is driven with
 * synthetic-clock streams at KNOWN rates and must publish approximately
 * those rates — not merely "a number". A perfectly linear byte stream
 * makes the cumulative post-settle average exact, so the tolerance is
 * tight (2%): any regression to windowed division of a replayed counter
 * fails these by an order of magnitude, not by a rounding error.
 */
class DesktopDownloadRateMeterTest {

    /**
     * Feeds [meter] a linear stream: at each 100 ms step the counter is
     * startBytes + rate * t. Returns every published (non-null) speed.
     */
    private fun driveLinear(
        meter: DownloadRateMeter,
        rateBytesPerSecond: Long,
        durationMs: Long,
        startBytes: Long = 0L,
        startAtMs: Long = 0L
    ): List<Long> {
        val published = mutableListOf<Long>()
        meter.start(startBytes, startAtMs)
        var t = startAtMs
        while (t <= startAtMs + durationMs) {
            val bytes = startBytes + rateBytesPerSecond * (t - startAtMs) / 1_000L
            meter.speedAt(bytes, t)?.let { published += it }
            t += 100L
        }
        return published
    }

    private fun assertNearRate(expected: Long, actual: Long, tolerancePercent: Long = 2L) {
        val tolerance = expected * tolerancePercent / 100L
        assertTrue(
            "expected ≈$expected B/s (±$tolerancePercent%) but was $actual B/s",
            kotlin.math.abs(actual - expected) <= tolerance
        )
    }

    @Test
    fun `a controlled 2 MB per second stream displays approximately 2 MB per second`() {
        val published = driveLinear(DownloadRateMeter(), 2_000_000L, durationMs = 10_000L)
        // The settle-boundary publish is 0 by design; every later publish
        // is the measured rate.
        val measured = published.filter { it > 0L }
        assertTrue("the meter must publish measured speeds", measured.size >= 5)
        measured.forEach { assertNearRate(2_000_000L, it) }
    }

    @Test
    fun `a controlled 500 KB per second stream displays approximately 500 KB per second`() {
        val published = driveLinear(DownloadRateMeter(), 500_000L, durationMs = 10_000L)
        val measured = published.filter { it > 0L }
        assertTrue(measured.size >= 5)
        measured.forEach { assertNearRate(500_000L, it) }
    }

    @Test
    fun `two concurrent rows at different known rates display different values summing to the combined rate`() {
        val rowA = DownloadRateMeter()
        val rowB = DownloadRateMeter()
        var lastA = 0L
        var lastB = 0L
        // Interleaved event streams, exactly as two simultaneous executions
        // would feed their own meters from their own counters.
        rowA.start(0L, 0L)
        rowB.start(0L, 0L)
        var t = 0L
        while (t <= 10_000L) {
            rowA.speedAt(1_500_000L * t / 1_000L, t)?.let { if (it > 0) lastA = it }
            rowB.speedAt(500_000L * t / 1_000L, t)?.let { if (it > 0) lastB = it }
            t += 100L
        }
        assertNearRate(1_500_000L, lastA)
        assertNearRate(500_000L, lastB)
        assertTrue("rows at different rates must not display the same figure", lastA != lastB)
        assertNearRate(2_000_000L, lastA + lastB)
    }

    @Test
    fun `an attach replay of 34 MB never becomes a speed`() {
        // The field recording: TDLib replays ~34 MB inside the first
        // moment after attach while the real movement is ~100 KB/s.
        val meter = DownloadRateMeter()
        val published = mutableListOf<Long?>()
        meter.start(0L, 0L)
        published += meter.speedAt(0L, 0L)
        published += meter.speedAt(34_000_000L, 150L) // the replay jump
        var t = 250L
        while (t <= 1_000L) {
            published += meter.speedAt(34_000_000L, t)
            t += 250L
        }
        // Genuine movement after the replay: 100 KB/s for 9 more seconds.
        t = 1_100L
        while (t <= 10_000L) {
            val bytes = 34_000_000L + 100_000L * (t - 1_000L) / 1_000L
            published += meter.speedAt(bytes, t)
            t += 100L
        }
        val measured = published.filterNotNull().filter { it > 0L }
        assertTrue(measured.isNotEmpty())
        assertTrue(
            "no publish may come from the replay (max was ${measured.maxOrNull()} B/s)",
            measured.all { it < 1_000_000L }
        )
        assertNearRate(100_000L, measured.last(), tolerancePercent = 5L)
    }

    @Test
    fun `speed decays toward zero when bytes stop moving`() {
        val meter = DownloadRateMeter()
        driveLinear(meter, 1_000_000L, durationMs = 5_000L)
        var last = Long.MAX_VALUE
        var previous = Long.MAX_VALUE
        var t = 6_000L
        while (t <= 15_000L) {
            // Counter frozen at the 5 s value; one probe per publish window.
            meter.speedAt(5_000_000L, t)?.let {
                previous = last
                last = it
            }
            t += 1_000L
        }
        assertTrue("a stalled transfer must decay (was $last B/s)", last < 400_000L)
        assertTrue("decay must be monotonic here", last <= previous)
    }

    @Test
    fun `a counter restart below the baseline reads as zero, never negative or huge`() {
        val meter = DownloadRateMeter()
        driveLinear(meter, 1_000_000L, durationMs = 3_000L)
        val afterRestart = meter.speedAt(10_000L, 4_000L)
        assertEquals(0L, afterRestart)
    }

    @Test
    fun `nothing is published inside the settle window except the settle-boundary zero`() {
        val meter = DownloadRateMeter()
        meter.start(0L, 0L)
        assertEquals(null, meter.speedAt(5_000_000L, 100L))
        assertEquals(null, meter.speedAt(9_000_000L, 500L))
        assertEquals(0L, meter.speedAt(9_500_000L, 1_000L))
    }
}
