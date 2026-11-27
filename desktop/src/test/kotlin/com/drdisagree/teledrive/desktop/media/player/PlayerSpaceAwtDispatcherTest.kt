package com.drdisagree.teledrive.desktop.media.player

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
 * PC 1.6.27 item P8 — the player's Compose Space handler was never
 * wrong; it simply never RECEIVED the key in the states the field
 * report names ("sometimes does not work — depends on what was clicked
 * before"): focus on the in-zip overlay Box that wraps the player, on
 * preview chrome, or nowhere after the auto-hide disposed the focused
 * control. [PlayerSpaceAwtDispatcher] intercepts Space in the
 * KeyboardFocusManager, BEFORE any component delivery — the same
 * pipeline a physical key traverses (PickerEscapeAwtDispatcherTest
 * conventions). These tests drive REAL AWT key events against REAL
 * windows: a focused JButton standing in for clicked chrome (the dead
 * state — and the state where Space would otherwise ACTIVIVATE the
 * button), a text field and a dialog (the pass-through states).
 */
class PlayerSpaceAwtDispatcherTest {

    private lateinit var frame: JFrame
    private lateinit var button: JButton
    private lateinit var field: JTextField
    private lateinit var dialog: JDialog
    private lateinit var fieldInDialog: JTextField
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
            button = JButton("chrome")
            button.addActionListener { buttonClicks.incrementAndGet() }
            frame.add(button)
            field = JTextField()
            dialog = JDialog()
            dialog.setSize(300, 200)
            fieldInDialog = JTextField()
            dialog.add(fieldInDialog)
            frame.isVisible = true
        }
    }

    @After
    fun tearDown() {
        dispatcher.dispose()
        SwingUtilities.invokeAndWait {
            if (::frame.isInitialized) frame.dispose()
            if (::dialog.isInitialized) dialog.dispose()
        }
    }

    private fun space(
        id: Int,
        component: java.awt.Component,
        modifiers: Int = 0
    ) = KeyEvent(
        component,
        id,
        System.currentTimeMillis(),
        modifiers,
        KeyEvent.VK_SPACE,
        ' '
    )

    @Test
    fun `space targeted at a focused button toggles once and never activates the button`() {
        // The production dead state: a click put focus on a button
        // outside (or disposed from) the player's key path. Direct
        // dispatch proves the decision; the end-to-end queue test below
        // proves consumption before delivery.
        assertTrue(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, button)))
        assertEquals(1, toggles.get())
        assertEquals(0, buttonClicks.get())
    }

    @Test
    fun `space posted to the AWT event queue toggles and is consumed before component delivery`() {
        val deliveredToButton = AtomicInteger(0)
        SwingUtilities.invokeAndWait {
            button.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    deliveredToButton.incrementAndGet()
                }
            })
            frame.toFront()
            button.requestFocusInWindow()
        }
        val focusDeadline = System.currentTimeMillis() + 10_000
        var focused = false
        while (!focused && System.currentTimeMillis() < focusDeadline) {
            SwingUtilities.invokeAndWait { focused = button.isFocusOwner }
            if (!focused) Thread.sleep(25)
        }
        assertTrue("the button must own focus before a physical key can target it", focused)
        dispatcher.install()
        java.awt.Toolkit.getDefaultToolkit().systemEventQueue
            .postEvent(space(KeyEvent.KEY_PRESSED, button))
        val deadline = System.currentTimeMillis() + 10_000
        while (toggles.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals("the dispatcher must run for a queued Space", 1, toggles.get())
        Thread.sleep(300)
        assertEquals("a consumed Space must never reach the component", 0, deliveredToButton.get())
        assertEquals(0, buttonClicks.get())
    }

    @Test
    fun `space with no component at all still toggles`() {
        // The no-focus-owner state (the focused control was disposed by
        // the auto-hide): the event can carry a null component.
        val orphan = object : KeyEvent(
            frame,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_SPACE,
            ' '
        ) {
            override fun getComponent(): java.awt.Component? = null
        }
        assertTrue(dispatcher.dispatchKeyEvent(orphan))
        assertEquals(1, toggles.get())
    }

    @Test
    fun `space in a text field passes through and types a space`() {
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, field)))
        assertEquals(0, toggles.get())
        // End to end: an undispatched (passed-through) typed space is
        // the field's normal behaviour; the dispatcher must not have
        // consumed the press that produces it.
        val typed = KeyEvent(
            field,
            KeyEvent.KEY_TYPED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_UNDEFINED,
            ' '
        )
        assertFalse(dispatcher.dispatchKeyEvent(typed))
    }

    @Test
    fun `space inside a dialog window passes through`() {
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, fieldInDialog)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `space while suppressed passes through`() {
        suppressed = true
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_PRESSED, button)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `modified space and key releases never toggle`() {
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
        assertFalse(dispatcher.dispatchKeyEvent(space(KeyEvent.KEY_RELEASED, button)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `other keys never toggle`() {
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
