package com.drdisagree.teledrive.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadBatchGenerationTest {

    @Test
    fun `generation starts at zero and snapshot remains current`() {
        val generation = UploadBatchGeneration()
        val snapshot = generation.current()

        assertEquals(0L, snapshot)
        assertTrue(generation.isCurrent(snapshot))
    }

    @Test
    fun `cancel all invalidates an earlier import snapshot`() {
        val generation = UploadBatchGeneration()
        val oldBatch = generation.current()

        val nextGeneration = generation.advance()

        assertEquals(1L, nextGeneration)
        assertFalse(generation.isCurrent(oldBatch))
        assertTrue(generation.isCurrent(nextGeneration))
    }

    @Test
    fun `a later batch gets a fresh snapshot after cancellation`() {
        val generation = UploadBatchGeneration()
        val cancelledBatch = generation.current()
        generation.advance()

        val newBatch = generation.current()

        assertFalse(generation.isCurrent(cancelledBatch))
        assertTrue(generation.isCurrent(newBatch))
    }
}
