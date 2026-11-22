package com.drdisagree.teledrive.presentation.transfers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.platform.TransferWidgetStyle
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.bubble_active_count
import com.drdisagree.teledrive.resources.bubble_cancel_all
import com.drdisagree.teledrive.resources.bubble_downloading
import com.drdisagree.teledrive.resources.bubble_file_active
import com.drdisagree.teledrive.resources.bubble_files_active
import com.drdisagree.teledrive.resources.bubble_hide
import com.drdisagree.teledrive.resources.bubble_more
import com.drdisagree.teledrive.resources.bubble_no_active
import com.drdisagree.teledrive.resources.bubble_overall
import com.drdisagree.teledrive.resources.bubble_queue_waiting
import com.drdisagree.teledrive.resources.bubble_show_transfers
import com.drdisagree.teledrive.resources.bubble_transferred_of
import com.drdisagree.teledrive.resources.bubble_uploading
import com.drdisagree.teledrive.resources.common_backup_queued
import com.drdisagree.teledrive.resources.common_cancel
import com.drdisagree.teledrive.resources.common_pause
import com.drdisagree.teledrive.resources.common_resume
import com.drdisagree.teledrive.resources.transfers
import com.drdisagree.teledrive.resources.transfers_pause
import com.drdisagree.teledrive.resources.transfers_resume
import com.drdisagree.teledrive.resources.transfers_section_paused
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

/**
 * The floating transfer widget, ported from Windows: an in-app overlay in one of two
 * shells chosen in Settings — the draggable chat-head **bubble** or the **bottom strip**.
 * Both shells open the same panel, whose pause / resume / cancel controls call the very
 * same [TransfersViewModel] functions the Transfers screen calls, over the same
 * [TransfersUiState] the screen renders. UI only: no transfer logic lives here.
 *
 * Android adaptation: the app's floating bottom navigation occupies the bottom ~100 dp,
 * so the shells lay out in the area above it (the strip's "window bottom" is the top of
 * that nav area), and the widget's persisted position follows the same store pattern as
 * Windows (dp offsets, sanitized and clamped on read).
 */
@Composable
fun TransferWidgetOverlay(store: AndroidTransferBubbleStore) {
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
    val actions = remember(viewModel) {
        TransferWidgetActions(
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onCancel = viewModel::cancel,
            onPauseAll = viewModel::pauseAll,
            onResumeAll = viewModel::resumeAll,
            onCancelAll = viewModel::cancelAll
        )
    }
    when (style) {
        TransferWidgetStyle.BUBBLE -> BubbleShell(
            state = state,
            summary = summary,
            nowMillis = nowMillis,
            actions = actions,
            accent = accent,
            store = store
        )
        TransferWidgetStyle.BOTTOM_STRIP -> StripShell(
            state = state,
            summary = summary,
            nowMillis = nowMillis,
            actions = actions,
            accent = accent
        )
    }
}

private data class TransferWidgetActions(
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onCancel: (String) -> Unit,
    val onPauseAll: () -> Unit,
    val onResumeAll: () -> Unit,
    val onCancelAll: () -> Unit
)

/** Height of the floating bottom navigation area the shells must clear. */
private const val BOTTOM_NAV_LIFT_DP = 100f

@Composable
private fun BubbleShell(
    state: TransfersUiState,
    summary: TransferBubbleSummary,
    nowMillis: Long,
    actions: TransferWidgetActions,
    accent: WidgetAccent?,
    store: AndroidTransferBubbleStore
) {
    var userHidden by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val windowHeight = (maxHeight.value - BOTTOM_NAV_LIFT_DP).coerceAtLeast(0f)
        val density = LocalDensity.current

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

        when (bubbleVisibility(enabled = true, fullscreen = false, userHidden = userHidden)) {
            BubbleVisibility.GONE -> Unit
            BubbleVisibility.TAB -> {
                val onLeft = TransferBubbleLayout.tabOnLeftEdge(effectiveOffset, windowWidth)
                TransferBubbleTab(
                    offset = TransferBubbleLayout.tabOffset(effectiveOffset, windowWidth, windowHeight),
                    onLeftEdge = onLeft,
                    accent = accent,
                    onClick = { userHidden = false }
                )
            }
            BubbleVisibility.BUBBLE -> {
                if (panelOpen) {
                    var panelSize by remember { mutableStateOf(IntSize.Zero) }
                    val listedCount = (state.active + state.paused).size
                    val panelHeightDp = if (panelSize == IntSize.Zero) {
                        estimatePanelHeightDp(listedCount)
                    } else {
                        panelSize.height / density.density
                    }
                    val panelWidthDp = if (panelSize == IntSize.Zero) {
                        PANEL_WIDTH_DP
                    } else {
                        panelSize.width / density.density
                    }
                    TransferBubblePanel(
                        state = state,
                        summary = summary,
                        nowMillis = nowMillis,
                        offset = TransferBubbleLayout.panelOffset(
                            bubble = effectiveOffset,
                            panelWidth = panelWidthDp,
                            panelHeight = panelHeightDp,
                            windowWidth = windowWidth,
                            windowHeight = windowHeight
                        ),
                        onSizeChanged = { panelSize = it },
                        actions = actions,
                        accent = accent,
                        onHide = {
                            panelOpen = false
                            userHidden = true
                        }
                    )
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
                    onClick = { panelOpen = !panelOpen }
                )
            }
        }
    }
}

