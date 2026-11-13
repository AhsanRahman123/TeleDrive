package com.drdisagree.teledrive.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Gives a collection surface a fallback keyboard target when no individual row owns focus.
 */
@Composable
fun rememberCollectionCommandFocusRequester(): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(requester) { requester.requestFocus() }
    return requester
}

/**
 * Handles commands at the collection boundary only. A callback returns false when the action
 * is unavailable (for example, Ctrl+C with no selection), allowing normal key handling to continue.
 *
 * By default commands bubble from focused children, preserving native text editing. A workspace
 * with no inline text editor can opt into preview interception so clickable rows and controls
 * cannot consume a Files-level shortcut before the workspace receives it.
 */
fun Modifier.collectionCommandShortcuts(
    focusRequester: FocusRequester,
    onSelectAll: (() -> Boolean)? = null,
    onCopy: (() -> Boolean)? = null,
    onCut: (() -> Boolean)? = null,
    onPaste: (() -> Boolean)? = null,
    onNewFolder: (() -> Boolean)? = null,
    onNewTab: (() -> Boolean)? = null,
    onDelete: (() -> Boolean)? = null,
    interceptBeforeChildren: Boolean = false
): Modifier {
    val handleShortcut: (KeyEvent) -> Boolean = { event ->
        val command = event.isCtrlPressed || event.isMetaPressed
        val plainCommand = command && !event.isAltPressed && !event.isShiftPressed
        val plain = !command && !event.isAltPressed && !event.isShiftPressed
        if (event.type != KeyEventType.KeyDown) {
            false
        } else if (plain && event.key == Key.Delete) {
            onDelete?.invoke() == true
        } else if (!plainCommand) {
            false
        } else {
            val action = when (event.key) {
                Key.A -> onSelectAll
                Key.C -> onCopy
                Key.X -> onCut
                Key.V -> onPaste
                Key.N -> onNewFolder
                Key.T -> onNewTab
                else -> null
            }
            action?.invoke() == true
        }
    }

    return this
        .focusRequester(focusRequester)
        .then(
            if (interceptBeforeChildren) {
                Modifier.onPreviewKeyEvent(handleShortcut)
            } else {
                Modifier.onKeyEvent(handleShortcut)
            }
        )
        .focusable()
}
