package com.drdisagree.teledrive.desktop.window

import com.sun.jna.platform.win32.WinUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC-21 exit contract: the resize cycle that repairs the stale
 * fullscreen scene must be the user's own workaround — a Windows snap
 * to half the screen and back — issued as native SetWindowPos calls,
 * not the 1.6.4 AWT setSize nudge (a 1 px change AWT/Compose could
 * coalesce into nothing, so the frame was never recalculated and the
 * scene stayed cut). These tests pin the cycle's calls: their order,
 * their rects and their flags. The Win32 behaviour itself can only be
 * confirmed on his Windows PC.
 */
class FullscreenSnapCycleTest {

    // A 1920x1080 monitor whose taskbar leaves a 1920x1040 work area.
    private val work = WindowRect(left = 0, top = 0, right = 1920, bottom = 1040)

    // A floating window restored to the right side of the screen.
    private val normal = WindowRect(left = 900, top = 100, right = 1700, bottom = 800)

    @Test
    fun `the cycle is exactly two calls - snap away, then back to the restored rect`() {
        val calls = fullscreenExitSnapCycle(normal, work)
        assertEquals(2, calls.size)
        assertEquals(normal, calls[1].rect)
    }

    @Test
    fun `the snap rect is the left half of the work area, like a windows snap-left`() {
        val snap = fullscreenExitSnapCycle(normal, work).first().rect
        assertEquals(WindowRect(left = 0, top = 0, right = 960, bottom = 1040), snap)
    }

    @Test
    fun `the snap rect is genuinely different from the restored rect`() {
        val calls = fullscreenExitSnapCycle(normal, work)
        assertNotEquals(calls[0].rect, calls[1].rect)
    }

    @Test
    fun `a window already on the left half snaps to the right half instead`() {
        val onLeftHalf = WindowRect(left = 0, top = 0, right = 960, bottom = 1040)
        val snap = fullscreenExitSnapCycle(onLeftHalf, work).first().rect
        assertEquals(WindowRect(left = 960, top = 0, right = 1920, bottom = 1040), snap)
        assertNotEquals(onLeftHalf, snap)
    }

    @Test
    fun `both calls force a frame recalculation and touch neither z-order nor activation`() {
        val expected = WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE
        fullscreenExitSnapCycle(normal, work).forEach { call ->
            assertEquals(expected, call.flags)
            assertTrue(call.flags and WinUser.SWP_FRAMECHANGED != 0)
        }
    }

    @Test
    fun `the snap honours a work area that does not start at the origin`() {
        val offsetWork = WindowRect(left = 1920, top = 30, right = 4480, bottom = 1410)
        val snap = fullscreenExitSnapCycle(normal, offsetWork).first().rect
        // 2560-wide work area starting at x=1920: left half ends at 3200.
        assertEquals(WindowRect(left = 1920, top = 30, right = 3200, bottom = 1410), snap)
    }
}
