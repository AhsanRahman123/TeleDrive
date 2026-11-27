package com.drdisagree.teledrive.desktop.media.player

import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.caprica.vlcj.player.base.MediaPlayer

/**
 * PC 1.6.26 P4 — the bounded start's proof latch.
 *
 * The latch is the decision core of [startMediaBounded], which replaced
 * the unbounded `MediaApi.start()` wait on the viewer's switch path: a
 * start that never reports must fail within the timeout (freeing the
 * session's single-thread executor for later switches and controls),
 * while real start outcomes — playing, paused, finished, error — must be
 * reported exactly. The latch never calls methods on the player it is
 * handed, so a bare interface proxy stands in for it.
 */
class MediaStartLatchTest {

    private val player: MediaPlayer = Proxy.newProxyInstance(
        MediaPlayer::class.java.classLoader,
        arrayOf(MediaPlayer::class.java)
    ) { _, method, _ ->
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            else -> null
        }
    } as MediaPlayer

    @Test
    fun `playing proves the start`() {
        val latch = MediaStartLatch()
        latch.playing(player)
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `paused proves the start`() {
        val latch = MediaStartLatch()
        latch.paused(player)
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `finished proves the start - a very short media did start`() {
        val latch = MediaStartLatch()
        latch.finished(player)
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `error fails the start promptly`() {
        val latch = MediaStartLatch()
        latch.error(player)
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
        latch.stopped(player)
        latch.playing(player)
        assertTrue(latch.await(1_000))
    }

    @Test
    fun `stopped alone never proves a start`() {
        val latch = MediaStartLatch()
        latch.stopped(player)
        assertFalse(latch.await(200))
    }

    @Test
    fun `the first terminal report wins`() {
        val latch = MediaStartLatch()
        latch.error(player)
        latch.playing(player)
        assertFalse(latch.await(1_000))
    }
}
