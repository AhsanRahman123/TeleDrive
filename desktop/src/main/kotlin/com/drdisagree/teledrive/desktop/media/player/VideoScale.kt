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
 * [stageWidth]/[stageHeight] must be the size of the single area both
 * boxes are computed against: if they disagree with the area the factors
 * are rendered into, target boxes are sized for one area and rendered in
 * another and the picture overflows asymmetrically. The player no longer
 * calls this directly with an inset area — it uses [videoPictureTransform],
 * which composes this primitive across the full stage and the inset draw
 * area analytically. This function remains the pinned single-area form.
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
    val boxes = pictureBoxes(preset, stageWidth, stageHeight, videoWidth, videoHeight, resizeMode)
        ?: return 1f to 1f
    val (baseW, baseH) = boxes.first
    val (targetW, targetH) = boxes.second
    return (targetW / baseW) to (targetH / baseH)
}

/**
 * The (base, target) picture boxes for [preset] on a stage of
 * [stageWidth] x [stageHeight]: the box the aspect mode renders before any
 * preset scaling, and the box the preset wants that picture to end up as.
 * Null for degenerate input (a size not known yet).
 */
private fun pictureBoxes(
    preset: VideoScalePreset,
    stageWidth: Int,
    stageHeight: Int,
    videoWidth: Int,
    videoHeight: Int,
    resizeMode: VideoScaling
): Pair<Pair<Float, Float>, Pair<Float, Float>>? {
    if (stageWidth <= 0 || stageHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) {
        return null
    }
    val stageW = stageWidth.toFloat()
    val stageH = stageHeight.toFloat()
    val videoW = videoWidth.toFloat()
    val videoH = videoHeight.toFloat()

    val fitScale = min(stageW / videoW, stageH / videoH)
    val coverScale = max(stageW / videoW, stageH / videoH)

    // The picture the aspect mode renders today, before any preset scaling.
    val base = when (resizeMode) {
        VideoScaling.FIT -> (videoW * fitScale) to (videoH * fitScale)
        VideoScaling.ZOOM -> (videoW * coverScale) to (videoH * coverScale)
        VideoScaling.FILL -> stageW to stageH
    }

    val ratio = preset.aspectRatio
    val target = when {
        preset == VideoScalePreset.BEST_FIT -> base
        preset == VideoScalePreset.FIT_SCREEN -> (videoW * coverScale) to (videoH * coverScale)
        preset == VideoScalePreset.FILL -> stageW to stageH
        preset == VideoScalePreset.CENTER -> videoW to videoH
        ratio != null -> {
            // The largest box with the preset's aspect that fits inside the stage.
            val boxW = min(stageW, stageH * ratio)
            boxW to (boxW / ratio)
        }

        else -> base
    }

    return base to target
}

/** A centre scale plus a vertical shift for the video picture layer. */
internal data class VideoPictureTransform(
    val scaleX: Float,
    val scaleY: Float,
    val translationYPx: Float
)

/**
 * The transform that renders [preset] inside the stage area ABOVE a bottom
 * inset (the subtitle safe area while the control bar is visible), for a
 * video surface whose layout box is the FULL stage and never changes.
 *
 * Why this exists (PC-8): the safe area used to be a layout padding on the
 * video image, so showing/hiding the controls resized the image's layout
 * box; the scale factors were computed from a measured size that lagged a
 * layout pass behind, and for one or more frames the picture was scaled
 * for the wrong area — it jumped, and on hide it expanded past the stage
 * top over the title bar. Here the surface is fixed and everything is
 * derived analytically from the stage size in the same pass that knows the
 * inset:
 *
 * - the aspect mode's base picture is computed against the FULL stage
 *   (that is what the surface's own content scale renders), while the
 *   preset's target box is computed against the DRAW area (stage minus the
 *   bottom inset);
 * - the layer scale maps base → target per axis, and the translation moves
 *   the picture's centre from the stage centre to the draw-area centre
 *   (−inset/2 vertically; the inset is bottom-only, so x never shifts).
 *
 * With a zero inset this reduces exactly to [videoScaleFactors] against
 * the full stage with no translation. The rendered picture — subtitles
 * included, since VLC paints cues into the frame — sits entirely inside
 * the draw area for the in-stage presets, so cues are never under the
 * overlaid control bar and the frame's bottom edge is never cropped by
 * the stage, in fullscreen or windowed.
 */
internal fun videoPictureTransform(
    preset: VideoScalePreset,
    stageWidth: Int,
    stageHeight: Int,
    bottomInsetPx: Int,
    videoWidth: Int,
    videoHeight: Int,
    resizeMode: VideoScaling
): VideoPictureTransform {
    val inset = bottomInsetPx.coerceAtLeast(0)
    val drawHeight = stageHeight - inset
    val full = pictureBoxes(preset, stageWidth, stageHeight, videoWidth, videoHeight, resizeMode)
    val draw = pictureBoxes(preset, stageWidth, drawHeight, videoWidth, videoHeight, resizeMode)
    if (full == null || draw == null) return VideoPictureTransform(1f, 1f, 0f)
    val (baseW, baseH) = full.first
    val (targetW, targetH) = draw.second
    return VideoPictureTransform(
        scaleX = targetW / baseW,
        scaleY = targetH / baseH,
        translationYPx = -inset / 2f
    )
}
