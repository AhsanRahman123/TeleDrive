package com.drdisagree.teledrive.presentation.transfers

import androidx.compose.ui.graphics.Color
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.presentation.platform.TransferWidgetColor

/**
 * Pure logic for the Android transfer widget — a direct port of the Windows widget's
 * layout, summary and drag-session code (desktop TransferBubbleLayout / TransferStripLayout
 * / TransferBubbleSummary / BubbleDragSession), so both platforms behave the same and the
 * geometry is unit-testable without Compose.
 */

/** A dp offset of a top-left corner from the window's top-left. */
data class BubbleOffset(val x: Float, val y: Float)

/** What the transfer-bubble overlay shows, if anything. */
enum class BubbleVisibility { GONE, TAB, BUBBLE }

/**
 * The overlay's visibility decision, in priority order: the Settings master switch wins
 * over everything, fullscreen video hides the widget completely, the user's hide collapses
 * it to the edge pull-tab, otherwise the bubble shows.
 */
fun bubbleVisibility(
    enabled: Boolean,
    fullscreen: Boolean,
    userHidden: Boolean
): BubbleVisibility = when {
    !enabled -> BubbleVisibility.GONE
    fullscreen -> BubbleVisibility.GONE
    userHidden -> BubbleVisibility.TAB
    else -> BubbleVisibility.BUBBLE
}

/** Pure layout math for the transfer bubble, in dp (ported from the Windows layout). */
object TransferBubbleLayout {

    const val BUBBLE_SIZE_DP = 64f

    /** Gap kept between the bubble and every window edge. */
    const val EDGE_MARGIN_DP = 16f

    /** Smallest gap the panel/tab keep from window edges. */
    const val PANEL_MARGIN_DP = 8f

    /** Vertical gap between the bubble and its expanded panel. */
    const val PANEL_GAP_DP = 10f

    const val TAB_WIDTH_DP = 26f
    const val TAB_HEIGHT_DP = 56f

    /** Material 3 Scaffold FAB: size and margin from the window edges. */
    const val FAB_SIZE_DP = 56f
    const val FAB_MARGIN_DP = 16f

    /** Extra air between the bubble's default spot and the FAB's top edge. */
    const val FAB_CLEARANCE_DP = 16f

    /**
     * The first-run position: bottom-right, sitting above the Create button with
     * [FAB_CLEARANCE_DP] of air — never covering it.
     */
    fun defaultOffset(windowWidth: Float, windowHeight: Float): BubbleOffset {
        val bottomFromWindowBottom = FAB_MARGIN_DP + FAB_SIZE_DP + FAB_CLEARANCE_DP
        return clampOffset(
            x = windowWidth - EDGE_MARGIN_DP - BUBBLE_SIZE_DP,
            y = windowHeight - bottomFromWindowBottom - BUBBLE_SIZE_DP,
            windowWidth = windowWidth,
            windowHeight = windowHeight
        )
    }