/**
 * Drag-vs-click for the bubble: movement past the touch slop is a drag (the release then
 * does NOT toggle the panel); anything less is a click.
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
    onClick: () -> Unit
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = true)
        down.consume()
        var dragged = false
        var upChange: PointerInputChange? = null
        var cancelled = false
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
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
            upChange != null -> if (dragged) onDragFinished() else onClick()
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
    onClick: () -> Unit
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
                onDragStart, onDragDelta, onDragFinished, onDragCancelled, onClick
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

@Composable
private fun TransferBubblePanel(
    state: TransfersUiState,
    summary: TransferBubbleSummary,
    nowMillis: Long,
    offset: BubbleOffset,
    onSizeChanged: (IntSize) -> Unit,
    actions: TransferWidgetActions,
    accent: WidgetAccent?,
    onHide: () -> Unit,
    widthDp: Float = PANEL_WIDTH_DP
) {
    Surface(
        modifier = Modifier
            .offset {
                IntOffset(
                    (offset.x * density).roundToInt(),
                    (offset.y * density).roundToInt()
                )
            }
            .width(widthDp.dp)
            .onSizeChanged(onSizeChanged),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(accentColor(accent), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.SwapVert,
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = onAccentColor(accent)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(Res.string.transfers),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = Formatters.percent(summary.overallProgress),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = accentColor(accent)
                )
                // The same bulk actions, under the same conditions, as the Transfers
                // screen's top bar.
                if (summary.anyRunning) {
                    IconButton(onClick = actions.onPauseAll) {
                        Icon(
                            Icons.Filled.Pause,
                            contentDescription = stringResource(Res.string.transfers_pause)
                        )
                    }
                } else if (summary.anyQueued || state.paused.isNotEmpty()) {
                    IconButton(onClick = actions.onResumeAll) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(Res.string.transfers_resume)
                        )
                    }
                }
                IconButton(onClick = onHide) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(Res.string.bubble_hide)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { summary.overallProgress },
                modifier = Modifier.fillMaxWidth(),
                color = accentColor(accent)
            )
            Spacer(Modifier.height(14.dp))
            BubbleDirectionRow(
                uploading = true,
                activeCount = summary.uploadingActive,
                speedBytesPerSecond = summary.uploadingSpeedBytesPerSecond,
                transferredBytes = summary.uploadingTransferredBytes,
                totalBytes = summary.uploadingTotalBytes,
                progress = summary.uploadingProgress
            )
            Spacer(Modifier.height(12.dp))
            BubbleDirectionRow(
                uploading = false,
                activeCount = summary.downloadingActive,
                speedBytesPerSecond = summary.downloadingSpeedBytesPerSecond,
                transferredBytes = summary.downloadingTransferredBytes,
                totalBytes = summary.downloadingTotalBytes,
                progress = summary.downloadingProgress
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        Res.string.bubble_queue_waiting,
                        summary.queuedCount
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "↑ ${Formatters.bytes(summary.uploadedTotalBytes)}" +
                        "   ↓ ${Formatters.bytes(summary.downloadedTotalBytes)}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            if (summary.anyCancellable) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = actions.onCancelAll) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = stringResource(Res.string.bubble_cancel_all),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            // Every active and paused transfer the shared state holds — queued rows
            // included, each with its own Pause / Resume / Cancel. The list scrolls
            // under a height cap instead of truncating at a fixed count.
            val listed = state.active + state.paused
            if (listed.isEmpty()) {
                Text(
                    text = stringResource(Res.string.bubble_no_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp)
                )
            } else {
                Spacer(Modifier.height(8.dp))
                LazyColumn(
                    modifier = Modifier.heightIn(max = PANEL_LIST_MAX_HEIGHT_DP.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(listed, key = { it.id }) { task ->
                        BubbleTransferRow(
                            task = task,
                            nowMillis = nowMillis,
                            onPause = actions.onPause,
                            onResume = actions.onResume,
                            onCancel = actions.onCancel
                        )
                    }
                }
                val extra = (state.activeTotal - state.active.size) +
                    (state.pausedTotal - state.paused.size)
                if (extra > 0) {
                    Text(
                        text = stringResource(Res.string.bubble_more, extra),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun BubbleDirectionRow(
    uploading: Boolean,
    activeCount: Int,
    speedBytesPerSecond: Long,
    transferredBytes: Long,
    totalBytes: Long,
    progress: Float
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(
                    if (uploading) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    },
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (uploading) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (uploading) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSecondaryContainer
                }
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        if (uploading) {
                            Res.string.bubble_uploading
                        } else {
                            Res.string.bubble_downloading
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = Formatters.speed(speedBytesPerSecond),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        Res.string.bubble_transferred_of,
                        Formatters.bytes(transferredBytes),
                        Formatters.bytes(totalBytes),
                        (progress * 100).roundToInt()
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = stringResource(
                        if (activeCount == 1) {
                            Res.string.bubble_file_active
                        } else {
                            Res.string.bubble_files_active
                        },
                        activeCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
    }
}

@Composable
private fun BubbleTransferRow(
    task: TransferTask,
    nowMillis: Long,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (task.type.isIncoming) Icons.Filled.ArrowDownward else Icons.Filled.ArrowUpward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = task.displayName,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = bubbleTransferSublabel(task, nowMillis),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        when (task.state) {
            TransferState.RUNNING -> IconButton(
                onClick = { onPause(task.id) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Pause,
                    contentDescription = stringResource(Res.string.common_pause),
                    modifier = Modifier.size(16.dp)
                )
            }
            TransferState.PAUSED -> IconButton(
                onClick = { onResume(task.id) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = stringResource(Res.string.common_resume),
                    modifier = Modifier.size(16.dp)
                )
            }
            else -> Unit
        }
        if (
            task.state == TransferState.RUNNING ||
            task.state == TransferState.QUEUED ||
            task.state == TransferState.PAUSED
        ) {
            IconButton(
                onClick = { onCancel(task.id) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(Res.string.common_cancel),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun bubbleTransferSublabel(task: TransferTask, nowMillis: Long): String =
    when (task.state) {
        TransferState.RUNNING -> {
            val speed = effectiveSpeedBytesPerSecond(task, nowMillis)
            if (speed > 0) {
                "${Formatters.percent(task.progress)} · ${Formatters.speed(speed)}"
            } else {
                Formatters.percent(task.progress)
            }
        }
        TransferState.QUEUED ->
            "${stringResource(Res.string.common_backup_queued)} · ${Formatters.percent(task.progress)}"
        TransferState.PAUSED ->
            "${stringResource(Res.string.transfers_section_paused)} · ${Formatters.percent(task.progress)}"
        else -> Formatters.percent(task.progress)
    }

/**
 * The bottom strip: a slim bar centred at the bottom of the content area — live ↑/↓
 * speeds, the active count, the overall progress bar and the session totals — that
 * expands upward into the SAME panel the bubble opens and hides to a small centred
 * handle tab. A second shell over the shared panel, not a second implementation.
 */
