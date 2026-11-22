package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic behind the ZIP viewer's tap routing, text preview cap, multi-selection,
 * folder download, search filter and sort composition. Everything is keyed by listing
 * index, so the tests pin identity under duplicates, filtering and re-sorting.
 */
class ArchiveSelectionTest {

    private fun entry(
        name: String,
        size: Long = 10,
        isDirectory: Boolean = false,
        modified: Long = 0
    ) = ArchiveEntry(
        name = name,
        sizeBytes = size,
        compressedBytes = size,
        isDirectory = isDirectory,
        modifiedAtMillis = modified
    )

    // ---- Tap routing (item 4 + item 8) ----

    @Test
    fun `image and audio entries preview, video never does`() {
        assertEquals(ArchiveEntryTapAction.PREVIEW_IMAGE, archiveEntryTapAction(entry("a/photo.JPG")))
        assertEquals(ArchiveEntryTapAction.PLAY_AUDIO, archiveEntryTapAction(entry("a/song.mp3")))
        assertEquals(ArchiveEntryTapAction.PLAY_AUDIO, archiveEntryTapAction(entry("a/tone.flac")))
        // Video stays download-only: no playback action exists for it.
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("a/movie.mp4")))
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("a/movie.mkv")))
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("a/blob.bin")))
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("a/", isDirectory = true)))
    }

    @Test
    fun `text entries preview only within the size cap`() {
        assertEquals(ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("notes.txt")))
        assertEquals(ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("readme.md")))
        assertEquals(ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("run.log")))
        assertTrue(archiveEntryTextPreviewable(entry("notes.txt", size = ARCHIVE_TEXT_PREVIEW_MAX_BYTES)))
        assertFalse(
            archiveEntryTextPreviewable(entry("notes.txt", size = ARCHIVE_TEXT_PREVIEW_MAX_BYTES + 1))
        )
        // A huge image is not a text preview candidate at any size.
        assertFalse(archiveEntryTextPreviewable(entry("photo.png", size = 1)))
    }

    // ---- Download planning (item 5) ----

    @Test
    fun `plan follows listing order, dedupes, skips directories and bad indices`() {
        val listing = listOf(
            entry("b.txt", 1),
            entry("dir/", 0, isDirectory = true),
            entry("a.txt", 2),
            entry("c.txt", 3)
        )
        val plan = planArchiveDownloads(listing, setOf(3, 0, 0, 1, 99, -1))
        assertEquals(listOf(0, 3), plan.map { it.entryIndex })
        assertTrue(plan.all { it.subFolderPath.isEmpty() })
    }

    @Test
    fun `plan resolves duplicate names to their own occurrences`() {
        val listing = listOf(
            entry("dup.txt", 1),
            entry("other.txt", 2),
            entry("dup.txt", 3)
        )
        val plan = planArchiveDownloads(listing, setOf(0, 2))
        assertEquals(listOf(0 to 0, 2 to 1), plan.map { it.entryIndex to it.occurrence })
    }

    @Test
    fun `toggle adds then removes`() {
        var selection = toggleArchiveSelection(emptySet(), 4)
        assertEquals(setOf(4), selection)
        selection = toggleArchiveSelection(selection, 4)
        assertEquals(emptySet<Int>(), selection)
    }

    @Test
    fun `range selection paints onto the drag-start snapshot`() {
        val base = setOf(0, 5)
        // Selecting a range adds it to the base…
        assertEquals(setOf(0, 1, 2, 5), applyArchiveRangeSelection(base, listOf(1, 2), true))
        // …and painting back over the same range with deselect removes exactly the range,
        // leaving the base untouched (the range never accumulates onto the live set).
        assertEquals(setOf(0, 5), applyArchiveRangeSelection(base, listOf(1, 2), false))
        assertEquals(setOf(5), applyArchiveRangeSelection(base, listOf(0), false))
    }

    // ---- Folder download (Completion B) ----

    @Test
    fun `folder expansion is recursive, nested and prefix exact`() {
        val listing = listOf(
            entry("dir/", 0, isDirectory = true),       // 0
            entry("dir/a.txt", 1),                      // 1
            entry("dir/sub/", 0, isDirectory = true),   // 2
            entry("dir/sub/b.txt", 2),                  // 3
            entry("dir2/c.txt", 3),                     // 4 — sibling prefix must NOT match
            entry("dir/sub/deep/d.txt", 4)              // 5
        )
        assertEquals(listOf(1, 3, 5), expandArchiveFolder(listing, "dir"))
        assertEquals(listOf(3, 5), expandArchiveFolder(listing, "dir/sub"))
        assertEquals(emptyList<Int>(), expandArchiveFolder(listing, "missing"))
    }

    @Test
    fun `folder expansion keeps duplicate names as separate indices`() {
        val listing = listOf(
            entry("dir/dup.txt", 1),
            entry("dir/dup.txt", 2),
            entry("other/dup.txt", 3)
        )
        assertEquals(listOf(0, 1), expandArchiveFolder(listing, "dir"))
        val plan = planArchiveFolderDownloads(listing, setOf("dir"))
        assertEquals(listOf(0 to 0, 1 to 1), plan.map { it.entryIndex to it.occurrence })
        // Folder downloads recreate the in-zip structure under Downloads.
        assertTrue(plan.all { it.subFolderPath == "dir" })
    }

    @Test
    fun `selection merge dedupes entries covered by a selected folder`() {
        val listing = listOf(
            entry("dir/a.txt", 1),
            entry("dir/b.txt", 2),
            entry("loose.txt", 3)
        )
        val plan = planArchiveSelectionDownloads(
            listing,
            entryIndices = setOf(0, 2),
            folderPaths = setOf("dir")
        )
        assertEquals(listOf(0, 1, 2), plan.map { it.entryIndex })
        // Index 0 came through the folder: structured. Index 2 was direct: flat.
        assertEquals("dir", plan.first { it.entryIndex == 0 }.subFolderPath)
        assertEquals("", plan.first { it.entryIndex == 2 }.subFolderPath)
    }

    @Test
    fun `empty folder plans nothing`() {
        val listing = listOf(entry("empty/", 0, isDirectory = true), entry("a.txt", 1))
        assertTrue(planArchiveSelectionDownloads(listing, emptySet(), setOf("empty")).isEmpty())
    }

    // ---- Search filter (item 7) ----

    @Test
    fun `filter matches file names case-insensitively and keeps ancestor folders reachable`() {
        val listing = listOf(
            entry("Music/Rock/song.mp3", 1),
            entry("Music/Jazz/tune.mp3", 2),
            entry("Docs/report.txt", 3)
        )
        assertEquals(setOf(0), archiveFilterIndices(listing, "SONG"))
        assertEquals(setOf(0), archiveFilterIndices(listing, "rock"))
        assertEquals(setOf(0, 1), archiveFilterIndices(listing, "music"))
        assertEquals(setOf(0, 1, 2), archiveFilterIndices(listing, ""))
        assertEquals(emptySet<Int>(), archiveFilterIndices(listing, "zzz"))
    }

    @Test
    fun `selection prunes to the filtered results like the main view search`() {
        assertEquals(setOf(1), pruneArchiveSelection(setOf(0, 1, 2), setOf(1, 3)))
        assertEquals(emptySet<Int>(), pruneArchiveSelection(setOf(0), setOf(1)))
    }

    // ---- Filter + sort composition: identity survives (items 5 + 6 + 7) ----

    @Test
    fun `selection made under one sort still targets the same entries after re-sort and filter`() {
        val listing = listOf(
            entry("zeta.txt", 30),
            entry("alpha.txt", 10),
            entry("mid.txt", 20),
            entry("alpha.txt", 40)
        )
        // Rows under NAME ascending: alpha(1), alpha(3), mid(2), zeta(0).
        val nameRoot = buildArchiveTree(listing)
        val nameRows = flattenArchive(nameRoot, emptySet(), FileSortField.NAME, SortDirection.ASCENDING)
        assertEquals(listOf(1, 3, 2, 0), nameRows.map { it.node.entryIndex })
        // The user selects the two visible alpha rows and mid.
        val selected = setOf(1, 3, 2)
        // Re-sort by SIZE descending: rows reorder but carry the same indices.
        val sizeRows = flattenArchive(nameRoot, emptySet(), FileSortField.SIZE, SortDirection.DESCENDING)
        assertEquals(listOf(3, 0, 2, 1), sizeRows.map { it.node.entryIndex })
        assertEquals(
            selected,
            sizeRows.filter { it.node.entryIndex in selected }.map { it.node.entryIndex }.toSet()
        )
        // Filter to "alpha": selection prunes to the alpha entries, and the plan still
        // resolves the second alpha to occurrence 1 — not to the first alpha's bytes.
        val visible = archiveFilterIndices(listing, "alpha")
        val pruned = pruneArchiveSelection(selected, visible)
        assertEquals(setOf(1, 3), pruned)
        val plan = planArchiveDownloads(listing, pruned)
        assertEquals(listOf(1 to 0, 3 to 1), plan.map { it.entryIndex to it.occurrence })
    }
}
