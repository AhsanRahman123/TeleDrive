package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure logic behind the desktop ZIP viewer's tap routing, text preview cap,
 * multi-selection, folder download, search filter and sort composition (ported from
 * the Android vc175 suites; the flat desktop list sorts indices directly instead of
 * tree siblings). Everything is keyed by listing index, so the tests pin identity
 * under duplicates, filtering and re-sorting.
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
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("a/blob.zip")))
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
        assertEquals(setOf(0, 1, 2, 5), applyArchiveRangeSelection(base, listOf(1, 2), true))
        assertEquals(setOf(0, 5), applyArchiveRangeSelection(base, listOf(1, 2), false))
        assertEquals(setOf(5), applyArchiveRangeSelection(base, listOf(0), false))
    }

    // ---- Folder download (Completion B) ----

    @Test
    fun `folder expansion is recursive, nested and prefix exact`() {
        val listing = listOf(
            entry("dir/", 0, isDirectory = true),
            entry("dir/a.txt", 1),
            entry("dir/sub/", 0, isDirectory = true),
            entry("dir/sub/b.txt", 2),
            entry("dir2/c.txt", 3),
            entry("dir/sub/deep/d.txt", 4)
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
    fun `filter matches file names case-insensitively on any path segment`() {
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
    fun `folder row stays visible when it contains a match`() {
        val listing = listOf(
            entry("Music/", 0, isDirectory = true),
            entry("Music/Rock/song.mp3", 1),
            entry("Docs/", 0, isDirectory = true),
            entry("Docs/report.txt", 3)
        )
        // "song" matches only the file; its folder row stays visible.
        assertEquals(setOf(0, 1), archiveVisibleIndices(listing, "song"))
        // A folder's own name match keeps the folder (its contents match by segment too).
        assertEquals(setOf(2, 3), archiveVisibleIndices(listing, "docs"))
        assertEquals(setOf(0, 1, 2, 3), archiveVisibleIndices(listing, ""))
    }

    @Test
    fun `selection prunes to the filtered results like the main view search`() {
        assertEquals(setOf(1), pruneArchiveSelection(setOf(0, 1, 2), setOf(1, 3)))
        assertEquals(emptySet<Int>(), pruneArchiveSelection(setOf(0), setOf(1)))
    }

    // ---- Sorting (item 6, flat desktop ordering) ----

    private fun sortedNames(
        entries: List<ArchiveEntry>,
        field: FileSortField,
        direction: SortDirection
    ): List<String> = sortArchiveIndices(entries, entries.indices.toList(), field, direction)
        .map { entries[it].name }

    @Test
    fun `name sort is case-insensitive like the browser collation`() {
        val entries = listOf(entry("banana.txt"), entry("Apple.txt"), entry("cherry.txt"))
        assertEquals(
            listOf("Apple.txt", "banana.txt", "cherry.txt"),
            sortedNames(entries, FileSortField.NAME, SortDirection.ASCENDING)
        )
        assertEquals(
            listOf("cherry.txt", "banana.txt", "Apple.txt"),
            sortedNames(entries, FileSortField.NAME, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `size sort uses byte size in both directions`() {
        val entries = listOf(entry("a.bin", 30), entry("b.bin", 10), entry("c.bin", 20))
        assertEquals(
            listOf("b.bin", "c.bin", "a.bin"),
            sortedNames(entries, FileSortField.SIZE, SortDirection.ASCENDING)
        )
        assertEquals(
            listOf("a.bin", "c.bin", "b.bin"),
            sortedNames(entries, FileSortField.SIZE, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `type sort orders by mime type string`() {
        val entries = listOf(entry("song.mp3"), entry("pic.png"), entry("notes.txt"))
        assertEquals(
            listOf("song.mp3", "pic.png", "notes.txt"),
            sortedNames(entries, FileSortField.TYPE, SortDirection.ASCENDING)
        )
    }

    @Test
    fun `date sort places entries without a timestamp last in both directions`() {
        val entries = listOf(
            entry("new.txt", modified = 3_000),
            entry("unknown.txt", modified = 0),
            entry("old.txt", modified = 1_000),
            entry("mid.txt", modified = 2_000)
        )
        assertEquals(
            listOf("old.txt", "mid.txt", "new.txt", "unknown.txt"),
            sortedNames(entries, FileSortField.DATE_MODIFIED, SortDirection.ASCENDING)
        )
        assertEquals(
            listOf("new.txt", "mid.txt", "old.txt", "unknown.txt"),
            sortedNames(entries, FileSortField.DATE_MODIFIED, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `folders stay first under every sort`() {
        val entries = listOf(
            entry("aaa.txt", 1),
            entry("zzz/", 0, isDirectory = true)
        )
        for (field in listOf(FileSortField.NAME, FileSortField.SIZE, FileSortField.DATE_MODIFIED)) {
            val sorted = sortArchiveIndices(
                entries, entries.indices.toList(), field, SortDirection.ASCENDING
            )
            assertEquals(1, sorted.first())
        }
    }

    @Test
    fun `equal keys keep listing order`() {
        val entries = listOf(entry("same.txt", 10), entry("same.txt", 10), entry("same.txt", 10))
        val sorted = sortArchiveIndices(
            entries, entries.indices.toList(), FileSortField.SIZE, SortDirection.DESCENDING
        )
        assertEquals(listOf(0, 1, 2), sorted)
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
        val nameOrder = sortArchiveIndices(
            listing, listing.indices.toList(), FileSortField.NAME, SortDirection.ASCENDING
        )
        assertEquals(listOf(1, 3, 2, 0), nameOrder)
        val selected = setOf(1, 3, 2)
        val sizeOrder = sortArchiveIndices(
            listing, listing.indices.toList(), FileSortField.SIZE, SortDirection.DESCENDING
        )
        assertEquals(listOf(3, 0, 2, 1), sizeOrder)
        assertEquals(
            selected,
            sizeOrder.filter { it in selected }.toSet()
        )
        val visible = archiveFilterIndices(listing, "alpha")
        val pruned = pruneArchiveSelection(selected, visible)
        assertEquals(setOf(1, 3), pruned)
        val plan = planArchiveDownloads(listing, pruned)
        assertEquals(listOf(1 to 0, 3 to 1), plan.map { it.entryIndex to it.occurrence })
    }
}
