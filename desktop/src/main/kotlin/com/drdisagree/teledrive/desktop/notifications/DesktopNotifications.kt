package com.drdisagree.teledrive.desktop.notifications

import com.drdisagree.teledrive.core.common.SafeLog
import java.awt.Color
import java.awt.RenderingHints
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.image.BufferedImage

/**
 * Desktop counterpart of Android's AppNotifications: the same three events
 * (transfer failure, backup result, update available) surfaced as Windows
 * tray balloon notifications. The tray icon is installed lazily on the first
 * notification so an idle app leaves no tray footprint.
 */
class DesktopNotifications {

    @Volatile
    private var trayIcon: TrayIcon? = null

    fun notifyFailure(title: String, message: String) =
        show(title, message, TrayIcon.MessageType.ERROR)

    fun notifyBackupResult(title: String, message: String) =
        show(title, message, TrayIcon.MessageType.INFO)

    fun notifyUpdate(title: String, message: String) =
        show(title, message, TrayIcon.MessageType.INFO)

    private fun show(title: String, message: String, type: TrayIcon.MessageType) {
        runCatching {
            val icon = trayIcon() ?: return
            icon.displayMessage(title, message, type)
        }.onFailure { SafeLog.w(TAG, "Could not show a notification", it) }
    }

    @Synchronized
    private fun trayIcon(): TrayIcon? {
        trayIcon?.let { return it }
        if (!SystemTray.isSupported()) return null
        val icon = TrayIcon(trayImage(), APP_NAME).apply { isImageAutoSize = true }
        SystemTray.getSystemTray().add(icon)
        trayIcon = icon
        return icon
    }

    /** Brand-orange disc with the app initial, drawn in code so no resource plumbing is needed. */
    private fun trayImage(): BufferedImage {
        val size = 32
        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.color = Color(0xFE, 0x87, 0x23)
            g.fillOval(0, 0, size, size)
            g.color = Color.WHITE
            g.font = java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 20)
            val metrics = g.fontMetrics
            val label = "P"
            g.drawString(
                label,
                (size - metrics.stringWidth(label)) / 2,
                (size - metrics.height) / 2 + metrics.ascent
            )
        } finally {
            g.dispose()
        }
        return image
    }

    private companion object {
        const val TAG = "DesktopNotifications"
        const val APP_NAME = "Pranto Drive"
    }
}
