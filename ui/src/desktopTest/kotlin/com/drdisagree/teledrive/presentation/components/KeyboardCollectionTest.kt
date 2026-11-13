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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
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

@OptIn(ExperimentalTestApi::class)
class KeyboardCollectionTest {

    @get:Rule
    val rule = createComposeRule()

    private val items = mutableStateListOf<String>()
    private val deleted = mutableListOf<Int>()
    private var backs = 0

    private fun setGrid(columns: Int, count: Int = 12) {
        items.addAll((0 until count).map { "item-$it" })
        rule.setContent {
            Column {
                BasicText("before", Modifier.testTag("before").clickable {})
                val gridState = rememberLazyGridState()
                val keyboard = rememberKeyboardCollection(gridState, columns)
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .size(300.dp, 200.dp)
                        .keyboardCollection(
                            keyboard,
                            items.size,
                            CollectionShortcuts(
                                onDelete = { index ->
                                    deleted += index
                                    items.removeAt(index)
                                },
                                onBack = { backs++ }
                            )
                        )
                ) {
                    items(items.size, key = { items[it] }) { index ->
                        BasicText(
                            items[index],
                            Modifier
                                .keyboardCollectionItem(keyboard, index)
                                .testTag(items[index])
                                .size(60.dp)
                                .clickable {}
                        )
                    }
                }
                BasicText("after", Modifier.testTag("after").clickable {})
            }
        }
    }

    @Test
    fun `arrows move by row and column`() {
        setGrid(columns = 3)
        rule.onNodeWithTag("item-0").requestFocus()

        rule.onNodeWithTag("item-0").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("item-3").assertIsFocused()

        rule.onNodeWithTag("item-3").performKeyInput { pressKey(Key.DirectionRight) }
        rule.onNodeWithTag("item-4").assertIsFocused()

        rule.onNodeWithTag("item-4").performKeyInput { pressKey(Key.MoveEnd) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-11").assertIsFocused()
    }

    @Test
    fun `tab enters the list once and leaves it on the next press`() {
        setGrid(columns = 1)
        rule.onNodeWithTag("before").requestFocus()

        rule.onNodeWithTag("before").performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithTag("item-0").assertIsFocused()

        rule.onNodeWithTag("item-0").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("item-1").performKeyInput { pressKey(Key.Tab) }
        rule.onNodeWithTag("after").assertIsFocused()
        rule.onNodeWithTag("item-2").assertIsNotFocused()
    }

    @Test
    fun `delete keeps focus on the item that takes the removed one's place`() {
        setGrid(columns = 1)
        rule.onNodeWithTag("item-0").requestFocus()
        rule.onNodeWithTag("item-0").performKeyInput { pressKey(Key.DirectionDown) }

        rule.onNodeWithTag("item-1").performKeyInput { pressKey(Key.Delete) }
        rule.waitForIdle()

        assertEquals(listOf(1), deleted)
        rule.onNodeWithTag("item-2").assertIsFocused()
    }

    @Test
    fun `focus follows enter into a folder and backspace returns to it`() {
        var screen by mutableStateOf("root")
        rule.setContent {
            val holder = rememberSaveableStateHolder()
            holder.SaveableStateProvider(screen) {
                val listState = rememberLazyListState()
                val keyboard = rememberKeyboardCollection(listState)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .size(300.dp, 400.dp)
                        .keyboardCollection(
                            keyboard,
                            5,
                            CollectionShortcuts(
                                onBack = if (screen == "root") null else ({ screen = "root" })
                            )
                        )
                ) {
                    items(5, key = { "$screen-$it" }) { index ->
                        BasicText(
                            "$screen-$index",
                            Modifier
                                .keyboardCollectionItem(keyboard, index)
                                .testTag("$screen-$index")
                                .size(60.dp)
                                .clickable { screen = "child" }
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("root-0").requestFocus()
        rule.onNodeWithTag("root-0").performKeyInput { pressKey(Key.DirectionDown) }
        rule.onNodeWithTag("root-1").performKeyInput { pressKey(Key.DirectionDown) }

        rule.onNodeWithTag("root-2").performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        rule.onNodeWithTag("child-0").assertIsFocused()

        rule.onNodeWithTag("child-0").performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        rule.onNodeWithTag("root-2").assertIsFocused()
    }

    @Test
    fun `tab skips a leading header and lands on the first item`() {
        rule.setContent {
            Column {
                BasicText("before", Modifier.testTag("before").clickable {})
                val listState = rememberLazyListState()
                val keyboard = rememberKeyboardCollection(listState) { it != 0 }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .size(300.dp, 400.dp)
                        .keyboardCollection(keyboard, 3)
                ) {
                    item { BasicText("header") }
                    items(3) { index ->
                        BasicText(
                            "row-$index",
                            Modifier
                                .keyboardCollectionItem(keyboard, index + 1, index)
                                .testTag("row-$index")
                                .clickable {}
                        )
                    }
                }
            }
        }
        rule.onNodeWithTag("before").requestFocus()

        rule.onNodeWithTag("before").performKeyInput { pressKey(Key.Tab) }

        rule.onNodeWithTag("row-0").assertIsFocused()
    }

    @Test
    fun `collection commands are dispatched while a row owns focus`() {
        var selectedAll = 0
        var copied = 0
        var cut = 0
        var pasted = 0
        var newFolder = 0
        var newTab = 0

        rule.setContent {
            val listState = rememberLazyListState()
            val keyboard = rememberKeyboardCollection(listState)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(300.dp, 200.dp)
                    .keyboardCollection(
                        keyboard,
                        itemCount = 1,
                        shortcuts = CollectionShortcuts(
                            onSelectAll = { selectedAll++ },
                            onCopy = { copied++ },
                            onCut = { cut++ },
                            onPaste = { pasted++ },
                            onNewFolder = { newFolder++ },
                            onNewTab = { newTab++ }
                        )
                    )
            ) {
                item {
                    BasicText(
                        "row",
                        Modifier
                            .keyboardCollectionItem(keyboard, 0)
                            .testTag("command-row")
                            .size(100.dp)
                            .clickable {}
                    )
                }
            }
        }

        rule.onNodeWithTag("command-row").requestFocus()
        rule.onNodeWithTag("command-row").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            pressKey(Key.C)
            pressKey(Key.X)
            pressKey(Key.V)
            pressKey(Key.N)
            pressKey(Key.T)
            keyUp(Key.CtrlLeft)
        }

        assertEquals(1, selectedAll)
        assertEquals(1, copied)
        assertEquals(1, cut)
        assertEquals(1, pasted)
        assertEquals(1, newFolder)
        assertEquals(1, newTab)
    }

    @Test
    fun `collection select all does not intercept a nested text field shortcut`() {
        var selectedAll = 0
        rule.setContent {
            val listState = rememberLazyListState()
            val keyboard = rememberKeyboardCollection(listState)
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .size(300.dp, 200.dp)
                    .keyboardCollection(
                        keyboard,
                        itemCount = 1,
                        shortcuts = CollectionShortcuts(onSelectAll = { selectedAll++ })
                    )
            ) {
                item {
                    Box(Modifier.keyboardCollectionItem(keyboard, 0)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = "editable text",
                            onValueChange = {},
                            modifier = Modifier.testTag("nested-editor")
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag("nested-editor").requestFocus()
        rule.onNodeWithTag("nested-editor").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }

        assertEquals(0, selectedAll)
    }

    @Test
    fun `backspace goes back`() {
        setGrid(columns = 1)
        rule.onNodeWithTag("item-0").requestFocus()

        rule.onNodeWithTag("item-0").performKeyInput { pressKey(Key.Backspace) }

        assertEquals(1, backs)
    }
}
