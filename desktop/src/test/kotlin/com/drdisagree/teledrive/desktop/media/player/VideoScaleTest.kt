package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    // -- Portrait and square coverage -------------------------------------
    // The rendered picture box is the aspect mode's base picture scaled by
    // the returned factors; these helpers reconstruct it so tests can assert
    // where the picture actually lands, not just the raw factors.

    private fun baseBox(
        stageW: Int,
        stageH: Int,
        videoW: Int,
        videoH: Int,
        mode: VideoScaling
    ): Pair<Float, Float> = when (mode) {
        VideoScaling.FIT -> {
            val s = minOf(stageW.toFloat() / videoW, stageH.toFloat() / videoH)
            (videoW * s) to (videoH * s)
        }
        VideoScaling.ZOOM -> {
            val s = maxOf(stageW.toFloat() / videoW, stageH.toFloat() / videoH)
            (videoW * s) to (videoH * s)
        }
        VideoScaling.FILL -> stageW.toFloat() to stageH.toFloat()
    }

    private fun renderedBox(
        preset: VideoScalePreset,
        stageW: Int = 1920,
        stageH: Int = 1080,
        videoW: Int,
        videoH: Int,
        mode: VideoScaling = VideoScaling.FIT
    ): Pair<Float, Float> {
        val (x, y) = factors(preset, stageW, stageH, videoW, videoH, mode)
        val (baseW, baseH) = baseBox(stageW, stageH, videoW, videoH, mode)
        return (baseW * x) to (baseH * y)
    }

    @Test
    fun `portrait video best fit is the identity and uses the full stage height`() {
        val (x, y) = factors(VideoScalePreset.BEST_FIT, videoW = 720, videoH = 1280)
        assertEquals(1f, x, 0.001f)
        assertEquals(1f, y, 0.001f)
        // Fit base of 720x1280 in 1920x1080 is height-bound: 607.5 x 1080,
        // fully inside the stage — nothing may crop in the default mode.
        val (w, h) = renderedBox(VideoScalePreset.BEST_FIT, videoW = 720, videoH = 1280)
        assertEquals(607.5f, w, 0.5f)
        assertEquals(1080f, h, 0.5f)
    }

    @Test
    fun `portrait video fit screen scales uniformly until it covers`() {
        val (x, y) = factors(VideoScalePreset.FIT_SCREEN, videoW = 720, videoH = 1280)
        assertEquals(x, y, 0.001f)
        val (w, h) = renderedBox(VideoScalePreset.FIT_SCREEN, videoW = 720, videoH = 1280)
        assertEquals(1920f, w, 0.5f)
        assertTrue(h >= 1080f)
    }

    @Test
    fun `portrait video ratio presets land on their exact boxes`() {
        val box169 = renderedBox(VideoScalePreset.RATIO_16_9, videoW = 720, videoH = 1280)
        assertEquals(1920f, box169.first, 0.5f)
        assertEquals(1080f, box169.second, 0.5f)
        val box43 = renderedBox(VideoScalePreset.RATIO_4_3, videoW = 720, videoH = 1280)
        assertEquals(1440f, box43.first, 0.5f)
        assertEquals(1080f, box43.second, 0.5f)
        val box54 = renderedBox(VideoScalePreset.RATIO_5_4, videoW = 720, videoH = 1280)
        assertEquals(1350f, box54.first, 0.5f)
        assertEquals(1080f, box54.second, 0.5f)
    }

    @Test
    fun `square video across presets`() {
        val (bx, by) = factors(VideoScalePreset.BEST_FIT, videoW = 1000, videoH = 1000)
        assertEquals(1f, bx, 0.001f)
        assertEquals(1f, by, 0.001f)
        val fill = renderedBox(VideoScalePreset.FILL, videoW = 1000, videoH = 1000)
        assertEquals(1920f, fill.first, 0.5f)
        assertEquals(1080f, fill.second, 0.5f)
        val center = renderedBox(VideoScalePreset.CENTER, videoW = 1000, videoH = 1000)
        assertEquals(1000f, center.first, 0.5f)
        assertEquals(1000f, center.second, 0.5f)
        val ratio = renderedBox(VideoScalePreset.RATIO_16_9, videoW = 1000, videoH = 1000)
        assertEquals(1920f, ratio.first, 0.5f)
        assertEquals(1080f, ratio.second, 0.5f)
    }

    @Test
    fun `landscape video on a portrait stage`() {
        val (x, y) = factors(
            VideoScalePreset.BEST_FIT,
            stageW = 1080,
            stageH = 1920,
            videoW = 1920,
            videoH = 1080
        )
        assertEquals(1f, x, 0.001f)
        assertEquals(1f, y, 0.001f)
        val box = renderedBox(
            VideoScalePreset.RATIO_4_3,
            stageW = 1080,
            stageH = 1920,
            videoW = 1920,
            videoH = 1080
        )
        assertEquals(1080f, box.first, 0.5f)
        assertEquals(810f, box.second, 0.5f)
    }

    @Test
    fun `in-stage presets never overflow the stage for any picture shape`() {
        val stages = listOf(1920 to 1080, 1080 to 1920, 1000 to 1000, 1366 to 768)
        val videos = listOf(
            720 to 1280, 480 to 854, 1920 to 1080, 1000 to 1000, 3840 to 2160, 1080 to 1080
        )
        val inStage = listOf(VideoScalePreset.BEST_FIT) +
            VideoScalePreset.entries.filter { it.aspectRatio != null }
        for ((stageW, stageH) in stages) {
            for ((videoW, videoH) in videos) {
                for (preset in inStage) {
                    val (w, h) = renderedBox(preset, stageW, stageH, videoW, videoH)
                    assertTrue(
                        "$preset of ${videoW}x$videoH overflows ${stageW}x$stageH: ${w}x$h",
                        w <= stageW + 1f && h <= stageH + 1f
                    )
                    val ratio = preset.aspectRatio
                    if (ratio != null) {
                        assertEquals(
                            "$preset box must keep its aspect",
                            ratio,
                            w / h,
                            0.01f
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `fit screen covers the stage for any picture shape`() {
        val stages = listOf(1920 to 1080, 1080 to 1920, 1000 to 1000)
        val videos = listOf(720 to 1280, 1920 to 1080, 1000 to 1000, 3840 to 2160)
        for ((stageW, stageH) in stages) {
            for ((videoW, videoH) in videos) {
                val (w, h) = renderedBox(
                    VideoScalePreset.FIT_SCREEN, stageW, stageH, videoW, videoH
                )
                assertTrue(
                    "Fit screen of ${videoW}x$videoH must cover ${stageW}x$stageH: ${w}x$h",
                    w >= stageW - 1f && h >= stageH - 1f
                )
            }
        }
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
