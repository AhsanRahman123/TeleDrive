package com.drdisagree.teledrive.presentation.transfers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.platform.TransferWidgetStyle
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.bubble_active_count
import com.drdisagree.teledrive.resources.bubble_overall
import com.drdisagree.teledrive.resources.bubble_show_transfers
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The floating transfer widget, ported from Windows: an in-app overlay in one of two
 * shells chosen in Settings — the draggable chat-head **bubble** or the **bottom strip**.
 * Both shells render the same [TransfersUiState] summary the Transfers screen renders.
 *
 * vc186 A6: tapping either shell opens the full Transfers screen — the tap
 * no longer expands an in-widget summary panel. Both shells receive the
 * SAME [onWidgetTap] handler, built here from the single [widgetTapTarget]
 * decision, so the two styles cannot drift apart; per-transfer controls
 * live on the Transfers screen the tap opens. The bubble's drag behaviour
 * is untouched: the gesture layer still separates a tap from a drag, and
 * only a tap fires [onWidgetTap].
 *
 * Android adaptation: the app's floating bottom navigation occupies the bottom ~100 dp,
 * so the shells lay out in the area above it (the strip's "window bottom" is the top of
 * that nav area), and the widget's persisted position follows the same store pattern as
 * Windows (dp offsets, sanitized and clamped on read).
 */
@Composable
fun TransferWidgetOverlay(
    store: AndroidTransferBubbleStore,
    onOpenTransfers: () -> Unit
) {
    val enabled by store.enabled.collectAsState()
    if (!enabled) return
    val viewModel: TransfersViewModel = koinViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val style by store.style.collectAsState()
    val colorPreset by store.color.collectAsState()
    val accent = widgetAccent(colorPreset)
    // One-second clock — the cadence the Transfers screen uses for its stale-speed display.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    val summary = summarizeTransfers(state, nowMillis)
    // The one tap handler both shells share (vc186 A6).
    val currentOpenTransfers = rememberUpdatedState(onOpenTransfers)
    val currentStyle = rememberUpdatedState(style)
    val onWidgetTap: () -> Unit = remember {
        {
            when (widgetTapTarget(currentStyle.value)) {
                WidgetTapTarget.TRANSFERS_SCREEN -> currentOpenTransfers.value()
            }
        }
    }
    when (style) {
        TransferWidgetStyle.BUBBLE -> BubbleShell(
            summary = summary,
            accent = accent,
            store = store,
            onTap = onWidgetTap
        )
        TransferWidgetStyle.BOTTOM_STRIP -> StripShell(
            state = state,
            summary = summary,
            accent = accent,
            onTap = onWidgetTap
        )
    }
}

/** Height of the floating bottom navigation area the shells must clear. */
private const val BOTTOM_NAV_LIFT_DP = 100f

@Composable
private fun BubbleShell(
    summary: TransferBubbleSummary,
    accent: WidgetAccent?,
    store: AndroidTransferBubbleStore,
    onTap: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val windowHeight = (maxHeight.value - BOTTOM_NAV_LIFT_DP).coerceAtLeast(0f)

        var bubbleOffset by remember {
            mutableStateOf(
                TransferBubbleLayout.defaultOffset(windowWidth, windowHeight)
            )
        }
        var positionApplied by remember { mutableStateOf(false) }
        val storedPosition by store.position.collectAsState()
        // The persisted position arrives asynchronously from DataStore; apply it once,
        // sanitized, until the user drags the bubble themselves.
        LaunchedEffect(storedPosition) {
            if (!positionApplied && storedPosition != null) {
                bubbleOffset = TransferBubbleLayout.sanitizePosition(
                    storedPosition!!, windowWidth, windowHeight
                )
                positionApplied = true
            }
        }
        val effectiveOffset = TransferBubbleLayout.clampOffset(
            bubbleOffset.x, bubbleOffset.y, windowWidth, windowHeight
        )
        // State ≡ visible position: whenever the clamp disagrees with the stored state,
        // the state snaps to the clamped value, so a later drag can never start from a
        // phantom offset.
        if (bubbleOffset != effectiveOffset) {
            bubbleOffset = effectiveOffset
        }
        val bubbleDrag = remember { BubbleDragSession() }

        // SF-1 (second-pass): the hide-to-tab affordance the panel used to
        // carry must survive the panel's removal — a long-press on the
        // bubble hides it to the slim edge tab; tapping the tab restores
        // it. Tap still opens Transfers (A6); drag is unchanged.
        var userHidden by remember { mutableStateOf(false) }
        when (bubbleVisibility(enabled = true, fullscreen = false, userHidden = userHidden)) {
            BubbleVisibility.GONE -> return@BoxWithConstraints
            BubbleVisibility.TAB -> {
                TransferBubbleTab(
                    offset = TransferBubbleLayout.tabOffset(
                        effectiveOffset, windowWidth, windowHeight
                    ),
                    onLeftEdge = TransferBubbleLayout.tabOnLeftEdge(effectiveOffset, windowWidth),
                    accent = accent,
                    onClick = { userHidden = false }
                )
                return@BoxWithConstraints
            }
            BubbleVisibility.BUBBLE -> Unit
        }

        TransferBubble(
            summary = summary,
            offset = effectiveOffset,
            accent = accent,
            onDragStart = {
                positionApplied = true
                bubbleDrag.begin(
                    TransferBubbleLayout.clampOffset(
                        bubbleOffset.x, bubbleOffset.y,
                        windowWidth, windowHeight
                    )
                )
            },
            onDragDelta = { dxDp, dyDp ->
                // Deltas accumulate from the EFFECTIVE (visible) offset, not raw
                // state — the gesture callbacks outlive any single composition,
                // so the effective offset is derived from the live state here.
                val effective = TransferBubbleLayout.clampOffset(
                    bubbleOffset.x, bubbleOffset.y,
                    windowWidth, windowHeight
                )
                bubbleOffset = TransferBubbleLayout.movedBy(
                    effective, dxDp, dyDp,
                    windowWidth, windowHeight
                )
            },
            onDragFinished = {
                // Persist ONCE, on release, with the final offset.
                bubbleDrag.complete(bubbleOffset)?.let { final ->
                    store.savePosition(final.x, final.y)
                }
            },
            onDragCancelled = {
                // A cancelled drag restores the pre-drag offset, persists nothing.
                bubbleDrag.cancel()?.let { restore -> bubbleOffset = restore }
            },
            // A tap (not a drag — the gesture layer separates them) opens the
            // full Transfers screen (vc186 A6).
            onClick = onTap,
            // A long-press hides the widget to its edge tab (SF-1).
            onLongPress = { userHidden = true }
        )
    }
}

