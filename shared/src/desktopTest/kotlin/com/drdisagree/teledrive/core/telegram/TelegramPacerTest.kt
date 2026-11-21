package com.drdisagree.teledrive.core.telegram

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-36: the pacer park the PC-35 suite could not see. Its fake
 * drains reported their own waits, so a wait that reported NOTHING —
 * the pacer sleeping out a pre-existing account flood deadline
 * before any request was sent — had no test at this seam. These pin
 * the pacer's new contract directly: a flood wait ticks the caller's
 * onWaiting from the first second, one call never sleeps more than
 * the 30 s inline cap before throwing a 429 that carries the seconds
 * remaining, and cancellation lands within a tick.
 *
 * The long-window cases run on the coroutines-test clock: the pacer
 * reads its deadline from the wall clock (which stays ~900 s out for
 * the whole test) while its sleeps are virtual, so the 30 s cap is
 * exercised in milliseconds of real time.
 */
class TelegramPacerTest {

    @Test
    fun `a long pre-existing flood deadline ticks the wait then throws 429 at the cap`() =
        runTest {
            val pacer = TelegramPacer()
            // The field scenario: the deadline was set BEFORE the call
            // (by an earlier run's last 429) — 3,600 s asked, 900 s
            // stored after the record-time clamp.
            pacer.recordFloodWait(3_600)
            val deadlineAtStart = pacer.floodWaitSeconds
            assertTrue("stored deadline is the clamp, was $deadlineAtStart",
                deadlineAtStart in 890..901)

            val ticks = mutableListOf<Int>()
            var blockRan = false
            val error = try {
                pacer.pacedEdit({ blockRan = true }, onWaiting = { ticks += it })
                null
            } catch (e: TelegramException) {
                e
            }

            assertNotNull("the capped wait must end in a 429, not a park", error)
            assertEquals(429, error!!.code)
            assertFalse("no request may be sent during a flood park", blockRan)
            assertTrue(
                "the wait must tick from the first second, got ${ticks.size} ticks",
                ticks.size >= 30
            )
            assertTrue(ticks.all { it in 850..901 })
            // The 429 carries the seconds REMAINING (deadline minus
            // roughly the 30 s cap the call rode out — on the virtual
            // clock the wall-clock remainder stays near the start).
            val remaining = error.retryAfterSeconds
            assertNotNull(remaining)
            assertTrue(
                "retryAfter $remaining should sit just under the deadline $deadlineAtStart",
                remaining!! in (deadlineAtStart - 40)..deadlineAtStart
            )
            // The thrown 429 re-armed the deadline from the remainder
            // through the paced catch: the account is still parked,
            // not silently cleared.
            assertTrue(pacer.floodWaitSeconds > 800)
        }

    @Test
    fun `the creation lane ticks and caps a pre-existing flood deadline the same way`() =
        runTest {
            val pacer = TelegramPacer()
            pacer.recordFloodWait(3_600)

            val ticks = mutableListOf<Int>()
            var blockRan = false
            val error = try {
                pacer.paced({ blockRan = true }, onWaiting = { ticks += it })
                null
            } catch (e: TelegramException) {
                e
            }

            assertEquals(429, error?.code)
            assertFalse(blockRan)
            assertTrue(ticks.size >= 30)
        }

    @Test
    fun `cancelling a parked call lands within a tick, not at the end of the window`() =
        runTest {
            val pacer = TelegramPacer()
            pacer.recordFloodWait(3_600)
            val ticks = mutableListOf<Int>()
            var blockRan = false
            val job = async {
                pacer.pacedEdit({ blockRan = true }, onWaiting = { ticks += it })
            }
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue("the park ticks before cancellation", ticks.isNotEmpty())
            assertFalse("no request may be sent during a flood park", blockRan)

            job.cancel()
            advanceTimeBy(2_000)
            runCurrent()
            assertTrue("cancellation must land promptly", job.isCancelled)
        }

    @Test
    fun `a short flood window is ridden out visibly and the call then runs`() = runBlocking {
        val pacer = TelegramPacer()
        pacer.recordFloodWait(1) // deadline ~2 s out, under the cap
        val ticks = mutableListOf<Int>()
        var ran = false
        pacer.pacedEdit({ ran = true }, onWaiting = { ticks += it })
        assertTrue("the call runs once the window passes", ran)
        assertTrue("the short wait still ticks", ticks.isNotEmpty())
        assertEquals(0, pacer.floodWaitSeconds)
    }

    @Test
    fun `an absurd retry after is clamped when recorded`() {
        val pacer = TelegramPacer()
        pacer.recordFloodWait(100_000)
        assertTrue(
            "stored deadline is clamped, was ${pacer.floodWaitSeconds}",
            pacer.floodWaitSeconds in 800..901
        )
    }
}
