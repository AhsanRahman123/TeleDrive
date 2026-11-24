package com.drdisagree.teledrive.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The desktop ZIP viewer's selection state — ONE source of truth, mirroring the desktop
 * Files section's model (a file set plus a folder set owned by one state holder, with the
 * checkbox ticks, the row highlight and the "N selected" count all derived from it, so
 * the three can never disagree).
 *
 * Identity is the archive LISTING index for files and the exact '/'-separated path for
 * folders (synthesized folder rows included), exactly as the shared archive helpers use.
 * This state is local to the preview screen, keyed by the ZIP's file id, and fully
 * independent of the Files screen's selection state: neither reads or writes the other.
 */
class ArchiveSelectionState {

    var entries: Set<Int> by mutableStateOf(emptySet())
        private set

    var folders: Set<String> by mutableStateOf(emptySet())
        private set

    /** Selection mode is active exactly when something is selected (Files' rule). */
    val isActive: Boolean get() = entries.isNotEmpty() || folders.isNotEmpty()

    /** The one count the selection bar shows; derived from the same sets as the ticks. */
    val count: Int get() = entries.size + folders.size

    fun isEntrySelected(entryIndex: Int): Boolean = entryIndex in entries

    fun isFolderSelected(path: String): Boolean = path in folders

    fun toggleEntry(entryIndex: Int) {
        entries = if (entryIndex in entries) entries - entryIndex else entries + entryIndex
    }

    fun toggleFolder(path: String) {
        folders = if (path in folders) folders - path else folders + path
    }

    /** Replaces both sets at once (drag paint onto the drag-start snapshot, select-all). */
    fun set(newEntries: Set<Int>, newFolders: Set<String>) {
        entries = newEntries
        folders = newFolders
    }

    fun clear() {
        entries = emptySet()
        folders = emptySet()
    }

    /**
     * Toggle-select-all over the currently displayed rows, mirroring Files'
     * `toggleSelectAll`: when every displayed row is already selected it clears the
     * selection, otherwise it selects every displayed row.
     */
    fun toggleSelectAll(displayedEntries: Set<Int>, displayedFolders: Set<String>) {
        val allDisplayedSelected = displayedEntries.all { it in entries } &&
            displayedFolders.all { it in folders } &&
            (displayedEntries.isNotEmpty() || displayedFolders.isNotEmpty())
        if (allDisplayedSelected) {
            clear()
        } else {
            entries = entries + displayedEntries
            folders = folders + displayedFolders
        }
    }

    /** Filtering prunes the selection to what is still visible (Files/Search rule). */
    fun prune(visibleEntries: Set<Int>, visibleFolders: Set<String>) {
        entries = entries intersect visibleEntries
        folders = folders intersect visibleFolders
    }
}
