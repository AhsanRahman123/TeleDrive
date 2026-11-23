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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
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
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

/**
 * Composed pointer tests for the archive (zip viewer) list's drag selection.
 *
 * The harness replicates ArchiveList's REAL configuration (PreviewScreen.kt): the LIST
 * variant of rememberDragSelect with resolveNearestVisibleItem = true, a LazyColumn
 * emitting header item / data items keyed by the real archiveRowKey / hint item, the
 * preview content padding class (~100.dp top), Arrangement.spacedBy(4.dp), 72.dp rows
 * (12.dp vertical padding around the constant 48.dp row content), and the REAL
 * translation callbacks (archiveDragAnchorSelected / applyArchiveDragRange) over rows
 * built by the REAL tree/flatten code — the screen and this test consume one plan.
 *
 * Presses are aimed at each row's DRAWN centre: the row's LayoutCoordinates captured
 * with onGloballyPositioned, relative to the LazyColumn's own coordinates. That is
 * where a user's finger lands; layoutInfo offsets are never used to aim.
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

    private var listStateRef: LazyListState? = null
    private var scrollScopeRef: CoroutineScope? = null
    private var listCoords: LayoutCoordinates? = null
    private val itemCoordsByListIndex = mutableMapOf<Int, LayoutCoordinates>()

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
        itemCoordsByListIndex.clear()
        listCoords = null
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
                    .onGloballyPositioned { listCoords = it }
                    .then(dragSelect),
                // previewContentPadding(): systemBars + top = PreviewTopBarHeight (64.dp)
                // + 8.dp; on a typical phone the top inset totals ~100.dp.
                contentPadding = PaddingValues(horizontal = 16.dp, top = 100.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                item {
                    // Header card slot (ArchiveList's Card + its bottom 12.dp padding).
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp)
                            .height(190.dp)
                            .onGloballyPositioned { itemCoordsByListIndex[0] = it }
                    )
                }
                items(rows, key = { row -> archiveRowKey(row) }) { row ->
                    val listIndex = rows.indexOf(row) + ARCHIVE_LIST_HEADER_ITEMS
                    Box(
                        Modifier
                            .fillMaxWidth()
                            // ArchiveRow: 12.dp vertical padding around 48.dp content.
                            .height(72.dp)
                            .onGloballyPositioned { itemCoordsByListIndex[listIndex] = it }
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
                            .onGloballyPositioned {
                                itemCoordsByListIndex[rows.size + ARCHIVE_LIST_HEADER_ITEMS] = it
                            }
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    private fun drawnCenter(listIndex: Int): Offset {
        val coords = itemCoordsByListIndex[listIndex]
            ?: fail("list item $listIndex is not composed; composed=${itemCoordsByListIndex.keys.sorted()}")
        val list = listCoords ?: fail("list coordinates not captured")
        return coords.positionInRoot() - list.positionInRoot() +
            Offset(coords.size.width / 2f, coords.size.height / 2f)
    }

    private fun scrollToListIndex(listIndex: Int) {
        val state = listStateRef ?: fail("no list state")
        val scope = scrollScopeRef ?: fail("no scope")
        rule.runOnIdle { scope.launch { state.scrollToItem(listIndex) } }
        rule.waitForIdle()
    }

    private fun longPressListIndex(listIndex: Int) {
        val center = drawnCenter(listIndex)
        rule.onNodeWithTag(ARCHIVE_LIST_TAG).performTouchInput { down(center) }
        rule.waitForIdle()
    }

    private fun releasePointer() {
        rule.onNodeWithTag(ARCHIVE_LIST_TAG).performTouchInput { up() }
        rule.waitForIdle()
    }

    private fun resetSelection() {
        rule.runOnIdle {
            selectedEntries = emptySet()
            selectedFolders = emptySet()
            rangeBaseEntries = null
            rangeBaseFolders = null
        }
        rule.waitForIdle()
    }

    private fun layoutInfoSummary(): String {
        val state = listStateRef ?: return "<no state>"
        return state.layoutInfo.visibleItemsInfo.joinToString(prefix = "[", postfix = "]") {
            "#${it.index}@${it.offset}+${it.size}"
        }
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun `diagnostic - list item offsets versus drawn positions`() {
        val rows = rowsFor(flatEntries(7))
        setArchiveContent(rows)
        val state = listStateRef!!
        val list = listCoords!!
        val mismatches = mutableListOf<String>()
        state.layoutInfo.visibleItemsInfo.forEach { info ->
            val coords = itemCoordsByListIndex[info.index]
            if (coords == null) {
                mismatches += "#${info.index}: no drawn coordinates captured"
            } else {
                val drawnTop = (coords.positionInRoot() - list.positionInRoot()).y
                if (drawnTop != info.offset.toFloat()) {
                    mismatches += "#${info.index}: layoutInfo.offset=${info.offset} size=${info.size} " +
                        "drawnTop=$drawnTop delta=${drawnTop - info.offset}"
                }
            }
        }
        assertTrue(
            "visibleItemsInfo offsets disagree with drawn positions: $mismatches " +
                "(beforeContentPadding=${state.layoutInfo.beforeContentPadding})",
            mismatches.isEmpty()
        )
    }

    @Test
    fun `diagnostic - grid item offsets versus drawn positions`() {
        val gridCoordsByIndex = mutableMapOf<Int, LayoutCoordinates>()
        var gridListCoords: LayoutCoordinates? = null
        var gridStateRef: androidx.compose.foundation.lazy.grid.LazyGridState? = null
        rule.setContent {
            val gridState = rememberLazyGridState()
            gridStateRef = gridState
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(1),
                modifier = Modifier
                    .size(width = 360.dp, height = 700.dp)
                    .onGloballyPositioned { gridListCoords = it },
                contentPadding = PaddingValues(horizontal = 16.dp, top = 100.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(7) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .onGloballyPositioned { gridCoordsByIndex[index] = it }
                    )
                }
            }
        }
        rule.waitForIdle()
        val state = gridStateRef!!
        val list = gridListCoords!!
        val mismatches = mutableListOf<String>()
        state.layoutInfo.visibleItemsInfo.forEach { info ->
            val coords = gridCoordsByIndex[info.index]
            if (coords == null) {
                mismatches += "#${info.index}: no drawn coordinates captured"
            } else {
                val drawnTop = (coords.positionInRoot() - list.positionInRoot()).y
                if (drawnTop != info.offset.y.toFloat()) {
                    mismatches += "#${info.index}: layoutInfo.offset=${info.offset} drawnTop=$drawnTop " +
                        "delta=${drawnTop - info.offset.y}"
                }
            }
        }
        assertTrue(
            "grid visibleItemsInfo offsets disagree with drawn positions: $mismatches",
            mismatches.isEmpty()
        )
    }

    // ---------------------------------------------------------------- behaviour

    @Test
    fun `archive flat list - long press selects exactly the pressed row`() {
        val entries = flatEntries(7)
        val rows = rowsFor(entries)
        setArchiveContent(rows)
        rows.forEachIndexed { rowIndex, row ->
            val listIndex = rowIndex + ARCHIVE_LIST_HEADER_ITEMS
            scrollToListIndex(listIndex)
            resetSelection()
            longPressListIndex(listIndex)
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
            releasePointer()
            assertEquals(
                "selection changed on release after pressing row $rowIndex",
                setOf(row.node.entryIndex),
                selectedEntries
            )
        }
    }

    @Test
    fun `archive flat list - drag paint selects exactly the crossed rows`() {
        val entries = flatEntries(7)
        val rows = rowsFor(entries)
        setArchiveContent(rows)
        // Rows 1..3 (displayed) are all visible without scrolling in this viewport.
        val startListIndex = 1 + ARCHIVE_LIST_HEADER_ITEMS
        val endListIndex = 3 + ARCHIVE_LIST_HEADER_ITEMS
        val start = drawnCenter(startListIndex)
        val end = drawnCenter(endListIndex)
        rule.onNodeWithTag(ARCHIVE_LIST_TAG).performTouchInput { down(start) }
        rule.waitForIdle()
        assertEquals(setOf(rows[1].node.entryIndex), selectedEntries)
        rule.onNodeWithTag(ARCHIVE_LIST_TAG).performTouchInput {
            moveBy(Offset(0f, end.y - start.y))
        }
        rule.waitForIdle()
        assertEquals(
            "drag from displayed row 1 to row 3 painted the wrong entries; " +
                "layoutInfo=${layoutInfoSummary()}",
            setOf(rows[1].node.entryIndex, rows[2].node.entryIndex, rows[3].node.entryIndex),
            selectedEntries
        )
        releasePointer()
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
            longPressListIndex(listIndex)
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
            releasePointer()
        }
    }

    @Test
    fun `picker shaped list - long press selects exactly the pressed row`() {
        // The picker dialog's list configuration (AndroidUploadPickerDialog): headerless,
        // direct indexing, PaddingValues(horizontal 12.dp, vertical 6.dp), spacedBy(2.dp),
        // resolveNearestVisibleItem = true and processReleasePosition = true.
        var pickerSelected by mutableStateOf(emptySet<Int>())
        var pickerBase by mutableStateOf(emptySet<Int>())
        val pickerCoords = mutableMapOf<Int, LayoutCoordinates>()
        var pickerListCoords: LayoutCoordinates? = null
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
                    .onGloballyPositioned { pickerListCoords = it }
                    .then(dragSelect),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items((0 until 5).toList()) { index ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .onGloballyPositioned { pickerCoords[index] = it }
                            .combinedClickable(onClick = {}, onLongClick = {})
                    )
                }
            }
        }
        rule.waitForIdle()
        (0 until 5).forEach { index ->
            rule.runOnIdle { pickerSelected = emptySet(); pickerBase = emptySet() }
            rule.waitForIdle()
            val coords = pickerCoords[index] ?: fail("picker row $index not composed")
            val list = pickerListCoords ?: fail("picker list coordinates not captured")
            val center = coords.positionInRoot() - list.positionInRoot() +
                Offset(coords.size.width / 2f, coords.size.height / 2f)
            rule.onNodeWithTag(PICKER_LIST_TAG).performTouchInput { down(center) }
            rule.waitForIdle()
            assertEquals(
                "picker-shaped long-press on row $index selected the wrong rows",
                setOf(index),
                pickerSelected
            )
            rule.onNodeWithTag(PICKER_LIST_TAG).performTouchInput { up() }
            rule.waitForIdle()
        }
    }

    private companion object {
        const val ARCHIVE_LIST_TAG = "archive-shaped-list"
        const val PICKER_LIST_TAG = "picker-shaped-list"
    }
}