@Composable
private fun StripShell(
    state: TransfersUiState,
    summary: TransferBubbleSummary,
    nowMillis: Long,
    actions: TransferWidgetActions,
    accent: WidgetAccent?
) {
    var userHidden by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val windowWidth = maxWidth.value
        val windowHeight = (maxHeight.value - BOTTOM_NAV_LIFT_DP).coerceAtLeast(0f)
        val densityFloat = LocalDensity.current.density
        val accentCol = accentColor(accent)

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
        if (panelOpen) {
            var panelSize by remember { mutableStateOf(IntSize.Zero) }
            val panelHeightDp = if (panelSize == IntSize.Zero) {
                estimatePanelHeightDp((state.active + state.paused).size)
            } else {
                panelSize.height / densityFloat
            }
            val panelWidthDp = if (panelSize == IntSize.Zero) {
                stripWidth
            } else {
                panelSize.width / densityFloat
            }
            TransferBubblePanel(
                state = state,
                summary = summary,
                nowMillis = nowMillis,
                offset = TransferStripLayout.panelOffset(
                    panelWidth = panelWidthDp,
                    panelHeight = panelHeightDp,
                    windowWidth = windowWidth,
                    windowHeight = windowHeight
                ),
                onSizeChanged = { panelSize = it },
                actions = actions,
                accent = accent,
                onHide = {
                    panelOpen = false
                    userHidden = true
                },
                widthDp = stripWidth
            )
        }

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
                .clickable { panelOpen = !panelOpen },
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
                    if (panelOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                    contentDescription = stringResource(Res.string.bubble_show_transfers),
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** First-frame estimate; the measured size replaces it after one layout. */
private fun estimatePanelHeightDp(listedCount: Int): Float =
    330f + minOf(listedCount, PANEL_LIST_VISIBLE_ROWS) * 40f

private const val PANEL_WIDTH_DP = 352f

/** The panel list's height cap: rows scroll beneath it, none are dropped. */
private const val PANEL_LIST_MAX_HEIGHT_DP = 240f

/** Rows visible under the height cap — only for the first-frame estimate. */
private const val PANEL_LIST_VISIBLE_ROWS = 6
