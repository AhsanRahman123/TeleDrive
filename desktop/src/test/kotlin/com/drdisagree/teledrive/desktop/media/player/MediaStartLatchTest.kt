package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.26 P4 — the bounded start's proof latch.
 *
 * The latch is the decision core of [startMediaBounded], which replaced
 * the unbounded `MediaApi.start()` wait on the viewer's switch path: a
 * start that never reports must fail within the timeout (freeing the
 * session's single-thread executor for later switches and controls),
 * while real start outcomes — playing, paused, finished, error — must be
 * reported exactly. Tests drive the latch's decision core directly
 * (vlcj's MediaPlayer is a class and cannot be faked without natives).
 */
class MediaStartLatchTest {

    @Test
    fun `playing proves the start`() {
        val latch = MediaStartLatch()
        latch.onPlaying()
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `paused proves the start`() {
        val latch = MediaStartLatch()
        latch.onPaused()
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `finished proves the start - a very short media did start`() {
        val latch = MediaStartLatch()
        latch.onFinished()
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `error fails the start promptly`() {
        val latch = MediaStartLatch()
        latch.onError()
        assertFalse(latch.await(1_000))
    }

    @Test
    fun `no report at all fails the start within the timeout - the wedge case`() {
        val latch = MediaStartLatch()
        val startedAt = System.nanoTime()
        assertFalse(latch.await(200))
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        assertTrue("await returned before its timeout: ${elapsedMs}ms", elapsedMs >= 150)
    }

    @Test
    fun `a stale stopped report from the outgoing media does not fail the incoming start`() {
        // The latch is registered right after the outgoing media's stop();
        // its asynchronous Stopped report can land inside this window and
        // must be ignored — the incoming media's own report still decides.
        val latch = MediaStartLatch()
        latch.onStopped()
        latch.onPlaying()
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `stopped alone never proves a start`() {
        val latch = MediaStartLatch()
        latch.onStopped()
        assertFalse(latch.await(200))
    }

    @Test
    fun `the first terminal report wins`() {
        val latch = MediaStartLatch()
        latch.onError()
        latch.onPlaying()
        assertFalse(latch.await(1_000))
    }
}
