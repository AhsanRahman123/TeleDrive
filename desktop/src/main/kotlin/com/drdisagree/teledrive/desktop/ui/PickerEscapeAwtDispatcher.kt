package com.drdisagree.teledrive.desktop.ui

import java.awt.Dialog
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities

/**
 * Routes the picker's Escape at the AWT level (PC-30, round 8).
 *
 * The ladder itself was never broken — the on-screen back button ran
 * it correctly — but on the user's real Windows machine the physical
 * Escape key never reached the Compose key handlers, while the same
 * handlers fire in the Compose test scene. The Compose layer sits at
 * the END of a long delivery chain (AWT window → focus owner → Skiko
 * scene → focus chain → preview handlers), and a key can be consumed
 * or dropped at any hop without leaving a trace in the code that
 * "should" have received it — the same failure family the F11
 * fullscreen shortcut already routes around at this level
 * (WindowsF11Shortcut: "Compose's key event can be lost... AWT sees
 * the key before dispatching it to the focused child").
 *
 * A [KeyEventDispatcher] is consulted by the KeyboardFocusManager
 * BEFORE the event is delivered to any component, so a physical
 * Escape targeted at a showing [Dialog] cannot die in the focus
 * chain: it runs the ladder here and is consumed, which also means
 * the Compose handlers below never see it — exactly one ladder step
 * per press, never two. Events for any other window, non-Escape
 * keys, key releases (one step per press, not per release), and
 * Escapes while a transient layer (the sort menu) is open pass
 * through untouched, so the menu keeps its own Escape-to-close.
 *
 * Scope: the dispatcher is registered only while the picker is
 * composed, and it only consumes events whose target window is a
 * showing AWT [Dialog] — while the modal picker is up, that is the
 * picker's window.
 */
internal class PickerEscapeAwtDispatcher(
    private val isTransientLayerOpen: () -> Boolean,
    private val onEscape: () -> Unit
) : KeyEventDispatcher {

    fun install() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .addKeyEventDispatcher(this)
    }

    fun dispose() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .removeKeyEventDispatcher(this)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        if (event.keyCode != KeyEvent.VK_ESCAPE) return false
        if (isTransientLayerOpen()) return false
        val window = SwingUtilities.getWindowAncestor(event.component) ?: return false
        if (window !is Dialog || !window.isShowing) return false
        onEscape()
        return true
    }
}
