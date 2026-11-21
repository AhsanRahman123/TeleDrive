package com.drdisagree.teledrive.desktop.startup

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 11: the transfer-bubble overlay holds the shared TransfersStore
 * in a [LazyValue] so the store (and its eight DAO flows) is not built
 * at app start. Pins: not created at construction, created on first
 * get, and created exactly once.
 */
class LazyValueTest {

    @Test
    fun `value is created on first get only, exactly once`() {
        val creations = AtomicInteger(0)
        val lazy = LazyValue { creations.incrementAndGet(); Any() }

        assertFalse(lazy.isCreated)
        assertEquals(0, creations.get())

        val first = lazy.get()
        assertTrue(lazy.isCreated)
        assertEquals(1, creations.get())

        assertSame(first, lazy.get())
        assertEquals(1, creations.get())
    }
}
