package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.presentation.platform.DEFAULT_TRANSFERS_SORT_ORDER
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Test

/** The desktop Transfers sort store (PC-6). */
class DesktopTransfersSortPreferenceTest {

    @Test
    fun `nothing stored yields the current default`() = withTemporaryPreferences { node ->
        assertEquals(DEFAULT_TRANSFERS_SORT_ORDER, DesktopTransfersSortPreference.load(node))
        assertEquals("PRIORITY", DesktopTransfersSortPreference.load(node))
    }

    @Test
    fun `a saved order is restored`() = withTemporaryPreferences { node ->
        DesktopTransfersSortPreference.save(node, "NEWEST")
        assertEquals("NEWEST", DesktopTransfersSortPreference.load(node))
    }

    @Test
    fun `an unknown stored value falls back to the default`() = withTemporaryPreferences { node ->
        node.put("sort_order", "BY_MAGIC")
        assertEquals(DEFAULT_TRANSFERS_SORT_ORDER, DesktopTransfersSortPreference.load(node))
    }

    @Test
    fun `saving an unknown value stores the default instead`() = withTemporaryPreferences { node ->
        DesktopTransfersSortPreference.save(node, "BY_MAGIC")
        assertEquals(DEFAULT_TRANSFERS_SORT_ORDER, DesktopTransfersSortPreference.load(node))
    }

    private fun withTemporaryPreferences(block: (Preferences) -> Unit) {
        val node = Preferences.userRoot()
            .node("teledrive-tests/transfers-sort-${UUID.randomUUID()}")
        try {
            block(node)
        } finally {
            runCatching { node.removeNode() }
            runCatching { node.flush() }
        }
    }
}
