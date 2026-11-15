package com.drdisagree.teledrive.presentation.platform

import androidx.compose.runtime.staticCompositionLocalOf

interface PlatformCapabilities {

    val supportsAutoBackup: Boolean

    val requiresPermissions: Boolean

    val supportsPullToRefresh: Boolean

    val supportsAppLock: Boolean

    val supportsScreenCaptureBlocking: Boolean

    val supportsDynamicColor: Boolean

    /** Whether returning from an item preview restores the previous collection scroll position. */
    val savesCollectionScrollOnPreview: Boolean get() = false

    /** Android file manager exposes a fixed touch-first bulk-selection action bar. */
    val supportsTouchSelectionActionBar: Boolean get() = false
}

val LocalPlatformCapabilities = staticCompositionLocalOf<PlatformCapabilities> {
    error("PlatformCapabilities is not provided")
}

/**
 * Requests a physical display mode exposed by Android, rather than changing only app animation
 * timing. Implementations must reject rates that are not present in the display's supported modes.
 */
interface DisplayRefreshRateController {
    fun supportedRates(): Set<Int>

    fun resolveSupportedRefreshRate(refreshRate: Int): Float?

    fun requestRefreshRate(refreshRate: Int): Boolean
}

val LocalDisplayRefreshRateController = staticCompositionLocalOf<DisplayRefreshRateController?> {
    null
}
