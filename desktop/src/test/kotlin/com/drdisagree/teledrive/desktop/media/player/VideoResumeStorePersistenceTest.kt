package com.drdisagree.teledrive.desktop.media.player

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Persistence behaviour of [VideoResumeStore] against a real (temporary)
 * Preferences node with a fresh throttle cache: round-trip, per-media keying,
 * the 5-second persist throttle, and completion clearing. Writes are async by
 * design, so assertions poll with a deadline.
 */
class VideoResumeStorePersistenceTest {

    @Test
    fun `saved position is loaded back for the same media only`() = withStore { node, cache ->
        VideoResumeStore.savePosition(node, cache, "media-a", 45_000L, 120_000L, force = true)
        awaitValue(45_000L) { VideoResumeStore.loadPosition(node, cache, "media-a") }

        assertEquals(45_000L, VideoResumeStore.loadPosition(node, cache, "media-a"))
        assertEquals(0L, VideoResumeStore.loadPosition(node, cache, "media-b"))
    }

    @Test
    fun `positions for different media are keyed independently`() = withStore { node, cache ->
        VideoResumeStore.savePosition(node, cache, "media-a", 30_000L, 120_000L, force = true)
        VideoResumeStore.savePosition(node, cache, "media-b", 80_000L, 120_000L, force = true)
        awaitValue(30_000L) { VideoResumeStore.loadPosition(node, cache, "media-a") }
        awaitValue(80_000L) { VideoResumeStore.loadPosition(node, cache, "media-b") }

        assertEquals(30_000L, VideoResumeStore.loadPosition(node, cache, "media-a"))
        assertEquals(80_000L, VideoResumeStore.loadPosition(node, cache, "media-b"))
    }

    @Test
    fun `small position changes inside the throttle window are not persisted`() =
        withStore { node, cache ->
            VideoResumeStore.savePosition(node, cache, "media-a", 60_000L, 300_000L, force = true)
            awaitValue(60_000L) { VideoResumeStore.loadPosition(node, cache, "media-a") }

            // 2 s later is inside the 5 s persist delta: skipped synchronously.
            VideoResumeStore.savePosition(node, cache, "media-a", 62_000L, 300_000L)
            Thread.sleep(600)
            assertEquals(60_000L, VideoResumeStore.loadPosition(node, cache, "media-a"))

            // A change beyond the delta persists.
            VideoResumeStore.savePosition(node, cache, "media-a", 66_000L, 300_000L)
            awaitValue(66_000L) { VideoResumeStore.loadPosition(node, cache, "media-a") }
        }

    @Test
    fun `saving a position at the end clears the resume point`() = withStore { node, cache ->
        VideoResumeStore.savePosition(node, cache, "media-a", 45_000L, 120_000L, force = true)
        awaitValue(45_000L) { VideoResumeStore.loadPosition(node, cache, "media-a") }

        VideoResumeStore.savePosition(node, cache, "media-a", 119_600L, 120_000L, force = true)
        awaitValue(0L) { VideoResumeStore.loadPosition(node, cache, "media-a") }
    }

    @Test
    fun `clear removes a saved position`() = withStore { node, cache ->
        VideoResumeStore.savePosition(node, cache, "media-a", 45_000L, 120_000L, force = true)
        awaitValue(45_000L) { VideoResumeStore.loadPosition(node, cache, "media-a") }

        VideoResumeStore.clear(node, cache, "media-a")
        awaitValue(0L) { VideoResumeStore.loadPosition(node, cache, "media-a") }
    }

    @Test
    fun `non-positive positions are never saved`() = withStore { node, cache ->
        VideoResumeStore.savePosition(node, cache, "media-a", 0L, 120_000L, force = true)
        VideoResumeStore.savePosition(node, cache, "media-a", -5L, 120_000L, force = true)
        Thread.sleep(300)
        assertEquals(0L, VideoResumeStore.loadPosition(node, cache, "media-a"))
    }

    private fun awaitValue(expected: Long, read: () -> Long) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            if (read() == expected) return
            Thread.sleep(25)
        }
        assertTrue("expected $expected but was ${read()}", read() == expected)
    }

    private fun withStore(block: (Preferences, ConcurrentHashMap<String, Long>) -> Unit) {
        val node = Preferences.userRoot()
            .node("teledrive-tests/video-resume-${UUID.randomUUID()}")
        try {
            block(node, ConcurrentHashMap())
        } finally {
            runCatching { node.removeNode() }
            runCatching { node.flush() }
        }
    }
}
