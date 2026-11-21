package com.drdisagree.teledrive.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Window-level Ctrl+A routing for collection surfaces (PC-33).
 *
 * The select-all toggle itself (round 8) was never broken — but its
 * Compose handlers hang on the workspace container, so the key only
 * arrives when Compose focus happens to rest inside that subtree.
 * Clicking Sort, the view toggle or any toolbar control parks focus
 * on chrome OUTSIDE the container; after an Alt-Tab round trip there
 * may be no Compose focus owner at all. In those states the press
 * died silently — his "Ctrl+A sometimes doesn't work" (round 9
 * diagnosis §3).
 *
 * The fix mirrors the picker's Escape routing (PC-30): a platform
 * AWT [java.awt.KeyEventDispatcher] (desktop actual below) sees
 * Ctrl+A before any component delivery and forwards it here. This
 * registry is the pure-Kotlin half: surfaces register while they
 * are composed, the most recently registered surface owns the key
 * (so the modal upload picker, registered over Files, wins while
 * it is up), and unregistration on dispose restores the surface
 * below. The Compose handlers remain in place underneath — the
 * dispatcher consumes the event when it routes it, so a press can
 * never toggle twice.
 */
object WindowSelectAllRegistry {

    private val registrations = CopyOnWriteArrayList<WindowSelectAllRegistration>()

    fun register(
        modal: Boolean,
        isTextInputFocused: () -> Boolean,
        onToggleSelectAll: () -> Boolean
    ): WindowSelectAllRegistration {
        val registration = WindowSelectAllRegistration(
            modal = modal,
            isTextInputFocused = isTextInputFocused,
            onToggleSelectAll = onToggleSelectAll,
            onUnregister = { registrations.remove(it) }
        )
        registrations += registration
        return registration
    }

    /**
     * Routes one window-level Ctrl+A. Returns true when the key was
     * consumed (the toggle ran); false lets normal delivery proceed
     * (text fields keep their select-all-text meaning).
     *
     * [targetIsDialog] is the AWT shape of the event's window: a
     * non-modal surface must never fire while one of the app's
     * dialogs (rename, create folder, …) is up over it — the key
     * belongs to the dialog. A modal registration (the upload
     * picker) owns the key whatever window the event targets,
     * because while it is up it is the only surface the user can
     * mean.
     */
    fun dispatchSelectAll(targetIsDialog: Boolean): Boolean {
        val top = registrations.lastOrNull() ?: return false
        if (!top.modal && targetIsDialog) return false
        if (top.isTextInputFocused()) return false
        return top.onToggleSelectAll()
    }

    /** Test hook: drops every registration. */
    internal fun clearAll() = registrations.clear()
}

/** One surface's window-level Ctrl+A registration. */
class WindowSelectAllRegistration internal constructor(
    internal val modal: Boolean,
    internal val isTextInputFocused: () -> Boolean,
    internal val onToggleSelectAll: () -> Boolean,
    private val onUnregister: (WindowSelectAllRegistration) -> Unit
) {
    fun unregister() = onUnregister(this)
}

/**
 * Counts how many of a surface's text fields currently hold focus,
 * so the surface can answer the registry's text-input check without
 * tracking each field's state by hand. Attach
 * [Modifier.trackTextInputFocus] to every text field that shares
 * the surface's window (search boxes, filter inputs); dialog fields
 * need no tracking — the dialog-window rule covers them.
 */
class TextInputFocusTracker {

    private val focusedFields = AtomicInteger(0)

    val anyFocused: Boolean
        get() = focusedFields.get() > 0

    fun onFocusChanged(focused: Boolean) {
        if (focused) {
            focusedFields.incrementAndGet()
        } else {
            focusedFields.updateAndGet { count -> maxOf(0, count - 1) }
        }
    }
}

/** Reports this text field's focus to [tracker] (see [TextInputFocusTracker]). */
fun Modifier.trackTextInputFocus(tracker: TextInputFocusTracker): Modifier =
    onFocusChanged { tracker.onFocusChanged(it.isFocused) }

/**
 * Registers the enclosing surface for window-level Ctrl+A routing
 * (see [WindowSelectAllRegistry]) for as long as it is composed,
 * and acquires the platform dispatcher alongside. [onToggleSelectAll]
 * must be the surface's one select-all toggle — the same action its
 * Compose shortcut wiring runs — and must return whether the key
 * was consumed.
 */
@Composable
fun RegisterWindowSelectAll(
    modal: Boolean = false,
    isTextInputFocused: () -> Boolean = { false },
    onToggleSelectAll: () -> Boolean
) {
    val currentTextCheck by rememberUpdatedState(isTextInputFocused)
    val currentToggle by rememberUpdatedState(onToggleSelectAll)
    DisposableEffect(modal) {
        val registration = WindowSelectAllRegistry.register(
            modal = modal,
            isTextInputFocused = { currentTextCheck() },
            onToggleSelectAll = { currentToggle() }
        )
        acquireWindowSelectAllDispatcher()
        onDispose {
            registration.unregister()
            releaseWindowSelectAllDispatcher()
        }
    }
}

/**
 * Platform glue for the window-level Ctrl+A dispatcher: the desktop
 * actual installs a refcounted AWT KeyEventDispatcher that forwards
 * to [WindowSelectAllRegistry]; the Android actual is a no-op
 * (Android has no AWT — its Compose shortcut path is unchanged).
 */
internal expect fun acquireWindowSelectAllDispatcher()

internal expect fun releaseWindowSelectAllDispatcher()
