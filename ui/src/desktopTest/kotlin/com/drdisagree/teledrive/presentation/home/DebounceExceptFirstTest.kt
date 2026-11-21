package com.drdisagree.teledrive.presentation.home

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 11 (report 29 §5): Home's counts flow used a plain 300 ms
 * debounce, whose first emission waits for a quiet window — during
 * startup, writers keep invalidating the files table, so the combine
 * that lifts Home's skeleton could be held up for the whole burst.
 * [debounceExceptFirst] lets the first value through immediately and
 * debounces only later values. Pinned here with a 2 s window so the
 * two behaviours are unambiguous: plain debounce would deliver the
 * first value at >= 2 s; the bypass delivers it at once, and the
 * trailing burst value still waits for the quiet window.
 *
 * The source is a cold flow builder so emissions begin exactly when
 * collection starts — no subscription race. It then stays OPEN:
 * debounce semantics flush a pending value when the upstream
 * completes, so a source that ends right after its last emission
 * would deliver the trailing value at once and the quiet window
 * could never be observed (that completion flush is what the first
 * version of this test actually measured).
 */
class DebounceExceptFirstTest {

    @Test
    fun `first value passes immediately and later values are debounced`() = runBlocking {
        val received = mutableListOf<Pair<Int, Long>>()
        var startedAt = 0L
        val job = async {
            flow {
                startedAt = System.nanoTime()
                emit(1)
                delay(100)
                emit(2)
                delay(50)
                emit(3)
                // Stay open: take(2) cancels collection long before this
                // elapses; it only stops the completion flush (see above).
                delay(60_000)
            }.debounceExceptFirst(2_000.milliseconds).take(2).collect { value ->
                received += value to (System.nanoTime() - startedAt) / 1_000_000
            }
        }
        withTimeout(9_000) { job.await() }

        assertEquals(2, received.size)
        assertEquals(1, received[0].first)
        assertTrue(
            "first value must not wait for the debounce window (took ${received[0].second} ms)",
            received[0].second < 1_000
        )
        assertEquals(3, received[1].first)
        assertTrue(
            "trailing value must wait for the quiet window (took ${received[1].second} ms)",
            received[1].second >= 1_800
        )
    }
}
