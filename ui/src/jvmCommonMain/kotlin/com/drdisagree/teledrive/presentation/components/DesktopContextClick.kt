package com.drdisagree.teledrive.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Adds a mouse secondary-click handler without changing touch long-press selection.
 * The callback fires once per secondary-button press and the event is consumed so the
 * platform context menu does not race the app's own selection actions.
 */
@Composable
fun Modifier.onDesktopContextClick(onContextClick: () -> Unit): Modifier {
    val currentOnContextClick by rememberUpdatedState(onContextClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                var event = awaitPointerEvent(PointerEventPass.Initial)
                if (
                    event.type == PointerEventType.Press &&
                    event.buttons.isSecondaryPressed
                ) {
                    currentOnContextClick()
                    event.changes.forEach { it.consume() }
                    do {
                        event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.buttons.isSecondaryPressed)
                }
            }
        }
    }
}
