package com.drdisagree.teledrive.desktop.media.player

import kotlin.math.max
import kotlin.math.min

/**
 * Manual, preset picture sizes for the video player — the VLC-style replacement for the
 * removed pinch zoom. The user picks one entry from a list; each entry maps to an exact
 * picture size calculated from the stage (display) size, so the same entry produces the
 * same, pre-calculated size for every video. There is no gesture math and no free
 * variable state: the applied transform is a pure function of (preset, stage size,
 * video size, aspect mode), applied as a centre scale of the whole video surface with
 * no translation, so it cannot drift, overshoot or throw.
 *
 * The entries mirror the reference app's list, in the same order:
 * Best fit, Fit screen, Fill, the fixed aspect ratios, and Center.
 */
enum class VideoScalePreset(
    val label: String,
    /** The picture-box aspect ratio (width / height) for the fixed-ratio entries. */
    val aspectRatio: Float? = null
) {
    /** No preset scaling: the picture is exactly what the aspect mode renders. */
    BEST_FIT("Best fit"),

    /** The picture grows (uniformly) until it covers the whole stage, cropping the overflow. */
    FIT_SCREEN("Fit screen"),

    /** The picture is stretched to exactly the stage size, ignoring its own aspect. */
    FILL("Fill"),

    RATIO_16_9("16:9", 16f / 9f),
    RATIO_4_3("4:3", 4f / 3f),
    RATIO_16_10("16:10", 16f / 10f),
    RATIO_2_1("2:1", 2f),
    RATIO_2_21("2.21:1", 2.21f),
    RATIO_2_35("2.35:1", 2.35f),
    RATIO_2_39("2.39:1", 2.39f),
    RATIO_5_4("5:4", 5f / 4f),

    /** The picture at the video's native size, centred; may overflow the stage. */
    CENTER("Center")
}

/**
 * The centre-scale factors (x, y) that render [preset] for a video of
 * [videoWidth] x [videoHeight] (display size, pixel aspect already applied) on a stage
 * of [stageWidth] x [stageHeight], given the picture the current [resizeMode] aspect
 * setting produces as the base.
 *
 * [stageWidth]/[stageHeight] must be the size of the area the picture is
 * actually drawn in — the image's own (post-inset) layout size — never the
 * outer window or player size. The returned factors are applied as a centre
 * scale of that same area, so if the two disagree, target boxes are sized
 * for one area and rendered in another: the picture overflows asymmetrically
 * and its edges crop (this is how portrait videos lost their top edge while
 * the subtitle safe-area inset was active).
 *
 * Composition with the aspect modes is deliberate and simple: the preset defines a
 * TARGET picture box inside (or, for Center / Fit screen, possibly beyond) the stage,
 * and the returned factors map the aspect mode's base picture exactly onto that box.
 * Both boxes are centred on the stage, so a pure centre scale — no translation — maps
 * one onto the other. Changing the aspect mode afterwards simply re-derives the
 * factors from the new base; the chosen preset stays meaningful.
 *
 * Any degenerate input (a size not known yet) yields the neutral (1, 1).
 */
internal fun videoScaleFactors(
    preset: VideoScalePreset,
    stageWidth: Int,
    stageHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    resizeMode: VideoScaling
): Pair<Float, Float> {
    if (stageWidth <= 0 || stageHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
        return 1f to 1f
    }
    val stageW = stageWidth.toFloat()
    val stageH = stageHeight.toFloat()
    val videoW = videoWidth.toFloat()
    val videoH = videoHeight.toFloat()

    val fitScale = min(stageW / videoW, stageH / videoH)
    val coverScale = max(stageW / videoW, stageH / videoH)

    // The picture the aspect mode renders today, before any preset scaling.
    val (baseW, baseH) = when (resizeMode) {
        VideoScaling.FIT -> (videoW * fitScale) to (videoH * fitScale)
        VideoScaling.ZOOM -> (videoW * coverScale) to (videoH * coverScale)
        VideoScaling.FILL -> stageW to stageH
    }

    val ratio = preset.aspectRatio
    val (targetW, targetH) = when {
        preset == VideoScalePreset.BEST_FIT -> baseW to baseH
        preset == VideoScalePreset.FIT_SCREEN -> (videoW * coverScale) to (videoH * coverScale)
        preset == VideoScalePreset.FILL -> stageW to stageH
        preset == VideoScalePreset.CENTER -> videoW to videoH
        ratio != null -> {
            // The largest box with the preset's aspect that fits inside the stage.
            val boxW = min(stageW, stageH * ratio)
            boxW to (boxW / ratio)
        }

        else -> baseW to baseH
    }

    return (targetW / baseW) to (targetH / baseH)
}
