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

    /** Copy clipboard content survives paste; cut content is consumed after a successful move. */
    fun peek(): CutItems? = content.value

    /**
     * Clear only the cut payload that was actually pasted. A newer clipboard operation must
     * not be discarded when an earlier asynchronous move completes.
     */
    fun clearIfMatches(items: CutItems): Boolean = content.compareAndSet(items, null)

    fun take(): CutItems? = content.getAndUpdate { null }
}
