package com.drdisagree.teledrive.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transfer bubble's visibility decision and layout geometry. */
class TransferBubbleLayoutTest {

    @Test
    fun `visibility priority is switch then fullscreen then user hide`() {
        assertEquals(
            BubbleVisibility.GONE,
            bubbleVisibility(enabled = false, fullscreen = false, userHidden = false)
        )
        assertEquals(
            BubbleVisibility.GONE,
            bubbleVisibility(enabled = false, fullscreen = true, userHidden = true)
        )
        assertEquals(
            BubbleVisibility.GONE,
            bubbleVisibility(enabled = true, fullscreen = true, userHidden = false)
        )
        assertEquals(
            BubbleVisibility.GONE,
            bubbleVisibility(enabled = true, fullscreen = true, userHidden = true)
        )
        assertEquals(
            BubbleVisibility.TAB,
            bubbleVisibility(enabled = true, fullscreen = false, userHidden = true)
        )
        assertEquals(
            BubbleVisibility.BUBBLE,
            bubbleVisibility(enabled = true, fullscreen = false, userHidden = false)
        )
    }

    @Test
    fun `default spot sits above the create button with clearance`() {
        val windowWidth = 1200f
        val windowHeight = 800f
        val offset = TransferBubbleLayout.defaultOffset(windowWidth, windowHeight)

        // Right-aligned with the standard edge margin.
        assertEquals(
            windowWidth - TransferBubbleLayout.EDGE_MARGIN_DP -
                TransferBubbleLayout.BUBBLE_SIZE_DP,
            offset.x, 0.001f
        )
        // The bubble's bottom edge is FAB_CLEARANCE above the FAB's top
        // edge: their vertical ranges never meet.
        val bubbleBottomFromWindowBottom =
            windowHeight - (offset.y + TransferBubbleLayout.BUBBLE_SIZE_DP)
        assertEquals(
            TransferBubbleLayout.FAB_CLEARANCE_DP,
            bubbleBottomFromWindowBottom - TransferBubbleLayout.fabTopFromBottomDp,
            0.001f
        )
        val fabTopY = windowHeight - TransferBubbleLayout.fabTopFromBottomDp
        assertTrue(offset.y + TransferBubbleLayout.BUBBLE_SIZE_DP < fabTopY)
    }

    @Test
    fun `clamp keeps the whole bubble inside the window`() {
        assertEquals(
            BubbleOffset(0f, 0f),
            TransferBubbleLayout.clampOffset(-50f, -20f, 1200f, 800f)
        )
        assertEquals(
            BubbleOffset(1200f - 64f, 800f - 64f),
            TransferBubbleLayout.clampOffset(5000f, 5000f, 1200f, 800f)
        )
        // A window smaller than the bubble clamps to the origin, never negative.
        assertEquals(
            BubbleOffset(0f, 0f),
            TransferBubbleLayout.clampOffset(10f, 10f, 50f, 40f)
        )
    }

    @Test
    fun `panel right-aligns with the bubble and opens above it`() {
        val bubble = BubbleOffset(1120f, 648f)
        val panel = TransferBubbleLayout.panelOffset(
            bubble = bubble,
            panelWidth = 352f,
            panelHeight = 460f,
            windowWidth = 1200f,
            windowHeight = 800f
        )
        assertEquals(bubble.x + 64f, panel.x + 352f, 0.001f)
        assertEquals(
            bubble.y - TransferBubbleLayout.PANEL_GAP_DP - 460f,
            panel.y, 0.001f
        )
    }

    @Test
    fun `panel opens below when above does not fit`() {
        val bubble = BubbleOffset(1120f, 20f)
        val panel = TransferBubbleLayout.panelOffset(
            bubble = bubble,
            panelWidth = 352f,
            panelHeight = 460f,
            windowWidth = 1200f,
            windowHeight = 800f
        )
        assertEquals(
            bubble.y + 64f + TransferBubbleLayout.PANEL_GAP_DP,
            panel.y, 0.001f
        )
    }

    @Test
    fun `panel clamps inside a small window`() {
        val panel = TransferBubbleLayout.panelOffset(
            bubble = BubbleOffset(400f, 500f),
            panelWidth = 352f,
            panelHeight = 460f,
            windowWidth = 480f,
            windowHeight = 600f
        )
        assertTrue(panel.x >= TransferBubbleLayout.PANEL_MARGIN_DP)
        assertTrue(panel.x + 352f <= 480f - TransferBubbleLayout.PANEL_MARGIN_DP + 0.001f)
        assertTrue(panel.y >= TransferBubbleLayout.PANEL_MARGIN_DP)
        assertTrue(panel.y + 460f <= 600f - TransferBubbleLayout.PANEL_MARGIN_DP + 0.001f)
    }

    @Test
    fun `tab hugs the nearest edge centred on the bubble`() {
        val rightBubble = BubbleOffset(1120f, 648f)
        val rightTab = TransferBubbleLayout.tabOffset(rightBubble, 1200f, 800f)
        assertEquals(1200f - TransferBubbleLayout.TAB_WIDTH_DP, rightTab.x, 0.001f)
        assertEquals(
            648f + 32f - TransferBubbleLayout.TAB_HEIGHT_DP / 2f,
            rightTab.y, 0.001f
        )
        assertFalse(TransferBubbleLayout.tabOnLeftEdge(rightBubble, 1200f))

        val leftBubble = BubbleOffset(16f, 100f)
        val leftTab = TransferBubbleLayout.tabOffset(leftBubble, 1200f, 800f)
        assertEquals(0f, leftTab.x, 0.001f)
        assertTrue(TransferBubbleLayout.tabOnLeftEdge(leftBubble, 1200f))

        // Near the top the tab clamps instead of sliding off-window.
        val topTab = TransferBubbleLayout.tabOffset(BubbleOffset(16f, 0f), 1200f, 800f)
        assertEquals(4f, topTab.y, 0.001f)
    }
}
