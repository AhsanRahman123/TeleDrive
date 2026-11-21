package com.drdisagree.teledrive.core.publish

/**
 * The terminal outcome of the last FINISHED publish run (PC-33).
 *
 * [PublishRunState] alone cannot express this: a completed drain
 * returns the state to [PublishRunState.Idle], which is
 * indistinguishable from "no run has ever finished" — so Home had no
 * honest resting state to show when the backlog reached zero, and the
 * sync card simply vanished at the exact moment sync first succeeded
 * (round 9 diagnosis §2). The outcome is recorded only when a run
 * actually finishes (never for a run still going or waiting to
 * retry), and it is replaced only by the next finished run: a Failed
 * or Stopped outcome therefore stays visible until a later drain
 * completes and clears it.
 */
sealed interface PublishRunOutcome {

    /** The last finished run drained the backlog cleanly. */
    data object Completed : PublishRunOutcome

    /** The last finished run was cancelled by the user (lossless). */
    data object Stopped : PublishRunOutcome

    /**
     * The last finished run drained, but [failedFiles] files were
     * permanently rejected by Telegram and stay pending.
     * [reason] is the first rejection's message when known.
     */
    data class Failed(val failedFiles: Int, val reason: String?) : PublishRunOutcome
}
