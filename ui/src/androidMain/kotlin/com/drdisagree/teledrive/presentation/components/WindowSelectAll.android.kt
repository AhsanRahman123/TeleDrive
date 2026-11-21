package com.drdisagree.teledrive.presentation.components

/**
 * Android has no AWT window layer: window-level Ctrl+A routing is
 * a desktop-only mechanism, and the Compose shortcut path remains
 * the way the key reaches a surface here. Registrations still run
 * (and are still balanced), they simply never receive a dispatch.
 */
internal actual fun acquireWindowSelectAllDispatcher() = Unit

internal actual fun releaseWindowSelectAllDispatcher() = Unit
