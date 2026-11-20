package com.drdisagree.teledrive.desktop.ui

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transfer bubble's desktop store (switch + dragged position). */
class DesktopTransferBubbleStoreTest {

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
