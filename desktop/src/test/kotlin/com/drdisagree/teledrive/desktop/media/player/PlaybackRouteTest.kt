package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins [playbackRouteForMrl] (PC 1.6.27 item P3): the route tag on the
 * viewer's per-start log line is what attributes each
 * negotiated-buffer-format line in a field log to the route that
 * produced it, so a misclassification would silently misdiagnose the
 * first-play flicker. The shapes below are the exact MRL shapes each
 * route hands the player (MediaStreamServer.serve's URL; the archive
 * services' temp-file prefixes; plain local paths).
 */
class PlaybackRouteTest {

    @Test
    fun `loopback stream url is the streamed route`() {
        assertEquals(
            PlaybackRoute.STREAMED,
            playbackRouteForMrl("http://127.0.0.1:54321/stream/ab12cd34/clip%20one.mp4")
        )
        assertEquals(
            PlaybackRoute.STREAMED,
            playbackRouteForMrl("http://localhost:54321/stream/ab12cd34/clip.mp4")
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
    fun `a local file merely named like a stream is not streamed`() {
        assertEquals(
            PlaybackRoute.LOCAL,
            playbackRouteForMrl("/home/user/Videos/http-127.0.0.1-clip.mp4")
        )
    }
}
