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
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastFirstOrNull

/**
 * The pressed item becomes the anchor; holding near an edge keeps the list scrolling so a range can
 * run past the viewport.
 *
 * [mouseDragAddsToSelection] makes desktop mouse drags additive even when they start on an item
 * that is already selected. This avoids turning a normal drag-to-select gesture into a range
 * deselection. Touch long-press selection retains its existing toggle behavior.
 *
 * [resolveNearestVisibleItem] lets a desktop drag keep extending through spacing and the padded
 * edge area of a lazy collection, including while edge auto-scroll is moving the visible items.
 */
@Composable
fun rememberDragSelect(
    gridState: LazyGridState,
    onStart: () -> Unit,
    isSelected: (Int) -> Boolean,
    onRange: (IntRange, shouldSelect: Boolean) -> Unit,
    onEnd: () -> Unit,
    edgeSize: Dp = EDGE_SIZE,
    scrollPerFrame: Dp = SCROLL_PER_FRAME,
    mouseDragAddsToSelection: Boolean = false,
    resolveNearestVisibleItem: Boolean = false,
    processReleasePosition: Boolean = false,
    touchDragEnabled: Boolean = false,
    touchOnly: Boolean = false
): Modifier = dragSelect(
    scrollable = gridState,
    indexAt = { offset ->
        val visibleItems = gridState.layoutInfo.visibleItemsInfo
        visibleItems.fastFirstOrNull { item ->
            offset.x >= item.offset.x &&
                    offset.x <= item.offset.x + item.size.width &&
                    offset.y >= item.offset.y &&
                    offset.y <= item.offset.y + item.size.height
        }?.index ?: if (resolveNearestVisibleItem) {
            visibleItems.minByOrNull { item ->
                distanceToItemSquared(
                    point = offset,
                    left = item.offset.x.toFloat(),
                    top = item.offset.y.toFloat(),
                    right = (item.offset.x + item.size.width).toFloat(),
                    bottom = (item.offset.y + item.size.height).toFloat()
                )
            }?.index
        } else {
            null
        }
    },
    onStart = onStart,
    isSelected = isSelected,
    onRange = onRange,
    onEnd = onEnd,
    edgeSize = edgeSize,
    scrollPerFrame = scrollPerFrame,
    mouseDragAddsToSelection = mouseDragAddsToSelection,
    processReleasePosition = processReleasePosition,
    touchDragEnabled = touchDragEnabled,
    touchOnly = touchOnly
)

