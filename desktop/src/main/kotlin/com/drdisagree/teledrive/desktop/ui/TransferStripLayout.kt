package com.drdisagree.teledrive.desktop.ui

/**
 * Pure layout math for the bottom-strip transfer widget (round 6,
 * Variant C of the approved mockups), in dp. The strip is a second shell
 * over the same state and panel content as the bubble — only its frame
 * differs: a slim bar centred at the window's bottom, expanding upward
 * into the shared panel, collapsing to a small centred handle tab.
 * Kept free of Compose so the geometry is unit-testable.
 */
object TransferStripLayout {

    /** The bar never grows past this wide, however wide the window is. */
    const val MAX_WIDTH_DP = 560f

    /** Air kept between the bar and the window's side edges. */
    const val SIDE_MARGIN_DP = 16f

    /** The bar floats this far above the window's bottom edge. */
    const val BOTTOM_MARGIN_DP = 12f

    const val BAR_HEIGHT_DP = 44f

    /** The hidden state's handle tab. */
    const val TAB_WIDTH_DP = 84f
    const val TAB_HEIGHT_DP = 24f

    /** Vertical gap between the bar and the panel it expands into. */
    const val PANEL_GAP_DP = 8f

    fun stripWidth(windowWidth: Float): Float =
        minOf(MAX_WIDTH_DP, (windowWidth - SIDE_MARGIN_DP * 2f).coerceAtLeast(0f))

    /** Top-left of the bar: horizontally centred, [BOTTOM_MARGIN_DP] off the bottom. */
    fun stripOffset(windowWidth: Float, windowHeight: Float): BubbleOffset = BubbleOffset(
        x = (windowWidth - stripWidth(windowWidth)) / 2f,
        y = (windowHeight - BOTTOM_MARGIN_DP - BAR_HEIGHT_DP).coerceAtLeast(0f)
    )

    /** Top-left of the handle tab: centred, flush with the bottom edge. */
    fun tabOffset(windowWidth: Float, windowHeight: Float): BubbleOffset = BubbleOffset(
        x = (windowWidth - TAB_WIDTH_DP) / 2f,
        y = (windowHeight - TAB_HEIGHT_DP).coerceAtLeast(0f)
    )

    /**
     * Where the expanded panel goes: centred on the strip, directly
     * above it, clamped inside the window with
     * [TransferBubbleLayout.PANEL_MARGIN_DP] to spare.
     */
    fun panelOffset(
        panelWidth: Float,
        panelHeight: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        val strip = stripOffset(windowWidth, windowHeight)
        val margin = TransferBubbleLayout.PANEL_MARGIN_DP
        return BubbleOffset(
            x = (strip.x + stripWidth(windowWidth) / 2f - panelWidth / 2f).coerceIn(
                margin,
                (windowWidth - panelWidth - margin).coerceAtLeast(margin)
            ),
            y = (strip.y - PANEL_GAP_DP - panelHeight).coerceIn(
                margin,
                (windowHeight - panelHeight - margin).coerceAtLeast(margin)
            )
        )
    }
}
