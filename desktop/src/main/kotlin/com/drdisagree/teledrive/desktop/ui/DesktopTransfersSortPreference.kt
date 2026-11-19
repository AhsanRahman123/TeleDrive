package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.presentation.platform.TransfersSortPreference
import com.drdisagree.teledrive.presentation.platform.sanitizeTransfersSortOrder
import java.util.prefs.Preferences

/**
 * Desktop [TransfersSortPreference] (PC-6): the Transfers sort order in the
 * same java.util.prefs store family as the other desktop-only preferences
 * (player controls, subtitle appearance, picker positions). Stored values
 * are sanitized on the way in and out, so a corrupt or outdated value can
 * never reach the screen.
 */
internal object DesktopTransfersSortPreference : TransfersSortPreference {

    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/transfers")
    }

    override fun loadSortOrder(): String = load(preferences)

    override fun saveSortOrder(value: String) = save(preferences, value)

    internal fun load(source: Preferences): String =
        sanitizeTransfersSortOrder(
            runCatching { source.get(KEY_SORT_ORDER, null) }.getOrNull()
        )

    internal fun save(target: Preferences, value: String) {
        runCatching {
            target.put(KEY_SORT_ORDER, sanitizeTransfersSortOrder(value))
            target.flush()
        }
    }

    private const val KEY_SORT_ORDER = "sort_order"
}
