package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Pure page-target math behind Page Up / Page Down in a collection (R3 P9). */
class PageTargetIndexTest {

    @Test
    fun `page down lands one visible page below the current item`() {
        assertEquals(12, pageTargetIndex(current = 0, direction = 1, pageSize = 12, totalCount = 30))
        assertEquals(24, pageTargetIndex(current = 12, direction = 1, pageSize = 12, totalCount = 30))
    }

    @Test
    fun `page down clamps to the last item`() {
        assertEquals(29, pageTargetIndex(current = 24, direction = 1, pageSize = 12, totalCount = 30))
        assertEquals(29, pageTargetIndex(current = 29, direction = 1, pageSize = 12, totalCount = 30))
    }

    @Test
    fun `page up is symmetric and clamps to the first item`() {
        assertEquals(17, pageTargetIndex(current = 29, direction = -1, pageSize = 12, totalCount = 30))
        assertEquals(0, pageTargetIndex(current = 5, direction = -1, pageSize = 12, totalCount = 30))
        assertEquals(0, pageTargetIndex(current = 0, direction = -1, pageSize = 12, totalCount = 30))
    }

    @Test
    fun `page size follows the visible item count`() {
        // List layout: 4 visible rows. Grid layout: 12 visible cells. The
        // same current item pages by exactly the supplied visible count.
        assertEquals(4, pageTargetIndex(current = 0, direction = 1, pageSize = 4, totalCount = 20))
        assertEquals(12, pageTargetIndex(current = 0, direction = 1, pageSize = 12, totalCount = 20))
    }

    @Test
    fun `degenerate inputs stay in range`() {
        assertEquals(0, pageTargetIndex(current = 0, direction = 1, pageSize = 0, totalCount = 0))
        assertEquals(1, pageTargetIndex(current = 0, direction = 1, pageSize = 0, totalCount = 5))
    }
}

/**
 * R3 item P9: Page Up / Page Down at the production seams the Files
 * section uses — the keyboard collection on the grid/list itself, its
 * container tab stop, and the workspace shortcut container wired to the
 * same hoisted collection state (the FilesScreen arrangement). The
 * baseline failures pinned here: with focus on the workspace container
 * (what owns focus when Files opens) the keys did nothing at all, and
 * the row-level page() read stale layout cells after scrolling, landing
 * on the old viewport's edge instead of one page away.
 */
@OptIn(ExperimentalTestApi::class)
class FilesPageKeyNavigationTest {

    @get:Rule
    val rule = createComposeRule()

