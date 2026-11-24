package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
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
 *
 * For touch gestures the END of the painted range uses center-crossing hit testing: a row only
 * joins the range once the finger has crossed that row's center. A stationary long-press hold
 * therefore paints the anchor row alone — the small downward drift a thumb makes during the
 * hold (or on lift-off) stays inside the anchor's half of the boundary and can no longer tick
 * the neighbouring row the way raw bounds hit testing did. The anchor itself still comes from
 * plain bounds hit testing (the row actually pressed), and mouse drags keep bounds hit testing
 * throughout.
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
    multiTouchEnabled: Boolean = false,
    onMultiTouchSelect: (Int) -> Unit = {}
): Modifier = dragSelect(
    scrollable = gridState,
    indexAt = { offset ->
        gridItemAt(gridState, offset, resolveNearestVisibleItem)?.index
    },
    endIndexAt = { offset, anchorIndex ->
        gridEndIndexAt(gridState, offset, anchorIndex, resolveNearestVisibleItem)
    },
    onStart = onStart,
    isSelected = isSelected,
    onRange = onRange,
    onEnd = onEnd,
    edgeSize = edgeSize,
    scrollPerFrame = scrollPerFrame,
    mouseDragAddsToSelection = mouseDragAddsToSelection,
    processReleasePosition = processReleasePosition,
    multiTouchEnabled = multiTouchEnabled,
    onMultiTouchSelect = onMultiTouchSelect
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
    processReleasePosition: Boolean = false
): Modifier = dragSelect(
    scrollable = listState,
    indexAt = { offset ->
        listItemAt(listState, offset, resolveNearestVisibleItem)?.index
    },
    endIndexAt = { offset, anchorIndex ->
        listEndIndexAt(listState, offset, anchorIndex, resolveNearestVisibleItem)
    },
    onStart = onStart,
    isSelected = isSelected,
    onRange = onRange,
    onEnd = onEnd,
    edgeSize = edgeSize,
    scrollPerFrame = scrollPerFrame,
    mouseDragAddsToSelection = mouseDragAddsToSelection,
    processReleasePosition = processReleasePosition
)

