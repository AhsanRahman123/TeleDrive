package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.drdisagree.teledrive.presentation.files.fileTabDragSource
import com.drdisagree.teledrive.presentation.files.keepFilesSelectionMode
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class DragSelectPointerTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `mouse drag selects every item crossed by the pointer`() {
        var selected by mutableStateOf(emptySet<Int>())
        var rangeBase by mutableStateOf(emptySet<Int>())
        var selectionMode by mutableStateOf(false)
        var rangeGestureActive by mutableStateOf(false)

        rule.setContent {
            val gridState = rememberLazyGridState()
            val dragSelect = rememberDragSelect(
                gridState = gridState,
                onStart = {
                    rangeBase = selected
                    rangeGestureActive = true
                    selectionMode = true
                },
                isSelected = { it in selected },
                onRange = { range, shouldSelect ->
                    selected = if (shouldSelect) {
                        rangeBase + range
                    } else {
                        rangeBase - range.toSet()
                    }
                    selectionMode = keepFilesSelectionMode(
                        rangeSelectionActive = rangeGestureActive,
                        selectedFileCount = selected.size,
                        selectedFolderCount = 0
                    )
                },
                onEnd = {
                    rangeGestureActive = false
                    selectionMode = keepFilesSelectionMode(
                        rangeSelectionActive = false,
                        selectedFileCount = selected.size,
                        selectedFolderCount = 0
                    )
                }
            )
            Column {
                Text(
                    text = selected.size.toString(),
                    modifier = Modifier.testTag("selected-count")
                )
                Text(
                    text = selectionMode.toString(),
                    modifier = Modifier.testTag("selection-mode")
                )
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(1),
                    modifier = Modifier
                        .size(width = 180.dp, height = 220.dp)
                        .testTag("selection-grid")
                        .then(dragSelect)
                ) {
                    items((0 until 5).toList()) { index ->
                        Box(
                            Modifier
                                .size(width = 180.dp, height = 60.dp)
                                .background(if (index in selected) Color.Blue else Color.Gray)
                                .testTag("item-$index")
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag("selection-grid").performMouseInput {
            moveTo(androidx.compose.ui.geometry.Offset(50f, 30f))
            press()
            moveTo(androidx.compose.ui.geometry.Offset(50f, 150f), delayMillis = 100)
            release()
        }
        rule.waitForIdle()

        rule.onNodeWithTag("selected-count").assertTextEquals("3")
        rule.onNodeWithTag("selection-mode").assertTextEquals("true")
        assertEquals(setOf(0, 1, 2), selected)
        assertFalse(rangeGestureActive)
        assertTrue(selectionMode)
    }
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun `mouse drag adds to an existing Files selection across real row gestures`() {
        var selected by mutableStateOf(setOf(0, 1))
        var rangeBase by mutableStateOf(setOf(0, 1))
        var selectionMode by mutableStateOf(true)
        var rangeGestureActive by mutableStateOf(false)
        var clicks = 0
        var longClicks = 0

        rule.setContent {
            val gridState = rememberLazyGridState()
            val dragSelect = rememberDragSelect(
                gridState = gridState,
                onStart = {
                    rangeBase = selected
                    rangeGestureActive = true
                    selectionMode = true
                },
                isSelected = { it in selected },
                onRange = { range, shouldSelect ->
                    selected = if (shouldSelect) {
                        rangeBase + range
                    } else {
                        rangeBase - range.toSet()
                    }
                    selectionMode = keepFilesSelectionMode(
                        rangeSelectionActive = rangeGestureActive,
                        selectedFileCount = selected.size,
                        selectedFolderCount = 0
                    )
                },
                onEnd = {
                    rangeGestureActive = false
                    selectionMode = keepFilesSelectionMode(
                        rangeSelectionActive = false,
                        selectedFileCount = selected.size,
                        selectedFolderCount = 0
                    )
                },
                mouseDragAddsToSelection = true,
                resolveNearestVisibleItem = true
            )
            Column {
                Text(
                    text = selected.size.toString(),
                    modifier = Modifier.testTag("files-selected-count")
                )
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(1),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .size(width = 180.dp, height = 220.dp)
                        .testTag("files-selection-grid")
                        .then(dragSelect)
                ) {
                    items((0 until 5).toList()) { index ->
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(60.dp)
                                .background(if (index in selected) Color.Blue else Color.Gray)
                                .testTag("files-row-$index")
                                .fileTabDragSource { "test-file-$index" }
                                .combinedClickable(
                                    onClick = { clicks++ },
                                    onLongClick = { longClicks++ }
                                )
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag("files-selection-grid").performMouseInput {
            moveTo(Offset(100f, 30f))
            press()
            // Cross a row gap and continue into the third row. The gap must not terminate
            // the range, and an already-selected anchor must not invert it into deselection.
            moveTo(Offset(100f, 65f), delayMillis = 50)
            moveTo(Offset(100f, 95f), delayMillis = 50)
            moveTo(Offset(100f, 155f), delayMillis = 50)
            release()
        }
        rule.waitForIdle()

        rule.onNodeWithTag("files-selected-count").assertTextEquals("3")
        assertEquals(setOf(0, 1, 2), selected)
        assertFalse(rangeGestureActive)
        assertTrue(selectionMode)
        assertEquals(0, clicks)
        assertEquals(0, longClicks)
    }

}
