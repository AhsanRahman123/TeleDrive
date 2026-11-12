package com.drdisagree.teledrive.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Gives a collection surface a fallback keyboard target when no individual row owns focus.
 * The event bubbles from focused child controls, so text fields retain their native Ctrl+A/C/X/V.
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
 */
fun Modifier.collectionCommandShortcuts(
    focusRequester: FocusRequester,
    onSelectAll: (() -> Boolean)? = null,
    onCopy: (() -> Boolean)? = null,
    onCut: (() -> Boolean)? = null,
    onPaste: (() -> Boolean)? = null,
    onNewFolder: (() -> Boolean)? = null,
    onNewTab: (() -> Boolean)? = null
): Modifier = this
    .focusRequester(focusRequester)
    .onKeyEvent { event ->
        val command = event.isCtrlPressed || event.isMetaPressed
        val plainCommand = command && !event.isAltPressed && !event.isShiftPressed
        if (event.type != KeyEventType.KeyDown || !plainCommand) {
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
    .focusable()
