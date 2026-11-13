package com.drdisagree.teledrive.presentation.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.collectionItemInfo
import androidx.compose.ui.semantics.semantics

fun Modifier.keyboardCollection(
    state: KeyboardCollectionState,
    itemCount: Int,
    shortcuts: CollectionShortcuts = CollectionShortcuts(),
    label: String? = null
): Modifier = this
    .semantics {
        collectionInfo = state.collectionInfo(itemCount)
        if (label != null) collectionRole(label, grid = state.columns > 1)
    }
    // Only Enter needs preview handling: a clickable row may consume it before
    // bubbling, and the collection uses it to transfer focus into a newly opened folder.
    // All other keys bubble first so editable descendants can process their native keys.
    .onPreviewKeyEvent { event ->
        if (event.isPlainCollectionEnter()) state.handle(event, shortcuts)
        else false
    }
    .onKeyEvent { event ->
        if (event.isPlainCollectionEnter()) false
        else state.handle(event, shortcuts)
    }

private fun KeyEvent.isPlainCollectionEnter(): Boolean =
    type == KeyEventType.KeyDown &&
        !isCtrlPressed &&
        !isMetaPressed &&
        !isAltPressed &&
        !isShiftPressed &&
        (key == Key.Enter || key == Key.NumPadEnter)

fun Modifier.keyboardCollectionItem(
    state: KeyboardCollectionState,
    index: Int,
    itemIndex: Int = index
): Modifier = this
    .focusRequester(state.requester(index))
    .focusProperties { canFocus = state.canFocus(index) }
    .onFocusChanged { state.onFocusChanged(index, it.isFocused, it.hasFocus) }
    .semantics { collectionItemInfo = state.itemInfo(itemIndex) }