@Composable
fun rememberDragSelect(
    listState: LazyListState,
    onStart: () -> Unit,
    isSelected: (Int) -> Boolean,
    onRange: (IntRange, shouldSelect: Boolean) -> Unit,
    onEnd: () -> Unit,
    edgeSize: Dp = EDGE_SIZE,
    scrollPerFrame: Dp = SCROLL_PER_FRAME,
    mouseDragAddsToSelection: Boolean = false,
    resolveNearestVisibleItem: Boolean = false,
    processReleasePosition: Boolean = false,
    touchDragEnabled: Boolean = false,
    touchOnly: Boolean = false
): Modifier = dragSelect(
    scrollable = listState,
    indexAt = { offset ->
        val visibleItems = listState.layoutInfo.visibleItemsInfo
        visibleItems.fastFirstOrNull { item ->
            offset.y >= item.offset && offset.y <= item.offset + item.size
        }?.index ?: if (resolveNearestVisibleItem) {
            visibleItems.minByOrNull { item ->
                distanceToInterval(offset.y, item.offset, item.offset + item.size)
            }?.index
        } else {
            null
        }
    },
    onStart = onStart,
    isSelected = isSelected,
    onRange = onRange,
    onEnd = onEnd,
    edgeSize = edgeSize,
    scrollPerFrame = scrollPerFrame,
    mouseDragAddsToSelection = mouseDragAddsToSelection,
    processReleasePosition = processReleasePosition,
    touchDragEnabled = touchDragEnabled,
    touchOnly = touchOnly
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
    scrollPerFrame: Dp,
    mouseDragAddsToSelection: Boolean,
    processReleasePosition: Boolean,
    touchDragEnabled: Boolean,
    touchOnly: Boolean
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
            // Files opts into nearest-item hit testing so the range continues through the
            // collection's bottom content padding instead of scrolling without selecting.
            val anchor = activeAnchor
            val pointer = latestPointerPosition
            val current = if (anchor >= 0 && pointer != null) {
                currentIndexAt(pointer)
            } else {
                null
            }
            if (anchor >= 0 && current != null) {
                currentOnRange(dragSelectionRange(anchor, current), activeShouldSelect)
            }
        }
    }

    return Modifier.pointerInput(scrollable, touchDragEnabled, touchOnly) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Android range selection is hosted on the collection container. Ignore pointer
            // downs unless that explicit touch mode is active; item long-press remains intact.
            if (touchOnly && !touchDragEnabled) return@awaitEachGesture
            val mouseDragStart = if (down.type == PointerType.Mouse) {
                awaitMouseDragStart(down)
            } else {
                null
            }
            val touchDragStart = if (down.type != PointerType.Mouse && touchDragEnabled) {
                awaitTouchDragStart(down)
            } else {
                null
            }
            val shouldStart = when {
                touchOnly -> touchDragStart != null
                down.type == PointerType.Mouse -> mouseDragStart != null
                touchDragEnabled -> touchDragStart != null
                else -> awaitLongPress(down)
            }
            if (!shouldStart) return@awaitEachGesture

            var selectionStarted = false
            try {
                var anchor = currentIndexAt(down.position)
                val includeReleasePosition = processReleasePosition || touchDragStart != null
                currentOnStart()
                selectionStarted = true
                val clampPosition: (Offset) -> Offset = { point ->
                    Offset(
                        x = point.x.coerceIn(0f, (size.width - 1).coerceAtLeast(0).toFloat()),
                        y = point.y.coerceIn(0f, (size.height - 1).coerceAtLeast(0).toFloat())
                    )
                }
                val initialPosition = clampPosition(
                    mouseDragStart?.position ?: touchDragStart?.position ?: down.position
                )
                latestPointerPosition = initialPosition
                val initialIndex = currentIndexAt(initialPosition)
                val selectionAnchor = anchor ?: initialIndex
                // Keep the resolved fallback anchor for the whole gesture. Without this,
                // a drag that starts outside an item can re-anchor on its next event after
                // the initial range has already changed the selection state.
                if (selectionAnchor != null && includeReleasePosition) {
                    anchor = selectionAnchor
                }
                val additiveMouseRange = down.type == PointerType.Mouse && mouseDragAddsToSelection
                if (selectionAnchor != null) {
                    activeAnchor = selectionAnchor
                    // Desktop drag-to-select is always additive when opted in. Starting on an
                    // already-selected row must not invert the gesture and erase the range.
                    // Touch long-press keeps its previous selected/unselected toggle semantics.
                    activeShouldSelect = additiveMouseRange || touchDragStart != null ||
                            !currentIsSelected(selectionAnchor)
                    val current = initialIndex ?: selectionAnchor
                    currentOnRange(
                        dragSelectionRange(selectionAnchor, current),
                        activeShouldSelect
                    )
                } else {
                    activeAnchor = -1
                    activeShouldSelect = true
                }

                // The pointer movement that crosses mouse drag-slop is consumed by
                // awaitMouseDragStart(). It may be the last movement before release,
                // so initialize edge scrolling here rather than waiting for another event.
                if (mouseDragStart != null || touchDragStart != null) {
                    autoScroll = edgeScroll(
                        initialPosition.y,
                        size.height,
                        edgePx,
                        maxScrollPx
                    )
                }

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                    val released = !change.pressed
                    if (released && !includeReleasePosition) break
                    if (!released) change.consume()

                    // The release event can carry the final pointer coordinate. Process it
                    // before ending the gesture so the last row is included for both select
                    // and deselect drags.
                    val position = clampPosition(change.position)
                    latestPointerPosition = position
                    val current = currentIndexAt(position)
                    if (current != null) {
                        val start = anchor ?: current.also {
                            anchor = it
                            activeAnchor = it
                            activeShouldSelect = additiveMouseRange || touchDragStart != null ||
                                !currentIsSelected(it)
                        }
                        currentOnRange(dragSelectionRange(start, current), activeShouldSelect)
                    }
                    if (released) break
                    autoScroll = edgeScroll(change.position.y, size.height, edgePx, maxScrollPx)
                }
            } finally {
                // Always release the selection/auto-scroll state if the pointer gesture is
                // cancelled by focus changes, recomposition, or navigation.
                autoScroll = 0f
                activeAnchor = -1
                latestPointerPosition = null
                if (selectionStarted) currentOnEnd()
            }
        }
    }
}

private fun distanceToItemSquared(
    point: Offset,
    left: Float,
    top: Float,
    right: Float,
    bottom: Float
): Float {
    val dx = when {
        point.x < left -> left - point.x
        point.x > right -> point.x - right
        else -> 0f
    }
    val dy = when {
        point.y < top -> top - point.y
        point.y > bottom -> point.y - bottom
        else -> 0f
    }
    return dx * dx + dy * dy
}

private fun distanceToInterval(value: Float, start: Int, end: Int): Float = when {
    value < start -> start - value
    value > end -> value - end
    else -> 0f
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
        // Shift is reserved for deliberate file-to-tab transfers. Unmodified
        // primary-button drags belong to the shared range-selection gesture.
        if (event.keyboardModifiers.isShiftPressed) return null
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
 * In explicit Android range-selection mode, a finger drag takes over after touch slop.
 * Until then the event is left untouched, so a tap remains a tap and normal scrolling is
 * unaffected whenever the mode is off.
 */
private suspend fun AwaitPointerEventScope.awaitTouchDragStart(
    down: PointerInputChange
): PointerInputChange? {
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
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

/**
 * Inclusive range between an anchor and the current pointer item. min/max makes
 * upward and downward sweeps produce the same range.
 */
internal fun dragSelectionRange(anchorIndex: Int, currentIndex: Int): IntRange =
    minOf(anchorIndex, currentIndex)..maxOf(anchorIndex, currentIndex)

private val EDGE_SIZE = 88.dp
private val SCROLL_PER_FRAME = 10.dp
