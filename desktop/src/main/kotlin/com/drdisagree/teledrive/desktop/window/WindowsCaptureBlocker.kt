package com.drdisagree.teledrive.desktop.window

import com.sun.jna.Native
import com.sun.jna.Platform
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window

internal interface User32DisplayAffinity : StdCallLibrary {

    @Suppress("FunctionName")
    fun SetWindowDisplayAffinity(hwnd: WinDef.HWND, affinity: Int): Boolean

    companion object {
        val INSTANCE: User32DisplayAffinity = Native.load("user32", User32DisplayAffinity::class.java)
    }
}

/**
 * Desktop counterpart of Android's FLAG_SECURE for the Block screenshots
 * setting: WDA_EXCLUDEFROMCAPTURE keeps the window out of screenshots and
 * screen sharing (captures see a black window) while it stays fully usable
 * on the physical display. Turning the setting off restores WDA_NONE.
 */
object WindowsCaptureBlocker {

    private const val WDA_NONE = 0x00000000
    private const val WDA_EXCLUDEFROMCAPTURE = 0x00000011

    fun setBlocked(window: Window, blocked: Boolean) {
        if (!Platform.isWindows()) return
        val pointer = Native.getComponentPointer(window) ?: return
        runCatching {
            User32DisplayAffinity.INSTANCE.SetWindowDisplayAffinity(
                WinDef.HWND(pointer),
                if (blocked) WDA_EXCLUDEFROMCAPTURE else WDA_NONE
            )
        }
    }
}
