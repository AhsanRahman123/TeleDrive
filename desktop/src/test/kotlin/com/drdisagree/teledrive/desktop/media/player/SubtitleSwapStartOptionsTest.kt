package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The candidate-start contract for an appearance swap (PC-19, report
 * 20): the candidate instance is built with the appearance's factory
 * arguments, and the SAME media is started on it with only the start
 * directives — the position inside the start itself (`:start-time`, so
 * no post-start seek races the event stream) and `:start-paused` when
 * the video was paused. These are core input options, honoured on any
 * instance; no appearance option rides on the media (that was the
 * 1.6.3 route, proven inert on the user's PC).
 */
class SubtitleSwapStartOptionsTest {

    @Test
    fun `apply while playing resumes at the captured position inside the start`() {
        // His recording: 34:16 into the film when he applied.
        val options = swapStartOptions(restartPositionMs = 2_056_000L, restartWasPlaying = true)
        assertEquals(listOf(":start-time=2056.0"), options)
    }

    @Test
    fun `apply while paused starts paused at the captured position`() {
        val options = swapStartOptions(restartPositionMs = 61_500L, restartWasPlaying = false)
        assertEquals(listOf(":start-time=61.5", ":start-paused"), options)
    }

    @Test
    fun `a swap at the very beginning needs no start-time`() {
        assertTrue(swapStartOptions(restartPositionMs = 400L, restartWasPlaying = true).isEmpty())
        assertEquals(
            listOf(":start-paused"),
            swapStartOptions(restartPositionMs = 0L, restartWasPlaying = false)
        )
    }

    @Test
    fun `no appearance option ever rides on the candidate start`() {
        val options = swapStartOptions(restartPositionMs = 90_000L, restartWasPlaying = false)
        assertFalse(options.any { it.contains("freetype") || it.contains("sub-margin") })
        assertTrue(options.all { it.startsWith(":start-") })
    }

    @Test
    fun `repeated applies are deterministic - the option list never drifts`() {
        val first = swapStartOptions(restartPositionMs = 90_000L, restartWasPlaying = true)
        repeat(5) {
            assertEquals(
                first,
                swapStartOptions(restartPositionMs = 90_000L, restartWasPlaying = true)
            )
        }
    }
}
