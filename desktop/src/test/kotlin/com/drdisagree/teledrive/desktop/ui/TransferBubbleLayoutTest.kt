package com.drdisagree.teledrive.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `sanitize keeps an in-window stored position`() {
        assertEquals(
            BubbleOffset(300f, 200f),
            TransferBubbleLayout.sanitizePosition(BubbleOffset(300f, 200f), 1200f, 800f)
        )
    }

    @Test
    fun `sanitize clamps a partly visible stored position instead of discarding it`() {
        // Saved on a larger monitor, read back on a smaller window: the
        // bubble is partly inside, so it clamps into view — the user's
        // chosen spot is honoured as closely as the window allows.
        assertEquals(
            BubbleOffset(1200f - 64f, 800f - 64f),
            TransferBubbleLayout.sanitizePosition(BubbleOffset(1150f, 780f), 1200f, 800f)
        )
        assertEquals(
            BubbleOffset(0f, 0f),
            TransferBubbleLayout.sanitizePosition(BubbleOffset(-30f, -30f), 1200f, 800f)
        )
    }

    @Test
    fun `sanitize sends a wholly off-window stored position to the default spot`() {
        val fallback = TransferBubbleLayout.defaultOffset(1200f, 800f)
        assertEquals(
            fallback,
            TransferBubbleLayout.sanitizePosition(BubbleOffset(5000f, 300f), 1200f, 800f)
        )
        assertEquals(
            fallback,
            TransferBubbleLayout.sanitizePosition(BubbleOffset(-500f, -500f), 1200f, 800f)
        )
        assertEquals(
            fallback,
            TransferBubbleLayout.sanitizePosition(BubbleOffset(300f, 900f), 1200f, 800f)
        )
    }

    @Test
    fun `sanitize sends non-finite stored positions to the default spot`() {
        val fallback = TransferBubbleLayout.defaultOffset(1200f, 800f)
        assertEquals(
            fallback,
            TransferBubbleLayout.sanitizePosition(BubbleOffset(Float.NaN, 100f), 1200f, 800f)
        )
        assertEquals(
            fallback,
            TransferBubbleLayout.sanitizePosition(
                BubbleOffset(100f, Float.POSITIVE_INFINITY), 1200f, 800f
            )
        )
        assertEquals(
            fallback,
            TransferBubbleLayout.sanitizePosition(
                BubbleOffset(Float.NEGATIVE_INFINITY, Float.NaN), 1200f, 800f
            )
        )
    }

    @Test
    fun `drag deltas land on the effective offset not the raw state`() {
        // A raw state far outside the window displays clamped at the
        // bottom-right corner; a small drag must move the bubble from
        // THERE — accumulating onto the raw state instead would leave
        // the visible bubble pinned until the raw value re-entered the
        // window, then jump.
        val effective = TransferBubbleLayout.clampOffset(5000f, 5000f, 1200f, 800f)
        assertEquals(
            BubbleOffset(1200f - 64f - 10f, 800f - 64f - 20f),
            TransferBubbleLayout.movedBy(effective, -10f, -20f, 1200f, 800f)
        )
        // A delta that would leave the window still clamps.
        assertEquals(
            BubbleOffset(0f, 0f),
            TransferBubbleLayout.movedBy(effective, -5000f, -5000f, 1200f, 800f)
        )
        // From an ordinary in-window position the delta applies exactly.
        assertEquals(
            BubbleOffset(310f, 180f),
            TransferBubbleLayout.movedBy(BubbleOffset(300f, 200f), 10f, -20f, 1200f, 800f)
        )
    }

    @Test
    fun `a cancelled drag restores the start offset and leaves nothing to persist`() {
        val session = BubbleDragSession()
        assertFalse(session.active)
        session.begin(BubbleOffset(100f, 200f))
        assertTrue(session.active)
        assertEquals(BubbleOffset(100f, 200f), session.cancel())
        assertFalse(session.active)
        // After a cancel there is nothing left to complete or persist.
        assertNull(session.complete(BubbleOffset(300f, 300f)))
        assertNull(session.cancel())
    }

    @Test
    fun `a completed drag yields the final offset exactly once`() {
        val session = BubbleDragSession()
        // Completing with no drag in progress persists nothing.
        assertNull(session.complete(BubbleOffset(1f, 2f)))
        session.begin(BubbleOffset(100f, 200f))
        assertEquals(
            BubbleOffset(150f, 260f),
            session.complete(BubbleOffset(150f, 260f))
        )
        assertFalse(session.active)
        // A second completion (or a stray cancel) yields nothing.
        assertNull(session.complete(BubbleOffset(150f, 260f)))
        assertNull(session.cancel())
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
