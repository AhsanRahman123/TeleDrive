package com.drdisagree.teledrive.presentation.home

import com.drdisagree.teledrive.core.publish.PublishRunOutcome
import com.drdisagree.teledrive.core.publish.PublishRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-33: the Home sync card's state mapping. Before this round the
 * card was gated on the pending count alone, so it vanished at
 * count zero in every run state — sync succeeding for the first
 * time looked exactly like the card being broken (round 9 §2).
 * The mapping pinned here: pending work and active runs keep the
 * original card; at zero, a Failed/Stopped last run is sticky
 * until a later run completes; only a Completed last run earns
 * the "All synced" resting card; before any run finishes, hidden.
 */
class SyncCardDisplayTest {

    private val none = PendingSync(files = 0, folders = false)
    private val someFiles = PendingSync(files = 3, folders = false)
    private val someFolders = PendingSync(files = 0, folders = true)

    @Test
    fun `pending files show the active card`() {
        assertEquals(
            SyncCardDisplay.Active,
            syncCardDisplay(someFiles, PublishRunState.Idle, null)
        )
        assertTrue(SyncCardState(pending = someFiles).visible)
    }

    @Test
    fun `pending folders show the active card`() {
        assertEquals(
            SyncCardDisplay.Active,
            syncCardDisplay(someFolders, PublishRunState.Idle, null)
        )
    }

    @Test
    fun `a running drain shows the active card even at zero pending`() {
        assertEquals(
            SyncCardDisplay.Active,
            syncCardDisplay(none, PublishRunState.Running(2, 5), null)
        )
    }

    @Test
    fun `a drain parked in a rate limit wait shows the active card even at zero pending`() {
        // PC-35: Running now also carries the rate-limit countdown;
        // the card stays the active card while it shows.
        assertEquals(
            SyncCardDisplay.Active,
            syncCardDisplay(
                none,
                PublishRunState.Running(0, 144, waitingSecondsLeft = 87),
                null
            )
        )
    }

    @Test
    fun `a drain waiting to retry shows the active card even at zero pending`() {
        assertEquals(
            SyncCardDisplay.Active,
            syncCardDisplay(none, PublishRunState.WaitingRetry("boom"), null)
        )
    }

    @Test
    fun `zero pending after a completed run rests at All synced`() {
        val state = SyncCardState(
            pending = none,
            runState = PublishRunState.Idle,
            lastOutcome = PublishRunOutcome.Completed,
            lastFinishedAtMs = 1_700_000_000_000L
        )
        assertEquals(SyncCardDisplay.AllSynced, state.display)
        assertTrue(state.visible)
    }

    @Test
    fun `zero pending after a failed run stays sticky-failed`() {
        assertEquals(
            SyncCardDisplay.StickyFailed(2, "MESSAGE_ID_INVALID"),
            syncCardDisplay(
                none,
                PublishRunState.Idle,
                PublishRunOutcome.Failed(2, "MESSAGE_ID_INVALID")
            )
        )
    }

    @Test
    fun `zero pending with a failed run state stays sticky-failed`() {
        assertEquals(
            SyncCardDisplay.StickyFailed(1, null),
            syncCardDisplay(none, PublishRunState.Failed(1, null), null)
        )
    }

    @Test
    fun `zero pending after a stopped run stays sticky-stopped`() {
        assertEquals(
            SyncCardDisplay.StickyStopped,
            syncCardDisplay(none, PublishRunState.Idle, PublishRunOutcome.Stopped)
        )
        assertEquals(
            SyncCardDisplay.StickyStopped,
            syncCardDisplay(none, PublishRunState.Stopped, null)
        )
    }

    @Test
    fun `a completed outcome replaces a failed one in the mapping`() {
        // The loop-level flip (fail, then a successful re-run) is
        // pinned in PublishRunLoopTest; here the mapping half: the
        // same zero/Idle inputs with the NEW outcome rest at
        // All synced instead of staying failed.
        assertEquals(
            SyncCardDisplay.AllSynced,
            syncCardDisplay(none, PublishRunState.Idle, PublishRunOutcome.Completed)
        )
    }

    @Test
    fun `pending work wins over a sticky outcome`() {
        assertEquals(
            SyncCardDisplay.Active,
            syncCardDisplay(
                someFiles,
                PublishRunState.Idle,
                PublishRunOutcome.Failed(2, "MESSAGE_ID_INVALID")
            )
        )
    }

    @Test
    fun `before any run finishes and nothing is pending the card is hidden`() {
        val state = SyncCardState(pending = none, runState = PublishRunState.Idle)
        assertEquals(SyncCardDisplay.Hidden, state.display)
        assertFalse(state.visible)
    }

    @Test
    fun `last run time formats as HH MM in the given offset`() {
        assertEquals("00:00", formatSyncTimeOfDay(0L, 0L))
        assertEquals("09:05", formatSyncTimeOfDay(9 * 3_600_000L + 5 * 60_000L, 0L))
        // Dhaka offset (+06:00) applied to a UTC-midnight instant.
        assertEquals("06:00", formatSyncTimeOfDay(0L, 6 * 3_600_000L))
        // A negative offset wraps to the previous evening.
        assertEquals("23:00", formatSyncTimeOfDay(0L, -3_600_000L))
        // Minutes are zero-padded; seconds never round up.
        assertEquals("23:59", formatSyncTimeOfDay(86_399_000L, 0L))
    }
}
