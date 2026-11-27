package com.drdisagree.teledrive.desktop.media.player

import java.awt.Component
import java.awt.GraphicsEnvironment
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFrame
import javax.swing.JTextField
import javax.swing.SwingUtilities
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Test

/**
 * N8 (PC 1.6.27): Space intermittently did not pause/resume in the
 * desktop player. The only handler was the stage Box's Compose
 * onPreviewKeyEvent, which fires only when the stage is an ancestor
 * of the focused node — and production focus routinely rests outside
 * it (the focusable in-zip overlay Box that wraps the player, a
 * control disposed when the bar auto-hides, preview chrome). The fix
 * routes Space through [PlayerSpaceAwtDispatcher], installed while
 * the player is composed and consulted by the KeyboardFocusManager
 * BEFORE component delivery — the same pipeline a physical key
 * traverses, driven here exactly as PickerEscapeAwtDispatcherTest
 * drives the picker's Escape: real AWT windows, real event queue.
 *
 * The states pinned:
 *  - focus on a Space-consuming control (a focused JButton activates
 *    on Space — the AWT analogue of the player's focused transport
 *    button / the preview's chrome buttons): Space must toggle
 *    playback exactly once and must NOT activate the button;
 *  - focus in another window, or no focus owner at all: Space must
 *    still toggle (the focus-lost states);
 *  - guards: while the player reports a text/menu state suppressed,
 *    while the target is a text field (Space must type a space), and
 *    while the target sits in a dialog window, the press passes
 *    through untouched;
 *  - releases, other keys, and modified Space (Ctrl/Shift/…) are
 *    never the player's.
 */
class PlayerSpaceAwtDispatcherTest {

    private lateinit var frame: JFrame
    private lateinit var button: JButton
    private lateinit var field: JTextField
    private lateinit var otherWindow: JFrame
    private lateinit var dialog: JDialog
    private lateinit var dialogButton: JButton

    private val toggles = AtomicInteger(0)
    private val buttonClicks = AtomicInteger(0)

    @Volatile
    private var suppressed = false

    private val dispatcher = PlayerSpaceAwtDispatcher(
        isSuppressed = { suppressed },
        onTogglePlayback = { toggles.incrementAndGet() }
    )

    @Before
    fun setUp() {
        assumeFalse("needs a display for real AWT windows", GraphicsEnvironment.isHeadless())
        SwingUtilities.invokeAndWait {
            frame = JFrame()
            frame.setSize(400, 300)
            button = JButton("Play")
            button.addActionListener { buttonClicks.incrementAndGet() }
            frame.add(button)
            frame.isVisible = true

            otherWindow = JFrame()
            otherWindow.setSize(300, 200)
            field = JTextField()
            otherWindow.add(field)
            otherWindow.isVisible = true

            dialog = JDialog(frame)
            dialog.setSize(200, 120)
            dialogButton = JButton("OK")
            dialog.add(dialogButton)
            dialog.isVisible = true
        }
    }

    @After
    fun tearDown() {
        dispatcher.dispose()
        SwingUtilities.invokeAndWait {
            if (::dialog.isInitialized) dialog.dispose()
            if (::otherWindow.isInitialized) otherWindow.dispose()
            if (::frame.isInitialized) frame.dispose()
        }
    }

    private fun space(id: Int, component: Component, modifiers: Int = 0) = KeyEvent(
        component,
        id,
        System.currentTimeMillis(),
        modifiers,
        KeyEvent.VK_SPACE,
        ' '
    )

    private fun awaitFocusOwner(component: Component) {
        val deadline = System.currentTimeMillis() + 10_000
        var focused = false
        while (!focused && System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait { focused = component.isFocusOwner }
            if (!focused) Thread.sleep(25)
        }
        assertTrue("the component must own focus before a physical key can target it", focused)
    }

