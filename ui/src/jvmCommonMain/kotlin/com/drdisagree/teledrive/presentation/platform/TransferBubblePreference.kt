package com.drdisagree.teledrive.presentation.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Master switch for the floating transfer bubble.
 *
 * The bubble itself is a desktop-only overlay (an in-app chat-head with live
 * transfer progress and controls). This hook exists so the shared Settings
 * screen can offer its "Show transfer bubble" toggle without knowing about
 * the desktop store behind it. Desktop provides an implementation backed by
 * its java.util.prefs store family; other platforms do not provide one (the
 * local stays null) and never render the row — Android behaviour is
 * unchanged, exactly like [TransfersSortPreference].
 */
interface TransferBubblePreference {
    fun isEnabled(): Boolean
    fun setEnabled(value: Boolean)
}

val LocalTransferBubblePreference = staticCompositionLocalOf<TransferBubblePreference?> { null }
