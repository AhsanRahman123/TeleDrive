package com.drdisagree.teledrive.desktop.ui

import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent

/**
 * Routes the picker's Escape at the AWT level (PC-30, round 8;
 * widened PC-33, round 10).
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
 * Escape cannot die in the focus chain: it runs the ladder here and
 * is consumed, which also means the Compose handlers below never
 * see it — exactly one ladder step per press, never two.
 *
 * Round 8 scoped consumption to events whose target window was a
 * showing [java.awt.Dialog]. His 1.6.10 retest still lost presses
 * ("sometimes"), and round 9 §3 named the remaining states: after
 * an Alt-Tab round trip Windows can restore AWT focus to the main
 * window while the modal picker is up (the event's window is then
 * the Frame, not the Dialog); with no focus owner the event can
 * carry a null component; a popup (JWindow) target is neither.
 * The dispatcher is installed ONLY while the picker is composed,
 * and the picker is modal — while it is up, an Escape anywhere in
 * the app can only mean the ladder. PC-33 therefore consumes every
 * Escape press that reaches this dispatcher, whatever window or
 * component it targets. The one exception is unchanged: while a
 * transient layer (the sort menu) is open, Escape passes through
 * so the menu keeps its own Escape-to-close. Non-Escape keys and
 * key releases (one step per press, not per release) also pass.
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
        onEscape()
        return true
    }
}
