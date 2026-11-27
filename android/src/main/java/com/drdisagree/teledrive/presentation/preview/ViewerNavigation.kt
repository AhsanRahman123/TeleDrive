package com.drdisagree.teledrive.presentation.preview

/**
 * Pure viewer logic shared by the Files viewer, the in-zip viewer and the
 * local-file viewer (vc186 round 3: A3, A7, A11). Kept free of Compose state
 * so the unit tests pin the exact functions the screens call.
 */

/**
 * A3 — the standing tap-to-toggle rule: one tap hides the player controls,
 * the next tap shows them again, on every player surface and route. Every
 * surface keeps its visibility in a real state and routes its tap through
 * this function; a surface that passes a constant `true` and a no-op
 * callback to the player (the old in-zip wiring) can never hide anything.
 */
fun toggledControlsVisible(current: Boolean): Boolean = !current

/**
 * A11 — the linear header step: the page a Previous/Next header press moves
 * to, or null when the press is a no-op (already at that end, or an empty
 * sequence). Deliberately linear — no shuffle, no repeat wrap: the header
 * buttons walk the same sequence the pager counter shows (e.g. 5/47), and
 * the pager itself does not wrap at the ends either. (The media player's
 * own Skip buttons keep their separate shuffle/repeat behaviour; these
 * helpers are only for the header buttons and the in-zip step.)
 */
fun viewerStepTarget(currentPage: Int, pageCount: Int, delta: Int): Int? {
    if (pageCount <= 0 || delta == 0) return null
    val target = currentPage + delta
    return target.takeIf { it in 0 until pageCount }
}

/** A11 — header Previous enablement: mirrors the pager's start end. */
fun viewerHasPreviousFile(currentPage: Int): Boolean = currentPage > 0

/** A11 — header Next enablement: mirrors the pager's finish end. */
fun viewerHasNextFile(currentPage: Int, pageCount: Int): Boolean =
    pageCount > 0 && currentPage < pageCount - 1

/**
 * A7 (text viewers) — the scale a pinch settles into when the gesture ends.
 * During the gesture only a transient graphicsLayer transform changes (see
 * PreviewPage), so no text is re-measured per frame; on release the gesture's
 * accumulated factor is committed once, through the same clamp the buttons
 * and the persisted preference use, and exactly one re-layout happens.
 */
fun settledTextScale(currentScale: Float, transientFactor: Float): Float =
    clampTextPreviewScale(currentScale * transientFactor)