@Composable
private fun dragSelect(
    scrollable: ScrollableState,
    indexAt: (Offset) -> Int?,
    endIndexAt: (Offset, Int) -> Int?,
    onStart: () -> Unit,
    isSelected: (Int) -> Boolean,
    onRange: (IntRange, shouldSelect: Boolean) -> Unit,
    onEnd: () -> Unit,
    edgeSize: Dp,
    scrollPerFrame: Dp,
    mouseDragAddsToSelection: Boolean,
    processReleasePosition: Boolean,
    multiTouchEnabled: Boolean = false,
    onMultiTouchSelect: (Int) -> Unit = {}
): Modifier {
    val density = LocalDensity.current
    val edgePx = with(density) { edgeSize.toPx() }
    val maxScrollPx = with(density) { scrollPerFrame.toPx() }
    var autoScroll by remember { mutableFloatStateOf(0f) }
    var activeAnchor by remember { mutableIntStateOf(-1) }
    var activeShouldSelect by remember { mutableStateOf(true) }
    var latestPointerPosition by remember { mutableStateOf<Offset?>(null) }
    var touchPainting by remember { mutableStateOf(false) }
    val scrolling = autoScroll != 0f
    // The frame loop below runs for the whole of a touch painting gesture, not only while
    // edge auto-scroll is active. On a busy device the move events of a fast slide can be
    // batched so the gesture loop only ever sees the final finger position; re-applying the
    // anchor..current range every frame from the latest known position is what keeps the
    // painted block contiguous. It is the same mechanism that already made edge auto-scroll
    // painting reliable, extended to the whole gesture.
    val framePainting = scrolling || touchPainting

    val currentIndexAt by rememberUpdatedState(indexAt)
    val currentEndIndexAt by rememberUpdatedState(endIndexAt)
    val currentOnStart by rememberUpdatedState(onStart)
    val currentIsSelected by rememberUpdatedState(isSelected)
    val currentOnRange by rememberUpdatedState(onRange)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentMultiTouchEnabled by rememberUpdatedState(multiTouchEnabled)
    val currentOnMultiTouchSelect by rememberUpdatedState(onMultiTouchSelect)

    LaunchedEffect(framePainting, scrollable) {
        while (framePainting) {
            withFrameNanos { }
            scrollable.scrollBy(autoScroll)

            // Keep extending the range while the pointer is stationary: near an edge the
            // list scrolls under the finger, and within the viewport this re-application
            // is the safety net that fills the range even when the gesture loop received
            // no intermediate move events. Files opts into nearest-item hit testing so the
            // range continues through the collection's bottom content padding instead of
            // scrolling without selecting.
            val anchor = activeAnchor
            val pointer = latestPointerPosition
            val current = if (anchor >= 0 && pointer != null) {
                // Touch painting resolves the range end with center-crossing hit
                // testing so a held finger resting near a row boundary cannot pull
                // the neighbouring row into the range; the mouse auto-scroll loop
                // keeps plain bounds hit testing.
                if (touchPainting) {
                    currentEndIndexAt(pointer, anchor)
                } else {
                    currentIndexAt(pointer)
                }
            } else {
                null
            }
            if (anchor >= 0 && current != null) {
                currentOnRange(dragSelectionRange(anchor, current), activeShouldSelect)
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
            val touchStart = if (
                down.type == PointerType.Touch && currentMultiTouchEnabled
            ) {
                awaitLongPressOrMultiTouch(
                    down = down,
                    indexAt = currentIndexAt,
                    onSelectIndex = currentOnMultiTouchSelect
                )
            } else {
                null
            }
            val shouldStart = when {
                down.type == PointerType.Mouse -> mouseDragStart != null
                touchStart != null -> touchStart == TouchGestureStart.LONG_PRESS
                else -> awaitLongPress(down)
            }
            if (touchStart == TouchGestureStart.MULTI_TOUCH || !shouldStart) {
                return@awaitEachGesture
            }

            var selectionStarted = false
            try {
                var anchor = currentIndexAt(down.position)
                currentOnStart()
                selectionStarted = true
                // Touch painting is additionally driven by the per-frame loop above;
                // mouse keeps its event-driven painting plus the auto-scroll loop.
                if (down.type == PointerType.Touch) touchPainting = true
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
                // Keep the resolved fallback anchor for the whole gesture. Without this,
                // a drag that starts outside an item can re-anchor on its next event after
                // the initial range has already changed the selection state, and only the
                // endpoint of the drag gets painted. Touch gestures always keep it: a
                // long-press starts on (or nearest to) a row, and that row must remain
                // the anchor for the whole slide.
                if (selectionAnchor != null &&
                    (processReleasePosition || down.type == PointerType.Touch)
                ) {
                    anchor = selectionAnchor
                }
                val additiveMouseRange = down.type == PointerType.Mouse && mouseDragAddsToSelection
                if (selectionAnchor != null) {
                    activeAnchor = selectionAnchor
                    // Desktop drag-to-select is always additive when opted in. Starting on an
                    // already-selected row must not invert the gesture and erase the range.
                    // Touch long-press keeps its previous selected/unselected toggle semantics.
                    activeShouldSelect = additiveMouseRange || !currentIsSelected(selectionAnchor)
                    // The initial position is the anchor's own press point, so the
                    // center-crossing end test resolves it back to the anchor itself;
                    // routing touch through it keeps every painted end consistent.
                    val current = if (down.type == PointerType.Touch) {
                        currentEndIndexAt(initialPosition, selectionAnchor) ?: selectionAnchor
                    } else {
                        initialIndex ?: selectionAnchor
                    }
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
                if (mouseDragStart != null) {
                    autoScroll = edgeScroll(
                        initialPosition.y,
                        size.height,
                        edgePx,
                        maxScrollPx
                    )
                }

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (down.type == PointerType.Touch && currentMultiTouchEnabled) {
                        val additionalTouches = event.changes.filter {
                            it.id != down.id &&
                                    it.type == PointerType.Touch &&
                                    it.pressed &&
                                    !it.previousPressed
                        }
                        additionalTouches.mapNotNull { currentIndexAt(it.position) }
                            .distinct()
                            .forEach(currentOnMultiTouchSelect)
                        additionalTouches.forEach { it.consume() }
                    }
                    val change = event.changes.fastFirstOrNull { it.id == down.id } ?: break
                    println("DRAGLOOP pos=${change.position} pressed=${change.pressed} downPos=${down.position}")
                    val released = !change.pressed
                    // The release position is painted only for callers that opt in via
                    // processReleasePosition (picker list, Storage Explorer, Trash).
                    // For the other surfaces (Files above all) the lift-off point of a
                    // hold is thumb-peel noise, not a drag endpoint: a thumb resting in
                    // the anchor row's lower half peels downward as it lifts, and the
                    // release event can land past the next row's center, adding that
                    // row to a selection the user never dragged over. Painting it made
                    // a plain hold-and-lift select the row under the anchor. Drags on
                    // these surfaces are painted from move events and the per-frame
                    // loop above, so the gesture's last move position is its final
                    // range and the release adds nothing.
                    if (released && !processReleasePosition) break
                    if (!released) change.consume()

                    // For callers that opted into release processing, the release event
                    // can carry the final pointer coordinate. Process it before ending
                    // the gesture so the last row is included for both select and
                    // deselect drags.
                    val position = clampPosition(change.position)
                    latestPointerPosition = position
                    // Once the anchor is known, touch resolves the range end with
                    // center-crossing hit testing (see endIndexAt): a finger resting
                    // in the anchor row, or drifted just past its boundary during the
                    // hold, keeps the end on the anchor instead of also painting the
                    // next row. Before an anchor exists (a press that started in a
                    // gap) and for mouse drags, plain bounds hit testing applies.
                    val knownAnchor = anchor
                    val current = if (knownAnchor != null && down.type == PointerType.Touch) {
                        currentEndIndexAt(position, knownAnchor)
                    } else {
                        currentIndexAt(position)
                    }
                    if (current != null) {
                        val start = anchor ?: current.also {
                            anchor = it
                            activeAnchor = it
                            activeShouldSelect =
                                additiveMouseRange || !currentIsSelected(it)
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
                touchPainting = false
                activeAnchor = -1
                latestPointerPosition = null
                if (selectionStarted) currentOnEnd()
            }
        }
    }
}

/**
 * Plain bounds hit testing for grids: the item whose bounds contain [offset], or — when
 * [resolveNearest] is set — the nearest visible item, so drags keep resolving through
 * spacing and content padding. Used for anchors and for mouse drags.
 */
private fun gridItemAt(
    gridState: LazyGridState,
    offset: Offset,
    resolveNearest: Boolean
): LazyGridItemInfo? {
    val visibleItems = gridState.layoutInfo.visibleItemsInfo
    // Item offsets are measured from the start of the grid's CONTENT: they exclude
    // the before-content padding, while pointer positions are measured from the
    // container's edge and include it. Hit-test in the items' space — comparing the
    // two directly lands every hit beforeContentPadding too low (on the archive
    // list, ~100.dp against a 76.dp row stride: exactly one row too low, and past
    // the last row entirely). Proven by ArchiveDragSelectPointerTest.
    val point = Offset(offset.x, offset.y - gridState.layoutInfo.beforeContentPadding)
    return visibleItems.fastFirstOrNull { item ->
        point.x >= item.offset.x &&
                point.x <= item.offset.x + item.size.width &&
                point.y >= item.offset.y &&
                point.y <= item.offset.y + item.size.height
    } ?: if (resolveNearest) {
        visibleItems.minByOrNull { item ->
            distanceToItemSquared(
                point = point,
                left = item.offset.x.toFloat(),
                top = item.offset.y.toFloat(),
                right = (item.offset.x + item.size.width).toFloat(),
                bottom = (item.offset.y + item.size.height).toFloat()
            )
        }
    } else {
        null
    }
}

/**
 * Center-crossing hit testing for the END of a touch-painted grid range. The candidate is
 * the bounds (or nearest) item under the finger, but it only becomes the range end once
 * the finger has crossed its center in the direction away from the anchor; until then the
 * end stays one item back. A stationary hold inside the anchor item therefore resolves to
 * the anchor alone, and a slow slide extends the range one deliberate row at a time. While
 * edge auto-scroll moves items under a stationary finger, each arriving item's center
 * sweeps past the finger in turn, so the range keeps extending exactly as before.
 */
private fun gridEndIndexAt(
    gridState: LazyGridState,
    offset: Offset,
    anchorIndex: Int,
    resolveNearest: Boolean
): Int? {
    val candidate = gridItemAt(gridState, offset, resolveNearest) ?: return null
    if (anchorIndex < 0 || candidate.index == anchorIndex) return candidate.index
    val anchorItem = gridState.layoutInfo.visibleItemsInfo
        .fastFirstOrNull { it.index == anchorIndex }
    val centerX = candidate.offset.x + candidate.size.width / 2
    val centerY = candidate.offset.y + candidate.size.height / 2
    // Center-crossing compares in the items' coordinate space (see gridItemAt).
    val pointY = offset.y - gridState.layoutInfo.beforeContentPadding
    val crossed = when {
        anchorItem != null && candidate.offset.y > anchorItem.offset.y -> pointY >= centerY
        anchorItem != null && candidate.offset.y < anchorItem.offset.y -> pointY <= centerY
        anchorItem == null && candidate.index > anchorIndex -> pointY >= centerY
        anchorItem == null && candidate.index < anchorIndex -> pointY <= centerY
        candidate.index > anchorIndex -> offset.x >= centerX
        else -> offset.x <= centerX
    }
    return when {
        crossed -> candidate.index
        candidate.index > anchorIndex -> candidate.index - 1
        else -> candidate.index + 1
    }
}

/** Plain bounds hit testing for lists; see [gridItemAt]. */
private fun listItemAt(
    listState: LazyListState,
    offset: Offset,
    resolveNearest: Boolean
): LazyListItemInfo? {
    val visibleItems = listState.layoutInfo.visibleItemsInfo
    // Item offsets are measured from the start of the list's CONTENT: they exclude
    // the before-content padding, while pointer positions are measured from the
    // container's edge and include it. Hit-test in the items' space — comparing the
    // two directly lands every hit beforeContentPadding too low (on the archive
    // list, ~100.dp against a 76.dp row stride: exactly one row too low, and past
    // the last row entirely). Proven by ArchiveDragSelectPointerTest.
    val y = offset.y - listState.layoutInfo.beforeContentPadding
    return visibleItems.fastFirstOrNull { item ->
        y >= item.offset && y <= item.offset + item.size
    } ?: if (resolveNearest) {
        visibleItems.minByOrNull { item ->
            distanceToInterval(y, item.offset, item.offset + item.size)
        }
    } else {
        null
    }
}

/** Center-crossing hit testing for the END of a touch-painted list range; see [gridEndIndexAt]. */
private fun listEndIndexAt(
    listState: LazyListState,
    offset: Offset,
    anchorIndex: Int,
    resolveNearest: Boolean
): Int? {
    val candidate = listItemAt(listState, offset, resolveNearest) ?: return null
    println("DRAGEND offsetY=${offset.y} bcp=${listState.layoutInfo.beforeContentPadding} cand=${candidate.index} anchor=$anchorIndex")
    if (anchorIndex < 0 || candidate.index == anchorIndex) return candidate.index
    val centerY = candidate.offset + candidate.size / 2
    // Center-crossing compares in the items' coordinate space (see listItemAt).
    val y = offset.y - listState.layoutInfo.beforeContentPadding
    return when {
        candidate.index > anchorIndex ->
            if (y >= centerY) candidate.index else candidate.index - 1

        y <= centerY -> candidate.index
        else -> candidate.index + 1
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

private enum class TouchGestureStart {
    LONG_PRESS,
    MULTI_TOUCH,
    CANCELLED
}

/**
 * A normal first tap remains untouched. When another finger joins before long-press or scrolling,
 * select the items beneath all currently pressed fingers and consume those presses so the tile's
 * ordinary open action cannot also fire.
 */
private suspend fun AwaitPointerEventScope.awaitLongPressOrMultiTouch(
    down: PointerInputChange,
    indexAt: (Offset) -> Int?,
    onSelectIndex: (Int) -> Unit
): TouchGestureStart = try {
    withTimeout(viewConfiguration.longPressTimeoutMillis) {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val anchor = event.changes.fastFirstOrNull { it.id == down.id }
                ?: return@withTimeout TouchGestureStart.CANCELLED
            if (!anchor.pressed ||
                (anchor.position - down.position).getDistance() > viewConfiguration.touchSlop
            ) {
                return@withTimeout TouchGestureStart.CANCELLED
            }
            val activeTouches = event.changes.filter {
                it.type == PointerType.Touch && it.pressed
            }
            if (activeTouches.size >= 2) {
                activeTouches.mapNotNull { indexAt(it.position) }
                    .distinct()
                    .forEach(onSelectIndex)
                activeTouches.forEach { it.consume() }
                return@withTimeout TouchGestureStart.MULTI_TOUCH
            }
        }
        @Suppress("UNREACHABLE_CODE")
        TouchGestureStart.CANCELLED
    }
} catch (_: PointerEventTimeoutCancellationException) {
    TouchGestureStart.LONG_PRESS
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
