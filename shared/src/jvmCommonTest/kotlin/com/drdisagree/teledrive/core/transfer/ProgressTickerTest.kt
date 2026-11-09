package com.drdisagree.teledrive.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTickerTest {

    @Test
    fun `reports measured throughput for each complete window`() {
        val ticker = ProgressTicker(intervalMs = 1_000L)
        ticker.start(bytes = 1_000L, now = 1_000L)

        assertNull(ticker.tick(bytes = 1_500L, now = 1_500L))
        assertEquals(1_000L, ticker.tick(bytes = 2_000L, now = 2_000L))
    }

    @Test
    fun `reports zero immediately when bytes stop moving`() {
        val ticker = ProgressTicker(intervalMs = 1_000L)
        ticker.start(bytes = 0L, now = 0L)

        assertEquals(2_000L, ticker.tick(bytes = 2_000L, now = 1_000L))
        assertEquals(0L, ticker.tick(bytes = 2_000L, now = 2_000L))
        assertEquals(1_000L, ticker.tick(bytes = 3_000L, now = 3_000L))
    }

    @Test
    fun `re-seeds after a byte counter reset`() {
        val ticker = ProgressTicker(intervalMs = 1_000L)
        ticker.start(bytes = 1_000L, now = 0L)

        assertEquals(1_000L, ticker.tick(bytes = 2_000L, now = 1_000L))
        assertEquals(0L, ticker.tick(bytes = 100L, now = 2_000L))
        assertEquals(900L, ticker.tick(bytes = 1_000L, now = 3_000L))
    }
}
