package com.drdisagree.teledrive.desktop.window

import com.sun.jna.platform.win32.WinUser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WindowsBorderlessFullscreenStyleTest {

    @Test
    fun removesCaptionAndResizeFrameOnly() {
        val original = WinUser.WS_OVERLAPPEDWINDOW or WinUser.WS_VISIBLE
        val result = borderlessWindowStyle(original)

        assertEquals(0, result and WinUser.WS_CAPTION)
        assertEquals(0, result and WinUser.WS_THICKFRAME)
        assertEquals(
            original and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME).inv(),
            result
        )
    }

    @Test
    fun keepsNormalTopLevelWindowStylesForTaskSwitching() {
        val original = WinUser.WS_OVERLAPPEDWINDOW or WinUser.WS_VISIBLE
        val result = borderlessWindowStyle(original)

        assertNotEquals(0, result and WinUser.WS_VISIBLE)
        assertNotEquals(0, result and WinUser.WS_SYSMENU)
        assertNotEquals(0, result and WinUser.WS_MINIMIZEBOX)
        assertNotEquals(0, result and WinUser.WS_MAXIMIZEBOX)
        assertEquals(0, result and WinUser.WS_CHILD)
    }
}
