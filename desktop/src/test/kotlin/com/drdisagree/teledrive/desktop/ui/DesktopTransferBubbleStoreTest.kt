package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.presentation.platform.TransferWidgetColor
import com.drdisagree.teledrive.presentation.platform.TransferWidgetStyle
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transfer widget's desktop store (switch, style, colour, position). */
class DesktopTransferBubbleStoreTest {

    @Test
    fun `widget style defaults to bubble and round trips`() = withTemporaryPreferences { node ->
        assertEquals(
            TransferWidgetStyle.BUBBLE,
            DesktopTransferBubbleStore.loadStyle(node)
        )
        DesktopTransferBubbleStore.saveStyle(node, TransferWidgetStyle.BOTTOM_STRIP)
        assertEquals(
            TransferWidgetStyle.BOTTOM_STRIP,
            DesktopTransferBubbleStore.loadStyle(node)
        )
        DesktopTransferBubbleStore.saveStyle(node, TransferWidgetStyle.BUBBLE)
        assertEquals(
            TransferWidgetStyle.BUBBLE,
            DesktopTransferBubbleStore.loadStyle(node)
        )
    }

    @Test
    fun `widget color defaults to app accent and round trips every preset`() =
        withTemporaryPreferences { node ->
            assertEquals(
                TransferWidgetColor.APP_ACCENT,
                DesktopTransferBubbleStore.loadColor(node)
            )
            for (preset in TransferWidgetColor.entries) {
                DesktopTransferBubbleStore.saveColor(node, preset)
                assertEquals(preset, DesktopTransferBubbleStore.loadColor(node))
            }
        }

    @Test
    fun `an unknown stored style or color falls back to the defaults`() =
        withTemporaryPreferences { node ->
            node.put("widget_style", "NOT_A_STYLE")
            node.put("widget_color", "NOT_A_COLOR")
            assertEquals(
                TransferWidgetStyle.BUBBLE,
                DesktopTransferBubbleStore.loadStyle(node)
            )
            assertEquals(
                TransferWidgetColor.APP_ACCENT,
                DesktopTransferBubbleStore.loadColor(node)
            )
        }

    @Test
    fun `style and color live beside the switch without disturbing it`() =
        withTemporaryPreferences { node ->
            DesktopTransferBubbleStore.saveEnabled(node, false)
            DesktopTransferBubbleStore.saveStyle(node, TransferWidgetStyle.BOTTOM_STRIP)
            DesktopTransferBubbleStore.saveColor(node, TransferWidgetColor.ORANGE)
            assertFalse(DesktopTransferBubbleStore.loadEnabled(node))
            assertEquals(
                TransferWidgetStyle.BOTTOM_STRIP,
                DesktopTransferBubbleStore.loadStyle(node)
            )
            assertEquals(
                TransferWidgetColor.ORANGE,
                DesktopTransferBubbleStore.loadColor(node)
            )
        }

    @Test
    fun `the switch defaults to on and round trips`() = withTemporaryPreferences { node ->
        assertTrue(DesktopTransferBubbleStore.loadEnabled(node))
        DesktopTransferBubbleStore.saveEnabled(node, false)
        assertFalse(DesktopTransferBubbleStore.loadEnabled(node))
        DesktopTransferBubbleStore.saveEnabled(node, true)
        assertTrue(DesktopTransferBubbleStore.loadEnabled(node))
    }

    @Test
    fun `no stored position reads as never dragged`() = withTemporaryPreferences { node ->
        assertNull(DesktopTransferBubbleStore.loadPosition(node))
    }

    @Test
    fun `non-finite stored positions are rejected`() = withTemporaryPreferences { node ->
        node.putFloat("position_x_dp", Float.POSITIVE_INFINITY)
        node.putFloat("position_y_dp", 100f)
        node.flush()
        assertNull(DesktopTransferBubbleStore.loadPosition(node))
        node.putFloat("position_x_dp", 100f)
        node.putFloat("position_y_dp", Float.NEGATIVE_INFINITY)
        node.flush()
        assertNull(DesktopTransferBubbleStore.loadPosition(node))
        node.putFloat("position_x_dp", Float.NaN)
        node.putFloat("position_y_dp", 100f)
        node.flush()
        assertNull(DesktopTransferBubbleStore.loadPosition(node))
    }

    @Test
    fun `a saved position round trips exactly`() = withTemporaryPreferences { node ->
        DesktopTransferBubbleStore.savePosition(node, 123.5f, 456.25f)
        val loaded = DesktopTransferBubbleStore.loadPosition(node)
        assertEquals(123.5f, loaded!!.first, 0.001f)
        assertEquals(456.25f, loaded.second, 0.001f)
    }

    @Test
    fun `switch and position live side by side without disturbing each other`() =
        withTemporaryPreferences { node ->
            DesktopTransferBubbleStore.saveEnabled(node, false)
            DesktopTransferBubbleStore.savePosition(node, 10f, 20f)
            assertFalse(DesktopTransferBubbleStore.loadEnabled(node))
            assertEquals(10f, DesktopTransferBubbleStore.loadPosition(node)!!.first, 0.001f)
            DesktopTransferBubbleStore.saveEnabled(node, true)
            assertEquals(20f, DesktopTransferBubbleStore.loadPosition(node)!!.second, 0.001f)
        }

    private fun withTemporaryPreferences(block: (Preferences) -> Unit) {
        val node = Preferences.userRoot()
            .node("teledrive-tests/transfer-bubble-${UUID.randomUUID()}")
        try {
            block(node)
        } finally {
            runCatching { node.removeNode() }
            runCatching { node.flush() }
        }
    }
}
