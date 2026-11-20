package com.drdisagree.teledrive.desktop.window

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
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

    // The most recent completed exit, kept so the deferred re-assert (see
    // exit()) can verify no newer transition has superseded it.
    private var lastExit: Snapshot? = null
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
        val geometry = readMonitorGeometry(hwnd)
        val monitor = geometry.monitor
        val snapshot = Snapshot(
            style, placement, monitor, geometry.work,
            windowState.position, windowState.size
        )
        lastExit = null

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
            refreshAwtLayout()
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
                        refreshAwtLayout()
                    }
                }
            }
        } catch (failure: Throwable) {
            runCatching {
                setStyle(hwnd, style)
                frameChanged(hwnd)
                writePlacement(hwnd, placement)
                windowState.placement = placement.composePlacement
                refreshAwtLayout()
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
            // Also restore the Compose-tracked floating geometry. During
            // borderless fullscreen the AWT window is monitor-sized, and
            // WindowState tracks that; if exit only restores the native
            // placement, Compose's asynchronous placement application can
            // re-apply the fullscreen size/position afterwards and push
            // the app UI off-screen (controls cut at half screen, PC-14).
            if (snapshot.placement.composePlacement == WindowPlacement.Floating) {
                windowState.position = snapshot.composePosition
                windowState.size = snapshot.composeSize
            }
            refreshAwtLayout()
            saved = null
            lastExit = snapshot

            // RESTORE_PLACEMENT has run synchronously above. Run the
            // remaining exit steps (see fullscreenExitSteps) deferred:
            // Compose applies WindowState changes asynchronously, and —
            // the PC-20/PC-21 defect — restoring the SAME bounds never
            // produces a real size change, so the Compose scene keeps
            // its stale fullscreen surface (UI cut / scaled wrong) until
            // one arrives. The user's own workaround is a WINDOWS snap
            // to half screen and back — a window-manager operation. The
            // 1.6.4 attempt imitated it with AWT setSize nudges, which
            // the window layer can coalesce into nothing (a 1 px change
            // that AWT/Compose may absorb without any frame
            // recalculation ever reaching the native window). A floating
            // exit therefore now performs the snap itself through the
            // native channel: SetWindowPos to a genuinely different
            // rect (half the work area) and back to the exact restored
            // rect, both with SWP_FRAMECHANGED (see
            // forceNativeSnapCycle). Every step is guarded by the exit
            // token — a fast re-enter (or a newer exit) supersedes it.
            runExitSteps(
                fullscreenExitSteps(
                    wasMaximized =
                        snapshot.placement.composePlacement == WindowPlacement.Maximized
                ).drop(1),
                snapshot,
                first = true
            )
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
                refreshAwtLayout()
            }
            throw failure
        }
    }

    /**
     * Runs the deferred exit steps one EDT turn at a time (the first step
     * waits two turns, matching the timing the old deferred re-assert
     * used, so Compose's asynchronous placement application has landed).
     * Aborts silently when a newer transition supersedes this exit.
     */
    private fun runExitSteps(
        steps: List<FullscreenExitStep>,
        snapshot: Snapshot,
        first: Boolean
    ) {
        if (steps.isEmpty()) return
        val runStep = Runnable {
            if (lastExit === snapshot && !isFullscreen) {
                when (steps.first()) {
                    FullscreenExitStep.FORCE_RESIZE_CYCLE ->
                        forceNativeSnapCycle(snapshot, steps.drop(1))

                    FullscreenExitStep.REASSERT_PLACEMENT -> {
                        runCatching {
                            writePlacement(hwnd(), snapshot.placement)
                            frameChanged(hwnd())
                            refreshAwtLayout()
                        }
                        runExitSteps(steps.drop(1), snapshot, first = false)
                    }

                    // Already performed synchronously in exit(); skip.
                    FullscreenExitStep.RESTORE_PLACEMENT ->
                        runExitSteps(steps.drop(1), snapshot, first = false)
                }
            }
        }
        if (first) {
            EventQueue.invokeLater { EventQueue.invokeLater(runStep) }
        } else {
            EventQueue.invokeLater(runStep)
        }
    }

    /**
     * His snap workaround, performed by the app through the native
     * channel (PC-21): SetWindowPos the window to half the work area —
     * the rect a Windows snap-left would give it — then SetWindowPos it
     * back to the exact restored rect, both with SWP_FRAMECHANGED.
     *
     * Why this and not the 1.6.4 AWT cycle: `Window.setSize` travels
     * through AWT/Compose's own size bookkeeping, which can coalesce or
     * swallow a small change entirely (the 1 px shrink could be absorbed
     * against the size Compose already believed the window had), so no
     * frame recalculation ever reached the native window and the scene
     * stayed stale. SetWindowPos bypasses that bookkeeping: it is the
     * same call the window manager path uses, it is synchronous (the
     * window receives WM_WINDOWPOSCHANGED/WM_SIZE before the call
     * returns), and SWP_FRAMECHANGED forces the frame to be recalculated
     * for each rect. The two calls run back-to-back on this EDT turn —
     * deliberately NOT split across turns waiting for paints, which is
     * what made the AWT version's second half skippable. After the
     * cycle, the AWT hierarchy is revalidated/repainted (the same
     * refresh every other native transition in this class performs), so
     * the Compose scene re-lays out against the final native bounds.
     */
    private fun forceNativeSnapCycle(snapshot: Snapshot, remaining: List<FullscreenExitStep>) {
        val hwnd = hwnd()
        val normal = WindowRect(
            snapshot.placement.normalLeft, snapshot.placement.normalTop,
            snapshot.placement.normalRight, snapshot.placement.normalBottom
        )
        val work = WindowRect(
            snapshot.work.left, snapshot.work.top,
            snapshot.work.right, snapshot.work.bottom
        )
        fullscreenExitSnapCycle(normal, work).forEach { call ->
            setBounds(
                hwnd,
                Rect(call.rect.left, call.rect.top, call.rect.right, call.rect.bottom),
                call.flags
            )
        }
        refreshAwtLayout()
        runExitSteps(remaining, snapshot, first = false)
    }

    private fun refreshAwtLayout() {
        // A native frame-style transition also changes the drawable client area.
        // Revalidate the AWT hierarchy after the peer receives the frame/bounds update.
        window.invalidate()
        window.validate()
        window.repaint()
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

    /** The monitor's full rect and its work area (monitor minus taskbar). */
    private data class MonitorGeometry(val monitor: Rect, val work: Rect)

    private fun readMonitorGeometry(hwnd: WinDef.HWND): MonitorGeometry {
        val monitor = User32.INSTANCE.MonitorFromWindow(hwnd, WinUser.MONITOR_DEFAULTTONEAREST)
            ?: error("Could not locate the monitor containing TeleDrive.")
        val info = WinUser.MONITORINFO()
        checkWin32(User32.INSTANCE.GetMonitorInfo(monitor, info).booleanValue(), "GetMonitorInfo")
        info.read()
        return MonitorGeometry(
            monitor = Rect(
                info.rcMonitor.left, info.rcMonitor.top,
                info.rcMonitor.right, info.rcMonitor.bottom
            ),
            work = Rect(
                info.rcWork.left, info.rcWork.top,
                info.rcWork.right, info.rcWork.bottom
            )
        )
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

    private data class Snapshot(
        val style: Int,
        val placement: SavedPlacement,
        val monitor: Rect,
        /** Work area of [monitor] at enter time — the snap cycle's target geometry. */
        val work: Rect,
        val composePosition: WindowPosition,
        val composeSize: DpSize
    )
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

/** One step of a fullscreen exit, in execution order. */
internal enum class FullscreenExitStep {
    /** Restore the snapshot style + native placement + Compose geometry. */
    RESTORE_PLACEMENT,

    /** Force a real native resize: the snap cycle (see [fullscreenExitSnapCycle]). */
    FORCE_RESIZE_CYCLE,

    /** Re-assert the restored placement after the deferred steps. */
    REASSERT_PLACEMENT
}

/**
 * The ordered exit sequence (PC-20). A floating window gets the resize
 * cycle: restoring identical bounds emits no real WM_SIZE, so without it
 * the Compose scene stays stale until the user resizes by hand. A
 * maximized restore skips it — Windows generates genuine resizes itself
 * when a window returns to maximized, so the scene re-lays out anyway.
 */
internal fun fullscreenExitSteps(wasMaximized: Boolean): List<FullscreenExitStep> =
    if (wasMaximized) {
        listOf(
            FullscreenExitStep.RESTORE_PLACEMENT,
            FullscreenExitStep.REASSERT_PLACEMENT
        )
    } else {
        listOf(
            FullscreenExitStep.RESTORE_PLACEMENT,
            FullscreenExitStep.FORCE_RESIZE_CYCLE,
            FullscreenExitStep.REASSERT_PLACEMENT
        )
    }

/** A window rectangle in virtual-screen pixels, Win32 RECT semantics. */
internal data class WindowRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** One native SetWindowPos call of the fullscreen-exit snap cycle. */
internal data class NativeSetWindowPosCall(val rect: WindowRect, val flags: Int)

/**
 * Flags for both snap-cycle calls. SWP_FRAMECHANGED is the point of the
 * exercise: it makes the window manager recalculate the non-client frame
 * for the new rect, which is what a real snap does and what an AWT
 * setSize nudge never guarantees. Z-order and activation stay untouched.
 */
internal val fullscreenSnapCycleFlags: Int =
    WinUser.SWP_FRAMECHANGED or WinUser.SWP_NOZORDER or WinUser.SWP_NOACTIVATE

/**
 * The rect a Windows snap-to-left would give a window on [work]: the
 * left half of the work area, full work-area height. If the restored
 * window already occupies exactly that rect, the right half is used
 * instead — the cycle only repairs the scene if its first rect is
 * genuinely different from the restored one.
 */
internal fun snapHalfRect(work: WindowRect, normal: WindowRect): WindowRect {
    val midX = work.left + work.width / 2
    val leftHalf = WindowRect(work.left, work.top, midX, work.bottom)
    return if (leftHalf != normal) {
        leftHalf
    } else {
        WindowRect(midX, work.top, work.right, work.bottom)
    }
}

/**
 * The FORCE_RESIZE_CYCLE step as native calls (PC-21), in execution
 * order: snap to half the work area, then back to the exact restored
 * rect — the two genuine resizes of the user's manual snap + restore,
 * issued back-to-back through SetWindowPos.
 */
internal fun fullscreenExitSnapCycle(
    normal: WindowRect,
    work: WindowRect
): List<NativeSetWindowPosCall> =
    listOf(
        NativeSetWindowPosCall(snapHalfRect(work, normal), fullscreenSnapCycleFlags),
        NativeSetWindowPosCall(normal, fullscreenSnapCycleFlags)
    )
