package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.domain.model.UserPreferences

/**
 * PC-39 (1.6.17): per-direction simultaneous-transfer limits.
 *
 * Until now one shared pool served every direction: the user's single
 * "parallel transfers" number capped uploads + downloads COMBINED, so two
 * running downloads starved uploads completely and true simultaneous
 * up + down was impossible. The drainer now runs independent upload and
 * download lanes, each bounded by its own user setting
 * ([UserPreferences.uploadConcurrency] / [UserPreferences.downloadConcurrency],
 * persisted, 1..6, defaulting to the user's proven-safe 2/2).
 *
 * The limits are read from the preferences flow on every claim, so they
 * apply LIVE: raising a number starts queued transfers without touching
 * in-flight ones; lowering it never cancels anything in flight — the lane
 * simply makes no new starts until its active count falls below the new
 * limit. Nothing here ever writes the settings back: a flood storm can
 * slow transfers down, it can never shrink the user's chosen numbers.
 *
 * Send-side safety at the top of the range does not come from these
 * numbers but from [com.drdisagree.teledrive.core.telegram.TelegramPacer]:
 * every message creation (each upload start) is rationed by its token
 * bucket (burst 5, 20/minute sustained) and every flood response parks
 * the whole account behind one visible, bounded deadline. Downloads
 * create no messages and never touch the pacer.
 */
object TransferConcurrency {

    const val MIN_SIMULTANEOUS = 1
    const val MAX_SIMULTANEOUS = 6

    /** How many upload-lane transfers (UPLOAD + BACKUP) may run at once. */
    fun uploadLimit(prefs: UserPreferences): Int =
        prefs.uploadConcurrency.coerceIn(MIN_SIMULTANEOUS, MAX_SIMULTANEOUS)

    /** How many download-lane transfers (DOWNLOAD + RESTORE) may run at once. */
    fun downloadLimit(prefs: UserPreferences): Int =
        prefs.downloadConcurrency.coerceIn(MIN_SIMULTANEOUS, MAX_SIMULTANEOUS)
}
