package com.drdisagree.teledrive.presentation.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Bisect probe for the archive list drag-select offset: one ingredient at a time,
 * starting from the exact grid harness shape that DragSelectPointerTest proves.
 * Aiming is via semantics bounds (row node bounds minus list node top-left), so no
 * hand-rolled coordinate math is involved.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class DragSelectVariantProbeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun pressRowViaSemantics(listTag: String, rowTag: String) {
        val listBounds = rule.onNodeWithTag(listTag).fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
        val center = rowBounds.center - listBounds.topLeft
        rule.onNodeWithTag(listTag).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            up()
        }
        rule.waitForIdle()
    }

    private fun pressAndReleaseViaSemantics(listTag: String, rowTag: String) {
        val listBounds = rule.onNodeWithTag(listTag).fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
        val center = rowBounds.center - listBounds.topLeft
        rule.onNodeWithTag(listTag).performTouchInput {
            down(center)
            advanceEventTime(1_000)
            up()
        }
        rule.waitForIdle()
    }


    private fun release(listTag: String) {
        rule.onNodeWithTag(listTag).performTouchInput { up() }
        rule.waitForIdle()
    }

    @Test
    fun `v0 grid no padding - control`() {
        var selected by mutableStateOf(emptySet<Int>())
        var rangeBase by mutableStateOf(emptySet<Int>())
        rule.setContent {
            val gridState = rememberLazyGridState()
            val dragSelect = rememberDragSelect(
                gridState = gridState,
                onStart = { rangeBase = selected },
                isSelected = { it in selected },
                onRange = { range, shouldSelect ->
                    selected = if (shouldSelect) rangeBase + range else rangeBase - range.toSet()
                },
                onEnd = {},
                resolveNearestVisibleItem = true
            )
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(1),
                modifier = Modifier
                    .size(width = 180.dp, height = 360.dp)
                    .testTag("v0-grid")
                    .then(dragSelect)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("v0-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        pressAndReleaseViaSemantics("v0-grid", "v0-row-0")
        assertEquals("v0 grid control (single-call hold+release)", setOf(0), selected)
    }

    @Test
    fun `v1 list no padding no header`() {
        var selected by mutableStateOf(emptySet<Int>())
        var rangeBase by mutableStateOf(emptySet<Int>())
        rule.setContent {
            val listState = rememberLazyListState()
            val dragSelect = rememberDragSelect(
                listState = listState,
                onStart = { rangeBase = selected },
                isSelected = { it in selected },
                onRange = { range, shouldSelect ->
                    selected = if (shouldSelect) rangeBase + range else rangeBase - range.toSet()
                },
                onEnd = {},
                resolveNearestVisibleItem = true
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(width = 180.dp, height = 360.dp)
                    .testTag("v1-list")
                    .then(dragSelect)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("v1-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        pressRowViaSemantics("v1-list", "v1-row-0")
        assertEquals("v1 list plain", setOf(0), selected)
    }

    @Test
    fun `v2 list top padding 100 no header`() {
        var selected by mutableStateOf(emptySet<Int>())
        var rangeBase by mutableStateOf(emptySet<Int>())
        rule.setContent {
            val listState = rememberLazyListState()
            val dragSelect = rememberDragSelect(
                listState = listState,
                onStart = { rangeBase = selected },
                isSelected = { it in selected },
                onRange = { range, shouldSelect ->
                    selected = if (shouldSelect) rangeBase + range else rangeBase - range.toSet()
                },
                onEnd = {},
                resolveNearestVisibleItem = true
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(width = 180.dp, height = 360.dp)
                    .testTag("v2-list")
                    .then(dragSelect),
                contentPadding = PaddingValues(top = 100.dp)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("v2-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        pressRowViaSemantics("v2-list", "v2-row-0")
        assertEquals("v2 list + top padding 100", setOf(0), selected)
    }

    @Test
    fun `v3 list top padding 100 with header item`() {
        var selected by mutableStateOf(emptySet<Int>())
        var rangeBase by mutableStateOf(emptySet<Int>())
        rule.setContent {
            val listState = rememberLazyListState()
            val dragSelect = rememberDragSelect(
                listState = listState,
                onStart = { rangeBase = selected },
                isSelected = { listIndex -> (listIndex - 1) in selected },
                onRange = { range, shouldSelect ->
                    val rows = range.mapNotNull { li -> (li - 1).takeIf { it in 0 until 5 } }
                    selected = if (shouldSelect) rangeBase + rows else rangeBase - rows.toSet()
                },
                onEnd = {},
                resolveNearestVisibleItem = true
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(width = 180.dp, height = 560.dp)
                    .testTag("v3-list")
                    .then(dragSelect),
                contentPadding = PaddingValues(top = 100.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    Box(Modifier.fillMaxWidth().height(190.dp))
                }
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .testTag("v3-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        pressRowViaSemantics("v3-list", "v3-row-0")
        assertEquals("v3 list + padding + header", setOf(0), selected)
    }

    @Test
    fun `v4 grid top padding 100`() {
        var selected by mutableStateOf(emptySet<Int>())
        var rangeBase by mutableStateOf(emptySet<Int>())
        rule.setContent {
            val gridState = rememberLazyGridState()
            val dragSelect = rememberDragSelect(
                gridState = gridState,
                onStart = { rangeBase = selected },
                isSelected = { it in selected },
                onRange = { range, shouldSelect ->
                    selected = if (shouldSelect) rangeBase + range else rangeBase - range.toSet()
                },
                onEnd = {},
                resolveNearestVisibleItem = true
            )
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(1),
                modifier = Modifier
                    .size(width = 180.dp, height = 360.dp)
                    .testTag("v4-grid")
                    .then(dragSelect),
                contentPadding = PaddingValues(top = 100.dp)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("v4-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        pressRowViaSemantics("v4-grid", "v4-row-0")
        assertEquals("v4 grid + top padding 100", setOf(0), selected)
    }
}
