package com.drdisagree.teledrive.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressTickerTest {

    @Test
    fun `resumed download seeds speed from cached bytes instead of counting them as new`() {
        val ticker = ProgressTicker(intervalMs = 1_000L)
        val cachedBytes = 600L * 1024 * 1024
        val now = 10_000L

        ticker.start(cachedBytes, now)

        assertNull(ticker.tick(cachedBytes, now + 500L))
        assertEquals(
            3L * 1024 * 1024,
            ticker.tick(cachedBytes + 3L * 1024 * 1024, now + 1_000L)
        )
    }

    @Test
    fun `idle speed sample resets to zero rather than retaining an old rate`() {
        val ticker = ProgressTicker(intervalMs = 1_000L)
        ticker.start(100L, 1_000L)

        assertEquals(500L, ticker.tick(600L, 2_000L))
        assertEquals(0L, ticker.tick(600L, 3_000L))
    }
}
