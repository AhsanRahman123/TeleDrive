package com.drdisagree.teledrive.presentation.files

import android.content.ClipData
import android.content.ClipDescription
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.toAndroidDragEvent

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileTabDragSource(payload: () -> String): Modifier =
    dragAndDropSource { offset ->
        DragAndDropTransferData(
            ClipData.newPlainText("TeleDrive files", payload()),
            supportedActions = listOf(
                DragAndDropTransferAction.Move,
                DragAndDropTransferAction.Copy
            ),
            dragDecorationOffset = offset
        )
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
                    event.toAndroidDragEvent().clipData
                        ?.getItemAt(0)?.text?.toString()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false
                val copy = event.action == DragAndDropTransferAction.Copy
                return latestOnDrop.value(payload, copy)
            }
        }
    }
    Box(
        modifier = modifier.dragAndDropTarget(
            shouldStartDragAndDrop = { event ->
                event.mimeTypes().contains(ClipDescription.MIMETYPE_TEXT_PLAIN)
            },
            target = target
        )
    ) {
        content()
    }
}
