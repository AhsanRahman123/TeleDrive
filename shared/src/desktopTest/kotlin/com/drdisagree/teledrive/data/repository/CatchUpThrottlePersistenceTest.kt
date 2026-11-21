package com.drdisagree.teledrive.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 11 (report 29, headline cost B): the full-sync catch-up throttle
 * compared against an in-memory timestamp initialised to 0, so EVERY cold
 * launch paged the whole channel history (~5 s, result always +0 ~0 -0).
 * The timestamp is now persisted in the settings store. These tests pin:
 * a fresh persisted timestamp suppresses the full sync entirely on a new
 * repository instance (a simulated restart); a stale one runs exactly one
 * full sync, which records the new timestamp; and a manual full resync
 * records it too.
 */
class CatchUpThrottlePersistenceTest {

    private val harness = SyncHarness()
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `fresh persisted timestamp means a cold start runs no full sync`() = runBlocking {
        telegram.documents = listOf(
            document(20, harness.caption(manifest("f1", "a.jpg"))),
            document(21, harness.caption(manifest("f2", "b.jpg")))
        )
        harness.settings.update {
            it.copy(lastFullSyncAt = System.currentTimeMillis())
        }

        // A brand-new repository instance = a cold process start.
        val result = harness.newSync().catchUpWithRemote()

        assertNull(result)
        assertEquals(0, telegram.fetchDocumentsCalls)
    }

    @Test
    fun `stale timestamp runs exactly one full sync and persists the new one`() = runBlocking {
        telegram.documents = listOf(
            document(20, harness.caption(manifest("f1", "a.jpg")))
        )
        // lastFullSyncAt defaults to 0 = stale.
        val sync = harness.newSync()

        val result = sync.catchUpWithRemote()

        assertNotNull(result)
        assertTrue(telegram.fetchDocumentsCalls > 0)
        val persisted = harness.settings.preferences.first().lastFullSyncAt
        assertTrue(persisted > 0L)

        // Same instance: throttled now.
        val callsAfterFirst = telegram.fetchDocumentsCalls
        assertNull(sync.catchUpWithRemote())
        // New instance (simulated restart): still throttled, no new fetches.
        assertNull(harness.newSync().catchUpWithRemote())
        assertEquals(callsAfterFirst, telegram.fetchDocumentsCalls)
    }

    @Test
    fun `a manual full resync records the persisted timestamp`() = runBlocking {
        telegram.documents = listOf(
            document(20, harness.caption(manifest("f1", "a.jpg")))
        )

        harness.newSync().fullResync()

        assertTrue(harness.settings.preferences.first().lastFullSyncAt > 0L)
        val calls = telegram.fetchDocumentsCalls
        assertNull(harness.newSync().catchUpWithRemote())
        assertEquals(calls, telegram.fetchDocumentsCalls)
    }
}
