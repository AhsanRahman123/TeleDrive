package com.drdisagree.teledrive.presentation.files

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

class FileClipboard {
    private data class Entry(
        val revision: Long,
        val items: CutItems
    )

    private val content = MutableStateFlow<Entry?>(null)
    private var revision = 0L

    @Synchronized
    fun cut(items: CutItems) {
        content.value = Entry(++revision, items.copy(isCut = true))
    }

    @Synchronized
    fun copy(items: CutItems) {
        content.value = Entry(++revision, items.copy(isCut = false))
    }

    /** Copy clipboard content survives paste; cut content is consumed after a successful move. */
    fun peek(): CutItems? = content.value?.items

    /**
     * Clear only the exact cut payload that was pasted. The revision keeps equal consecutive
     * clipboard values distinct, so a newer cut of the same IDs survives an older move.
     */
    @Synchronized
    fun clearIfMatches(items: CutItems): Boolean {
        val current = content.value ?: return false
        if (current.items !== items || !current.items.isCut) return false
        content.value = null
        return true
    }

    @Synchronized
    fun take(): CutItems? = content.getAndUpdate { null }?.items
}
