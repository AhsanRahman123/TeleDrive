package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import com.drdisagree.teledrive.domain.model.ViewMode

const val MIN_GRID_COLUMNS = 2
const val MAX_GRID_COLUMNS = 12

fun galleryGridColumnsForWindow(gridSize: Int, wideWindow: Boolean): Int =
    // The gallery always renders the selected column count. Wide windows used to add two
    // hidden columns here, which made the rendered grid disagree with the Settings value.
    gridSize.coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)

fun GridZoomLevel.zoomedIn(): GridZoomLevel = when {
    viewMode == ViewMode.LIST || viewMode == ViewMode.DETAILS ->
        GridZoomLevel(ViewMode.GRID, MAX_GRID_COLUMNS)
    else -> GridZoomLevel(ViewMode.GRID, (gridSize - 1).coerceAtLeast(MIN_GRID_COLUMNS))
}

fun GridZoomLevel.zoomedOut(): GridZoomLevel = when {
    viewMode == ViewMode.LIST || viewMode == ViewMode.DETAILS -> this
    gridSize >= MAX_GRID_COLUMNS -> GridZoomLevel(ViewMode.LIST, gridSize)
    else -> GridZoomLevel(ViewMode.GRID, gridSize + 1)
}

/**
 * Keep the two-finger touch gesture on every platform, but only install Ctrl+mouse-wheel
 * zoom on desktop. The gesture coroutines use updated callbacks rather than callback instances
 * as pointerInput keys, so ordinary recompositions do not restart active input handling.
 */
@Composable
fun Modifier.pinchZoom(onZoomIn: () -> Unit, onZoomOut: () -> Unit): Modifier {
    val zoomIn by rememberUpdatedState(onZoomIn)
    val zoomOut by rememberUpdatedState(onZoomOut)

    val wheelModifier = if (LocalAndroidLayout.current) {
        this
    } else {
        pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type != PointerEventType.Scroll) continue
                    if (!event.keyboardModifiers.isCtrlPressed) continue
                    val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                    when {
                        delta < 0f -> zoomIn()
                        delta > 0f -> zoomOut()
                    }
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }

    return wheelModifier.pointerInput(Unit) {
        awaitEachGesture {
            var accumulated = 1f
            var multitouch = false
            // One zoom step per gesture: a long two-finger scroll can cross the span
            // threshold several times, and stepping on every crossing made grid column
            // counts run away (e.g. straight to the densest level) during ordinary
            // browsing. A deliberate pinch still steps exactly once; lift and pinch
            // again for another step.
            var stepped = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    multitouch = true
                    accumulated *= event.calculateZoom()
                    if (!stepped) {
                        when {
                            accumulated >= STEP_THRESHOLD -> {
                                zoomIn()
                                stepped = true
                            }

                            accumulated <= 1f / STEP_THRESHOLD -> {
                                zoomOut()
                                stepped = true
                            }
                        }
                    }
                }
                if (multitouch) event.changes.forEach { it.consume() }
            } while (event.changes.any { it.pressed })
        }
    }
}

private const val STEP_THRESHOLD = 1.3f
