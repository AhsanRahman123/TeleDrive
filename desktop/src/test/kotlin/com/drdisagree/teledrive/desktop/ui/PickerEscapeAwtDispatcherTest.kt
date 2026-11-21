package com.drdisagree.teledrive.desktop.ui

import java.awt.GraphicsEnvironment
import java.awt.KeyboardFocusManager
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger
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
 * PC-30 (round 8): the picker's ladder worked from its on-screen back
 * button but NOT from the physical Escape key on the user's machine —
 * the Compose handlers the round-7 test exercised sit at the end of
 * the AWT → focus → scene delivery chain and never saw the key.
 * [PickerEscapeAwtDispatcher] intercepts the key in the
 * KeyboardFocusManager, BEFORE any component delivery — the same
 * pipeline a physical key traverses. These tests drive REAL AWT key
 * events through that pipeline against REAL windows: a showing
 * JDialog standing in for the picker window, a field inside it as
 * the event target (the states the user is actually in: an item
 * clicked, the search field focused).
 *
 * PC-33 (round 10) widened the scope: round 8 consumed only events
 * targeted at the dialog itself, and his retest still lost presses
 * after Alt-Tab (focus restored to the MAIN window while the modal
 * picker was up) and in the no-focus-owner state. While the picker
 * is up it is modal — an Escape anywhere in the app can only mean
 * the ladder — so the second window below is now IN scope, and the
 * old "another window is never the picker's business" pin is
 * replaced by the widened expectation.
 */
class PickerEscapeAwtDispatcherTest {

    private lateinit var dialog: JDialog
    private lateinit var fieldInDialog: JTextField
    private lateinit var otherWindow: JFrame
    private lateinit var fieldElsewhere: JTextField
    private val ladderSteps = AtomicInteger(0)

    @Volatile
    private var menuOpen = false

    private val dispatcher = PickerEscapeAwtDispatcher(
        isTransientLayerOpen = { menuOpen },
        onEscape = { ladderSteps.incrementAndGet() }
    )

    @Before
    fun setUp() {
        assumeFalse("needs a display for real AWT windows", GraphicsEnvironment.isHeadless())
        SwingUtilities.invokeAndWait {
            dialog = JDialog()
            dialog.setSize(400, 300)
            fieldInDialog = JTextField()
            dialog.add(fieldInDialog)
            dialog.isVisible = true

            otherWindow = JFrame()
            otherWindow.setSize(300, 200)
            fieldElsewhere = JTextField()
            otherWindow.add(fieldElsewhere)
            otherWindow.isVisible = true
        }
    }

    @After
    fun tearDown() {
        dispatcher.dispose()
        SwingUtilities.invokeAndWait {
            if (::dialog.isInitialized) dialog.dispose()
            if (::otherWindow.isInitialized) otherWindow.dispose()
        }
    }

    private fun escape(id: Int, component: java.awt.Component) = KeyEvent(
        component,
        id,
        System.currentTimeMillis(),
        0,
        KeyEvent.VK_ESCAPE,
        KeyEvent.CHAR_UNDEFINED
    )

    @Test
    fun `escape pressed inside the picker dialog runs exactly one ladder step and is consumed`() {
        assertTrue(dispatcher.dispatchKeyEvent(escape(KeyEvent.KEY_PRESSED, fieldInDialog)))
        assertEquals(1, ladderSteps.get())
    }

    @Test
    fun `escape posted to the AWT event queue is consumed before component delivery`() {
        // The genuine pipeline a physical key traverses: the system
        // event queue -> KeyboardFocusManager (dispatchers first) ->
        // the focus owner's listeners. Calling the manager's
        // dispatchKeyEvent directly out-of-band is NOT that pipeline
        // (it can swallow events for unfocused windows before any
        // dispatcher runs), so this test posts to the queue and
        // asserts both halves of "consumed before delivery": the
        // ladder ran AND the field's own key listener never saw it.
        val deliveredToField = AtomicInteger(0)
        SwingUtilities.invokeAndWait {
            fieldInDialog.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    deliveredToField.incrementAndGet()
                }
            })
            // Reach the physically real state first: the dialog must
            // be the ACTIVE window with the field owning focus. The
            // second window was shown after the dialog in setUp and
            // holds activation; a key event targeted at a component
            // of a non-focused window is an impossible physical state
            // and the focus manager discards it before dispatchers.
            otherWindow.isVisible = false
            dialog.toFront()
            fieldInDialog.requestFocusInWindow()
        }
        val focusDeadline = System.currentTimeMillis() + 10_000
        var focused = false
        while (!focused && System.currentTimeMillis() < focusDeadline) {
            SwingUtilities.invokeAndWait { focused = fieldInDialog.isFocusOwner }
            if (!focused) Thread.sleep(25)
        }
        assertTrue("the field must own focus before a physical key can target it", focused)
        dispatcher.install()
        java.awt.Toolkit.getDefaultToolkit().systemEventQueue
            .postEvent(escape(KeyEvent.KEY_PRESSED, fieldInDialog))
        val deadline = System.currentTimeMillis() + 10_000
        while (ladderSteps.get() == 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertEquals("the dispatcher must run for a queued Escape", 1, ladderSteps.get())
        // Give any (wrong) component delivery a chance to happen.
        Thread.sleep(300)
        assertEquals("a consumed Escape must never reach the component", 0, deliveredToField.get())
    }

    @Test
    fun `escape targeted at the main window while the picker is up runs exactly one ladder step`() {
        // PC-33 (round 9 §3 state 1): after an Alt-Tab round trip,
        // Windows restores AWT focus to the owner frame — the event
        // targets the main window, not the dialog. The modal picker
        // is up, so the press can only mean the ladder.
        assertTrue(dispatcher.dispatchKeyEvent(escape(KeyEvent.KEY_PRESSED, fieldElsewhere)))
        assertEquals(1, ladderSteps.get())
    }

    @Test
    fun `escape with no component at all runs the ladder`() {
        // PC-33 (round 9 §3 state 2): with no focus owner the event
        // can carry a null component; the round-8 dispatcher dropped
        // exactly this shape on the floor.
        val orphan = object : KeyEvent(
            dialog,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            0,
            KeyEvent.VK_ESCAPE,
            KeyEvent.CHAR_UNDEFINED
        ) {
            override fun getComponent(): java.awt.Component? = null
        }
        assertTrue(dispatcher.dispatchKeyEvent(orphan))
        assertEquals(1, ladderSteps.get())
    }

    @Test
    fun `escape while a transient layer is open passes through to the layer`() {
        menuOpen = true
        assertFalse(dispatcher.dispatchKeyEvent(escape(KeyEvent.KEY_PRESSED, fieldInDialog)))
        assertEquals(0, ladderSteps.get())
    }

    @Test
    fun `key releases and other keys never run the ladder`() {
        assertFalse(dispatcher.dispatchKeyEvent(escape(KeyEvent.KEY_RELEASED, fieldInDialog)))
        val letterA = KeyEvent(
            fieldInDialog,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            KeyEvent.CTRL_DOWN_MASK,
            KeyEvent.VK_A,
            'a'
        )
        assertFalse(dispatcher.dispatchKeyEvent(letterA))
        assertEquals(0, ladderSteps.get())
    }
}
