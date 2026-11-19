package com.drdisagree.teledrive.desktop

import com.drdisagree.teledrive.presentation.platform.PlatformCapabilities

class DesktopPlatformCapabilities : PlatformCapabilities {

    override val supportsAutoBackup: Boolean = true

    override val requiresPermissions: Boolean = false

    override val supportsPullToRefresh: Boolean = false

    override val supportsAppLock: Boolean = false

    override val supportsScreenCaptureBlocking: Boolean = true

    override val supportsDynamicColor: Boolean = false

    override val supportsMeteredTransfers: Boolean = false
}
