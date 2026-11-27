package com.drdisagree.teledrive.desktop.media.player

import java.awt.Dialog
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.TextComponent
import java.awt.Window
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Routes the player's Space (play/pause) at the AWT level (PC 1.6.27 N8).
 *
 * The Compose half of this route was never wrong: the stage Box's
 * onPreviewKeyEvent toggles playback on Spacebar. But a preview-phase
 * handler only fires for ANCESTORS of the focused node, and in this
 * player the focused node is routinely NOT inside the stage Box:
 *
 *  - in-zip video/audio previews wrap the player in a focusable
 *    overlay Box whose focus requester fires on entry
 *    (DesktopPreviewScreen's rememberOverlayFocusRequester) — when it
 *    wins the race against the player's own focus request, focus
 *    rests on an ANCESTOR of the player and the player's handler is
 *    not in the dispatch path at all;
 *  - clicking or dragging a control (seek slider, transport buttons)
 *    focuses it, and when the control bar auto-hides the focused node
 *    is disposed with it — no code path re-requests player focus
 *    until the next pointer press on the surface;
 *  - focus on preview chrome outside the player (the Scaffold's own
 *    focus target, a top-bar button) makes Space a no-op or, worse,
 *    activates that chrome button as a click.
 *
 * Those are the "sometimes Space does not pause" states. This is the
 * same failure family — and the same fix — as the picker's Escape
 * (PickerEscapeAwtDispatcher, PC-30/PC-33) and the F11 shortcut
 * (WindowsF11Shortcut): a [KeyEventDispatcher] is consulted by the
 * KeyboardFocusManager BEFORE the event is delivered to any
 * component, so a physical Space cannot die in the focus chain, and
 * consuming it there means the Compose handlers below never see it —
 * exactly one toggle per press, never two. The Compose Spacebar
 * branch stays as a backstop for any environment where this
 * dispatcher is not installed.
 *
 * Installed ONLY while a DesktopMediaPlayer is composed, so on every
 * other surface Space keeps its platform meaning (a focused button
 * outside the player still activates on Space once the player is
 * gone). While the player IS up, Space means play/pause — with three
 * deliberate exceptions, all of which pass the press through
 * untouched:
 *
 *  - [isSuppressed] reports a player-owned text/menu state: the
 *    subtitle appearance dialog is open, or the controls bar reports
 *    a transient layer pinned open (any dropdown menu, the custom
 *    sleep-timer dialog with its text fields, an actively dragged
 *    volume/brightness slider). Space there belongs to that layer.
 *  - The event targets an AWT/Swing text component, or any component
 *    inside a [Dialog] window (the player's own dialogs are also
 *    covered by [isSuppressed]; this catches foreign dialogs such as
 *    the preview's info/trash dialogs composed over the player).
 *  - The press carries Ctrl/Alt/Meta/Shift — the same modifier rule
 *    the Compose branch has always applied.
 *
 * Slider keyboard behaviour is deliberately unaffected: this
 * dispatcher intercepts Space only, so a focused seek/volume slider
 * keeps its arrow/Home/End keys exactly as before. (Space was never
 * a slider key — and in the states where the Compose route worked,
 * the player's preview handler already claimed Space ahead of a
 * focused slider.)
 */
internal class PlayerSpaceAwtDispatcher(
    private val isSuppressed: () -> Boolean,
    private val onTogglePlayback: () -> Unit
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
        if (event.keyCode != KeyEvent.VK_SPACE) return false
        val modifiers = event.modifiersEx
        if (modifiers and (KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK or
                KeyEvent.META_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK) != 0
        ) {
            return false
        }
        if (isSuppressed()) return false
        val component = runCatching { event.component }.getOrNull()
        if (component is JTextComponent || component is TextComponent) return false
        if (component != null) {
            val window = (component as? Window)
                ?: SwingUtilities.getWindowAncestor(component)
            if (window is Dialog) return false
        }
        onTogglePlayback()
        return true
    }
}
