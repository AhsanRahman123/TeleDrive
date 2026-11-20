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
