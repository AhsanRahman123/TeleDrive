package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomPanClampTest {

    @Test
    fun `below 100 percent zoom the pan is pinned to zero instead of throwing`() {
        // Regression for the pinch force-close: the raw half-extent
        // width * (scale - 1) / 2 is negative below 1x, which inverted the
        // coerceIn range and threw IllegalArgumentException mid-pinch.
        val (x1, y1) = clampZoomPan(120f, -80f, 1000, 2000, 0.99f)
        assertEquals(0f, x1, 0.001f)
        assertEquals(0f, y1, 0.001f)

        val (x2, y2) = clampZoomPan(120f, -80f, 1000, 2000, 0.4f)
        assertEquals(0f, x2, 0.001f)
        assertEquals(0f, y2, 0.001f)
    }

    @Test
    fun `at exactly 100 percent zoom the pan is zero`() {
        val (x, y) = clampZoomPan(120f, -80f, 1000, 2000, 1f)
        assertEquals(0f, x, 0.001f)
        assertEquals(0f, y, 0.001f)
    }

    @Test
    fun `above 100 percent zoom the pan clamps to the scaled half extent`() {
        // Scale 2 on a 1000x2000 surface: half-extents are 500 and 1000.
        val (x, y) = clampZoomPan(900f, -1500f, 1000, 2000, 2f)
        assertEquals(500f, x, 0.001f)
        assertEquals(-1000f, y, 0.001f)

        val (insideX, insideY) = clampZoomPan(100f, 100f, 1000, 2000, 2f)
        assertEquals(100f, insideX, 0.001f)
        assertEquals(100f, insideY, 0.001f)
    }
}
