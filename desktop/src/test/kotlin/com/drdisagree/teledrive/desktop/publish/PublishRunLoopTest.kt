package com.drdisagree.teledrive.desktop.publish

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.publish.PublishRunOutcome
import com.drdisagree.teledrive.core.publish.PublishRunState
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * PC-32 + its addenda, pinned at the scheduler contract level:
 *
 * - a press of "Sync now" after a FAILED run starts a real new attempt
 *   immediately (the old scheduler dropped every kick while its loop
 *   was alive, and the loop never died — presses were no-ops forever);
 * - the same after a CANCEL mid-drain;
 * - cancel with no active run is a no-op;
 * - a kick during an active run schedules exactly one more pass.
 *
 * The retry backoff is set to an hour in these tests: any retry that
 * happens can only have come from the kick's wake-up, never the timer.
 */
class PublishRunLoopTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `sync now after a failed run retries immediately and drains`() {
        val attempts = AtomicInteger(0)
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, onFailure ->
                if (attempts.incrementAndGet() == 1) {
                    onFailure("f1", AppError.TelegramError(500, "server error"))
                    false
                } else {
                    true
                }
            },
            scope = scope,
            initialRetryDelaySeconds = 3_600,
            maxRetryDelaySeconds = 3_600
        )

        loop.kick()
        awaitState(loop, "the failed run parks in WaitingRetry") {
            it is PublishRunState.WaitingRetry
        }
        assertEquals(1, attempts.get())

        // The press that the old scheduler swallowed.
        loop.kick()
        awaitState(loop, "the re-kick drains and settles to Idle") {
            it == PublishRunState.Idle
        }
        assertEquals(2, attempts.get())
    }

    @Test
    fun `a throwing drain surfaces as WaitingRetry and a re-kick still drains`() {
        val attempts = AtomicInteger(0)
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    throw IllegalStateException("database is locked")
                }
                true
            },
            scope = scope,
            initialRetryDelaySeconds = 3_600,
            maxRetryDelaySeconds = 3_600
        )

        loop.kick()
        awaitState(loop, "the throw parks in WaitingRetry with its message") {
            it is PublishRunState.WaitingRetry && it.reason == "database is locked"
        }
        assertEquals(1, attempts.get())

        loop.kick()
        awaitState(loop, "the re-kick drains and settles to Idle") {
            it == PublishRunState.Idle
        }
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancel mid-drain stops the run and sync now afterwards resumes and drains`() {
        val attempts = AtomicInteger(0)
        val loop = PublishRunLoop(
            drain = PublishDrain { isStopped, onProgress, _ ->
                if (attempts.incrementAndGet() == 1) {
                    // A long drain: publishes until the stop flag lands,
                    // then reports itself unfinished — the drainer's
                    // cooperative-cancel contract.
                    var published = 0
                    while (!isStopped()) {
                        published++
                        onProgress(published, 1_000)
                        delay(5)
                    }
                    false
                } else {
                    true
                }
            },
            scope = scope,
            initialRetryDelaySeconds = 3_600,
            maxRetryDelaySeconds = 3_600
        )

        loop.kick()
        awaitState(loop, "the run is publishing") {
            it is PublishRunState.Running && it.published > 0
        }

        loop.cancel()
        awaitState(loop, "the cancel lands on Stopped") { it == PublishRunState.Stopped }

        loop.kick()
        awaitState(loop, "the post-cancel kick drains and settles to Idle") {
            it == PublishRunState.Idle
        }
        assertEquals(2, attempts.get())
    }

    @Test
    fun `cancel with no active run is a no-op`() {
        val attempts = AtomicInteger(0)
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, _ ->
                attempts.incrementAndGet()
                true
            },
            scope = scope
        )

        loop.cancel()

        assertEquals(PublishRunState.Idle, loop.runState.value)
        assertEquals(0, attempts.get())
    }

    @Test
    fun `a kick during an active run schedules exactly one more pass`() {
        val attempts = AtomicInteger(0)
        val release = java.util.concurrent.CountDownLatch(1)
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, _ ->
                if (attempts.incrementAndGet() == 1) {
                    release.await()
                }
                true
            },
            scope = scope
        )

        loop.kick()
        awaitAttempts(attempts, 1)
        loop.kick()
        loop.kick()
        release.countDown()

        awaitState(loop, "the loop settles after the extra pass") {
            it == PublishRunState.Idle
        }
        assertEquals(2, attempts.get())
    }

    @Test
    fun `a drain with permanently rejected files surfaces Failed instead of success`() {
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, onFailure ->
                onFailure("bad-1", AppError.TelegramError(400, "MESSAGE_ID_INVALID"))
                onFailure("bad-2", AppError.TelegramError(400, "MESSAGE_ID_INVALID"))
                true
            },
            scope = scope
        )

        loop.kick()
        awaitState(loop, "the rejected files surface as Failed") {
            it is PublishRunState.Failed
        }
        val state = loop.runState.value as PublishRunState.Failed
        assertEquals(2, state.failedFiles)
        assertEquals("MESSAGE_ID_INVALID", state.reason)
        assertTrue(loop.runState.value != PublishRunState.Idle)
    }

    // PC-33: the loop must also publish the last FINISHED run's
    // outcome + finish time — the Home card's resting/sticky states
    // are built on them. Nothing is recorded for a run that has not
    // finished (still going, or parked in WaitingRetry).

    @Test
    fun `a completed drain records a Completed outcome with its finish time`() {
        val nowMs = AtomicLong(1_700_000_000_000L)
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, _ -> true },
            scope = scope,
            clock = { Instant.ofEpochMilli(nowMs.get()) }
        )

        assertEquals(null, loop.lastOutcome.value)
        assertEquals(null, loop.lastFinishedAt.value)

        loop.kick()
        awaitOutcome(loop, "the completed drain records its outcome") {
            it == PublishRunOutcome.Completed
        }
        assertEquals(Instant.ofEpochMilli(1_700_000_000_000L), loop.lastFinishedAt.value)
    }

    @Test
    fun `a drain with rejected files records a Failed outcome`() {
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, onFailure ->
                onFailure("bad-1", AppError.TelegramError(400, "MESSAGE_ID_INVALID"))
                onFailure("bad-2", AppError.TelegramError(400, "MESSAGE_ID_INVALID"))
                true
            },
            scope = scope,
            clock = { Instant.ofEpochMilli(42_000L) }
        )

        loop.kick()
        awaitOutcome(loop, "the rejected files record a Failed outcome") {
            it is PublishRunOutcome.Failed
        }
        val outcome = loop.lastOutcome.value as PublishRunOutcome.Failed
        assertEquals(2, outcome.failedFiles)
        assertEquals("MESSAGE_ID_INVALID", outcome.reason)
        assertEquals(Instant.ofEpochMilli(42_000L), loop.lastFinishedAt.value)
    }

    @Test
    fun `a cancelled run records a Stopped outcome`() {
        val loop = PublishRunLoop(
            drain = PublishDrain { isStopped, onProgress, _ ->
                var published = 0
                while (!isStopped()) {
                    published++
                    onProgress(published, 1_000)
                    delay(5)
                }
                false
            },
            scope = scope,
            clock = { Instant.ofEpochMilli(7_500L) }
        )

        loop.kick()
        awaitState(loop, "the run is publishing") {
            it is PublishRunState.Running && it.published > 0
        }
        loop.cancel()
        awaitOutcome(loop, "the cancel records a Stopped outcome") {
            it == PublishRunOutcome.Stopped
        }
        assertEquals(Instant.ofEpochMilli(7_500L), loop.lastFinishedAt.value)
    }

    @Test
    fun `a completed drain after a failure replaces the outcome and clears the stickiness`() {
        val attempts = AtomicInteger(0)
        val nowMs = AtomicLong(10_000L)
        val loop = PublishRunLoop(
            drain = PublishDrain { _, _, onFailure ->
                if (attempts.incrementAndGet() == 1) {
                    onFailure("bad-1", AppError.TelegramError(400, "MESSAGE_ID_INVALID"))
                    true
                } else {
                    true
                }
            },
            scope = scope,
            clock = { Instant.ofEpochMilli(nowMs.get()) }
        )

        loop.kick()
        awaitOutcome(loop, "the first run fails") { it is PublishRunOutcome.Failed }
        assertEquals(Instant.ofEpochMilli(10_000L), loop.lastFinishedAt.value)

        nowMs.set(20_000L)
        loop.kick()
        awaitOutcome(loop, "the retry completes and replaces the outcome") {
            it == PublishRunOutcome.Completed
        }
        assertEquals(Instant.ofEpochMilli(20_000L), loop.lastFinishedAt.value)
        assertEquals(2, attempts.get())
    }

    private fun awaitOutcome(
        loop: PublishRunLoop,
        description: String,
        predicate: (PublishRunOutcome?) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(loop.lastOutcome.value)) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for: $description; outcome is ${loop.lastOutcome.value}")
    }

    private fun awaitState(
        loop: PublishRunLoop,
        description: String,
        predicate: (PublishRunState) -> Boolean
    ) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(loop.runState.value)) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for: $description; state is ${loop.runState.value}")
    }

    private fun awaitAttempts(attempts: AtomicInteger, expected: Int) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (attempts.get() >= expected) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for $expected drain attempts; saw ${attempts.get()}")
    }
}