    @Test
    fun `space pressed with focus on a consuming button toggles playback and does not activate the button`() {
        // The dead-Space state, reproduced through the genuine
        // pipeline: system event queue -> KeyboardFocusManager
        // (dispatchers first) -> focus owner. The button is the
        // focused, Space-consuming control; before the fix this press
        // was the button's (or nobody's), never the player's.
        SwingUtilities.invokeAndWait {
            dialog.isVisible = false
            otherWindow.isVisible = false
            frame.toFront()
            button.requestFocusInWindow()
        }
        awaitFocusOwner(button)
        dispatcher.install()
        val queue = java.awt.Toolkit.getDefaultToolkit().systemEventQueue
        queue.postEvent(space(KeyEvent.KEY_PRESSED, button))
        queue.postEvent(space(KeyEvent.KEY_RELEASED, button))
        val deadline = System.currentTimeMillis() + 10_000
        while (toggles.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals("the dispatcher must toggle playback for a queued Space", 1, toggles.get())
        // Give any (wrong) component delivery a chance to happen: a
        // Swing button fires on the release of an armed Space press —
        // the press was consumed, so it must never fire.
        Thread.sleep(300)
        assertEquals("a consumed Space must never activate the focused button", 0, buttonClicks.get())
    }

    @Test
    fun `space targeted at another window still toggles`() {
        // Focus restored somewhere else entirely (the Alt-Tab shape):
        // while the player is composed, Space is still play/pause.
        assertTrue(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, otherWindow)))
        assertEquals(1, toggles.get())
    }

    @Test
    fun `space with no component at all still toggles`() {
        // No focus owner: the event can carry a null component — the
        // state the disposed-control auto-hide leaves behind.
        val orphan = object : KeyEvent(
            frame,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_SPACE,
            ' '
        ) {
            override fun getComponent(): Component? = null
        }
        assertTrue(dispatcher.dispatchKeyEvent(orphan))
        assertEquals(1, toggles.get())
    }

    @Test
    fun `space while a player text or menu state is suppressed passes through`() {
        suppressed = true
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, button)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `space targeted at a text field is never hijacked and types a space`() {
        // Guard: a text field owns the press even when the player is
        // not reporting suppression (the AWT-level half of the guard;
        // the player's own Compose text fields are covered by the
        // suppression state in production).
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, field)))
        assertEquals(0, toggles.get())

        // End to end through the queue: with the dispatcher installed,
        // a typed space still lands in the focused field.
        SwingUtilities.invokeAndWait {
            dialog.isVisible = false
            frame.isVisible = false
            otherWindow.toFront()
            field.requestFocusInWindow()
        }
        awaitFocusOwner(field)
        dispatcher.install()
        val queue = java.awt.Toolkit.getDefaultToolkit().systemEventQueue
        queue.postEvent(space(KeyEvent.KEY_PRESSED, field))
        queue.postEvent(
            KeyEvent(field, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, ' ')
        )
        queue.postEvent(space(KeyEvent.KEY_RELEASED, field))
        val deadline = System.currentTimeMillis() + 10_000
        var text = ""
        while (System.currentTimeMillis() < deadline) {
            SwingUtilities.invokeAndWait { text = field.text }
            if (text == " ") break
            Thread.sleep(25)
        }
        assertEquals("space must type a space in a focused text field", " ", text)
        assertEquals("typing must never toggle playback", 0, toggles.get())
    }

    @Test
    fun `space targeted inside a dialog window passes through`() {
        // A dialog composed over the player (info/trash, or the
        // player's own dialogs as a backstop to the suppression
        // state): its buttons keep Space.
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, dialogButton)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `releases other keys and modified space never toggle`() {
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_RELEASED, button)))
        assertFalse(
            dispatcher.dispatchKeyEvent(
                space(KeyEvent.KEY_PRESSED, button, KeyEvent.CTRL_DOWN_MASK)
            )
        )
        assertFalse(
            dispatcher.dispatchKeyEvent(
                space(KeyEvent.KEY_PRESSED, button, KeyEvent.SHIFT_DOWN_MASK)
            )
        )
        val letterA = KeyEvent(
            button,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_A,
            'a'
        )
        assertFalse(dispatcher.dispatchKeyEvent(letterA))
        assertEquals(0, toggles.get())
    }
}
