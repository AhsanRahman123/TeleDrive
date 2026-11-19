package com.drdisagree.teledrive.desktop

import com.drdisagree.teledrive.presentation.platform.PlatformCapabilities

class DesktopPlatformCapabilities : PlatformCapabilities {

    // Folder auto-backup was removed from the desktop app (2026-11-20, user's
    // request): this hides the Backup settings section, the Home backup card,
    // the onboarding backup step and Exclusions, and — via AppViewModel's
    // capability check — stops the shared launch catch-up scan from running
    // on desktop. Android's flag stays true; its backup is untouched.
    override val supportsAutoBackup: Boolean = false

    override val requiresPermissions: Boolean = false

    override val supportsPullToRefresh: Boolean = false

    override val supportsAppLock: Boolean = false

    override val supportsScreenCaptureBlocking: Boolean = true

    override val supportsDynamicColor: Boolean = false

    override val supportsMeteredTransfers: Boolean = false
}
