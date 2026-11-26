package com.drdisagree.teledrive.presentation.transfers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Composed pointer tests for the Transfers list's NEW drag selection
 * (1.6.24, field note 3). The harness replicates TransfersScreen's exact
 * wiring: the shared list-variant rememberDragSelect
 * (mouseDragAddsToSelection = false, resolveNearestVisibleItem = true) over
 * a LazyColumn whose items are the toolbar slot, a section header slot and
 * row slots, translated through the screen's own helpers
 * (transferDragAnchorSelected / applyTransferDragRange) over a listRows
 * list parallel to the items — the same one-plan shape as the ZIP viewer's
 * ArchiveDragSelectPointerTest.
 *
 * Presses land on each row's drawn centre, taken from the row semantics
 * node's boundsInRoot relative to the list node's top-left.
 */
@OptIn(ExperimentalTestApi::class)
class TransfersDragSelectPointerTest {

    @get:Rule
    val rule = createComposeRule()

    private var selected by mutableStateOf(emptySet<String>())
    private var dragBase by mutableStateOf(emptySet<String>())

    private fun task(id: String) = TransferTask(
        id = id,
        type = TransferType.DOWNLOAD,
        fileId = "file-$id",
        displayName = "$id.bin",
        sizeBytes = 100,
        transferredBytes = 0,
        state = TransferState.FAILED,
        priority = 0,
        errorMessage = null,
        speedBytesPerSecond = 0,
        createdAt = 1,
        updatedAt = 1,
        completedAt = null
    )

    // Slots: 0 toolbar, 1 header, 2..4 rows a/b/c, 5 header, 6..7 rows d/e.
    private val listRows: List<TransferTask?> = listOf(
        null, null, task("a"), task("b"), task("c"), null, task("d"), task("e")
    )

    private fun setContent() {
        rule.setContent {
            val listState = rememberLazyListState()
            val dragSelect = rememberDragSelect(
                listState = listState,
                onStart = { dragBase = selected },
                isSelected = { listIndex ->
                    transferDragAnchorSelected(listRows, listIndex, selected)
                },
                onRange = { range, shouldSelect ->
                    selected = applyTransferDragRange(listRows, range, dragBase, shouldSelect)
                },
                onEnd = { },
                mouseDragAddsToSelection = false,
                resolveNearestVisibleItem = true
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.size(400.dp, 640.dp).then(dragSelect).testTag("list"),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Box(Modifier.fillMaxWidth().height(40.dp).testTag("toolbar")) }
                item { Text("Active", Modifier.testTag("header-1")) }
                listOf("a", "b", "c").forEach { id ->
                    item {
                        Box(Modifier.fillMaxWidth().height(60.dp).testTag("row-$id")) {
                            Text(id)
                        }
                    }
                }
                item { Text("Failed", Modifier.testTag("header-2")) }
                listOf("d", "e").forEach { id ->
                    item {
                        Box(Modifier.fillMaxWidth().height(60.dp).testTag("row-$id")) {
                            Text(id)
                        }
                    }
                }
            }
        }
    }

    private fun rowCenter(tag: String): Offset {
        val listBounds = rule.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        return Offset(
            x = rowBounds.left + rowBounds.width / 2f - listBounds.left,
            y = rowBounds.top + rowBounds.height / 2f - listBounds.top
        )
    }

    private fun mouseDrag(from: Offset, to: Offset) {
        rule.onNodeWithTag("list").performMouseInput {
            moveTo(from)
            press()
            moveTo(to, delayMillis = 100)
            release()
        }
        rule.waitForIdle()
    }

    @Test
    fun `mouse drag selects every row crossed, across a section header`() {
        setContent()
        mouseDrag(rowCenter("row-a"), rowCenter("row-d"))
        assertEquals(setOf("a", "b", "c", "d"), selected)
    }

    @Test
    fun `mouse drag starting on a selected row deselects the crossed range`() {
        selected = setOf("a", "b", "c")
        setContent()
        mouseDrag(rowCenter("row-a"), rowCenter("row-b"))
        assertEquals(setOf("c"), selected)
    }

    @Test
    fun `mouse drag over only the toolbar and a header selects nothing`() {
        setContent()
        mouseDrag(rowCenter("toolbar"), rowCenter("header-1"))
        assertEquals(emptySet<String>(), selected)
    }
}