/**
 * Drag-vs-click for the bubble: movement past the touch slop is a drag (the release then
 * does NOT fire the tap); anything less is a click.
 *
 * Phantom-drag hardening (the Windows 1.6.16 lessons): only an *unclaimed* press may arm
 * a drag — a press already consumed by another interaction must not turn into a drag —
 * and the initial down is consumed here so no other handler can claim the same press.
 * A drag whose tracked change vanishes without an up is a cancellation: the shell
 * restores the pre-drag offset and nothing is persisted.
 */
private fun Modifier.bubbleGestures(
    onDragStart: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    onDragCancelled: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        down.consume()
        var dragged = false
        var longPressed = false
        var upChange: PointerInputChange? = null
        var cancelled = false
        // Long-press: a hold past the platform timeout hides the widget
        // (SF-1). The deadline is anchored at the down event and every
        // wait is re-armed with the time REMAINING to that deadline, so
        // sub-slop jitter events cannot disarm it (delta-review must-fix);
        // a drag or a fired long-press ends the bounded waits. A fired
        // long-press swallows the release (no click).
        val longPressDeadline =
            down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
        var lastEventMillis = down.uptimeMillis
        while (true) {
            val event = if (!dragged && !longPressed) {
                val remaining = longPressDeadline - lastEventMillis
                if (remaining <= 0L) {
                    longPressed = true
                    onLongPress()
                    awaitPointerEvent()
                } else {
                    withTimeoutOrNull(remaining) { awaitPointerEvent() } ?: run {
                        longPressed = true
                        onLongPress()
                        continue
                    }
                }
            } else {
                awaitPointerEvent()
            }
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change != null) {
                lastEventMillis = change.uptimeMillis
            }
            if (change == null) {
                // The tracked pointer vanished without an up: a cancellation.
                cancelled = true
                break
            }
            if (!change.pressed) {
                upChange = change
                break
            }
            val delta = change.position - change.previousPosition
            if (!dragged &&
                (change.position - down.position).getDistance() > viewConfiguration.touchSlop
            ) {
                dragged = true
                onDragStart()
            }
            if (dragged && delta != Offset.Zero) {
                onDragDelta(delta.x / density, delta.y / density)
                change.consume()
            }
        }
        when {
            cancelled -> if (dragged) onDragCancelled()
            upChange != null -> when {
                longPressed -> Unit
                dragged -> onDragFinished()
                else -> onClick()
            }
            else -> Unit
        }
    }
}

@Composable
private fun accentColor(accent: WidgetAccent?): androidx.compose.ui.graphics.Color =
    accent?.accent ?: MaterialTheme.colorScheme.primary

@Composable
private fun onAccentColor(accent: WidgetAccent?): androidx.compose.ui.graphics.Color =
    accent?.onAccent ?: MaterialTheme.colorScheme.onPrimary

