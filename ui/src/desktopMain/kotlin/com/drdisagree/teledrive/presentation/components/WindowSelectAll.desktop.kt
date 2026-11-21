package com.drdisagree.teledrive.presentation.components

import java.awt.Dialog
import java.awt.KeyEventDispatcher
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

internal actual fun acquireWindowSelectAllDispatcher() =
    WindowSelectAllDispatcherControl.acquire()

internal actual fun releaseWindowSelectAllDispatcher() =
    WindowSelectAllDispatcherControl.release()

/**
 * Owns the single AWT dispatcher behind [RegisterWindowSelectAll]:
 * installed when the first surface registers, removed when the
 * last one unregisters, so nothing is left hooked into the focus
 * manager between surfaces.
 */
internal object WindowSelectAllDispatcherControl {

    private var refCount = 0
    private val dispatcher = WindowSelectAllAwtDispatcher()

    @Synchronized
    fun acquire() {
        if (refCount == 0) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .addKeyEventDispatcher(dispatcher)
        }
        refCount++
    }

    @Synchronized
    fun release() {
        if (refCount == 0) return
        refCount--
        if (refCount == 0) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                .removeKeyEventDispatcher(dispatcher)
        }
    }
}

/**
 * The AWT half of window-level Ctrl+A routing (PC-33), the twin of
 * the picker's Escape dispatcher (PC-30): a [KeyEventDispatcher]
 * is consulted by the KeyboardFocusManager BEFORE an event is
 * delivered to any component, so a physical Ctrl+A reaches the
 * registry whatever node — a toolbar button, the window itself,
 * or nothing at all — currently owns focus.
 *
 * Only the bare command is routed: Ctrl/Cmd+A with no Alt and no
 * Shift (mirroring `collectionCommandShortcuts`). Key releases
 * never toggle. A Swing text component as the event target or
 * the focus owner keeps the key untouched; Compose text fields
 * are covered by the registry's own text-input check, which the
 * surface answers from its focus tracker.
 */
internal class WindowSelectAllAwtDispatcher : KeyEventDispatcher {

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.id != KeyEvent.KEY_PRESSED) return false
        if (event.keyCode != KeyEvent.VK_A) return false
        val modifiers = event.modifiersEx
        val command = modifiers and
            (KeyEvent.CTRL_DOWN_MASK or KeyEvent.META_DOWN_MASK) != 0
        if (!command) return false
        if (modifiers and (KeyEvent.ALT_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK) != 0) {
            return false
        }
        if (event.component is JTextComponent) return false
        val focusOwner = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusOwner
        if (focusOwner is JTextComponent) return false
        val window = event.component?.let { SwingUtilities.getWindowAncestor(it) }
        return WindowSelectAllRegistry.dispatchSelectAll(targetIsDialog = window is Dialog)
    }
}
