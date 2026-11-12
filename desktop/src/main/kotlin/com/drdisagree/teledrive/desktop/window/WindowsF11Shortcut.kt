package com.drdisagree.teledrive.desktop.window

import com.drdisagree.teledrive.core.common.SafeLog
import com.sun.jna.platform.win32.User32
import java.awt.Component
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.KeyEventDispatcher
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.WindowEvent
import java.awt.event.WindowFocusListener
import javax.swing.SwingUtilities
import javax.swing.Timer

/**
 * Routes F11 at the AWT window level rather than through the Compose focus tree.
 *
 * Compose's key-up event can be lost when fullscreen changes or focus moves. AWT sees
 * the key before dispatching it to the focused child, and the short native-state poll
 * recovers the pressed latch even when a KEY_RELEASED event is not delivered here.
 */
internal class WindowsF11Shortcut(
    private val window: Window,
    private val onToggle: () -> Unit
) {
    private var installed = false
    private var f11IsDown = false

    private val releasePoller = Timer(RELEASE_POLL_INTERVAL_MS) {
        if (!isF11PhysicallyDown()) {
            resetPressedState()
        }
    }.apply {
        isRepeats = true
        isCoalesce = true
    }

    private val dispatcher = KeyEventDispatcher(::dispatchKeyEvent)

    private val focusListener = object : WindowFocusListener {
        override fun windowGainedFocus(event: WindowEvent) {
            synchronizeWithPhysicalKeyState()
        }

        override fun windowLostFocus(event: WindowEvent) {
            // Do not blindly clear the latch on focus loss: the key may still be held.
            // The native poller will re-arm it as soon as Windows reports key-up.
            synchronizeWithPhysicalKeyState()
        }
    }

    fun install() {
        onEventThread {
            if (installed) return@onEventThread
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(dispatcher)
            window.addWindowFocusListener(focusListener)
            installed = true
            synchronizeWithPhysicalKeyState()
        }
    }

    fun dispose() {
        onEventThread {
            if (!installed) return@onEventThread
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
            window.removeWindowFocusListener(focusListener)
            releasePoller.stop()
            f11IsDown = false
            installed = false
        }
    }

    private fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.VK_F11 || !belongsToThisWindow(event)) {
            return false
        }

        return when (event.id) {
            KeyEvent.KEY_PRESSED -> {
                if (!f11IsDown) {
                    f11IsDown = true
                    try {
                        onToggle()
                    } catch (failure: Throwable) {
                        SafeLog.w("DesktopWindow", "F11 toggle failed", failure)
                    }
                }
                startReleasePolling()
                true
            }

            KeyEvent.KEY_RELEASED -> {
                resetPressedState()
                true
            }

            else -> false
        }
    }

    private fun belongsToThisWindow(event: KeyEvent): Boolean {
        val source = event.source as? Component
        val sourceWindow = when (source) {
            is Window -> source
            null -> null
            else -> SwingUtilities.getWindowAncestor(source)
        }

        if (sourceWindow == null) {
            return window.isFocused || window.isActive
        }

        // Include owned dialogs/popups, but never intercept keys from another app window.
        var candidate: Window? = sourceWindow
        while (candidate != null) {
            if (candidate === window) return true
            candidate = candidate.owner
        }
        return false
    }

    private fun synchronizeWithPhysicalKeyState() {
        if (isF11PhysicallyDown()) {
            f11IsDown = true
            startReleasePolling()
        } else {
            resetPressedState()
        }
    }

    private fun startReleasePolling() {
        if (!releasePoller.isRunning) releasePoller.start()
    }

    private fun resetPressedState() {
        f11IsDown = false
        releasePoller.stop()
    }

    private fun isF11PhysicallyDown(): Boolean = runCatching {
        // Windows sets the high-order bit while the virtual key is physically down.
        User32.INSTANCE.GetAsyncKeyState(KeyEvent.VK_F11).toInt() < 0
    }.getOrDefault(false)

    private fun onEventThread(action: () -> Unit) {
        if (EventQueue.isDispatchThread()) {
            action()
        } else {
            EventQueue.invokeLater { action() }
        }
    }

    private companion object {
        const val RELEASE_POLL_INTERVAL_MS = 25
    }
}
