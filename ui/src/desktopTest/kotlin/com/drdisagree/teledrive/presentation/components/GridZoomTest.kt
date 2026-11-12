package com.drdisagree.teledrive.presentation.components

import com.drdisagree.teledrive.domain.model.ViewMode
import org.junit.Assert.assertEquals
import org.junit.Test

class GridZoomTest {

    @Test
    fun `zoom out adds columns through the denser grid steps before list view`() {
        var level = GridZoomLevel(ViewMode.GRID, MIN_GRID_COLUMNS)

        repeat(MAX_GRID_COLUMNS - MIN_GRID_COLUMNS) {
            level = level.zoomedOut()
            assertEquals(ViewMode.GRID, level.viewMode)
        }

        assertEquals(MAX_GRID_COLUMNS, level.gridSize)
        level = level.zoomedOut()
        assertEquals(ViewMode.LIST, level.viewMode)
    }

    @Test
    fun `zoom in reduces columns without going below the largest grid tiles`() {
        var level = GridZoomLevel(ViewMode.GRID, MAX_GRID_COLUMNS)

        repeat(MAX_GRID_COLUMNS - MIN_GRID_COLUMNS) {
            level = level.zoomedIn()
            assertEquals(ViewMode.GRID, level.viewMode)
        }

        assertEquals(MIN_GRID_COLUMNS, level.gridSize)
        assertEquals(
            GridZoomLevel(ViewMode.GRID, MIN_GRID_COLUMNS),
            level.zoomedIn()
        )
    }

    @Test
    fun `zooming in from list restores the densest grid option`() {
        assertEquals(
            GridZoomLevel(ViewMode.GRID, MAX_GRID_COLUMNS),
            GridZoomLevel(ViewMode.LIST, MAX_GRID_COLUMNS).zoomedIn()
        )
    }
    @Test
    fun `wide gallery grids receive two extra columns but never exceed the shared cap`() {
        assertEquals(8, galleryGridColumnsForWindow(6, wideWindow = true))
        assertEquals(MAX_GRID_COLUMNS, galleryGridColumnsForWindow(10, wideWindow = true))
        assertEquals(MAX_GRID_COLUMNS, galleryGridColumnsForWindow(12, wideWindow = true))
    }

    @Test
    fun `narrow gallery grids keep the selected column count`() {
        assertEquals(6, galleryGridColumnsForWindow(6, wideWindow = false))
        assertEquals(MAX_GRID_COLUMNS, galleryGridColumnsForWindow(12, wideWindow = false))
    }

}
