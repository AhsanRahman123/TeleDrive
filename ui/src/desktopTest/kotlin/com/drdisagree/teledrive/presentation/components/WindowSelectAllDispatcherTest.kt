package com.drdisagree.teledrive.presentation.components

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
 * PC-33: the select-all toggle (round 8) worked whenever Compose
 * focus rested inside the workspace container and died "sometimes"
 * — when focus was parked on toolbar chrome (Sort, the view
 * toggle), or nowhere at all after an Alt-Tab round trip (round 9
 * §3). [WindowSelectAllAwtDispatcher] + [WindowSelectAllRegistry]
 * route the physical Ctrl+A at the KeyboardFocusManager level,
 * before component delivery. These tests drive REAL AWT key events
 * against REAL windows in exactly those focus states, following
 * the PickerEscapeAwtDispatcherTest pattern.
 */
class WindowSelectAllDispatcherTest {

    private lateinit var frame: JFrame
    private lateinit var toolbarButton: JButton
    private lateinit var textField: JTextField
    private lateinit var dialog: JDialog
    private lateinit var fieldInDialog: JTextField

    private val dispatcher = WindowSelectAllAwtDispatcher()
    private val toggles = AtomicInteger(0)

    @Volatile
    private var selected = false

    @Volatile
    private var textFocused = false

    @Before
    fun setUp() {
        assumeFalse("needs a display for real AWT windows", GraphicsEnvironment.isHeadless())
        WindowSelectAllRegistry.clearAll()
        SwingUtilities.invokeAndWait {
            frame = JFrame()
            frame.setSize(400, 300)
            frame.contentPane.layout = java.awt.FlowLayout()
            toolbarButton = JButton("Sort")
            frame.add(toolbarButton)
            textField = JTextField(12)
            frame.add(textField)
            frame.isVisible = true

            dialog = JDialog(frame)
            dialog.setSize(300, 200)
            fieldInDialog = JTextField()
            dialog.add(fieldInDialog)
        }
    }

    @After
    fun tearDown() {
        WindowSelectAllRegistry.clearAll()
        SwingUtilities.invokeAndWait {
            if (::dialog.isInitialized) dialog.dispose()
            if (::frame.isInitialized) frame.dispose()
        }
    }

    private fun registerSurface(modal: Boolean = false): WindowSelectAllRegistration =
        WindowSelectAllRegistry.register(
            modal = modal,
            isTextInputFocused = { textFocused },
            onToggleSelectAll = {
                toggles.incrementAndGet()
                selected = !selected
                true
            }
        )

    private fun ctrlA(
        component: Component,
        id: Int = KeyEvent.KEY_PRESSED,
        modifiers: Int = KeyEvent.CTRL_DOWN_MASK
    ) = KeyEvent(component, id, System.currentTimeMillis(), modifiers, KeyEvent.VK_A, 'a')

