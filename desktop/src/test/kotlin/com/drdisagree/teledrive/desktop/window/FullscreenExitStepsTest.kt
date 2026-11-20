package com.drdisagree.teledrive.desktop.window

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC-20 exit contract: after fullscreen, the app must do to the
 * window what the user's manual snap-to-half-and-restore does — force a
 * REAL native resize — because restoring identical bounds emits no
 * WM_SIZE and the Compose scene keeps rendering at the stale fullscreen
 * surface. These tests pin the step plan the controller executes; the
 * actual Win32 timing can only be confirmed on his Windows PC.
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
    fun `a maximized exit skips the resize cycle - windows resizes it itself`() {
        val steps = fullscreenExitSteps(wasMaximized = true)
        assertEquals(
            listOf(
                FullscreenExitStep.RESTORE_PLACEMENT,
                FullscreenExitStep.REASSERT_PLACEMENT
            ),
            steps
        )
        assertFalse(steps.contains(FullscreenExitStep.FORCE_RESIZE_CYCLE))
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
