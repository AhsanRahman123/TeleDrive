package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VideoResumeStoreTest {

    @Test
    fun `restores a saved position when it is safely before the end`() {
        assertEquals(45_000L, videoResumeTarget(45_000L, 120_000L))
    }

    @Test
    fun `does not resume a video that was already near completion`() {
        assertNull(videoResumeTarget(119_000L, 120_000L))
    }

    @Test
    fun `does not resume from a negligible opening position`() {
        assertNull(videoResumeTarget(1_000L, 120_000L))
    }

    @Test
    fun `does not attempt to resume until duration is known`() {
        assertNull(videoResumeTarget(45_000L, 0L))
    }

    @Test
    fun `resume position remains bounded before the end margin`() {
        assertEquals(98_500L, videoResumeTarget(99_500L, 100_000L))
    }
}
