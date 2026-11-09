package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull

/**
 * The pressed item becomes the anchor; holding near an edge keeps the list scrolling so a range can
 * run past the viewport.
 */
@Composable
fun rememberDragSelect(
    gridState: LazyGridState,
    onStart: () -> Unit,
    isSelected: (Int) -> Boolean,
    onRange: (IntRange, shouldSelect: Boolean) -> Unit,
    onEnd: () -> Unit,
    edgeSize: Dp = EDGE_SIZE,
    scrollPerFrame: Dp = SCROLL_PER_FRAME
): Modifier = dragSelect(
    scrollable = gridState,
    indexAt = { offset ->
        gridState.layoutInfo.visibleItemsInfo.fastFirstOrNull { item ->
            offset.x >= item.offset.x &&
                    offset.x <= item.offset.x + item.size.width &&
                    offset.y >= item.offset.y &&
                    offset.y <= item.offset.y + item.size.height
        }?.index
    },
    onStart = onStart,
    isSelected = isSelected,
    onRange = onRange,
    onEnd = onEnd,
    edgeSize = edgeSize,
    scrollPerFrame = scrollPerFrame
)

@Composable
fun rememberDragSelect(
    listState: LazyListState,
    onStart: () -> Unit,
    isSelected: (Int) -> Boolean,
    onRange: (IntRange, shouldSelect: Boolean) -> Unit,
    onEnd: () -> Unit,
    edgeSize: Dp = EDGE_SIZE,
    scrollPerFrame: Dp = SCROLL_PER_FRAME
): Modifier = dragSelect(
    scrollable = listState,
    indexAt = { offset ->
        listState.layoutInfo.visibleItemsInfo.fastFirstOrNull { item ->
            offset.y >= item.offset && offset.y <= item.offset + item.size
        }?.index
    },
    onStart = onStart,
    isSelected = isSelected,
    onRange = onRange,
    onEnd = onEnd,
    edgeSize = edgeSize,
    scrollPerFrame = scrollPerFrame
)

@Composable
private fun dragSelect(
    scrollable: ScrollableState,
    indexAt: (Offset) -> Int?,
    onStart: () -> Unit,
    isSelected: (Int) -> Boolean,
    onRange: (IntRange, shouldSelect: Boolean) -> Unit,
    onEnd: () -> Unit,
    edgeSize: Dp,
    scrollPerFrame: Dp
): Modifier {
    val density = LocalDensity.current
    val edgePx = with(density) { edgeSize.toPx() }
    val maxScrollPx = with(density) { scrollPerFrame.toPx() }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var activeAnchor by remember { mutableIntStateOf(-1) }
    var activeShouldSelect by remember { mutableStateOf(true) }
    var latestPointerPosition by remember { mutableStateOf<Offset?>(null) }
    val scrolling = autoScroll != 0f

    val currentIndexAt by rememberUpdatedState(indexAt)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentIsSelected by rememberUpdatedState(isSelected)
    val currentOnRange by rememberUpdatedState(onRange)
    val currentOnEnd by rememberUpdatedState(onEnd)

    LaunchedEffect(scrolling, scrollable) {
        while (scrolling) {
            withFrameNanos { }
            scrollable.scrollBy(autoScroll)

            // Keep extending the range while the pointer is stationary near an edge.
            val anchor = activeAnchor
            val pointer = latestPointerPosition
            val current = if (anchor >= 0 && pointer != null) {
                currentIndexAt(pointer)
            } else {
                null
            }
            if (anchor >= 0 && current != null) {
                currentOnRange(minOf(anchor, current)..maxOf(anchor, current), activeShouldSelect)
            }
        }
    }

    return Modifier.pointerInput(scrollable) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val mouseDragStart = if (down.type == PointerType.Mouse) {
                awaitMouseDragStart(down)
            } else {
                null
            }
            val shouldStart = if (down.type == PointerType.Mouse) {
                mouseDragStart != null
            } else {
                awaitLongPress(down)
            }
            if (!shouldStart) return@awaitEachGesture

            var anchor = currentIndexAt(down.position)
            currentOnStart()
            val clampPosition: (Offset) -> Offset = { point ->
                Offset(
                    x = point.x.coerceIn(0f, (size.width - 1).coerceAtLeast(0).toFloat()),
                    y = point.y.coerceIn(0f, (size.height - 1).coerceAtLeast(0).toFloat())
                )
            }
            val initialPosition = clampPosition(mouseDragStart?.position ?: down.position)
            latestPointerPosition = initialPosition
            val initialIndex = currentIndexAt(initialPosition)
            val selectionAnchor = anchor ?: initialIndex
            if (selectionAnchor != null) {
                activeAnchor = selectionAnchor
                // The anchor's state at pointer-down fixes the operation for this
                // entire gesture. Dragging from a selected item removes the range;
                // dragging from an unselected item adds it.
                activeShouldSelect = !currentIsSelected(selectionAnchor)
                val current = initialIndex ?: selectionAnchor
                currentOnRange(
                    minOf(selectionAnchor, current)..maxOf(selectionAnchor, current),
                    activeShouldSelect
                )
            } else {
                activeAnchor = -1
                activeShouldSelect = true
            }
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                change.consume()
                if (!change.pressed) break
                val position = clampPosition(change.position)
                latestPointerPosition = position
                val current = currentIndexAt(position)
                if (current != null) {
                    val start = anchor ?: current.also {
                        anchor = it
                        activeAnchor = it
                    }
                    currentOnRange(minOf(start, current)..maxOf(start, current), activeShouldSelect)
                }
                autoScroll = edgeScroll(change.position.y, size.height, edgePx, maxScrollPx)
            }
            autoScroll = 0f
            activeAnchor = -1
            latestPointerPosition = null
            currentOnEnd()
        }
    }
}

/**
 * Mouse drag starts as soon as movement exceeds normal click slop. A simple click therefore stays
 * a normal click; touch and stylus retain the long-press gesture to avoid fighting scroll.
 */
private suspend fun AwaitPointerEventScope.awaitMouseDragStart(
    down: PointerInputChange
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (!event.buttons.isPrimaryPressed) return null
        val change = event.changes.fastFirstOrNull { it.id == down.id } ?: return null
        if (!change.pressed) return null
        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
            change.consume()
            return change
        }
    }
}

/**
 * Consumes nothing while waiting, so the item still gets its long press; moving past touch slop
 * means a scroll.
 */
private suspend fun AwaitPointerEventScope.awaitLongPress(down: PointerInputChange): Boolean =
    try {
        withTimeout(viewConfiguration.longPressTimeoutMillis) {
            var held = true
            while (held) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == down.id }
                held = change != null && change.pressed &&
                        (change.position - down.position).getDistance() <= viewConfiguration.touchSlop
            }
            false
        }
    } catch (_: PointerEventTimeoutCancellationException) {
        true
    }

private fun edgeScroll(y: Float, viewportHeight: Int, edgePx: Float, maxScrollPx: Float): Float =
    when {
        y < edgePx -> -((edgePx - y) / edgePx).coerceIn(0f, 1f) * maxScrollPx
        y > viewportHeight - edgePx ->
            ((y - (viewportHeight - edgePx)) / edgePx).coerceIn(0f, 1f) * maxScrollPx

        else -> 0f
    }

private val EDGE_SIZE = 88.dp
private val SCROLL_PER_FRAME = 10.dp
