package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalTestApi::class)
class CollectionCommandShortcutsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `workspace preview shortcuts run before focused child controls`() {
        val actions = mutableListOf<String>()
        var childConsumedShortcutCount = 0

        rule.setContent {
            val requester = rememberCollectionCommandFocusRequester()
            Box(
                modifier = Modifier
                    .size(300.dp, 200.dp)
                    .collectionCommandShortcuts(
                        focusRequester = requester,
                        onSelectAll = { actions += "A"; true },
                        onCopy = { actions += "C"; true },
                        onCut = { actions += "X"; true },
                        onPaste = { actions += "V"; true },
                        onNewFolder = { actions += "N"; true },
                        onNewTab = { actions += "T"; true },
                        interceptBeforeChildren = true
                    )
            ) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .testTag("shortcut-child")
                        .onKeyEvent { event ->
                            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed) {
                                childConsumedShortcutCount++
                                true
                            } else {
                                false
                            }
                        }
                        .focusable()
                )
            }
        }

        rule.onNodeWithTag("shortcut-child").requestFocus()
        rule.onNodeWithTag("shortcut-child").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            pressKey(Key.C)
            pressKey(Key.X)
            pressKey(Key.V)
            pressKey(Key.N)
            pressKey(Key.T)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()

        assertEquals(listOf("A", "C", "X", "V", "N", "T"), actions)
        assertEquals(0, childConsumedShortcutCount)
    }

    @Test
    fun `default bubbling mode preserves nested text field shortcuts`() {
        var selectedAll by mutableStateOf(0)
        var editorValue by mutableStateOf(
            TextFieldValue("editable text", selection = TextRange("editable text".length))
        )

        rule.setContent {
            val requester = rememberCollectionCommandFocusRequester()
            Box(
                modifier = Modifier
                    .size(300.dp, 200.dp)
                    .collectionCommandShortcuts(
                        focusRequester = requester,
                        onSelectAll = { selectedAll++; true }
                    )
            ) {
                BasicTextField(
                    value = editorValue,
                    onValueChange = { editorValue = it },
                    modifier = Modifier.testTag("shortcut-editor")
                )
            }
        }

        rule.onNodeWithTag("shortcut-editor").requestFocus()
        rule.onNodeWithTag("shortcut-editor").performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.A)
            keyUp(Key.CtrlLeft)
        }
        rule.waitForIdle()

        assertEquals(0, selectedAll)
        assertEquals(TextRange(0, "editable text".length), editorValue.selection)
    }
}
