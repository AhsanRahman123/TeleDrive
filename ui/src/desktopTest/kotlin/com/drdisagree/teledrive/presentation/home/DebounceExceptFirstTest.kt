package com.drdisagree.teledrive.presentation.home

import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 */
class DebounceExceptFirstTest {

    @Test
    fun `first value passes immediately and later values are debounced`() = runBlocking {
        val source = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val received = mutableListOf<Pair<Int, Long>>()
        val startedAt = System.nanoTime()
        val collector = launch {
            source.debounceExceptFirst(2_000.milliseconds).collect { value ->
                synchronized(received) {
                    received += value to (System.nanoTime() - startedAt) / 1_000_000
                }
            }
        }

        source.emit(1)
        delay(100)
        source.emit(2)
        delay(50)
        source.emit(3)

        // Wait for both expected deliveries (1 immediately, 3 after the
        // quiet window), with generous headroom for a loaded CI runner.
        val deadline = System.nanoTime() + 9_000_000_000L
        while (System.nanoTime() < deadline) {
            val size = synchronized(received) { received.size }
            if (size >= 2) break
            delay(50)
        }
        collector.cancel()

        val snapshot = synchronized(received) { received.toList() }
        assertEquals(2, snapshot.size)
        assertEquals(1, snapshot[0].first)
        assertTrue(
            "first value must not wait for the debounce window (took ${snapshot[0].second} ms)",
            snapshot[0].second < 1_000
        )
        assertEquals(3, snapshot[1].first)
        assertTrue(
            "trailing value must wait for the quiet window (took ${snapshot[1].second} ms)",
            snapshot[1].second >= 1_800
        )
    }
}
