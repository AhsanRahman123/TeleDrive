package com.drdisagree.teledrive.presentation.files

import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileTabDragSource(payload: () -> String): Modifier {
    val windowInfo = LocalWindowInfo.current
    val latestPayload = rememberUpdatedState(payload)
    return dragAndDropSource { offset ->
        // Keep ordinary left-drag available for range selection. Hold Shift
        // to start the separate native file-to-tab transfer gesture.
        if (!windowInfo.keyboardModifiers.isShiftPressed) {
            null
        } else {
            val encodedPayload = latestPayload.value()
            if (encodedPayload.isBlank()) {
                null
            } else {
                DragAndDropTransferData(
                    transferable = DragAndDropTransferable(StringSelection(encodedPayload)),
                    supportedActions = listOf(
                        DragAndDropTransferAction.Move,
                        DragAndDropTransferAction.Copy
                    ),
                    dragDecorationOffset = offset
                )
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun FileTabDropTarget(
    modifier: Modifier,
    onDrop: (payload: String, copy: Boolean) -> Boolean,
    content: @Composable () -> Unit
) {
    val latestOnDrop = rememberUpdatedState(onDrop)
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val payload = runCatching {
                    val transferable = event.awtTransferable
                    if (!transferable.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
                    transferable.getTransferData(DataFlavor.stringFlavor) as? String
                }.getOrNull() ?: return false
                val copy = event.action == DragAndDropTransferAction.Copy
                return latestOnDrop.value(payload, copy)
            }
        }
    }
    Box(
        modifier = modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.awtTransferable.isDataFlavorSupported(DataFlavor.stringFlavor)
            },
            target = target
        )
    ) {
        content()
    }
}
