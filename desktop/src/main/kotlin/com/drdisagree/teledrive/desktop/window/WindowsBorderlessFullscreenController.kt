package com.drdisagree.teledrive.desktop.window

import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.sun.jna.Native
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.EventQueue
import java.awt.Window

/**
 * Keeps fullscreen a normal top-level window on Windows. Do not use
 * GraphicsDevice.fullScreenWindow: Skiko maps that to exclusive fullscreen.
 */
internal class WindowsBorderlessFullscreenController(
    private val window: Window,
    private val windowState: WindowState
) {
    private var saved: Snapshot? = null
    val isFullscreen: Boolean get() = saved != null

    fun toggle() = setFullscreen(!isFullscreen)

    fun setFullscreen(value: Boolean) {
        check(EventQueue.isDispatchThread()) { "Fullscreen must change on the AWT event thread." }
        if (value == isFullscreen) return
        if (value) enter() else exit()
    }

    private fun enter() {
        val hwnd = hwnd()
        val style = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_STYLE)
        check(style != 0) { "Could not read window style (Win32 " + Native.getLastError() + ")." }
        val placement = readPlacement(hwnd)
        val monitor = readMonitor(hwnd)
        val snapshot = Snapshot(style, placement, monitor)

        try {
            // Let Compose leave its prior Maximized placement before applying native
            // monitor bounds. Its WindowState update can otherwise re-apply stale
            // position/size after the HWND has already been sized.
            windowState.placement = WindowPlacement.Floating

            // Restore first so a previously maximized window can cover the full monitor,
            // including the taskbar, without using the OS exclusive-fullscreen API.
            User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_RESTORE)
            setStyle(hwnd, borderlessWindowStyle(style))
            setBounds(
                hwnd, monitor,
                WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOZORDER or
                    WinUser.SWP_NOACTIVATE or WinUser.SWP_SHOWWINDOW
            )
            saved = snapshot

            // Compose applies WindowState changes asynchronously. Re-assert the final
            // borderless bounds after that update so the client content starts at the
            // actual monitor top-left instead of inheriting stale decorated-window bounds.
            EventQueue.invokeLater {
                EventQueue.invokeLater {
                    if (saved === snapshot) {
                        setBounds(
                            hwnd, monitor,
                            WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE or
                                WinUser.SWP_SHOWWINDOW
                        )
                    }
                }
            }
        } catch (failure: Throwable) {
            runCatching {
                setStyle(hwnd, style)
                frameChanged(hwnd)
                writePlacement(hwnd, placement)
                windowState.placement = placement.composePlacement
            }
            throw failure
        }
    }

    private fun exit() {
        val snapshot = saved ?: return
        val hwnd = hwnd()
        try {
            setStyle(hwnd, snapshot.style)
            frameChanged(hwnd)
            writePlacement(hwnd, snapshot.placement)
            windowState.placement = snapshot.placement.composePlacement
            saved = null
        } catch (failure: Throwable) {
            // Keep the borderless state and the snapshot so F11 can retry.
            runCatching {
                setStyle(hwnd, borderlessWindowStyle(snapshot.style))
                setBounds(
                    hwnd, snapshot.monitor,
                    WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOZORDER or
                        WinUser.SWP_NOACTIVATE or WinUser.SWP_SHOWWINDOW
                )
                windowState.placement = WindowPlacement.Floating
            }
            throw failure
        }
    }

    private fun hwnd(): WinDef.HWND {
        val pointer = Native.getComponentPointer(window)
            ?: error("Could not obtain the native TeleDrive window handle.")
        return WinDef.HWND(pointer)
    }

    private fun readPlacement(hwnd: WinDef.HWND): SavedPlacement {
        val p = WinUser.WINDOWPLACEMENT()
        checkWin32(User32.INSTANCE.GetWindowPlacement(hwnd, p).booleanValue(), "GetWindowPlacement")
        p.read()
        return SavedPlacement(
            p.flags, p.showCmd,
            p.ptMinPosition.x, p.ptMinPosition.y,
            p.ptMaxPosition.x, p.ptMaxPosition.y,
            p.rcNormalPosition.left, p.rcNormalPosition.top,
            p.rcNormalPosition.right, p.rcNormalPosition.bottom
        )
    }

    private fun readMonitor(hwnd: WinDef.HWND): Rect {
        val monitor = User32.INSTANCE.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
            ?: error("Could not locate the monitor containing TeleDrive.")
        val info = WinUser.MONITORINFO()
        checkWin32(User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue(), "GetMonitorInfo")
        info.read()
        return Rect(info.rcMonitor.left, info.rcMonitor.top, info.rcMonitor.right, info.rcMonitor.bottom)
    }

    private fun setStyle(hwnd: WinDef.HWND, style: Int) {
        // SetWindowLong may legally return zero when the previous value was zero.
        // Clear last error first so zero can be distinguished from an actual failure.
        Kernel32.INSTANCE.SetLastError(0)
        val previous = User32.INSTANCE.SetWindowLong(hwnd, WinUser.GWL_STYLE, style)
        val error = Kernel32.INSTANCE.GetLastError()
        check(previous != 0 || error == 0) {
            "SetWindowLong(GWL_STYLE) failed (Win32 " + error + ")."
        }
    }

    private fun setBounds(hwnd: WinDef.HWND, rect: Rect, flags: Int) {
        checkWin32(
            User32.INSTANCE.SetWindowPos(
                hwnd, null, rect.left, rect.top,
                rect.right - rect.left, rect.bottom - rect.top, flags
            ),
            "SetWindowPos"
        )
    }

    private fun frameChanged(hwnd: WinDef.HWND) {
        checkWin32(
            User32.INSTANCE.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOZORDER or
                    WinUser.SWP_NOACTIVATE or WinUser.SWP_NOMOVE or WinUser.SWP_NOSIZE
            ),
            "SetWindowPos(frame change)"
        )
    }

    private fun writePlacement(hwnd: WinDef.HWND, p: SavedPlacement) {
        val native = WinUser.WINDOWPLACEMENT().apply {
            flags = p.flags
            showCmd = p.showCommand
            ptMinPosition = WinDef.POINT().apply { x = p.minX; y = p.minY }
            ptMaxPosition = WinDef.POINT().apply { x = p.maxX; y = p.maxY }
            rcNormalPosition = WinDef.RECT().apply {
                left = p.normalLeft; top = p.normalTop
                right = p.normalRight; bottom = p.normalBottom
            }
            write()
        }
        checkWin32(User32.INSTANCE.SetWindowPlacement(hwnd, native).booleanValue(), "SetWindowPlacement")
    }

    private fun checkWin32(ok: Boolean, operation: String) {
        check(ok) { operation + " failed (Win32 " + Native.getLastError() + ")." }
    }

    private data class Snapshot(val style: Int, val placement: SavedPlacement, val monitor: Rect)
    private data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    private data class SavedPlacement(
        val flags: Int, val showCommand: Int,
        val minX: Int, val minY: Int, val maxX: Int, val maxY: Int,
        val normalLeft: Int, val normalTop: Int, val normalRight: Int, val normalBottom: Int
    ) {
        val composePlacement: WindowPlacement
            get() = if (showCommand == WinUser.SW_SHOWMAXIMIZED) {
                WindowPlacement.Maximized
            } else {
                WindowPlacement.Floating
            }
    }
}

internal fun borderlessWindowStyle(style: Int): Int =
    style and (WinUser.WS_CAPTION or WinUser.WS_THICKFRAME).inv()
