package com.drdisagree.teledrive.presentation.preview

/**
 * Button zoom for the text/document viewers (vc184 P5), shared pure logic so
 * both platforms step identically. The scale itself is the existing persisted
 * [com.drdisagree.teledrive.domain.model.UserPreferences.textPreviewScale]:
 * one value drives every text surface (EPUB/OOXML extraction, Markdown,
 * plain text/code, subtitles, JSON/XML pretty-print — all of them reach the
 * viewer as text), in the normal route and inside a ZIP alike, so a size
 * chosen in one viewer is the size in all of them.
 *
 * Buttons only: each press multiplies/divides by [TEXT_ZOOM_STEP_FACTOR].
 * No pinch gesture is added by this logic (the standing rule keeps pinch
 * zoom removed); the pre-existing Android text pinch writes the same value
 * through the same clamp.
 */
const val TEXT_PREVIEW_SCALE_MIN: Float = 0.7f
const val TEXT_PREVIEW_SCALE_MAX: Float = 3f
const val TEXT_ZOOM_STEP_FACTOR: Float = 1.25f

fun clampTextPreviewScale(scale: Float): Float =
    scale.coerceIn(TEXT_PREVIEW_SCALE_MIN, TEXT_PREVIEW_SCALE_MAX)

/** One zoom-in button press from [scale], clamped at the maximum. */
fun textZoomIn(scale: Float): Float =
    clampTextPreviewScale(scale * TEXT_ZOOM_STEP_FACTOR)

/** One zoom-out button press from [scale], clamped at the minimum. */
fun textZoomOut(scale: Float): Float =
    clampTextPreviewScale(scale / TEXT_ZOOM_STEP_FACTOR)
