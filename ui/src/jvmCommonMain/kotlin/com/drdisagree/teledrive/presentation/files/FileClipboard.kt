package com.drdisagree.teledrive.presentation.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

class FileClipboard {

    private val content = MutableStateFlow<CutItems?>(null)

    fun cut(items: CutItems) {
        content.value = items.copy(isCut = true)
    }

    fun copy(items: CutItems) {
        content.value = items.copy(isCut = false)
    }

    /** Copy clipboard content survives paste; cut content is consumed exactly once. */
    fun peek(): CutItems? = content.value

    fun clear() {
        content.value = null
    }

    fun take(): CutItems? = content.getAndUpdate { null }
}
