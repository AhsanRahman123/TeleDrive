package com.drdisagree.teledrive.desktop.ui

/** A dp offset of a top-left corner from the window's top-left. */
data class BubbleOffset(val x: Float, val y: Float)

/** What the transfer-bubble overlay shows, if anything. */
enum class BubbleVisibility { GONE, TAB, BUBBLE }

/**
 * The overlay's visibility decision, in priority order: the Settings
 * master switch wins over everything, fullscreen video hides the widget
 * completely (it must never sit over a fullscreen player), the user's
 * hide collapses it to the edge pull-tab, otherwise the bubble shows.
 */
fun bubbleVisibility(
    enabled: Boolean,
    fullscreen: Boolean,
    userHidden: Boolean
): BubbleVisibility = when {
    !enabled -> BubbleVisibility.GONE
    fullscreen -> BubbleVisibility.GONE
    userHidden -> BubbleVisibility.TAB
    else -> BubbleVisibility.BUBBLE
}

/**
 * Pure layout math for the transfer bubble, in dp. Kept free of Compose so
 * the geometry — especially the guarantee that the default spot clears the
 * Files screen's Create (+) button — is unit-testable.
 *
 * The Create button is a Material 3 Scaffold floating action button:
 * [FAB_SIZE_DP] tall, [FAB_MARGIN_DP] from the window's bottom and right
 * edges (its expanded menu grows upward only while the user holds it open;
 * the bubble's default spot clears the button itself, and the bubble is
 * draggable if the open menu ever reaches it).
 */
object TransferBubbleLayout {

    const val BUBBLE_SIZE_DP = 64f

    /** Gap kept between the bubble and every window edge. */
    const val EDGE_MARGIN_DP = 16f

    /** Smallest gap the panel/tab keep from window edges. */
    const val PANEL_MARGIN_DP = 8f

    /** Vertical gap between the bubble and its expanded panel. */
    const val PANEL_GAP_DP = 10f

    const val TAB_WIDTH_DP = 26f
    const val TAB_HEIGHT_DP = 56f

    /** Material 3 Scaffold FAB: size and margin from the window edges. */
    const val FAB_SIZE_DP = 56f
    const val FAB_MARGIN_DP = 16f

    /** Extra air between the bubble's default spot and the FAB's top edge. */
    const val FAB_CLEARANCE_DP = 16f

    /** Distance from the window bottom to the top edge of the FAB. */
    val fabTopFromBottomDp: Float = FAB_MARGIN_DP + FAB_SIZE_DP

    /**
     * The first-run position: bottom-right, sitting above the Create
     * button with [FAB_CLEARANCE_DP] of air — never covering it.
     */
    fun defaultOffset(windowWidth: Float, windowHeight: Float): BubbleOffset {
        val bottomFromWindowBottom = FAB_MARGIN_DP + FAB_SIZE_DP + FAB_CLEARANCE_DP
        return clampOffset(
            x = windowWidth - EDGE_MARGIN_DP - BUBBLE_SIZE_DP,
            y = windowHeight - bottomFromWindowBottom - BUBBLE_SIZE_DP,
            windowWidth = windowWidth,
            windowHeight = windowHeight
        )
    }

