package com.drdisagree.teledrive.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
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


/**
 * Handles a secondary click only when no child item consumed it. Attach this to a browsing
 * container: row/folder context handlers consume their own press in the Initial pass, so the
 * parent only opens its blank-space action when the click lands on unused content.
 */
@Composable
fun Modifier.onDesktopEmptySpaceContextClick(
    onContextClick: () -> Unit,
    onEmptySpaceClick: () -> Unit = {}
): Modifier {
    val currentOnContextClick by rememberUpdatedState(onContextClick)
    val currentOnEmptySpaceClick by rememberUpdatedState(onEmptySpaceClick)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                var event = awaitPointerEvent(PointerEventPass.Main)
                if (
                    event.type == PointerEventType.Press &&
                    event.changes.none { it.isConsumed }
                ) {
                    when {
                        event.buttons.isSecondaryPressed -> {
                            currentOnContextClick()
                            event.changes.forEach { it.consume() }
                            do {
                                event = awaitPointerEvent(PointerEventPass.Main)
                            } while (event.buttons.isSecondaryPressed)
                        }
                        event.buttons.isPrimaryPressed -> currentOnEmptySpaceClick()
                    }
                }
            }
        }
    }
}
