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
 * clicked, the search field focused), and a second window that must
 * never be touched.
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
    fun `escape driven through the KeyboardFocusManager is consumed before component delivery`() {
        dispatcher.install()
        val consumed = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            .dispatchKeyEvent(escape(KeyEvent.KEY_PRESSED, fieldInDialog))
        assertTrue("the manager must report the event consumed", consumed)
        assertEquals(1, ladderSteps.get())
    }

    @Test
    fun `escape in another window is never the picker's business`() {
        assertFalse(dispatcher.dispatchKeyEvent(escape(KeyEvent.KEY_PRESSED, fieldElsewhere)))
        assertEquals(0, ladderSteps.get())
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