    /** Keeps the whole bubble inside the window. */
    fun clampOffset(
        x: Float,
        y: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset = BubbleOffset(
        x = x.coerceIn(0f, (windowWidth - BUBBLE_SIZE_DP).coerceAtLeast(0f)),
        y = y.coerceIn(0f, (windowHeight - BUBBLE_SIZE_DP).coerceAtLeast(0f))
    )

    /**
     * Read-time validation for a persisted bubble position (report 40,
     * fix 1): a stored point that is non-finite or wholly outside the
     * current window can only be corruption — or a window that moved
     * entirely away from where the position was saved — so it falls
     * back to the default spot instead of being trusted forever. A
     * point at least partly inside the window (e.g. saved on a larger
     * monitor, read back on a smaller one) passes through and is
     * clamped into view as usual.
     */
    fun sanitizePosition(
        raw: BubbleOffset,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        if (!raw.x.isFinite() || !raw.y.isFinite()) {
            return defaultOffset(windowWidth, windowHeight)
        }
        val partlyInside = raw.x + BUBBLE_SIZE_DP > 0f && raw.x < windowWidth &&
            raw.y + BUBBLE_SIZE_DP > 0f && raw.y < windowHeight
        return if (partlyInside) {
            clampOffset(raw.x, raw.y, windowWidth, windowHeight)
        } else {
            defaultOffset(windowWidth, windowHeight)
        }
    }

    /**
     * One drag step (report 40, fix 2): the delta lands on the
     * *effective* (visible, clamped) offset, never on raw stored state,
     * so state and visible position cannot diverge — and a diverged
     * state cannot appear to jump when the first drag starts.
     */
    fun movedBy(
        effective: BubbleOffset,
        dxDp: Float,
        dyDp: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset = clampOffset(
        effective.x + dxDp, effective.y + dyDp, windowWidth, windowHeight
    )

    /**
     * Where the expanded panel goes: right-aligned with the bubble, above
     * it when that fits (the bubble lives near the bottom by default),
     * otherwise below it; always clamped inside the window with
     * [PANEL_MARGIN_DP] to spare.
     */
    fun panelOffset(
        bubble: BubbleOffset,
        panelWidth: Float,
        panelHeight: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        val rightAlignedX = bubble.x + BUBBLE_SIZE_DP - panelWidth
        val aboveY = bubble.y - PANEL_GAP_DP - panelHeight
        val belowY = bubble.y + BUBBLE_SIZE_DP + PANEL_GAP_DP
        val y = if (aboveY >= PANEL_MARGIN_DP) aboveY else belowY
        return BubbleOffset(
            x = rightAlignedX.coerceIn(
                PANEL_MARGIN_DP,
                (windowWidth - panelWidth - PANEL_MARGIN_DP).coerceAtLeast(PANEL_MARGIN_DP)
            ),
            y = y.coerceIn(
                PANEL_MARGIN_DP,
                (windowHeight - panelHeight - PANEL_MARGIN_DP).coerceAtLeast(PANEL_MARGIN_DP)
            )
        )
    }

    /**
     * The hidden state's pull-tab: flush against the window edge nearest
     * the bubble's centre, vertically centred on the bubble, clamped
     * inside the window.
     */
    fun tabOffset(
        bubble: BubbleOffset,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        val centerX = bubble.x + BUBBLE_SIZE_DP / 2f
        val centerY = bubble.y + BUBBLE_SIZE_DP / 2f
        val x = if (centerX <= windowWidth / 2f) 0f else windowWidth - TAB_WIDTH_DP
        val y = (centerY - TAB_HEIGHT_DP / 2f)
            .coerceIn(0f, (windowHeight - TAB_HEIGHT_DP).coerceAtLeast(0f))
        return BubbleOffset(x, y)
    }

    /** True when the pull-tab for this bubble position hugs the left edge. */
    fun tabOnLeftEdge(bubble: BubbleOffset, windowWidth: Float): Boolean =
        bubble.x + BUBBLE_SIZE_DP / 2f <= windowWidth / 2f
}

/**
 * The bubble's drag lifecycle as pure state (report 40, fix 3), kept
 * Compose-free so the gesture hardening is unit-testable: a drag begins
 * by remembering the pre-drag offset; completing a drag yields the
 * final offset exactly once (the shell persists it); cancelling a drag
 * yields the pre-drag offset (the shell restores it) and persists
 * nothing — a phantom or interrupted drag can never move the bubble
 * permanently.
 */
class BubbleDragSession {

    private var startOffset: BubbleOffset? = null

    /** True between [begin] and the drag's completion or cancellation. */
    val active: Boolean get() = startOffset != null

    /** Arms a drag at [offset] — the visible, clamped position. */
    fun begin(offset: BubbleOffset) {
        startOffset = offset
    }

    /**
     * The drag ended with a pointer-up. Returns the offset to persist,
     * or null when no drag was in progress (nothing to persist).
     */
    fun complete(finalOffset: BubbleOffset): BubbleOffset? =
        startOffset?.let {
            startOffset = null
            finalOffset
        }

    /**
     * The drag was cancelled — pointer cancel, or the tracked change
     * vanished without an up. Returns the pre-drag offset to restore,
     * or null when no drag was in progress. Never persists.
     */
    fun cancel(): BubbleOffset? =
        startOffset?.also { startOffset = null }
}
