package com.drdisagree.teledrive.presentation.preview

/**
 * The text/document viewer zoom steps (PC 1.6.26 item P5).
 *
 * The zoom VALUE itself is the long-standing shared preference
 * `UserPreferences.textPreviewScale` (DataStore key `text_preview_scale`),
 * exposed by [PreviewViewModel.textScale] / [PreviewViewModel.setTextScale]:
 * Android's viewer has pinched it since before this round, while the PC
 * viewers added in 1.6.25 (EPUB / OOXML text, Markdown, plain text,
 * subtitles, JSON/XML pretty-print) never read it and rendered at one fixed
 * size — that is the gap P5 closes on PC. The bounds match Android's
 * existing pinch bounds (0.7f..3f in its PreviewScreen), so a scale written
 * by either platform stays valid on the other.
 *
 * Desktop has no pinch (standing rule — pinch zoom stays removed), so the
 * PC controls step through this fixed ladder instead: buttons and
 * Ctrl+wheel / Ctrl+plus / Ctrl+minus move one step, Ctrl+0 returns to 1f.
 * Because the value lives in the persisted preference, it is automatically
 * consistent across every text viewer in the session (and beyond it).
 */
const val TEXT_PREVIEW_SCALE_MIN: Float = 0.7f
const val TEXT_PREVIEW_SCALE_MAX: Float = 3f

/** The discrete zoom ladder, ascending; 1f (100%) is the default step. */
val TEXT_PREVIEW_SCALE_STEPS: List<Float> =
    listOf(0.7f, 0.85f, 1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f, 2.5f, 3f)

private const val TEXT_PREVIEW_SCALE_EPSILON: Float = 0.001f

/** Clamps any stored scale into the supported range. */
fun clampTextPreviewScale(scale: Float): Float =
    scale.coerceIn(TEXT_PREVIEW_SCALE_MIN, TEXT_PREVIEW_SCALE_MAX)

/** One zoom step up from [current] (the next ladder step above it), capped at the top. */
fun textPreviewScaleZoomIn(current: Float): Float {
    val clamped = clampTextPreviewScale(current)
    return TEXT_PREVIEW_SCALE_STEPS.firstOrNull { it > clamped + TEXT_PREVIEW_SCALE_EPSILON }
        ?: TEXT_PREVIEW_SCALE_MAX
}

/** One zoom step down from [current] (the next ladder step below it), capped at the bottom. */
fun textPreviewScaleZoomOut(current: Float): Float {
    val clamped = clampTextPreviewScale(current)
    return TEXT_PREVIEW_SCALE_STEPS.lastOrNull { it < clamped - TEXT_PREVIEW_SCALE_EPSILON }
        ?: TEXT_PREVIEW_SCALE_MIN
}
