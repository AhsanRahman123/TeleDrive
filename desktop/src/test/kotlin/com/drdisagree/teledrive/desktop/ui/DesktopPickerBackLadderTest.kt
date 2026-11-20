package com.drdisagree.teledrive.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-22(b): ONE back ladder for the picker, run identically by Escape
 * and by the title back button, in file mode and folder mode alike:
 * clear the search → clear the selection → up one folder level → close
 * only at the top. Before the unification, folder mode's Esc could
 * bypass the ladder entirely (focus outside the content column let the
 * dialog's own dismiss close the whole picker) and its back button
 * called stepUp() directly, skipping the clear steps.
 *
 * These tests pin the shared decision function and then drive a
 * folder-mode picker state through it, press by press.
 */
class DesktopPickerBackLadderTest {

    @Test
    fun `search is cleared before anything else, even with a selection and a parent`() {
        assertEquals(
            PickerBackAction.CLEAR_SEARCH,
            pickerBackAction(search = "phx", hasSelection = true, hasParent = true)
        )
        assertEquals(
            PickerBackAction.CLEAR_SEARCH,
            pickerBackAction(search = "phx", hasSelection = false, hasParent = false)
        )
    }

    @Test
    fun `after the search step the ladder is exactly the PC-15 escape ladder`() {
        // For a blank search, pickerBackAction must agree with
        // pickerEscapeAction on every combination — the two are one
        // ladder, not two that can drift.
        for (hasSelection in listOf(false, true)) {
            for (hasParent in listOf(false, true)) {
                val expected = when (pickerEscapeAction(hasSelection, hasParent)) {
                    PickerEscapeAction.CLEAR_SELECTION -> PickerBackAction.CLEAR_SELECTION
                    PickerEscapeAction.NAVIGATE_UP -> PickerBackAction.NAVIGATE_UP
                    PickerEscapeAction.CLOSE -> PickerBackAction.CLOSE
                }
                assertEquals(
                    "selection=$hasSelection parent=$hasParent",
                    expected,
                    pickerBackAction(search = "  ", hasSelection = hasSelection, hasParent = hasParent)
                )
            }
        }
    }

    /** A folder-mode picker reduced to the state the ladder reads. */
    private class FolderPickerState(
        var depth: Int,
        var search: String = "",
        var hasSelection: Boolean = false
    ) {
        var closed = false
            private set

        /** One press of Esc — or of the back button; they are the same call. */
        fun back(): PickerBackAction {
            val action = pickerBackAction(search, hasSelection, hasParent = depth > 0)
            when (action) {
                PickerBackAction.CLEAR_SEARCH -> search = ""
                PickerBackAction.CLEAR_SELECTION -> hasSelection = false
                PickerBackAction.NAVIGATE_UP -> depth--
                PickerBackAction.CLOSE -> closed = true
            }
            return action
        }
    }

    @Test
    fun `folder mode - from a deep folder each press steps once and only the last closes`() {
        val picker = FolderPickerState(depth = 3, search = "phx", hasSelection = true)
        val actions = mutableListOf<PickerBackAction>()
        repeat(5) { actions += picker.back() }
        assertFalse("picker must still be open before the final step", picker.closed)
        assertEquals(0, picker.depth)
        actions += picker.back()
        assertTrue(picker.closed)
        assertEquals(
            listOf(
                PickerBackAction.CLEAR_SEARCH,
                PickerBackAction.CLEAR_SELECTION,
                PickerBackAction.NAVIGATE_UP,
                PickerBackAction.NAVIGATE_UP,
                PickerBackAction.NAVIGATE_UP,
                PickerBackAction.CLOSE
            ),
            actions
        )
    }

    @Test
    fun `folder mode - one press from a deep folder goes up exactly one level`() {
        val picker = FolderPickerState(depth = 2)
        assertEquals(PickerBackAction.NAVIGATE_UP, picker.back())
        assertEquals(1, picker.depth)
        assertFalse(picker.closed)
        assertEquals(PickerBackAction.NAVIGATE_UP, picker.back())
        assertEquals(0, picker.depth)
        assertFalse(picker.closed)
        assertEquals(PickerBackAction.CLOSE, picker.back())
        assertTrue(picker.closed)
    }

    @Test
    fun `at the top with nothing to clear, one press closes`() {
        val picker = FolderPickerState(depth = 0)
        assertEquals(PickerBackAction.CLOSE, picker.back())
        assertTrue(picker.closed)
    }
}
