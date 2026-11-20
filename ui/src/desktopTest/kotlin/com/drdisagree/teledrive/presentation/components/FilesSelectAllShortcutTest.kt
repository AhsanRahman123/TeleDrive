package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * PC-29, Files side: Ctrl+A must select all from EVERY focus state the
 * Files workspace can be in — the workspace surface itself, the
 * collection container (the desktop tab stop), and an individual item —
 * and it must fire the select-all exactly ONCE per press: the
 * production action toggles (a second fire would clear the selection
 * it just made). The composition mirrors FilesScreen's real wiring:
 * the workspace Box carries [collectionCommandShortcuts] in preview
 * mode (Files has no inline text editor in that layer), and the
 * collection inside it carries its own item-level shortcuts bound to
 * the same action. Item focus is reached the way a keyboard user
 * reaches it: Tab to the collection's container tab stop, then an
 * arrow key steps into the first item (the collection's ENTER_KEYS
 * are the arrows/Home/End, not Enter).
 */
@OptIn(ExperimentalTestApi::class)
class FilesSelectAllShortcutTest {

    @get:Rule
    val rule = createComposeRule()

    private var selectAllFires = 0

    private fun setWorkspace() {
        selectAllFires = 0
        rule.setContent {
            val requester = rememberCollectionCommandFocusRequester()
            Column {
            Box(
                modifier = Modifier
                    .testTag("before")
                    .size(10.dp)
                    .focusable()
            )
            Box(
                modifier = Modifier
                    .testTag("workspace")
                    .collectionCommandShortcuts(
                        focusRequester = requester,
                        onSelectAll = { selectAllFires++; true },
                        interceptBeforeChildren = true
                    )
            ) {
                val listState = rememberLazyListState()
                val keyboard = rememberKeyboardCollection(listState)
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .size(300.dp, 200.dp)
                        .testTag("collection")
                        .keyboardCollection(
                            keyboard,
                            ITEM_COUNT,
                            CollectionShortcuts(onSelectAll = { selectAllFires++ }),
                            label = "Files"
                        )
                ) {
                    items(ITEM_COUNT) { index ->
                        BasicText(
                            "item-$index",
                            Modifier
                                .keyboardCollectionItem(keyboard, index)
                                .testTag("item-$index")
                                .size(300.dp, 40.dp)
                                .clickable {}
                        )
                    }
                }
            }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `ctrl+a fires once when the workspace surface holds focus`() {
        setWorkspace()
        rule.onNodeWithTag("workspace").requestFocus()
        rule.onNodeWithTag("workspace").assertIsFocused()

        pressCtrlA("workspace")

        assertEquals(1, selectAllFires)
    }

    @Test
    fun `ctrl+a fires once when the collection container holds focus`() {
        setWorkspace()
        rule.onNodeWithTag("collection").requestFocus()
        rule.onNodeWithTag("collection").assertIsFocused()

        pressCtrlA("collection")

        assertEquals(1, selectAllFires)
    }

    @Test
    fun `ctrl+a fires exactly once when an item holds focus`() {
        setWorkspace()
        // The keyboard user's path: Tab from before the workspace lands
        // on the workspace tab stop, Tab again on the collection's
        // container tab stop, and an arrow key steps into item 0.
        rule.onNodeWithTag("before").requestFocus()
        rule.onNodeWithTag("before").performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithTag("workspace").assertIsFocused()
        rule.onNodeWithTag("workspace").performKeyInput { pressKey(Key.Tab) }
        rule.waitForIdle()
        rule.onNodeWithTag("collection").assertIsFocused()
        rule.onNodeWithTag("collection").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        rule.onNodeWithTag("item-0").assertIsFocused()

        pressCtrlA("item-0")

        assertEquals(1, selectAllFires)
    }

    private fun pressCtrlA(tag: String) {
        rule.onNodeWithTag(tag).performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()
    }

    private companion object {
        const val ITEM_COUNT = 6
    }
}
