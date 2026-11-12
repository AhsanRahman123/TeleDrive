package com.drdisagree.teledrive.presentation.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

class FileClipboard {
    private val content = MutableStateFlow<CutItems?>(null)

    @Synchronized
    fun cut(items: CutItems) {
        content.value = items.copy(isCut = true)
    }

    @Synchronized
    fun copy(items: CutItems) {
        content.value = items.copy(isCut = false)
    }

    /** Copy clipboard content survives paste; cut content is consumed after a successful move. */
    fun peek(): CutItems? = content.value

    /**
     * Clear only the exact cut payload that was pasted. Identity matters here: a newer cut of
     * the same IDs must survive if an older asynchronous move finishes after it.
     */
    @Synchronized
    fun clearIfMatches(items: CutItems): Boolean {
        if (content.value !== items || !items.isCut) return false
        content.value = null
        return true
    }

    @Synchronized
    fun take(): CutItems? = content.getAndUpdate { null }
}
