package com.drdisagree.teledrive.core.transfer

interface TransferScheduler {

    fun kick(allowMetered: Boolean)

    fun rekick(allowMetered: Boolean)

    /** Stops and joins the desktop drain after database rows have been marked PAUSED. */
    suspend fun pause() = Unit

    /** Explicitly starts queued work after a global pause. */
    fun resume(allowMetered: Boolean) {
        rekick(allowMetered)
    }

    /**
     * Whether the Pause-all hold is currently latched (vc184 MF-1). While it
     * is set, [resume] must NOT be used for a per-row repair: on both
     * platforms resume is the GLOBAL latch release (it clears the hold and
     * restarts the drain for the whole queue), so routing a single row's
     * resume through it would silently undo Pause all. Callers that only
     * need one row runnable again must re-queue that row and kick instead —
     * kicks are dropped while the hold is set, and Resume all re-queues any
     * remaining PAUSED rows and clears the hold anyway. Defaults to false
     * for schedulers with no latch.
     */
    fun isPauseHeld(): Boolean = false
}
