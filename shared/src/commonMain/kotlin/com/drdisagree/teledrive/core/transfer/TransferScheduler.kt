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
}
