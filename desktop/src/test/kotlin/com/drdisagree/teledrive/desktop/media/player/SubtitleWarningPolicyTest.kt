package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtitle-failure warning must never become a permanent fixture again:
 * it is claimable at most once per cause per session, and its on-screen
 * lifetime is a few seconds, not "until the flag clears".
 */
class SubtitleWarningPolicyTest {

    @Test
    fun `a cause can be claimed exactly once`() {
        val once = OncePerSession()
        assertTrue(once.claim("factory-failed"))
        assertFalse(once.claim("factory-failed"))
        assertFalse(once.claim("factory-failed"))
    }

    @Test
    fun `causes are independent`() {
        val once = OncePerSession()
        assertTrue(once.claim("factory-failed"))
        assertTrue(once.claim("renderer-missing"))
        assertFalse(once.claim("renderer-missing"))
    }

    @Test
    fun `the warning dismisses itself after a few seconds`() {
        assertEquals(6_000L, SubtitleWarningPolicy.DISPLAY_DURATION_MS)
        assertTrue(SubtitleWarningPolicy.DISPLAY_DURATION_MS in 3_000L..10_000L)
    }
}
