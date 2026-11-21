package com.drdisagree.teledrive.core.publish

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface PublishScheduler {

    /**
     * Ask for a publish run. Every call must count (PC-32): while a run
     * is active the scheduler owes another pass as soon as the current
     * one ends, and while it is backing off after a failure the press
     * retries immediately. The pre-PC-32 desktop implementation dropped
     * every kick that arrived while its loop was alive — which, combined
     * with a drain that could never succeed, made "Sync now" a no-op
     * forever.
     */
    fun kick()

    /**
     * Cooperatively stop the active run between batches (PC-32
     * addendum). Lossless: rows already published keep their cleared
     * pending flag; unpublished rows stay pending and a later [kick]
     * resumes them. A no-op when no run is active. Platforms whose
     * scheduler cannot observe or stop a run keep this default.
     */
    fun cancel() {}

    /**
     * The run's visible state, for surfaces like the Home banner.
     * Schedulers that cannot report state keep the shared [Idle] flow,
     * which renders exactly like the pre-PC-32 banner.
     */
    val runState: StateFlow<PublishRunState>
        get() = idleRunState

    /**
     * The last FINISHED run's outcome (PC-33), for surfaces like the
     * Home sync card: it lets the card show an honest resting state
     * ("All synced") after a completed drain and keep a failed or
     * stopped run visible until a later drain completes. Schedulers
     * that cannot report it keep the shared `null` flow (no run has
     * finished), which renders exactly like the pre-PC-33 card.
     */
    val lastOutcome: StateFlow<PublishRunOutcome?>
        get() = idleOutcome

    /**
     * When the last run finished, as epoch milliseconds; `null`
     * until a run finishes. Paired with [lastOutcome].
     */
    val lastFinishedAtMs: StateFlow<Long?>
        get() = idleFinishedAtMs

    companion object {
        private val idleRunState: StateFlow<PublishRunState> =
            MutableStateFlow<PublishRunState>(PublishRunState.Idle).asStateFlow()
        private val idleOutcome: StateFlow<PublishRunOutcome?> =
            MutableStateFlow<PublishRunOutcome?>(null).asStateFlow()
        private val idleFinishedAtMs: StateFlow<Long?> =
            MutableStateFlow<Long?>(null).asStateFlow()
    }
}