    /** Keeps the whole bubble inside the window. */
    fun clampOffset(
        x: Float,
        y: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset = BubbleOffset(
        x = x.coerceIn(0f, (windowWidth - BUBBLE_SIZE_DP).coerceAtLeast(0f)),
        y = y.coerceIn(0f, (windowHeight - BUBBLE_SIZE_DP).coerceAtLeast(0f))
    )

    /**
     * Read-time validation for a persisted bubble position: a stored point that is
     * non-finite or wholly outside the current window can only be corruption — or a
     * window that moved entirely away from where the position was saved — so it falls
     * back to the default spot instead of being trusted forever. A point at least partly
     * inside the window passes through and is clamped into view as usual.
     */
    fun sanitizePosition(
        raw: BubbleOffset,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        if (!raw.x.isFinite() || !raw.y.isFinite()) {
            return defaultOffset(windowWidth, windowHeight)
        }
        val partlyInside = raw.x + BUBBLE_SIZE_DP > 0f && raw.x < windowWidth &&
            raw.y + BUBBLE_SIZE_DP > 0f && raw.y < windowHeight
        return if (partlyInside) {
            clampOffset(raw.x, raw.y, windowWidth, windowHeight)
        } else {
            defaultOffset(windowWidth, windowHeight)
        }
    }

    /**
     * One drag step: the delta lands on the *effective* (visible, clamped) offset, never
     * on raw stored state, so state and visible position cannot diverge — and a diverged
     * state cannot appear to jump when the first drag starts.
     */
    fun movedBy(
        effective: BubbleOffset,
        dxDp: Float,
        dyDp: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset = clampOffset(
        effective.x + dxDp, effective.y + dyDp, windowWidth, windowHeight
    )

    /**
     * Where the expanded panel goes: right-aligned with the bubble, above it when that
     * fits (the bubble lives near the bottom by default), otherwise below it; always
     * clamped inside the window with [PANEL_MARGIN_DP] to spare.
     */
    fun panelOffset(
        bubble: BubbleOffset,
        panelWidth: Float,
        panelHeight: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        val rightAlignedX = bubble.x + BUBBLE_SIZE_DP - panelWidth
        val aboveY = bubble.y - PANEL_GAP_DP - panelHeight
        val belowY = bubble.y + BUBBLE_SIZE_DP + PANEL_GAP_DP
        val y = if (aboveY >= PANEL_MARGIN_DP) aboveY else belowY
        return BubbleOffset(
            x = rightAlignedX.coerceIn(
                PANEL_MARGIN_DP,
                (windowWidth - panelWidth - PANEL_MARGIN_DP).coerceAtLeast(PANEL_MARGIN_DP)
            ),
            y = y.coerceIn(
                PANEL_MARGIN_DP,
                (windowHeight - panelHeight - PANEL_MARGIN_DP).coerceAtLeast(PANEL_MARGIN_DP)
            )
        )
    }

    /**
     * The hidden state's pull-tab: flush against the window edge nearest the bubble's
     * centre, vertically centred on the bubble, clamped inside the window.
     */
    fun tabOffset(
        bubble: BubbleOffset,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        val centerX = bubble.x + BUBBLE_SIZE_DP / 2f
        val centerY = bubble.y + BUBBLE_SIZE_DP / 2f
        val x = if (centerX <= windowWidth / 2f) 0f else windowWidth - TAB_WIDTH_DP
        val y = (centerY - TAB_HEIGHT_DP / 2f)
            .coerceIn(0f, (windowHeight - TAB_HEIGHT_DP).coerceAtLeast(0f))
        return BubbleOffset(x, y)
    }

    /** True when the pull-tab for this bubble position hugs the left edge. */
    fun tabOnLeftEdge(bubble: BubbleOffset, windowWidth: Float): Boolean =
        bubble.x + BUBBLE_SIZE_DP / 2f <= windowWidth / 2f
}

/**
 * The bubble's drag lifecycle as pure state, kept Compose-free so the gesture hardening
 * is unit-testable: a drag begins by remembering the pre-drag offset; completing a drag
 * yields the final offset exactly once (the shell persists it); cancelling a drag yields
 * the pre-drag offset (the shell restores it) and persists nothing — a phantom or
 * interrupted drag can never move the bubble permanently.
 */
class BubbleDragSession {

    private var startOffset: BubbleOffset? = null

    /** True between [begin] and the drag's completion or cancellation. */
    val active: Boolean get() = startOffset != null

    /** Arms a drag at [offset] — the visible, clamped position. */
    fun begin(offset: BubbleOffset) {
        startOffset = offset
    }

    /**
     * The drag ended with a pointer-up. Returns the offset to persist, or null when no
     * drag was in progress (nothing to persist).
     */
    fun complete(finalOffset: BubbleOffset): BubbleOffset? =
        startOffset?.let {
            startOffset = null
            finalOffset
        }

    /**
     * The drag was cancelled — pointer cancel, or the tracked change vanished without an
     * up. Returns the pre-drag offset to restore, or null when no drag was in progress.
     * Never persists.
     */
    fun cancel(): BubbleOffset? =
        startOffset?.also { startOffset = null }
}

/** Pure layout math for the bottom-strip transfer widget, in dp (ported from Windows). */
object TransferStripLayout {

    /** The bar never grows past this wide, however wide the window is. */
    const val MAX_WIDTH_DP = 560f

    /** Air kept between the bar and the window's side edges. */
    const val SIDE_MARGIN_DP = 16f

    /** The bar floats this far above the window's bottom edge. */
    const val BOTTOM_MARGIN_DP = 12f

    const val BAR_HEIGHT_DP = 44f

    /** The hidden state's handle tab. */
    const val TAB_WIDTH_DP = 84f
    const val TAB_HEIGHT_DP = 24f

    /** Vertical gap between the bar and the panel it expands into. */
    const val PANEL_GAP_DP = 8f

    fun stripWidth(windowWidth: Float): Float =
        minOf(MAX_WIDTH_DP, (windowWidth - SIDE_MARGIN_DP * 2f).coerceAtLeast(0f))

    /** Top-left of the bar: horizontally centred, [BOTTOM_MARGIN_DP] off the bottom. */
    fun stripOffset(windowWidth: Float, windowHeight: Float): BubbleOffset = BubbleOffset(
        x = (windowWidth - stripWidth(windowWidth)) / 2f,
        y = (windowHeight - BOTTOM_MARGIN_DP - BAR_HEIGHT_DP).coerceAtLeast(0f)
    )

    /** Top-left of the handle tab: centred, flush with the bottom edge. */
    fun tabOffset(windowWidth: Float, windowHeight: Float): BubbleOffset = BubbleOffset(
        x = (windowWidth - TAB_WIDTH_DP) / 2f,
        y = (windowHeight - TAB_HEIGHT_DP).coerceAtLeast(0f)
    )

    /**
     * Where the expanded panel goes: centred on the strip, directly above it, clamped
     * inside the window with [TransferBubbleLayout.PANEL_MARGIN_DP] to spare.
     */
    fun panelOffset(
        panelWidth: Float,
        panelHeight: Float,
        windowWidth: Float,
        windowHeight: Float
    ): BubbleOffset {
        val strip = stripOffset(windowWidth, windowHeight)
        val margin = TransferBubbleLayout.PANEL_MARGIN_DP
        return BubbleOffset(
            x = (strip.x + stripWidth(windowWidth) / 2f - panelWidth / 2f).coerceIn(
                margin,
                (windowWidth - panelWidth - margin).coerceAtLeast(margin)
            ),
            y = (strip.y - PANEL_GAP_DP - panelHeight).coerceIn(
                margin,
                (windowHeight - panelHeight - margin).coerceAtLeast(margin)
            )
        )
    }
}

/**
 * Everything the transfer widget renders, derived from [TransfersUiState] — the exact
 * state the Transfers screen renders. Presentation arithmetic only: sums and counts over
 * the screen's own task lists. Direction follows the app-wide convention: incoming =
 * DOWNLOAD/RESTORE, outgoing = UPLOAD/BACKUP (see TransferType.isIncoming). A RUNNING
 * task whose last progress update is older than [SPEED_STALE_AFTER_MS] displays speed 0 —
 * the same rule, with the same threshold, as the Transfers screen.
 */
data class TransferBubbleSummary(
    val hasUnfinishedWork: Boolean,
    val overallProgress: Float,
    val uploadingActive: Int,
    val uploadingSpeedBytesPerSecond: Long,
    val uploadingTransferredBytes: Long,
    val uploadingTotalBytes: Long,
    val downloadingActive: Int,
    val downloadingSpeedBytesPerSecond: Long,
    val downloadingTransferredBytes: Long,
    val downloadingTotalBytes: Long,
    val queuedCount: Int,
    val pausedCount: Int,
    val uploadedTotalBytes: Long,
    val downloadedTotalBytes: Long,
    val anyRunning: Boolean,
    val anyQueued: Boolean
) {
    val anyCancellable: Boolean
        get() = anyRunning || anyQueued || pausedCount > 0

    val uploadingProgress: Float
        get() = directionProgress(uploadingTransferredBytes, uploadingTotalBytes)

    val downloadingProgress: Float
        get() = directionProgress(downloadingTransferredBytes, downloadingTotalBytes)

    private fun directionProgress(transferred: Long, total: Long): Float =
        if (total > 0) (transferred.toFloat() / total).coerceIn(0f, 1f) else 0f
}

fun summarizeTransfers(state: TransfersUiState, nowMillis: Long): TransferBubbleSummary {
    val unfinished = state.active + state.paused
    val uploading = unfinished.filter { !it.type.isIncoming }
    val downloading = unfinished.filter { it.type.isIncoming }

    fun List<TransferTask>.running(): List<TransferTask> =
        filter { it.state == TransferState.RUNNING }

    fun List<TransferTask>.speed(): Long = sumOf { effectiveSpeedBytesPerSecond(it, nowMillis) }

    val totalBytes = unfinished.sumOf { it.sizeBytes }
    val transferredBytes = unfinished.sumOf { it.transferredBytes }

    return TransferBubbleSummary(
        hasUnfinishedWork = unfinished.isNotEmpty(),
        overallProgress = when {
            unfinished.isEmpty() -> 1f
            totalBytes > 0L -> (transferredBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
            else -> 0f
        },
        uploadingActive = uploading.running().size,
        uploadingSpeedBytesPerSecond = uploading.speed(),
        uploadingTransferredBytes = uploading.sumOf { it.transferredBytes },
        uploadingTotalBytes = uploading.sumOf { it.sizeBytes },
        downloadingActive = downloading.running().size,
        downloadingSpeedBytesPerSecond = downloading.speed(),
        downloadingTransferredBytes = downloading.sumOf { it.transferredBytes },
        downloadingTotalBytes = downloading.sumOf { it.sizeBytes },
        queuedCount = state.active.count { it.state == TransferState.QUEUED },
        pausedCount = state.paused.size,
        uploadedTotalBytes = state.completed
            .filter { !it.type.isIncoming }
            .sumOf { it.transferredBytes },
        downloadedTotalBytes = state.completed
            .filter { it.type.isIncoming }
            .sumOf { it.transferredBytes },
        anyRunning = state.active.any { it.state == TransferState.RUNNING },
        anyQueued = state.active.any { it.state == TransferState.QUEUED }
    )
}

/** Mirrors TransfersScreen's SPEED_STALE_AFTER_MS (same rule, same value). */
internal const val SPEED_STALE_AFTER_MS = 5_000L

/**
 * The speed the UI may show for [task] at [nowMillis]: the engine's rate for a RUNNING
 * task, zeroed when the task's last progress update is older than [SPEED_STALE_AFTER_MS]
 * and for every non-running state.
 */
internal fun effectiveSpeedBytesPerSecond(task: TransferTask, nowMillis: Long): Long =
    when {
        task.state != TransferState.RUNNING -> 0L
        task.speedBytesPerSecond > 0L &&
            nowMillis - task.updatedAt > SPEED_STALE_AFTER_MS -> 0L
        else -> task.speedBytesPerSecond
    }

/** The widget's colour presets resolved to actual accents (ported from Windows). */
data class WidgetAccent(val accent: Color, val onAccent: Color)

/** [TransferWidgetColor.APP_ACCENT] resolves to null: "use the app's Material accent". */
fun widgetAccent(preset: TransferWidgetColor): WidgetAccent? = when (preset) {
    TransferWidgetColor.APP_ACCENT -> null
    TransferWidgetColor.ORANGE -> WidgetAccent(Color(0xFFFF7000), Color.White)
    TransferWidgetColor.BLUE -> WidgetAccent(Color(0xFF2563EB), Color.White)
    TransferWidgetColor.GREEN -> WidgetAccent(Color(0xFF16A34A), Color.White)
    TransferWidgetColor.PURPLE -> WidgetAccent(Color(0xFF7C3AED), Color.White)
    TransferWidgetColor.GRAPHITE -> WidgetAccent(Color(0xFF4B5563), Color.White)
}
