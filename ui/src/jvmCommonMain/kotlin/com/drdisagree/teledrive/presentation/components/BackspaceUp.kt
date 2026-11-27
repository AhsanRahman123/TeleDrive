package com.drdisagree.teledrive.presentation.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

fun Modifier.backspaceUp(onUp: (() -> Unit)?): Modifier = onPreviewKeyEvent { event ->
    val up = event.key == Key.Backspace ||
            event.isAltPressed && event.key == Key.DirectionLeft
    if (onUp == null || !up || event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
    KeyboardHandoff.armed = true
    onUp()
    true
}

/**
 * P2 (1.6.26): desktop Escape as a dialog back key. A desktop Dialog
 * otherwise routes Escape through its NavigationEvent back handler
 * straight to dismiss (DialogProperties.dismissOnBackPress, default
 * true — the PC-30 lesson from the upload picker), so one press closed
 * the folder pickers outright, skipping their up-one-level step. The
 * caller pairs this with `dismissOnBackPress = false` on desktop only:
 * Escape then dispatches as a normal key and runs the dialog's own back
 * ladder ([onEscape] steps up a level, or dismisses at the root). On
 * touch platforms this is a no-op — there is no Escape key, and the
 * system back button keeps its default dismiss.
 */
fun Modifier.dialogEscapeBack(enabled: Boolean, onEscape: () -> Unit): Modifier =
    if (!enabled) {
        this
    } else {
        onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                onEscape()
                true
            } else {
                false
            }
        }
    }
