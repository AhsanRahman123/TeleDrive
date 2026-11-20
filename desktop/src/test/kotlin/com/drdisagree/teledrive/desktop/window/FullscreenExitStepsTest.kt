package com.drdisagree.teledrive.desktop.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC-20 exit contract, corrected by PC-23: after fullscreen, the
 * app must do to the window what the user's manual
 * snap-to-half-and-restore does — force REAL native resizes — because
 * restoring identical bounds emits no WM_SIZE and the Compose scene
 * keeps rendering at the stale fullscreen surface. Up to 1.6.5 a
 * maximized exit skipped the cycle on the assumption that Windows
 * resizes a re-maximized window itself; his 1.6.5 test video (window
 * maximized throughout — restore-down glyph — scene ghosted after the
 * exit) disproved that, so a maximized exit now runs the cycle too
 * (executed as a show-state excursion, see
 * [fullscreenExitMaximizedResync]). These tests pin the step plan the
 * controller executes; the actual Win32 behaviour can only be
 * confirmed on his Windows PC.
 */
class FullscreenExitStepsTest {

    @Test
    fun `a floating exit restores, forces a resize cycle, then re-asserts`() {
        assertEquals(
            listOf(
                FullscreenExitStep.RESTORE_PLACEMENT,
                FullscreenExitStep.FORCE_RESIZE_CYCLE,
                FullscreenExitStep.REASSERT_PLACEMENT
            ),
            fullscreenExitSteps(wasMaximized = false)
        )
    }

    @Test
    fun `a maximized exit also forces a resize cycle - it is not skipped`() {
        val steps = fullscreenExitSteps(wasMaximized = true)
        assertEquals(
            listOf(
                FullscreenExitStep.RESTORE_PLACEMENT,
                FullscreenExitStep.FORCE_RESIZE_CYCLE,
                FullscreenExitStep.REASSERT_PLACEMENT
            ),
            steps
        )
        assertTrue(steps.contains(FullscreenExitStep.FORCE_RESIZE_CYCLE))
    }

    @Test
    fun `restore is always first and re-assert is always last`() {
        listOf(true, false).forEach { maximized ->
            val steps = fullscreenExitSteps(wasMaximized = maximized)
            assertEquals(FullscreenExitStep.RESTORE_PLACEMENT, steps.first())
            assertEquals(FullscreenExitStep.REASSERT_PLACEMENT, steps.last())
            assertTrue(steps.size >= 2)
        }
    }
}
