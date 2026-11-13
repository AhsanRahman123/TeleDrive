package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
}
