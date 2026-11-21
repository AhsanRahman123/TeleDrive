package com.drdisagree.teledrive.core.publish

/**
 * The visible state of the manifest-publish run (PC-32).
 *
 * Before this existed the publish loop had no observable state at all:
 * a stalled or failing drain looked exactly like a working one — the
 * Home banner showed a frozen pending count, "Sync now" presses were
 * swallowed by the scheduler's already-running guard, and nothing was
 * ever surfaced. The banner now renders this state next to the count,
 * and [PublishScheduler.cancel] can stop a run cooperatively.
 */
sealed interface PublishRunState {

    /** No run is active and the last run (if any) finished cleanly. */
    data object Idle : PublishRunState

    /**
     * A drain is publishing; [published] of [total] files are done in this run.
     * [waitingSecondsLeft] is non-null while the drain is parked in a
     * Telegram rate-limit wait (PC-35): the banner shows the countdown
     * instead of implying motion with a frozen count — the pre-PC-35
     * banner sat on "0 of N saved in this run" for hours while the
     * drain waited silently.
     */
    data class Running(
        val published: Int,
        val total: Int,
        val waitingSecondsLeft: Int? = null
    ) : PublishRunState

    /**
     * The last attempt failed and the scheduler is waiting to retry with
     * backoff. [reason] is the last failure's message when one is known.
     * A fresh `kick()` skips the wait and retries immediately.
     */
    data class WaitingRetry(val reason: String?) : PublishRunState

    /**
     * The run was cancelled by the user. Lossless: rows already published
     * keep their cleared flag, the rest stay pending, and a later
     * `kick()` resumes exactly the remainder.
     */
    data object Stopped : PublishRunState

    /**
     * The backlog drained, but [failedFiles] files were permanently
     * rejected by Telegram and stay pending (surfaced, never silently
     * dropped). [reason] is the first rejection's message.
     */
    data class Failed(val failedFiles: Int, val reason: String?) : PublishRunState
}
