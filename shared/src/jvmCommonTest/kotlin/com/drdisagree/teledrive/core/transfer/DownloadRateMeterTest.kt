package com.drdisagree.teledrive.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 A2: the download rate meter must never convert TDLib's attach replay
 * (bytes that were already on disk when this execution attached) into a
 * displayed speed, and after the settle window it reports the cumulative
 * average of this execution's own movement — which decays when bytes stop.
 */
class DownloadRateMeterTest {

    @Test
    fun `attach replay inside the settle window never becomes a speed`() {
        val meter = DownloadRateMeter()
        meter.start(bytes = 0, now = 0)

        // TDLib replays its current counter: +30 MB in 150 ms that this
        // execution did not download.
        assertNull(meter.speedAt(bytes = 30_000_000, now = 150))
        assertNull(meter.speedAt(bytes = 30_400_000, now = 900))
        // The first post-settle sample publishes 0, not the replay rate.
        assertEquals(0L, meter.speedAt(bytes = 30_600_000, now = 1_200))
    }

    @Test
    fun `post-settle speed is the cumulative average of real movement`() {
        val meter = DownloadRateMeter()
        meter.start(bytes = 0, now = 0)
        meter.speedAt(bytes = 30_000_000, now = 150)
        meter.speedAt(bytes = 30_600_000, now = 1_200)

        // 1 MB of genuine movement over the 1 s since the settle baseline.
        assertEquals(1_000_000L, meter.speedAt(bytes = 31_600_000, now = 2_200))
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
