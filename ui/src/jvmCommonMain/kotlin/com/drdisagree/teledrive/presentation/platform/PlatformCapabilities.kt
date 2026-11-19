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

    /**
     * Whether managed local copies are removed automatically after a confirmed upload.
     * Android opts in; desktop keeps its user-controlled Delete-after-upload toggle instead.
     * This is deliberately separate from [supportsAutoBackup]: the two coincided on Android
     * only, and re-using that flag here hid the desktop toggle once backup was ungated.
     */
    val autoRemovesManagedCopiesAfterUpload: Boolean get() = false

    /** Whether the Storage Explorer section is offered in Settings. */
    val supportsStorageExplorer: Boolean get() = true

    /**
     * Whether transfers can meaningfully be restricted to unmetered networks
     * (the "Transfers on Wi-Fi only" toggle). Desktop networking is wired/unmetered,
     * so the toggle could never fire there and is hidden instead of dead.
     */
    val supportsMeteredTransfers: Boolean get() = true

    /**
     * Whether the platform offers two distinct upload pickers — a built-in
     * one and the operating system's native one — and the create menu should
     * therefore list each upload action in both variants explicitly.
     * Desktop has both; Android has only its system picker, so its menu is
     * unchanged. The explicit variants are provided through
     * [LocalInAppMultiFilePicker], [LocalInAppFolderPicker],
     * [LocalNativeMultiFilePicker] and [LocalNativeFolderPicker]; the
     * classic pickers keep following the platform's default (on desktop,
     * the "Use Windows native file picker" setting).
     */
    val supportsExplicitPickerChoice: Boolean get() = false
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

    /** Returns the exact native rate matching the requested option at the current resolution. */
    fun resolveSupportedRefreshRate(refreshRate: Int): Float?

    /** Requests the selected native refresh rate for the Activity window. */
    fun requestRefreshRate(refreshRate: Int): Boolean
}

val LocalDisplayRefreshRateController = staticCompositionLocalOf<DisplayRefreshRateController?> {
    null
}
