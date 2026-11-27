package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [playbackRouteForMrl] (PC 1.6.27, comparison round — N3 widened):
 * the route tag on the viewer's per-start log line is what attributes
 * each negotiated-buffer-format line in a field log to the route that
 * produced it, so a misclassification would silently misdiagnose the
 * flicker. The shapes below are the exact MRL shapes each route hands
 * the player.
 */
class PlaybackRouteTest {

    @Test
    fun `loopback stream url is the streamed route`() {
        assertEquals(
            PlaybackRoute.STREAMED,
            playbackRouteForMrl("http://127.0.0.1:54321/stream/ab12cd/clip%20one.mp4")
        )
        assertEquals(
            PlaybackRoute.STREAMED,
            playbackRouteForMrl("http://localhost:54321/stream/ab12cd/clip.mp4")
        )
    }

    @Test
    fun `remote archive temp file is the in-zip route`() {
        assertEquals(
            PlaybackRoute.IN_ZIP,
            playbackRouteForMrl("/tmp/zip-entry-clip-123456789.mp4")
        )
    }

    @Test
    fun `local archive temp file is the in-zip route`() {
        assertEquals(
            PlaybackRoute.IN_ZIP,
            playbackRouteForMrl("/tmp/local-entry-clip-123456789.mp4")
        )
    }

    @Test
    fun `plain local path is the local route`() {
        assertEquals(
            PlaybackRoute.LOCAL,
            playbackRouteForMrl("/home/user/Videos/clip.mp4")
        )
        assertEquals(
            PlaybackRoute.LOCAL,
            playbackRouteForMrl("C:\\Users\\user\\Videos\\clip.mp4")
        )
    }

    @Test
    fun `a local file merely containing the temp prefix later in its name is local`() {
        // Only the name's START marks an extraction temp file; a user's
        // own file that happens to contain the marker mid-name must not
        // be reported as an in-zip play.
        assertEquals(
            PlaybackRoute.LOCAL,
            playbackRouteForMrl("/home/user/Videos/my-zip-entry-clip.mp4")
        )
    }
}
