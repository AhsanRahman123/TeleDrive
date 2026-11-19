package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The manual scale presets: each one is an exact picture size calculated from the
 * stage, so the factors are pure arithmetic on (preset, stage, video, aspect mode).
 * These tests pin the arithmetic — including how a preset composes with each of the
 * Fit / Crop / Stretch aspect modes — so the sizes can never silently change.
 */
class VideoScaleTest {

    private fun factors(
        preset: VideoScalePreset,
        stageW: Int = 1920,
        stageH: Int = 1080,
        videoW: Int = 1920,
        videoH: Int = 1080,
        mode: VideoScaling = VideoScaling.FIT
    ) = videoScaleFactors(preset, stageW, stageH, videoW, videoH, mode)

    @Test
    fun `best fit is the identity for every aspect mode`() {
        VideoScaling.entries.forEach { mode ->
            val (x, y) = factors(VideoScalePreset.BEST_FIT, videoW = 1440, videoH = 1080, mode = mode)
            assertEquals(1f, x, 0.001f)
            assertEquals(1f, y, 0.001f)
        }
    }

    @Test
    fun `unknown sizes are the identity`() {
        val (x1, y1) = factors(VideoScalePreset.RATIO_5_4, stageW = 0, stageH = 0)
        assertEquals(1f, x1, 0.001f)
        assertEquals(1f, y1, 0.001f)
        val (x2, y2) = factors(VideoScalePreset.FILL, videoW = 0, videoH = 0)
        assertEquals(1f, x2, 0.001f)
        assertEquals(1f, y2, 0.001f)
    }

    @Test
    fun `5-4 on a 16-9 stage and video narrows the picture to the 5-4 box`() {
        // Largest 5:4 box in 1920x1080 is 1350x1080; the Fit base is the full stage.
        val (x, y) = factors(VideoScalePreset.RATIO_5_4)
        assertEquals(1350f / 1920f, x, 0.001f)
        assertEquals(1f, y, 0.001f)
    }

    @Test
    fun `2-39-1 letterboxes a 16-9 picture to the 2-39 box`() {
        // Largest 2.39:1 box in 1920x1080 is 1920 x (1920/2.39).
        val (x, y) = factors(VideoScalePreset.RATIO_2_39)
        assertEquals(1f, x, 0.001f)
        assertEquals((1920f / 2.39f) / 1080f, y, 0.001f)
    }

    @Test
    fun `fit screen grows a 4-3 video uniformly until it covers the stage`() {
        // Fit base of 1440x1080 in 1920x1080 is 1440x1080; cover is 1920x1440.
        val (x, y) = factors(VideoScalePreset.FIT_SCREEN, videoW = 1440, videoH = 1080)
        assertEquals(1920f / 1440f, x, 0.001f)
        assertEquals(1440f / 1080f, y, 0.001f)
    }

    @Test
    fun `fill stretches the fit picture to exactly the stage`() {
        val (x, y) = factors(VideoScalePreset.FILL, videoW = 1440, videoH = 1080)
        assertEquals(1920f / 1440f, x, 0.001f)
        assertEquals(1f, y, 0.001f)
    }

    @Test
    fun `center shows the video at its native size`() {
        // 1280x720 fitted into 1920x1080 is scaled 1.5x by Fit; Center undoes that.
        val (x, y) = factors(VideoScalePreset.CENTER, videoW = 1280, videoH = 720)
        assertEquals(1280f / 1920f, x, 0.001f)
        assertEquals(720f / 1080f, y, 0.001f)
    }

    @Test
    fun `a ratio preset composes with the crop aspect mode`() {
        // Crop base of 1440x1080 in 1920x1080 is 1920x1440; the 16:9 box is 1920x1080.
        val (x, y) = factors(
            VideoScalePreset.RATIO_16_9,
            videoW = 1440,
            videoH = 1080,
            mode = VideoScaling.ZOOM
        )
        assertEquals(1f, x, 0.001f)
        assertEquals(1080f / 1440f, y, 0.001f)
    }

    @Test
    fun `a ratio preset composes with the stretch aspect mode`() {
        // Stretch base is the stage itself; the 4:3 box inside it is 1440x1080.
        val (x, y) = factors(
            VideoScalePreset.RATIO_4_3,
            videoW = 1440,
            videoH = 1080,
            mode = VideoScaling.FILL
        )
        assertEquals(1440f / 1920f, x, 0.001f)
        assertEquals(1f, y, 0.001f)
    }

    @Test
    fun `the fitted picture already is the largest box of its own ratio`() {
        // Portrait stage: a 16:9 video fitted by Fit is exactly the largest 16:9 box.
        val (x, y) = factors(
            VideoScalePreset.RATIO_16_9,
            stageW = 1080,
            stageH = 1920,
            videoW = 1920,
            videoH = 1080
        )
        assertEquals(1f, x, 0.001f)
        assertEquals(1f, y, 0.001f)
    }

    @Test
    fun `every preset label matches the reference list`() {
        assertEquals(
            listOf(
                "Best fit", "Fit screen", "Fill", "16:9", "4:3", "16:10",
                "2:1", "2.21:1", "2.35:1", "2.39:1", "5:4", "Center"
            ),
            VideoScalePreset.entries.map { it.label }
        )
    }
}
