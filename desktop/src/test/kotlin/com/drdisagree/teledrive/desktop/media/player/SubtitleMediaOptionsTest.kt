package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC-13 rescue contract (report 18): subtitle appearance is applied as
 * per-media options on the lifelong player, and an Apply reload carries
 * the position inside the start itself. These tests pin the exact option
 * lists the player start is built from, for the user's exact sequence:
 * switch to a subtitle, Apply (repeatedly), keep watching — same position,
 * same play state, no player recreation anywhere in the data.
 */
class SubtitleMediaOptionsTest {

    @Test
    fun `default appearance produces no media options`() {
        assertTrue(SubtitleAppearance().toVlcMediaOptions(videoHeight = 1080).isEmpty())
        assertTrue(
            mediaStartOptions(SubtitleAppearance(), restartPositionMs = null, restartWasPlaying = true)
                .isEmpty()
        )
    }

    @Test
    fun `media options are the verified factory set with a colon prefix`() {
        val appearance = SubtitleAppearance(
            useSystemDefaults = false,
            fontFamily = "Roboto",
            textScale = 1.5f,
            verticalPosition = 0.5f
        )
        val factoryForm = appearance.toVlcFactoryArguments(videoHeight = 1080)
        val mediaForm = appearance.toVlcMediaOptions(videoHeight = 1080)
        assertEquals(factoryForm.map { ":" + it.removePrefix("--") }, mediaForm)
        assertTrue(mediaForm.all { it.startsWith(":") && !it.startsWith("::") })
        // The renderer selection and the corrected (divisor) size mapping ride along.
        assertTrue(":text-renderer=freetype" in mediaForm)
        assertTrue(":freetype-font=Roboto" in mediaForm)
        assertTrue(":freetype-rel-fontsize=${freetypeRelFontSize(1.5f)}" in mediaForm)
        assertTrue(mediaForm.any { it.startsWith(":sub-margin=") })
    }

    @Test
    fun `apply while playing resumes at the captured position inside the start`() {
        // His recording: 34:16 into the film when he applied.
        val options = mediaStartOptions(
            appearance = SubtitleAppearance(useSystemDefaults = false),
            restartPositionMs = 2_056_000L,
            restartWasPlaying = true
        )
        assertTrue(":start-time=2056.0" in options)
        assertFalse(":start-paused" in options)
    }

    @Test
    fun `apply while paused restarts paused at the captured position`() {
        val options = mediaStartOptions(
            appearance = SubtitleAppearance(useSystemDefaults = false),
            restartPositionMs = 61_500L,
            restartWasPlaying = false
        )
        assertTrue(":start-time=61.5" in options)
        assertTrue(":start-paused" in options)
    }

    @Test
    fun `a restart at the very beginning needs no start-time`() {
        val options = mediaStartOptions(
            appearance = SubtitleAppearance(useSystemDefaults = false),
            restartPositionMs = 400L,
            restartWasPlaying = true
        )
        assertFalse(options.any { it.startsWith(":start-time") })
    }

    @Test
    fun `repeated applies are deterministic - the option list never drifts`() {
        val appearance = SubtitleAppearance(
            useSystemDefaults = false,
            textScale = 2f,
            verticalPosition = 0.25f
        )
        val first = mediaStartOptions(appearance, restartPositionMs = 90_000L, restartWasPlaying = true)
        repeat(5) {
            assertEquals(
                first,
                mediaStartOptions(appearance, restartPositionMs = 90_000L, restartWasPlaying = true)
            )
        }
    }
}
