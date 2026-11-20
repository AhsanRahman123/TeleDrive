package com.drdisagree.teledrive.desktop.window

import com.sun.jna.platform.win32.WinUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC-23 exit contract for a MAXIMIZED restore. Up to 1.6.5 the
 * maximized exit skipped the resize cycle entirely, and every
 * rect-based cycle is a no-op against a maximized window anyway (the
 * window manager owns its rect; SetWindowPos cannot resize it). The
 * only genuine resizes available are show-state changes — which are
 * also the user's own manual cure: snapping his maximized window
 * restores it to floating, and putting it back re-maximizes it. These
 * tests pin the excursion plan the controller executes: its order,
 * its snap rect and its flags. The Win32 behaviour itself can only be
 * confirmed on his Windows PC.
 */
class FullscreenMaximizedResyncTest {

    // A 1920x1080 monitor whose taskbar leaves a 1920x1040 work area.
    private val work = WindowRect(left = 0, top = 0, right = 1920, bottom = 1040)

    // The maximized window's stored normal rect: a floating window on
    // the right side of the screen.
    private val normal = WindowRect(left = 900, top = 100, right = 1700, bottom = 800)

    @Test
    fun `the excursion is exactly three steps - restore, snap, re-maximize`() {
        val steps = fullscreenExitMaximizedResync(normal, work)
        assertEquals(3, steps.size)
        assertEquals(
            listOf(
                MaximizedResyncStepKind.RESTORE_TO_NORMAL,
                MaximizedResyncStepKind.SNAP_TO_HALF,
                MaximizedResyncStepKind.REMAXIMIZE
            ),
            steps.map { it.kind }
        )
    }

    @Test
    fun `only the snap step carries a rect and flags`() {
        val steps = fullscreenExitMaximizedResync(normal, work)
        assertNull(steps[0].rect)
        assertEquals(0, steps[0].flags)
        assertNull(steps[2].rect)
        assertEquals(0, steps[2].flags)
    }

    @Test
    fun `the snap rect is the left half of the work area, like a windows snap-left`() {
        val snap = fullscreenExitMaximizedResync(normal, work)[1]
        assertEquals(WindowRect(left = 0, top = 0, right = 960, bottom = 1040), snap.rect)
    }

    @Test
    fun `the snap rect is genuinely different from the normal rect`() {
        val snap = fullscreenExitMaximizedResync(normal, work)[1].rect
        assertNotEquals(normal, snap)
    }

    @Test
    fun `a normal rect already on the left half snaps to the right half instead`() {
        val onLeftHalf = WindowRect(left = 0, top = 0, right = 960, bottom = 1040)
        val snap = fullscreenExitMaximizedResync(onLeftHalf, work)[1].rect
        assertEquals(WindowRect(left = 960, top = 0, right = 1920, bottom = 1040), snap)
        assertNotEquals(onLeftHalf, snap)
    }

    @Test
    fun `the snap call forces a frame recalculation and touches neither z-order nor activation`() {
        val expected = WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE
        val snap = fullscreenExitMaximizedResync(normal, work)[1]
        assertEquals(expected, snap.flags)
        assertTrue(snap.flags and WinUser.SWP_FRAMECHANGED != 0)
    }
}
