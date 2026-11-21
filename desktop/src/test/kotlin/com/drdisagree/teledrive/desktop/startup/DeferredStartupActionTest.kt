package com.drdisagree.teledrive.desktop.startup

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 11: the libVLC warm-up and the repository startup block are
 * wrapped in [DeferredStartupAction] so they cannot run during
 * process/window bring-up. Pins: nothing runs at construction, the
 * action runs after trigger, and repeated triggers run it once.
 */
class DeferredStartupActionTest {

    @Test
    fun `action does not run until triggered, then runs exactly once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runs = AtomicInteger(0)
            val action = DeferredStartupAction(scope) { runs.incrementAndGet() }

            assertFalse(action.isTriggered)
            delay(150)
            assertEquals(0, runs.get())

            action.trigger()
            assertTrue(action.isTriggered)
            val deadline = System.nanoTime() + 5_000_000_000L
            while (runs.get() == 0 && System.nanoTime() < deadline) delay(10)
            assertEquals(1, runs.get())

            action.trigger()
            action.trigger()
            delay(150)
            assertEquals(1, runs.get())
        } finally {
            scope.cancel()
        }
    }
}
