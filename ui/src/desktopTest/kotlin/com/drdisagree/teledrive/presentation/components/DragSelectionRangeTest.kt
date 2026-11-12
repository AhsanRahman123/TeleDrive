package com.drdisagree.teledrive.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class DragSelectionRangeTest {

    @Test
    fun `dragging downward selects the inclusive anchor to pointer range`() {
        assertEquals(2..7, dragSelectionRange(anchorIndex = 2, currentIndex = 7))
    }

    @Test
    fun `dragging upward selects the same inclusive range in reverse`() {
        assertEquals(2..7, dragSelectionRange(anchorIndex = 7, currentIndex = 2))
    }

    @Test
    fun `stationary drag selects only the anchor item`() {
        assertEquals(4..4, dragSelectionRange(anchorIndex = 4, currentIndex = 4))
    }
}
