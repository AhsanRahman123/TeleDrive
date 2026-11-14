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
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent

/**
 * Android's Compose drag-and-drop API exposes Android ClipData and platform flags;
 * it does not expose the desktop Copy/Move action model. Android in-app tab drags
 * therefore retain the existing move behaviour.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.fileTabDragSource(payload: () -> String): Modifier =
    dragAndDropSource {
        val encodedPayload = payload()
        if (encodedPayload.isBlank()) {
            null
        } else {
            DragAndDropTransferData(
                clipData = ClipData.newPlainText("TeleDrive files", encodedPayload)
            )
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
                    event.toAndroidDragEvent().clipData
                        ?.getItemAt(0)?.text?.toString()
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: return false

                // Android DragEvent has no Copy/Move action field. Match the
                // Android touch interaction's established default: move.
                return latestOnDrop.value(payload, false)
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
