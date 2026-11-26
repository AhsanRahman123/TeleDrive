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
        // (A known non-media type: an unknown extension would consult the platform
        // MimeTypeMap, which is a stub in host unit tests.)
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("a/blob.zip")))
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("a/", isDirectory = true)))
    }

    @Test
    fun `pdf entries preview in place within the size cap and join the pager`() {
        assertEquals(ArchiveEntryTapAction.PREVIEW_PDF, archiveEntryTapAction(entry("docs/book.pdf")))
        assertTrue(archiveEntryPdfPreviewable(entry("docs/book.pdf", size = ARCHIVE_PDF_PREVIEW_MAX_BYTES)))
        assertFalse(
            archiveEntryPdfPreviewable(entry("docs/book.pdf", size = ARCHIVE_PDF_PREVIEW_MAX_BYTES + 1))
        )
        assertTrue(archiveEntryPreviewable(entry("docs/book.pdf")))
        assertFalse(archiveEntryPreviewable(entry("docs/book.pdf", size = ARCHIVE_PDF_PREVIEW_MAX_BYTES + 1)))
        // Subtitles route as text now (B7).
        assertEquals(ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("subs/movie.srt")))
        assertEquals(ArchiveEntryTapAction.PREVIEW_TEXT, archiveEntryTapAction(entry("subs/movie.vtt")))
        // EPUB + OOXML extract as document text (B8/B9); legacy Office stays download-only.
        assertEquals(ArchiveEntryTapAction.PREVIEW_DOCUMENT, archiveEntryTapAction(entry("books/novel.epub")))
        assertEquals(ArchiveEntryTapAction.PREVIEW_DOCUMENT, archiveEntryTapAction(entry("docs/report.docx")))
        assertEquals(ArchiveEntryTapAction.PREVIEW_DOCUMENT, archiveEntryTapAction(entry("docs/sheet.xlsx")))
        assertEquals(ArchiveEntryTapAction.PREVIEW_DOCUMENT, archiveEntryTapAction(entry("docs/deck.pptx")))
        assertTrue(archiveEntryPreviewable(entry("books/novel.epub")))
        assertFalse(
            archiveEntryPreviewable(
                entry("books/novel.epub", size = ARCHIVE_DOCUMENT_PREVIEW_MAX_BYTES + 1)
            )
        )
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("docs/old.doc")))
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("docs/old.xls")))
        assertEquals(ArchiveEntryTapAction.DOWNLOAD, archiveEntryTapAction(entry("docs/old.ppt")))
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

    // ---- Drag-select range translation (vc176 defect b) ----
    //
    // The drag-select helper works in LazyColumn list indices; the archive list's
    // item 0 is the header card and its last item is the hint, so list index i maps
    // to rows[i - 1]. These tests pin that translation: a range covering exactly
    // one row selects exactly that row, a range covering N rows selects exactly
    // those N rows, and folder rows behave identically to file rows.

    private fun dragRows(): List<ArchiveRowState> {
        val listing = listOf(
            entry("dirA/a.txt", 1),   // 0
            entry("dirA/b.txt", 2),   // 1
            entry("dirB/c.txt", 3),   // 2
            entry("loose.txt", 4),    // 3
            entry("another.txt", 5)   // 4
        )
        // Collapsed rows, folders first then files by name:
        // [dirA, dirB, another(4), loose(3)] at list indices 1..4.
        return flattenArchive(buildArchiveTree(listing), expanded = emptySet())
    }

    @Test
    fun `single long-press on one folder selects exactly that folder`() {
        val rows = dragRows()
        assertEquals(listOf("dirA", "dirB"), rows.take(2).map { it.node.path })
        // dirB is row position 1 → list index 2. A stationary long-press paints
        // the single-index range anchor..anchor.
        val painted = applyArchiveDragRange(rows, 2..2, emptySet(), emptySet(), shouldSelect = true)
        assertEquals(emptySet<Int>(), painted.entries)
        assertEquals(setOf("dirB"), painted.folders)
    }

    @Test
    fun `single long-press on one file selects exactly that file`() {
        val rows = dragRows()
        // loose.txt is row position 3 → list index 4, listing index 3.
        val painted = applyArchiveDragRange(rows, 4..4, emptySet(), emptySet(), shouldSelect = true)
        assertEquals(setOf(3), painted.entries)
        assertEquals(emptySet<String>(), painted.folders)
    }

    @Test
    fun `drag across N rows selects exactly those N rows, folders and files alike`() {
        val rows = dragRows()
        // List indices 1..3 cover rows dirA, dirB, another.txt — exactly 3 rows.
        val painted = applyArchiveDragRange(rows, 1..3, emptySet(), emptySet(), shouldSelect = true)
        assertEquals(setOf("dirA", "dirB"), painted.folders)
        assertEquals(setOf(4), painted.entries)
        // The full span covers every row and nothing else.
        val all = applyArchiveDragRange(rows, 1..4, emptySet(), emptySet(), shouldSelect = true)
        assertEquals(setOf("dirA", "dirB"), all.folders)
        assertEquals(setOf(3, 4), all.entries)
    }

    @Test
    fun `drag range reaching the header and hint items adds no extra row`() {
        val rows = dragRows()
        // List index 0 is the header card and rows.size + 1 is the hint: a range
        // that runs into either end must still select exactly the rows it covers.
        val painted = applyArchiveDragRange(
            rows, 0..(rows.size + 1), emptySet(), emptySet(), shouldSelect = true
        )
        assertEquals(setOf("dirA", "dirB"), painted.folders)
        assertEquals(setOf(3, 4), painted.entries)
    }

    @Test
    fun `drag across expanded folder children selects exactly the crossed files`() {
        val listing = listOf(
            entry("dirA/a.txt", 1),
            entry("dirA/b.txt", 2),
            entry("dirB/c.txt", 3)
        )
        val rows = flattenArchive(
            buildArchiveTree(listing), expanded = setOf("dirA")
        )
        // Rows: [dirA, a(0), b(1), dirB] at list indices 1..4.
        assertEquals(listOf("dirA", "a.txt", "b.txt", "dirB"), rows.map { it.node.name })
        val painted = applyArchiveDragRange(rows, 2..3, emptySet(), emptySet(), shouldSelect = true)
        assertEquals(setOf(0, 1), painted.entries)
        assertEquals(emptySet<String>(), painted.folders)
        // A single press on the expanded folder row itself still selects only it.
        val folderPress = applyArchiveDragRange(rows, 1..1, emptySet(), emptySet(), shouldSelect = true)
        assertEquals(setOf("dirA"), folderPress.folders)
        assertEquals(emptySet<Int>(), folderPress.entries)
    }

    @Test
    fun `deselect drag removes exactly the crossed rows from the snapshot`() {
        val rows = dragRows()
        val painted = applyArchiveDragRange(
            rows,
            listRange = 1..2,
            baseEntries = setOf(4),
            baseFolders = setOf("dirA", "dirB"),
            shouldSelect = false
        )
        assertEquals(emptySet<String>(), painted.folders)
        assertEquals(setOf(4), painted.entries)
    }

    @Test
    fun `anchor selected check reads the pressed row for folders and files alike`() {
        val rows = dragRows()
        assertTrue(archiveDragAnchorSelected(rows, 2, emptySet(), setOf("dirB")))
        assertFalse(archiveDragAnchorSelected(rows, 2, emptySet(), setOf("dirA")))
        assertTrue(archiveDragAnchorSelected(rows, 4, setOf(3), emptySet()))
        assertFalse(archiveDragAnchorSelected(rows, 4, setOf(4), emptySet()))
        // Header and hint indices are never a selected anchor.
        assertFalse(archiveDragAnchorSelected(rows, 0, setOf(3), setOf("dirB")))
        assertFalse(archiveDragAnchorSelected(rows, rows.size + 1, setOf(3), setOf("dirB")))
    }

    // ---- Back-step priority (vc176 defect a) ----

    @Test
    fun `back step priority is preview, search, selection, folder, exit`() {
        assertEquals(
            ArchiveBackStep.CLOSE_PREVIEW,
            archiveBackStep(hasPreview = true, searchActive = true, selectionActive = true, hasExpandedFolder = true)
        )
        assertEquals(
            ArchiveBackStep.CLEAR_SEARCH,
            archiveBackStep(hasPreview = false, searchActive = true, selectionActive = true, hasExpandedFolder = true)
        )
        assertEquals(
            ArchiveBackStep.EXIT_SELECTION,
            archiveBackStep(hasPreview = false, searchActive = false, selectionActive = true, hasExpandedFolder = true)
        )
        assertEquals(
            ArchiveBackStep.COLLAPSE_FOLDER,
            archiveBackStep(hasPreview = false, searchActive = false, selectionActive = false, hasExpandedFolder = true)
        )
        assertEquals(
            ArchiveBackStep.EXIT_VIEWER,
            archiveBackStep(hasPreview = false, searchActive = false, selectionActive = false, hasExpandedFolder = false)
        )
    }

    @Test
    fun `back collapses the deepest expanded folder first`() {
        assertEquals(
            "a/b/c",
            deepestExpandedArchiveFolder(setOf("a", "a/b", "a/b/c", "x/y"))
        )
        assertEquals("a/b", deepestExpandedArchiveFolder(setOf("a", "a/b")))
        assertEquals(null, deepestExpandedArchiveFolder(emptySet()))
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

    // ---- Select all (selection bar) ----

    @Test
    fun `select all selects every visible row, folders and files alike`() {
        // Collapsed view: [dirA, dirB, another(4), loose(3)].
        val all = selectAllArchiveVisible(dragRows(), ArchiveSelectionSet())
        assertEquals(setOf("dirA", "dirB"), all.folderPaths)
        assertEquals(setOf(3, 4), all.entryIndices)
        // The plan covers the whole archive exactly once: folder rows pull in
        // their contents, loose files are planned flat.
        val listing = listOf(
            entry("dirA/a.txt", 1),
            entry("dirA/b.txt", 2),
            entry("dirB/c.txt", 3),
            entry("loose.txt", 4),
            entry("another.txt", 5)
        )
        val plan = planArchiveSelectionDownloads(listing, all.entryIndices, all.folderPaths)
        assertEquals(listOf(0, 1, 2, 3, 4), plan.map { it.entryIndex })
    }

    @Test
    fun `select all in an expanded view adds the visible children too`() {
        val listing = listOf(
            entry("dirA/a.txt", 1),
            entry("dirA/b.txt", 2),
            entry("dirB/c.txt", 3)
        )
        val rows = flattenArchive(buildArchiveTree(listing), expanded = setOf("dirA"))
        val all = selectAllArchiveVisible(rows, ArchiveSelectionSet())
        assertEquals(setOf("dirA", "dirB"), all.folderPaths)
        assertEquals(setOf(0, 1), all.entryIndices)
    }

    @Test
    fun `select all merges onto the current selection instead of replacing it`() {
        // Entry 99 is not a visible row (e.g. selected before a folder was
        // collapsed); Select all must keep it, like Transfers' Select visible.
        val merged = selectAllArchiveVisible(
            dragRows(),
            ArchiveSelectionSet(entryIndices = setOf(99))
        )
        assertEquals(setOf(99, 3, 4), merged.entryIndices)
        assertEquals(setOf("dirA", "dirB"), merged.folderPaths)
    }

    // ---- Preview pager data source (vc180) ----

    @Test
    fun `previewable means image, audio, or text within the cap`() {
        assertTrue(archiveEntryPreviewable(entry("photo.png")))
        assertTrue(archiveEntryPreviewable(entry("song.mp3")))
        assertTrue(archiveEntryPreviewable(entry("notes.txt", size = ARCHIVE_TEXT_PREVIEW_MAX_BYTES)))
        // An oversized text entry has no page: on tap it only shows the too-large message.
        assertFalse(archiveEntryPreviewable(entry("notes.txt", size = ARCHIVE_TEXT_PREVIEW_MAX_BYTES + 1)))
        // Download-only types are skipped, never shown as placeholder pages.
        assertFalse(archiveEntryPreviewable(entry("movie.mp4")))
        assertFalse(archiveEntryPreviewable(entry("blob.zip")))
        assertFalse(archiveEntryPreviewable(entry("dir/", isDirectory = true)))
    }

    @Test
    fun `pager follows the displayed order and skips download-only entries`() {
        val listing = listOf(
            entry("b/photo2.png", 1),                                        // 0 image
            entry("a/song.mp3", 2),                                          // 1 audio
            entry("b/movie.mp4", 3),                                         // 2 video: skipped
            entry("a/notes.txt", 4),                                         // 3 text
            entry("root.gif", 5),                                            // 4 image
            entry("big.txt", ARCHIVE_TEXT_PREVIEW_MAX_BYTES + 1),            // 5 oversized text: skipped
            entry("a/", 0, isDirectory = true)                               // 6 directory: no page
        )
        // Fully expanded NAME-ascending rows: a/notes(3), a/song(1), b/movie(2),
        // b/photo2(0), big(5), root(4) — folders first per level, files by name.
        assertEquals(listOf(3, 1, 0, 4), archivePreviewPagerIndices(listing))
    }

    @Test
    fun `pager order follows the archive sort`() {
        val listing = listOf(
            entry("a.png", 30),
            entry("b.mp3", 10),
            entry("c.txt", 20)
        )
        assertEquals(
            listOf(0, 2, 1),
            archivePreviewPagerIndices(listing, FileSortField.SIZE, SortDirection.DESCENDING)
        )
        assertEquals(
            listOf(1, 2, 0),
            archivePreviewPagerIndices(listing, FileSortField.SIZE, SortDirection.ASCENDING)
        )
    }

    @Test
    fun `pager keeps duplicate names as separate pages`() {
        val listing = listOf(entry("dup.png", 1), entry("dup.png", 2), entry("other.png", 3))
        val pager = archivePreviewPagerIndices(listing)
        assertEquals(listOf(0, 1, 2), pager)
        assertEquals(1, archivePreviewPagerPosition(pager, 1))
    }

    @Test
    fun `pager is empty when nothing is previewable`() {
        assertEquals(emptyList<Int>(), archivePreviewPagerIndices(emptyList()))
        assertEquals(
            emptyList<Int>(),
            archivePreviewPagerIndices(listOf(entry("movie.mp4"), entry("blob.zip")))
        )
        // A download-only entry has no position in the sequence.
        assertEquals(-1, archivePreviewPagerPosition(listOf(0, 2), 1))
    }

    @Test
    fun `pager target clamps at both ends without wrapping`() {
        assertEquals(1, archivePreviewPagerTarget(pagerSize = 3, position = 0, delta = 1))
        assertEquals(1, archivePreviewPagerTarget(pagerSize = 3, position = 2, delta = -1))
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 3, position = 0, delta = -1))
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 3, position = 2, delta = 1))
        // A fast swipe can settle more than one page away: the full delta applies…
        assertEquals(4, archivePreviewPagerTarget(pagerSize = 5, position = 1, delta = 3))
        // …but never past the end.
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 5, position = 1, delta = 4))
        // Empty sequence and unknown positions stay put.
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 0, position = 0, delta = 1))
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 3, position = -1, delta = 1))
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 3, position = 3, delta = -1))
    }
}