    @Test
    fun `ctrl+a targeted at a toolbar control toggles selection on and off`() {
        // §3 state 1: focus parked on chrome outside the workspace
        // container — the press must still reach the surface.
        registerSurface()
        assertTrue(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton)))
        assertEquals(1, toggles.get())
        assertTrue(selected)
        assertTrue(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton)))
        assertEquals(2, toggles.get())
        assertFalse(selected)
    }

    @Test
    fun `ctrl+a posted to the AWT event queue is consumed before component delivery`() {
        // The genuine pipeline: system event queue ->
        // KeyboardFocusManager (dispatchers first) -> the focus
        // owner. The toolbar button really owns focus here; the
        // toggle must run AND the button must never see the key.
        registerSurface()
        val deliveredToButton = AtomicInteger(0)
        SwingUtilities.invokeAndWait {
            toolbarButton.addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    deliveredToButton.incrementAndGet()
                }
            })
            frame.toFront()
            toolbarButton.requestFocusInWindow()
        }
        val focusDeadline = System.currentTimeMillis() + 10_000
        var focused = false
        while (!focused && System.currentTimeMillis() < focusDeadline) {
            SwingUtilities.invokeAndWait { focused = toolbarButton.isFocusOwner }
            if (!focused) Thread.sleep(25)
        }
        assertTrue("the button must own focus before a physical key can target it", focused)
        WindowSelectAllDispatcherControl.acquire()
        try {
            java.awt.Toolkit.getDefaultToolkit().systemEventQueue
                .postEvent(ctrlA(toolbarButton))
            val deadline = System.currentTimeMillis() + 10_000
            while (toggles.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertEquals("the dispatcher must run for a queued Ctrl+A", 1, toggles.get())
            Thread.sleep(300)
            assertEquals("a consumed Ctrl+A must never reach the component", 0, deliveredToButton.get())
        } finally {
            WindowSelectAllDispatcherControl.release()
        }
    }

    @Test
    fun `ctrl+a with no focus owner still toggles`() {
        // §3 state 3: after an Alt-Tab round trip the event can be
        // targeted at the window itself, or carry no component at
        // all. Both must reach the surface.
        registerSurface()
        assertTrue(dispatcher.dispatchKeyEvent(ctrlA(frame)))
        assertEquals(1, toggles.get())

        val orphan = object : KeyEvent(
            frame,
            KeyEvent.KEY_PRESSED,
            System.currentTimeMillis(),
            KeyEvent.CTRL_DOWN_MASK,
            KeyEvent.VK_A,
            'a'
        ) {
            override fun getComponent(): Component? = null
        }
        assertTrue(dispatcher.dispatchKeyEvent(orphan))
        assertEquals(2, toggles.get())
    }

    @Test
    fun `ctrl+a in a swing text field is not hijacked`() {
        registerSurface()
        assertFalse(dispatcher.dispatchKeyEvent(ctrlA(textField)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `ctrl+a while a compose text field holds focus is not hijacked`() {
        // The surface answers the registry's text-input check from
        // its focus tracker (a Compose field's focus is invisible
        // at the AWT level — the canvas owns it either way).
        textFocused = true
        registerSurface()
        assertFalse(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `ctrl+a inside a dialog never reaches the background surface`() {
        registerSurface(modal = false)
        SwingUtilities.invokeAndWait { dialog.isVisible = true }
        assertFalse(dispatcher.dispatchKeyEvent(ctrlA(fieldInDialog)))
        assertEquals(0, toggles.get())
    }

    @Test
    fun `a modal registration owns ctrl+a whatever window the event targets`() {
        // The upload picker over Files: while it is up, Ctrl+A is
        // the picker's — even for an event targeted at the main
        // window (the Alt-Tab focus anomaly).
        registerSurface(modal = true)
        assertTrue(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton)))
        assertTrue(dispatcher.dispatchKeyEvent(ctrlA(fieldInDialog)))
        assertEquals(2, toggles.get())
    }

    @Test
    fun `the topmost registration wins and unregistering restores the one below`() {
        val surfaceToggles = AtomicInteger(0)
        val pickerToggles = AtomicInteger(0)
        WindowSelectAllRegistry.register(
            modal = false,
            isTextInputFocused = { false },
            onToggleSelectAll = { surfaceToggles.incrementAndGet(); true }
        )
        val picker = WindowSelectAllRegistry.register(
            modal = true,
            isTextInputFocused = { false },
            onToggleSelectAll = { pickerToggles.incrementAndGet(); true }
        )
        assertTrue(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton)))
        assertEquals(1, pickerToggles.get())
        assertEquals(0, surfaceToggles.get())

        picker.unregister()
        assertTrue(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton)))
        assertEquals(1, surfaceToggles.get())
        assertEquals(1, pickerToggles.get())
    }

    @Test
    fun `with no registration the key passes through`() {
        assertFalse(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton)))
    }

    @Test
    fun `plain a, modified a and key releases never toggle`() {
        registerSurface()
        assertFalse(dispatcher.dispatchKeyEvent(ctrlA(toolbarButton, modifiers = 0)))
        assertFalse(
            dispatcher.dispatchKeyEvent(
                ctrlA(
                    toolbarButton,
                    modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.SHIFT_DOWN_MASK
                )
            )
        )
        assertFalse(
            dispatcher.dispatchKeyEvent(
                ctrlA(
                    toolbarButton,
                    modifiers = KeyEvent.CTRL_DOWN_MASK or KeyEvent.ALT_DOWN_MASK
                )
            )
        )
        assertFalse(
            dispatcher.dispatchKeyEvent(ctrlA(toolbarButton, id = KeyEvent.KEY_RELEASED))
        )
        assertEquals(0, toggles.get())
        // Cmd+A is the same command on macOS (parity with the
        // Compose shortcut wiring).
        assertTrue(
            dispatcher.dispatchKeyEvent(
                ctrlA(toolbarButton, modifiers = KeyEvent.META_DOWN_MASK)
            )
        )
        assertEquals(1, toggles.get())
    }
}
