package com.drdisagree.teledrive.desktop.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PC-30 / PC-29, exercised against the REAL picker dialog: one Escape
 * press performs exactly one back-ladder step (clear selection, clear
 * search, up one folder, close only at a filesystem root) instead of
 * dismissing the whole dialog, and Ctrl+A / the select-all button pick
 * every visible file without touching the search field's own Ctrl+A.
 *
 * On the pre-PC-30 code the first Escape already closed the dialog:
 * Compose Multiplatform's desktop Dialog routed the key through its
 * NavigationEvent back handler (dismissOnBackPress defaulted true)
 * straight to onDismissRequest, before any content key handler ran.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopUploadPickerDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private fun fixture(): Pair<File, File> {
        val base = java.nio.file.Files.createTempDirectory("picker-dialog").toFile()
        val child = File(base, "child").apply { mkdirs() }
        File(child, "a.txt").writeText("a")
        File(child, "b.txt").writeText("b")
        File(child, "sub").mkdirs()
        return base to child
    }

    @Test
    fun `escape walks the ladder one step at a time and closes only at a root`() {
        val (base, child) = fixture()
        val cancelled = AtomicBoolean(false)
        rule.setContent {
            DesktopUploadPickerDialog(
                mode = DesktopPickerMode.FILES,
                multiSelect = true,
                initialDirectory = child,
                onDirectoryChanged = {},
                onPicked = {},
                onCancel = { cancelled.set(true) }
            )
        }
        waitForText("a.txt")
        rule.onNodeWithText(child.absolutePath).assertIsDisplayed()

        // Select a file, then Escape: exactly the selection clears.
        rule.onNodeWithText("a.txt").performClick()
        waitForText("Select (1)")
        pressEscape()
        waitForText("Select (0)")
        assertFalse("one Escape must not close the picker", cancelled.get())
        rule.onNodeWithText(child.absolutePath).assertIsDisplayed()

        // Type a search, then Escape: exactly the search clears.
        rule.onNode(hasSetTextAction()).performTextInput("a")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("b.txt").fetchSemanticsNodes().isEmpty()
        }
        pressEscape()
        waitForText("b.txt")
        assertFalse(cancelled.get())
        rule.onNodeWithText(child.absolutePath).assertIsDisplayed()

        // Mouse-click navigation into a subfolder, then Escape: back
        // up exactly one level, dialog still open (round 8: the state
        // the user is in when the physical key must work — focus sits
        // wherever the click left it, not on a handler's node).
        rule.onNodeWithText("sub").performClick()
        waitForText(File(child, "sub").absolutePath)
        pressEscape()
        waitForText(child.absolutePath)
        rule.onNodeWithText("a.txt").assertIsDisplayed()
        assertFalse(cancelled.get())

        // Escape with nothing to clear: up exactly one folder.
        pressEscape()
        waitForText(base.absolutePath)
        assertFalse(cancelled.get())

        // Keep pressing: each press climbs exactly one level; only the
        // press at a filesystem root (no parent) closes the picker.
        var current = base
        var guard = 0
        while (!cancelled.get() && guard++ < 16) {
            val parent = current.parentFile
            pressEscape()
            if (parent == null) {
                rule.waitForIdle()
                assertTrue("Escape at a root closes the picker", cancelled.get())
            } else {
                waitForText(parent.absolutePath)
                assertFalse("Escape above a root must not close the picker", cancelled.get())
                current = parent
            }
        }
        assertTrue(cancelled.get())
        base.deleteRecursively()
    }

    @Test
    fun `escape steps up one folder in folder mode too`() {
        val (base, child) = fixture()
        val cancelled = AtomicBoolean(false)
        rule.setContent {
            DesktopUploadPickerDialog(
                mode = DesktopPickerMode.FOLDER,
                multiSelect = false,
                initialDirectory = child,
                onDirectoryChanged = {},
                onPicked = {},
                onCancel = { cancelled.set(true) }
            )
        }
        waitForText("sub")
        // Click into the subfolder first (the real folder-picking
        // flow), then walk back out one Escape at a time. The search
        // field is clicked after the navigation to give the dialog a
        // persistent focus home: the clicked row leaves the
        // composition when the listing reloads, and key input in the
        // test scene needs a live focus target (in the files-mode test
        // the search field plays this role after the search step).
        rule.onNodeWithText("sub").performClick()
        waitForText(File(child, "sub").absolutePath)
        rule.onNode(hasSetTextAction()).performClick()
        pressEscape()
        waitForText(child.absolutePath)
        assertFalse("one Escape in folder mode must not close the picker", cancelled.get())
        pressEscape()
        waitForText(base.absolutePath)
        assertFalse("Escape above a root must not close the picker", cancelled.get())
        base.deleteRecursively()
    }

    @Test
    fun `ctrl+a selects every visible file and the search field keeps its own ctrl+a`() {
        val (_, child) = fixture()
        val cancelled = AtomicBoolean(false)
        rule.setContent {
            DesktopUploadPickerDialog(
                mode = DesktopPickerMode.FILES,
                multiSelect = true,
                initialDirectory = child,
                onDirectoryChanged = {},
                onPicked = {},
                onCancel = { cancelled.set(true) }
            )
        }
        waitForText("a.txt")

        // Ctrl+A with the listing focused: both files (not the folder).
        rule.onNodeWithText("a.txt").performClick()
        rule.onNodeWithText("a.txt").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        // The click selected a.txt; Ctrl+A must now show both files.
        waitForText("Select (2)")

        // Ctrl+A is a TOGGLE (PC-29b): a second press deselects all,
        // a third selects all again. On the pre-round-8 handler the
        // second press just reselected and the count never dropped.
        rule.onNodeWithText("a.txt").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        waitForText("Select (0)")
        rule.onNodeWithText("a.txt").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        waitForText("Select (2)")

        // The select-all button toggles back to an empty selection.
        rule.onNodeWithContentDescription("Deselect all").performClick()
        waitForText("Select (0)")
        rule.onNodeWithContentDescription("Select all").performClick()
        waitForText("Select (2)")

        // With the search field focused, Ctrl+A is the FIELD's select
        // text, never the picker's select-all: type a query that hides
        // every file, press Ctrl+A, and the existing selection must be
        // untouched (a hijack would replace it with the empty visible
        // set and the count would drop to Select (0)).
        rule.onNode(hasSetTextAction()).performTextInput("zz")
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText("No files here").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNode(hasSetTextAction()).performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
        waitForText("Select (2)")

        assertFalse(cancelled.get())
        child.parentFile?.deleteRecursively()
    }

    private fun pressEscape() {
        // Key input goes to the focused element of the dialog's scene;
        // the search field node is only the stable handle into that scene.
        rule.onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Escape) }
    }

    private fun waitForText(text: String) {
        rule.waitUntil(10_000) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
    }
}