    // 100dp x 50dp cells in a 300dp x 200dp viewport: exactly 3 columns x 4
    // rows = 12 visible items per page, no partially visible row.
    private fun setExactPageGrid(count: Int = 30, label: String? = null) {
        rule.setContent {
            val gridState = rememberLazyGridState()
            val keyboard = rememberKeyboardCollection(gridState, 3)
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .testTag("grid")
                    .size(300.dp, 200.dp)
                    .keyboardCollection(keyboard, count, label = label)
            ) {
                items(count, key = { it }) { index ->
                    BasicText(
                        "item-$index",
                        Modifier
                            .keyboardCollectionItem(keyboard, index)
                            .testTag("item-$index")
                            .size(100.dp, 50.dp)
                            .clickable {}
                    )
                }
            }
        }
    }

    @Test
    fun `page down moves one visible page and clamps at the end, page up reverses`() {
        setExactPageGrid()
        rule.onNodeWithTag("item-0").requestFocus()

        rule.onNodeWithTag("item-0").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-12").assertIsFocused()

        rule.onNodeWithTag("item-12").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-24").assertIsFocused()

        rule.onNodeWithTag("item-24").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-29").assertIsFocused()

        rule.onNodeWithTag("item-29").performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-17").assertIsFocused()

        rule.onNodeWithTag("item-17").performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-5").assertIsFocused()

        rule.onNodeWithTag("item-5").performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-0").assertIsFocused()
    }

    @Test
    fun `arrows and home end still work after paging`() {
        // Coexistence pin: adding Page handling must not disturb the
        // collection's existing keys.
        setExactPageGrid()
        rule.onNodeWithTag("item-0").requestFocus()

        rule.onNodeWithTag("item-0").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-12").assertIsFocused()

        rule.onNodeWithTag("item-12").performKeyInput { pressKey(Key.DirectionRight) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-13").assertIsFocused()

        rule.onNodeWithTag("item-13").performKeyInput { pressKey(Key.MoveEnd) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-29").assertIsFocused()

        rule.onNodeWithTag("item-29").performKeyInput { pressKey(Key.MoveHome) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-0").assertIsFocused()
    }

    @Test
    fun `page keys move by visible rows in a single column list`() {
        // 50dp rows in a 200dp viewport: exactly 4 visible items per page.
        rule.setContent {
            val listState = rememberLazyListState()
            val keyboard = rememberKeyboardCollection(listState)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(300.dp, 200.dp)
                    .keyboardCollection(keyboard, 20)
            ) {
                items(20, key = { it }) { index ->
                    BasicText(
                        "row-$index",
                        Modifier
                            .keyboardCollectionItem(keyboard, index)
                            .testTag("row-$index")
                            .size(300.dp, 50.dp)
                            .clickable {}
                    )
                }
            }
        }
        rule.onNodeWithTag("row-0").requestFocus()

        rule.onNodeWithTag("row-0").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("row-4").assertIsFocused()

        rule.onNodeWithTag("row-4").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("row-8").assertIsFocused()

        rule.onNodeWithTag("row-8").performKeyInput { pressKey(Key.PageUp) }
        rule.waitForIdle()
        rule.onNodeWithTag("row-4").assertIsFocused()
    }

    @Test
    fun `page down from the list container tab stop enters and pages the collection`() {
        rule.setContent {
            Column {
                BasicText("before", Modifier.testTag("before").clickable {})
                val gridState = rememberLazyGridState()
                val keyboard = rememberKeyboardCollection(gridState, 3)
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .testTag("grid")
                        .size(300.dp, 200.dp)
                        .keyboardCollection(keyboard, 30, label = "Files")
                ) {
                    items(30, key = { it }) { index ->
                        BasicText(
                            "item-$index",
                            Modifier
                                .keyboardCollectionItem(keyboard, index)
                                .testTag("item-$index")
                                .size(100.dp, 50.dp)
                                .clickable {}
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("before").requestFocus()
        rule.onNodeWithTag("before").performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithTag("grid").assertIsFocused()

        rule.onNodeWithTag("grid").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-12").assertIsFocused()
    }

    @Test
    fun `workspace container pages the files collection exactly once per press`() {
        // The FilesScreen arrangement: the workspace shortcut container
        // wraps the grid and forwards Page keys to the same collection
        // state the grid uses. A press must page once, never twice
        // (container + grid both handling it).
        rule.setContent {
            val requester = rememberCollectionCommandFocusRequester()
            val gridState = rememberLazyGridState()
            val keyboard = rememberKeyboardCollection(gridState, 3)
            Box(
                modifier = Modifier
                    .testTag("workspace")
                    .size(300.dp, 200.dp)
                    .collectionCommandShortcuts(
                        focusRequester = requester,
                        onPage = { direction -> keyboard.pageBy(direction) },
                        interceptBeforeChildren = true
                    )
            ) {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(3),
                    modifier = Modifier
                        .size(300.dp, 200.dp)
                        .keyboardCollection(keyboard, 30, label = "Files")
                ) {
                    items(30, key = { it }) { index ->
                        BasicText(
                            "item-$index",
                            Modifier
                                .keyboardCollectionItem(keyboard, index)
                                .testTag("item-$index")
                                .size(100.dp, 50.dp)
                                .clickable {}
                        )
                    }
                }
            }
        }
        // Focus starts on the workspace container (the focus requester
        // asks for it on entry, as on the Files screen).
        rule.onNodeWithTag("workspace").requestFocus()
        rule.onNodeWithTag("workspace").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-12").assertIsFocused()
        rule.onNodeWithTag("item-24").assertIsNotFocused()

        // And with a row focused, the same press still pages exactly once:
        // the preview-intercepting container consumes it before the row's
        // own handler can page a second time.
        rule.onNodeWithTag("item-12").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-24").assertIsFocused()
    }

    @Test
    fun `page keys do not fire while a nested text field owns focus`() {
        var editorValue by mutableStateOf("editable text")
        rule.setContent {
            val listState = rememberLazyListState()
            val keyboard = rememberKeyboardCollection(listState)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(300.dp, 200.dp)
                    .keyboardCollection(keyboard, 20)
            ) {
                items(20, key = { it }) { index ->
                    if (index == 0) {
                        Box(Modifier.keyboardCollectionItem(keyboard, index)) {
                            BasicTextField(
                                value = editorValue,
                                onValueChange = { editorValue = it },
                                modifier = Modifier
                                    .testTag("nested-editor")
                                    .size(300.dp, 50.dp)
                            )
                        }
                    } else {
                        BasicText(
                            "row-$index",
                            Modifier
                                .keyboardCollectionItem(keyboard, index)
                                .testTag("row-$index")
                                .size(300.dp, 50.dp)
                                .clickable {}
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("nested-editor").requestFocus()
        rule.onNodeWithTag("nested-editor").performKeyInput { pressKey(Key.PageDown) }
        rule.waitForIdle()

        // The editor keeps focus and the collection does not page.
        rule.onNodeWithTag("nested-editor").assertIsFocused()
        rule.onNodeWithTag("row-4").assertIsNotFocused()
    }
}
