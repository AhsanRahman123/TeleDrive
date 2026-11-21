package com.drdisagree.teledrive.presentation.home

import com.drdisagree.teledrive.core.publish.PublishRunOutcome
import com.drdisagree.teledrive.core.publish.PublishRunState

data class PendingSync(
    val files: Int = 0,
    val folders: Boolean = false
) {
    val visible: Boolean get() = files > 0 || folders
}

/**
 * Everything the Home sync card renders from (PC-33): the live
 * backlog and run state, plus the last FINISHED run's outcome and
 * finish time. Before PC-33 the card was gated on the backlog count
 * alone, so it vanished at count zero in every run state — the exact
 * moment sync first succeeded looked identical to the card being
 * broken (round 9 diagnosis §2).
 */
data class SyncCardState(
    val pending: PendingSync = PendingSync(),
    val runState: PublishRunState = PublishRunState.Idle,
    val lastOutcome: PublishRunOutcome? = null,
    val lastFinishedAtMs: Long? = null
) {
    val display: SyncCardDisplay
        get() = syncCardDisplay(pending, runState, lastOutcome)

    val visible: Boolean
        get() = display != SyncCardDisplay.Hidden
}

/** What the Home sync card should render. */
sealed interface SyncCardDisplay {

    /** Nothing to say yet: no backlog, no active run, no finished run. */
    data object Hidden : SyncCardDisplay

    /** The original card: pending work waiting, or a run in flight. */
    data object Active : SyncCardDisplay

    /**
     * The resting state: the backlog is zero and the last finished
     * run completed — "All synced", with the finish time.
     */
    data object AllSynced : SyncCardDisplay

    /**
     * A run finished with permanently rejected files. Stays on
     * screen until a later run completes, even at a zero backlog.
     */
    data class StickyFailed(val failedFiles: Int, val reason: String?) : SyncCardDisplay

    /**
     * A run was cancelled by the user. Stays on screen until a later
     * run completes, even at a zero backlog.
     */
    data object StickyStopped : SyncCardDisplay
}

/**
 * The card-state mapping (PC-33), pure so it is pinned by tests:
 *
 * - a run in flight (Running / WaitingRetry) always shows the
 *   active card;
 * - a non-zero backlog always shows the active card (its title
 *   already reflects a Failed/Stopped run state);
 * - at a zero backlog with the loop idle, the last finished run
 *   decides: Failed and Stopped are sticky — they stay visible
 *   until a later run completes and replaces the outcome — and
 *   only a Completed outcome earns the "All synced" resting card;
 * - before any run has finished, the card stays hidden.
 */
fun syncCardDisplay(
    pending: PendingSync,
    runState: PublishRunState,
    lastOutcome: PublishRunOutcome?
): SyncCardDisplay = when {
    runState is PublishRunState.Running || runState is PublishRunState.WaitingRetry ->
        SyncCardDisplay.Active
    pending.visible -> SyncCardDisplay.Active
    runState is PublishRunState.Failed ->
        SyncCardDisplay.StickyFailed(runState.failedFiles, runState.reason)
    runState is PublishRunState.Stopped -> SyncCardDisplay.StickyStopped
    lastOutcome is PublishRunOutcome.Failed ->
        SyncCardDisplay.StickyFailed(lastOutcome.failedFiles, lastOutcome.reason)
    lastOutcome is PublishRunOutcome.Stopped -> SyncCardDisplay.StickyStopped
    lastOutcome is PublishRunOutcome.Completed -> SyncCardDisplay.AllSynced
    else -> SyncCardDisplay.Hidden
}

/**
 * "HH:MM" (24-hour) for an epoch-milliseconds instant at a fixed
 * UTC offset. Pure arithmetic — no calendar or locale involved —
 * so it is identical on every platform and pinnable by tests.
 */
internal fun formatSyncTimeOfDay(epochMs: Long, utcOffsetMs: Long): String {
    val dayMs = 86_400_000L
    val shifted = ((epochMs + utcOffsetMs) % dayMs + dayMs) % dayMs
    val hours = shifted / 3_600_000L
    val minutes = (shifted % 3_600_000L) / 60_000L
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}"
}

/** [formatSyncTimeOfDay] in the device's own time zone. */
fun formatSyncLastRun(epochMs: Long): String =
    formatSyncTimeOfDay(epochMs, java.util.TimeZone.getDefault().getOffset(epochMs).toLong())
