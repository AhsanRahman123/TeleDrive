package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.presentation.transfers.TransfersUiState

/**
 * Everything the transfer bubble renders, derived from [TransfersUiState] —
 * the exact state the Transfers screen renders (same repository flows, same
 * [com.drdisagree.teledrive.presentation.transfers.TransfersViewModel]
 * combine). This file is presentation arithmetic only: sums and counts over
 * the screen's own task lists. No speed or progress is computed here that
 * the engine did not already put on the task.
 *
 * Direction follows the app-wide convention: incoming = DOWNLOAD/RESTORE,
 * outgoing = UPLOAD/BACKUP (see TransferType.isIncoming).
 *
 * One rule is deliberately mirrored from the Transfers screen: a RUNNING
 * task whose last progress update is older than [SPEED_STALE_AFTER_MS]
 * displays speed 0 (TDLib can leave a job RUNNING while it waits for a
 * native resource). The screen applies the same rule with the same
 * threshold to the same tasks, so the bubble and the screen never disagree
 * about a speed.
 *
 * The section lists are capped at 200 rows by the ViewModel (the screen
 * lives with the same cap), so counts are counts of the visible lists —
 * again identical to what the screen itself counts.
 */
data class TransferBubbleSummary(
    /** Any unfinished work (active or paused sections non-empty). */
    val hasUnfinishedWork: Boolean,
    /** 0..1 across unfinished work; 1 when there is nothing unfinished. */
    val overallProgress: Float,
    val uploadingActive: Int,
    val uploadingSpeedBytesPerSecond: Long,
    val uploadingTransferredBytes: Long,
    val uploadingTotalBytes: Long,
    val downloadingActive: Int,
    val downloadingSpeedBytesPerSecond: Long,
    val downloadingTransferredBytes: Long,
    val downloadingTotalBytes: Long,
    /** QUEUED tasks in the active section, both directions (the badge). */
    val queuedCount: Int,
    val pausedCount: Int,
    /** Finished outgoing bytes (the Transfers screen's finished list). */
    val uploadedTotalBytes: Long,
    /** Finished incoming bytes (the Transfers screen's finished list). */
    val downloadedTotalBytes: Long,
    val anyRunning: Boolean,
    val anyQueued: Boolean
) {
    /**
     * Anything the shared store's cancel-all would act on (PC-31):
     * running or queued tasks in the active section, or any paused task.
     * The panel's labelled "Cancel all" button shows exactly when this
     * is true — the same condition family as the pause-all / resume-all
     * icons beside it.
     */
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
 * The speed the UI may show for [task] at [nowMillis]: the engine's rate
 * for a RUNNING task, zeroed when the task's last progress update is older
 * than [SPEED_STALE_AFTER_MS] (the Transfers screen's stale rule) and for
 * every non-running state.
 */
internal fun effectiveSpeedBytesPerSecond(task: TransferTask, nowMillis: Long): Long =
    when {
        task.state != TransferState.RUNNING -> 0L
        task.speedBytesPerSecond > 0L &&
            nowMillis - task.updatedAt > SPEED_STALE_AFTER_MS -> 0L
        else -> task.speedBytesPerSecond
    }