@Composable
private fun TransferBubble(
    summary: TransferBubbleSummary,
    offset: BubbleOffset,
    accent: WidgetAccent?,
    onDragStart: () -> Unit,
    onDragDelta: (Float, Float) -> Unit,
    onDragFinished: () -> Unit,
    onDragCancelled: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    val primary = accentColor(accent)
    val secondary = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    (offset.x * density).roundToInt(),
                    (offset.y * density).roundToInt()
                )
            }
            .size(TransferBubbleLayout.BUBBLE_SIZE_DP.dp)
            .bubbleGestures(
                onDragStart, onDragDelta, onDragFinished, onDragCancelled, onClick,
                onLongPress
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                drawArc(
                    color = track,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    style = stroke
                )
                drawArc(
                    color = primary,
                    startAngle = -90f,
                    sweepAngle = 360f * summary.overallProgress.coerceIn(0f, 1f),
                    useCenter = false,
                    style = stroke
                )
            }
            if (summary.hasUnfinishedWork) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row {
                        Icon(
                            Icons.Filled.ArrowUpward,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = primary
                        )
                        Icon(
                            Icons.Filled.ArrowDownward,
                            contentDescription = null,
                            modifier = Modifier.size(11.dp),
                            tint = secondary
                        )
                    }
                    Text(
                        text = Formatters.percent(summary.overallProgress),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(Res.string.bubble_overall),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.align(Alignment.Center).size(26.dp),
                    tint = primary
                )
            }
            if (summary.queuedCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(20.dp)
                        .background(primary, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = Formatters.badgeCount(summary.queuedCount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = onAccentColor(accent)
                    )
                }
            }
        }
    }
}

/**
 * The bubble's collapsed edge tab (restored, SF-1): shown while the widget
 * is user-hidden; tapping it brings the bubble back. Copied from the vc185
 * baseline implementation, which the A6 rewrite dropped along with the
 * panel that used to carry the Hide button.
 */
@Composable
private fun TransferBubbleTab(
    offset: BubbleOffset,
    onLeftEdge: Boolean,
    accent: WidgetAccent?,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    (offset.x * density).roundToInt(),
                    (offset.y * density).roundToInt()
                )
            }
            .size(
                TransferBubbleLayout.TAB_WIDTH_DP.dp,
                TransferBubbleLayout.TAB_HEIGHT_DP.dp
            )
            .clickable(onClick = onClick),
        shape = if (onLeftEdge) {
            RoundedCornerShape(topEnd = 14.dp, bottomEnd = 14.dp)
        } else {
            RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp)
        },
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 6.dp
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                if (onLeftEdge) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(Res.string.bubble_show_transfers),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Icon(
                Icons.Filled.SwapVert,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = accentColor(accent)
            )
        }
    }
}

/**
 * The bottom strip: a slim bar centred at the bottom of the content area — live ↑/↓
 * speeds, the active count, the overall progress bar and the session totals.
 * Tapping it opens the full Transfers screen, exactly like tapping the
 * bubble (vc186 A6: both shells share the one tap handler).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripShell(
    state: TransfersUiState,
    summary: TransferBubbleSummary,
    accent: WidgetAccent?,
    onTap: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val windowHeight = (maxHeight.value - BOTTOM_NAV_LIFT_DP).coerceAtLeast(0f)
        val accentCol = accentColor(accent)

        // SF-1: same hide contract as the bubble — a long-press collapses
        // the strip to its edge tab; tapping the tab restores it.
        var userHidden by remember { mutableStateOf(false) }
        if (userHidden) {
            val tab = TransferStripLayout.tabOffset(windowWidth, windowHeight)
            Surface(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (tab.x * density).roundToInt(),
                            (tab.y * density).roundToInt()
                        )
                    }
                    .size(
                        TransferStripLayout.TAB_WIDTH_DP.dp,
                        TransferStripLayout.TAB_HEIGHT_DP.dp
                    )
                    .clickable { userHidden = false },
                shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                color = accentCol,
                shadowElevation = 6.dp
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.KeyboardArrowUp,
                        contentDescription = stringResource(Res.string.bubble_show_transfers),
                        modifier = Modifier.size(16.dp),
                        tint = onAccentColor(accent)
                    )
                }
            }
            return@BoxWithConstraints
        }

        val stripWidth = TransferStripLayout.stripWidth(windowWidth)
        val strip = TransferStripLayout.stripOffset(windowWidth, windowHeight)
        Surface(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (strip.x * density).roundToInt(),
                        (strip.y * density).roundToInt()
                    )
                }
                .width(stripWidth.dp)
                .height(TransferStripLayout.BAR_HEIGHT_DP.dp)
                .combinedClickable(
                    onClick = { onTap() },
                    onLongClick = { userHidden = true }
                ),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    Icons.Filled.ArrowUpward,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = accentCol
                )
                Text(
                    text = Formatters.speed(summary.uploadingSpeedBytesPerSecond),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Icon(
                    Icons.Filled.ArrowDownward,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.secondary
                )
                Text(
                    text = Formatters.speed(summary.downloadingSpeedBytesPerSecond),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    text = stringResource(
                        Res.string.bubble_active_count,
                        state.activeTotal + state.pausedTotal
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                LinearProgressIndicator(
                    progress = { summary.overallProgress },
                    modifier = Modifier.weight(1f).height(6.dp),
                    color = accentCol,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text = "${Formatters.bytes(summary.uploadingTransferredBytes + summary.downloadingTransferredBytes)}" +
                        " / ${Formatters.bytes(summary.uploadingTotalBytes + summary.downloadingTotalBytes)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                Icon(
                    Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(Res.string.bubble_show_transfers),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
