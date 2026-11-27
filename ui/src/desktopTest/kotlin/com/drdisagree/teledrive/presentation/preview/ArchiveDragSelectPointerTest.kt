package com.drdisagree.teledrive.presentation.preview

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * Composed pointer tests for the archive (zip viewer) list's drag selection — the
 * regression suite for the vc178 defect "long-pressing visible row N selects row
 * N+1; the last row selects nothing" (English.zip, flat, 7 audio files).
 *
 * The archive harness replicates ArchiveList's REAL configuration (PreviewScreen.kt):
 * the LIST variant of rememberDragSelect with resolveNearestVisibleItem = true, a
 * LazyColumn emitting header item / data items keyed by the real archiveRowKey /
 * hint item, the preview content padding class (~100.dp top: system bars +
 * PreviewTopBarHeight 64.dp + 8.dp), Arrangement.spacedBy(4.dp), 80.dp rows (12.dp
 * vertical padding around the 56.dp row content — the vc183 item 6 thumbnail slot,
 * previously 72.dp rows around 48.dp content), and the REAL translation
 * callbacks (archiveDragAnchorSelected / applyArchiveDragRange) over rows built by
 * the REAL tree/flatten code. The screen and this test consume ONE plan: the tree,
 * flatten, key and translation code lives in :ui (ArchiveNode.kt) and is used by
 * both PreviewScreen (Android) and this test.
 *
 * Root cause these tests pin: LazyListItemInfo.offset / LazyGridItemInfo.offset are
 * measured from the start of the lazy layout's CONTENT — they exclude the
 * before-content padding — while pointer positions are measured from the
 * container's edge and include it. DragSelect's hit tests compared the two spaces
 * directly, so every hit landed beforeContentPadding too low: with the archive's
 * ~100.dp top padding against an 84.dp row stride that is still over one row too low,
 * and a press on the last row resolves to the trailing hint item (no row). The fix
 * translates the pointer into the items' space inside DragSelect's hit tests.
 *
 * Aiming: presses land on each row's DRAWN centre, taken from the row semantics
 * node's boundsInRoot relative to the list node's top-left — where a user's finger
 * lands. layoutInfo offsets are never used to aim.
 *
 * Gestures are single-call (down → advanceEventTime past the long-press timeout →
 * optional move → up): on this branch's compose-ui-test version a held pointer
 * keeps DragSelect's per-frame paint loop alive, so the staged down/waitForIdle
 * idiom either never fires the long-press or never goes idle. Mid-gesture states
 * are asserted through the recorded paint history instead.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalFoundationApi::class)
class ArchiveDragSelectPointerTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- archive harness state (fresh per test: JUnit instantiates per method) ----
    private var selectedEntries by mutableStateOf(emptySet<Int>())
    private var selectedFolders by mutableStateOf(emptySet<String>())
    private var rangeBaseEntries by mutableStateOf<Set<Int>?>(null)
    private var rangeBaseFolders by mutableStateOf<Set<String>?>(null)
    private val paintHistory = mutableListOf<Pair<Set<Int>, Set<String>>>()

    private var listStateRef: LazyListState? = null
    private var scrollScopeRef: CoroutineScope? = null

    private fun flatEntries(count: Int): List<ArchiveEntry> =
        (1..count).map { i ->
            ArchiveEntry(
                name = "track0$i.mp3",
                sizeBytes = 1_000L * i,
                compressedBytes = 900L * i,
                isDirectory = false
            )
        }

    private fun rowsFor(
        entries: List<ArchiveEntry>,
        expanded: Set<String> = emptySet()
    ): List<ArchiveRowState> = flattenArchive(
        buildArchiveTreeIndexed(entries.mapIndexed { index, entry -> IndexedArchiveEntry(index, entry) }),
        expanded
    )

    /** ArchiveList's exact composition shape; [rows] come from the real flatten code. */
    private fun setArchiveContent(rows: List<ArchiveRowState>) {
        rule.setContent {
            val listState = rememberLazyListState()
            listStateRef = listState
            scrollScopeRef = rememberCoroutineScope()
            val dragSelect = rememberDragSelect(
                listState = listState,
                onStart = {
                    rangeBaseEntries = selectedEntries
                    rangeBaseFolders = selectedFolders
                },
                isSelected = { listIndex ->
                    archiveDragAnchorSelected(rows, listIndex, selectedEntries, selectedFolders)
                },
                onRange = { range, shouldSelect ->
                    val painted = applyArchiveDragRange(
                        rows = rows,
                        listRange = range,
                        baseEntries = rangeBaseEntries ?: selectedEntries,
                        baseFolders = rangeBaseFolders ?: selectedFolders,
                        shouldSelect = shouldSelect
                    )
                    selectedEntries = painted.entries
                    selectedFolders = painted.folders
                    paintHistory += painted.entries to painted.folders
                },
                onEnd = {
                    rangeBaseEntries = null
                    rangeBaseFolders = null
                },
                resolveNearestVisibleItem = true
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(width = 360.dp, height = 700.dp)
                    .testTag(ARCHIVE_LIST_TAG)
                    .then(dragSelect),
                // previewContentPadding(): systemBars + top = PreviewTopBarHeight
                // (64.dp) + 8.dp; on a typical phone the top inset totals ~100.dp.
                contentPadding = PaddingValues(start = 16.dp, top = 100.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    // Header card slot (ArchiveList's Card + its bottom 12.dp padding).
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .height(190.dp)
                    )
                }
                items(rows, key = { row -> archiveRowKey(row) }) { row ->
                    val listIndex = rows.indexOf(row) + ARCHIVE_LIST_HEADER_ITEMS
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .testTag("archive-row-$listIndex")
                            // ArchiveRow: 12.dp vertical padding around 56.dp content
                            // (the vc183 item 6 thumbnail slot sets the row height).
                            .height(80.dp)
                            // Real rows absorb the long-press as a no-op long-click.
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
                item {
                    // Trailing hint slot.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp, bottom = 96.dp)
                            .height(20.dp)
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The drawn centre of the item at [listIndex], in list-node-local pixels. */
    private fun drawnCenter(listTag: String, rowTag: String): Offset {
        val listBounds = rule.onNodeWithTag(listTag).fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
        return rowBounds.center - listBounds.topLeft
    }

    private fun scrollToListIndex(listIndex: Int) {
        val state = listStateRef ?: throw AssertionError("no list state")
        val scope = scrollScopeRef ?: throw AssertionError("no scope")
        rule.runOnIdle { scope.launch { state.scrollToItem(listIndex) } }
        rule.waitForIdle()
    }

    /** One full long-press-and-release on the archive item at [listIndex]. */
    private fun longPressArchiveRow(listIndex: Int) {
        val center = drawnCenter(ARCHIVE_LIST_TAG, "archive-row-$listIndex")
        rule.onNodeWithTag(ARCHIVE_LIST_TAG).performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_ADVANCE_MS)
            up()
        }
        rule.waitForIdle()
    }

    private fun resetSelection() {
        rule.runOnIdle {
            selectedEntries = emptySet()
            selectedFolders = emptySet()
            rangeBaseEntries = null
            rangeBaseFolders = null
            paintHistory.clear()
        }
        rule.waitForIdle()
    }

    private fun layoutInfoSummary(): String {
        val state = listStateRef ?: return "<no state>"
        return state.layoutInfo.visibleItemsInfo.joinToString(prefix = "[", postfix = "]") {
            "#${it.index}@${it.offset}+${it.size}"
        }
    }

    // ---------------------------------------------------------------- archive

    @Test
    fun `archive flat list - long press selects exactly the pressed row`() {
        val entries = flatEntries(7)
        val rows = rowsFor(entries)
        setArchiveContent(rows)
        rows.forEachIndexed { rowIndex, row ->
            val listIndex = rowIndex + ARCHIVE_LIST_HEADER_ITEMS
            scrollToListIndex(listIndex)
            resetSelection()
            longPressArchiveRow(listIndex)
            assertEquals(
                "long-press on displayed row $rowIndex (${row.node.name}, list item $listIndex) " +
                    "selected the wrong entries; layoutInfo=${layoutInfoSummary()}",
                setOf(row.node.entryIndex),
                selectedEntries
            )
            assertEquals(
                "long-press on displayed row $rowIndex selected folders",
                emptySet<String>(),
                selectedFolders
            )
            assertEquals(
                "first paint of the row $rowIndex press was not the pressed row alone",
                setOf(row.node.entryIndex) to emptySet<String>(),
                paintHistory.first()
            )
        }
    }

    @Test
    fun `archive flat list - drag paint selects exactly the crossed rows`() {
        val entries = flatEntries(7)
        val rows = rowsFor(entries)
        setArchiveContent(rows)
        // Rows 1..3 (displayed) are all visible without scrolling in this viewport.
        val start = drawnCenter(ARCHIVE_LIST_TAG, "archive-row-${1 + ARCHIVE_LIST_HEADER_ITEMS}")
        val end = drawnCenter(ARCHIVE_LIST_TAG, "archive-row-${3 + ARCHIVE_LIST_HEADER_ITEMS}")
        rule.onNodeWithTag(ARCHIVE_LIST_TAG).performTouchInput {
            down(start)
            advanceEventTime(LONG_PRESS_ADVANCE_MS)
            moveBy(Offset(0f, end.y - start.y))
            up()
        }
        rule.waitForIdle()
        assertEquals(
            "drag from displayed row 1 to row 3 painted the wrong entries; " +
                "layoutInfo=${layoutInfoSummary()}",
            setOf(rows[1].node.entryIndex, rows[2].node.entryIndex, rows[3].node.entryIndex),
            selectedEntries
        )
        assertEquals(
            "first paint of the drag was not the anchor row alone",
            setOf(rows[1].node.entryIndex) to emptySet<String>(),
            paintHistory.first()
        )
    }

    @Test
    fun `archive expanded folder - long press selects exactly the pressed row`() {
        val entries = listOf(
            ArchiveEntry("band/one.mp3", 1_000, 900, false),
            ArchiveEntry("band/two.mp3", 2_000, 1_800, false),
            ArchiveEntry("band/three.mp3", 3_000, 2_700, false),
            ArchiveEntry("loose.mp3", 4_000, 3_600, false)
        )
        val rows = rowsFor(entries, expanded = setOf("band"))
        // Displayed rows: [folder band, one, two, three, loose]
        assertEquals(5, rows.size)
        setArchiveContent(rows)
        rows.forEachIndexed { rowIndex, row ->
            val listIndex = rowIndex + ARCHIVE_LIST_HEADER_ITEMS
            scrollToListIndex(listIndex)
            resetSelection()
            longPressArchiveRow(listIndex)
            if (row.node.isDirectory) {
                assertEquals(
                    "long-press on folder row $rowIndex selected the wrong folders; " +
                        "layoutInfo=${layoutInfoSummary()}",
                    setOf(row.node.path),
                    selectedFolders
                )
                assertEquals(emptySet<Int>(), selectedEntries)
            } else {
                assertEquals(
                    "long-press on displayed row $rowIndex (${row.node.name}) selected the " +
                        "wrong entries; layoutInfo=${layoutInfoSummary()}",
                    setOf(row.node.entryIndex),
                    selectedEntries
                )
                assertEquals(emptySet<String>(), selectedFolders)
            }
        }
    }

    // ------------------------------------------------------- helper-level pins

    @Test
    fun `list with large top padding - long press selects exactly the pressed row`() {
        // The helper defect in isolation: LIST variant, no header, direct indexing,
        // 100.dp top padding against 60.dp rows (padding > one full row stride).
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
                    .testTag(PADDED_LIST_TAG)
                    .then(dragSelect),
                contentPadding = PaddingValues(top = 100.dp)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("padded-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        rule.waitForIdle()
        (0 until 5).forEach { index ->
            rule.runOnIdle { selected = emptySet(); rangeBase = emptySet() }
            rule.waitForIdle()
            val center = drawnCenter(PADDED_LIST_TAG, "padded-row-$index")
            rule.onNodeWithTag(PADDED_LIST_TAG).performTouchInput {
                down(center)
                advanceEventTime(LONG_PRESS_ADVANCE_MS)
                up()
            }
            rule.waitForIdle()
            assertEquals(
                "padded-list long-press on row $index selected the wrong rows",
                setOf(index),
                selected
            )
        }
    }

    @Test
    fun `grid with large top padding - long press selects exactly the pressed row`() {
        // The GRID variant shared the same coordinate-space flaw (its item offsets
        // also exclude the before-content padding); Files/Trash use small paddings,
        // so it was invisible there. Pin the grid hit test at archive-scale padding.
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
                    .testTag(PADDED_GRID_TAG)
                    .then(dragSelect),
                contentPadding = PaddingValues(top = 100.dp)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(60.dp)
                            .testTag("padded-grid-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        rule.waitForIdle()
        (0 until 5).forEach { index ->
            rule.runOnIdle { selected = emptySet(); rangeBase = emptySet() }
            rule.waitForIdle()
            val center = drawnCenter(PADDED_GRID_TAG, "padded-grid-row-$index")
            rule.onNodeWithTag(PADDED_GRID_TAG).performTouchInput {
                down(center)
                advanceEventTime(LONG_PRESS_ADVANCE_MS)
                up()
            }
            rule.waitForIdle()
            assertEquals(
                "padded-grid long-press on row $index selected the wrong rows",
                setOf(index),
                selected
            )
        }
    }

    @Test
    fun `picker shaped list - long press selects exactly the pressed row`() {
        // The picker dialog's list configuration (AndroidUploadPickerDialog): headerless,
        // direct indexing, PaddingValues(horizontal 12.dp, vertical 6.dp), spacedBy(2.dp),
        // resolveNearestVisibleItem = true and processReleasePosition = true. Its 6.dp
        // padding kept the coordinate flaw sub-row; the fix must keep it exact.
        var pickerSelected by mutableStateOf(emptySet<Int>())
        var pickerBase by mutableStateOf(emptySet<Int>())
        rule.setContent {
            val listState = rememberLazyListState()
            val dragSelect = rememberDragSelect(
                listState = listState,
                onStart = { pickerBase = pickerSelected },
                isSelected = { index -> index in pickerSelected },
                onRange = { range, shouldSelect ->
                    pickerSelected = if (shouldSelect) {
                        pickerBase + range
                    } else {
                        pickerBase - range.toSet()
                    }
                },
                onEnd = {},
                resolveNearestVisibleItem = true,
                processReleasePosition = true
            )
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(width = 360.dp, height = 400.dp)
                    .testTag(PICKER_LIST_TAG)
                    .then(dragSelect),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .testTag("picker-row-$index")
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        rule.waitForIdle()
        (0 until 5).forEach { index ->
            rule.runOnIdle { pickerSelected = emptySet(); pickerBase = emptySet() }
            rule.waitForIdle()
            val center = drawnCenter(PICKER_LIST_TAG, "picker-row-$index")
            rule.onNodeWithTag(PICKER_LIST_TAG).performTouchInput {
                down(center)
                advanceEventTime(LONG_PRESS_ADVANCE_MS)
                up()
            }
            rule.waitForIdle()
            assertEquals(
                "picker-shaped long-press on row $index selected the wrong rows",
                setOf(index),
                pickerSelected
            )
        }
    }

    private companion object {
        const val ARCHIVE_LIST_TAG = "archive-shaped-list"
        const val PADDED_LIST_TAG = "padded-list"
        const val PADDED_GRID_TAG = "padded-grid"
        const val PICKER_LIST_TAG = "picker-shaped-list"
        const val LONG_PRESS_ADVANCE_MS = 1_000L
    }
}
