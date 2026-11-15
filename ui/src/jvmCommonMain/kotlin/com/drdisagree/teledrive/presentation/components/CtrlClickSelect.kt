package com.drdisagree.teledrive.presentation.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Makes Ctrl + primary mouse click a selection gesture for desktop file/folder rows.
 *
 * The press is consumed in the Initial pass so the row's ordinary click action (open file
 * or enter folder) cannot also run for the same gesture. Normal clicks and touch gestures
 * continue through the existing combinedClickable handler.
 */
fun Modifier.ctrlClickSelect(onSelect: () -> Unit): Modifier =
    pointerInput(onSelect) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (
                    event.type == PointerEventType.Press &&
                    event.buttons.isPrimaryPressed &&
                    event.keyboardModifiers.isCtrlPressed
                ) {
                    val mouseChange = event.changes.firstOrNull { it.type == PointerType.Mouse }
                    if (mouseChange != null) {
                        mouseChange.consume()
                        onSelect()
                    }
                }
            }
        }
    }
