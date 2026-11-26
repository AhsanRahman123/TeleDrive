package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection

/**
 * Pure decision logic for the ZIP viewer's richer interactions (in-archive image/audio/text
 * preview, multi-selection, folder download, search filter). Everything here is keyed by
 * LISTING INDEX — the position of an entry in the archive's central-directory listing —
 * never by name, so duplicate names, re-sorting and filtering can never retarget an action
 * onto a different entry than the one the user saw. The listing itself is never reordered;
 * sorting and filtering only change which rows are displayed and in what order.
 */

/** What a tap on a file row does. Video is deliberately DOWNLOAD: it never plays in-archive. */
enum class ArchiveEntryTapAction { PREVIEW_IMAGE, PLAY_AUDIO, PREVIEW_TEXT, DOWNLOAD }

fun archiveEntryTapAction(entry: ArchiveEntry): ArchiveEntryTapAction {
    if (entry.isDirectory) return ArchiveEntryTapAction.DOWNLOAD
    val mimeType = MimeTypes.fromFileName(entry.name)
    return when {
        MimeTypes.isImage(mimeType) -> ArchiveEntryTapAction.PREVIEW_IMAGE
        MimeTypes.isAudio(mimeType) -> ArchiveEntryTapAction.PLAY_AUDIO
        // The app's own text classification (MimeTypes.isText) decides what counts as text.
        MimeTypes.isText(mimeType) -> ArchiveEntryTapAction.PREVIEW_TEXT
        // Video and everything else stay download-only.
        else -> ArchiveEntryTapAction.DOWNLOAD
    }
}

/**
 * Whether [entry] can appear in the in-zip preview pager at all: it must open in place
 * (image / audio / text) rather than download, and a text entry must be within the
 * preview size cap — an oversized text entry only ever shows the too-large message on
 * tap, so it has no page to swipe to.
 */
fun archiveEntryPreviewable(entry: ArchiveEntry): Boolean =
    when (archiveEntryTapAction(entry)) {
        ArchiveEntryTapAction.PREVIEW_IMAGE,
        ArchiveEntryTapAction.PLAY_AUDIO -> true
        ArchiveEntryTapAction.PREVIEW_TEXT -> archiveEntryTextPreviewable(entry)
        ArchiveEntryTapAction.DOWNLOAD -> false
    }

/**
 * The in-zip preview pager sequence: the LISTING INDICES of the previewable entries of
 * [listing], in the order the archive list shows them — the archive tree flattened with
 * every folder expanded under the remembered archive sort, i.e. the rows of the fully
 * expanded list with the download-only rows removed. Download-only entries (video
 * included) are skipped rather than shown as placeholders, matching the Files pager,
 * whose sequence is built from the browsed query and simply contains no page for a
 * file that is not in it. Identity stays the listing index, so duplicate names each
 * keep their own page and extraction.
 */
fun archivePreviewPagerIndices(
    listing: List<ArchiveEntry>,
    sortField: FileSortField = FileSortField.NAME,
    sortDirection: SortDirection = SortDirection.ASCENDING
): List<Int> {
    if (listing.isEmpty()) return emptyList()
    val root = buildArchiveTreeIndexed(
        listing.mapIndexed { index, entry -> IndexedArchiveEntry(index, entry) }
    )
    val rows = flattenArchive(root, allFolderPaths(root), sortField, sortDirection)
    return rows.mapNotNull { row ->
        val entryIndex = row.node.entryIndex ?: return@mapNotNull null
        entryIndex.takeIf { archiveEntryPreviewable(listing[entryIndex]) }
    }
}

/** The position of [entryIndex] in a pager sequence, or -1 when it has no page. */
fun archivePreviewPagerPosition(pagerIndices: List<Int>, entryIndex: Int): Int =
    pagerIndices.indexOf(entryIndex)

/**
 * Where one pager step lands: [position] moved by [delta] (negative = previous),
 * clamped to the sequence — no wrap-around, like the Files pager for image/text
 * pages. Null when the step would leave the sequence (already at that end), the
 * sequence is empty, or [position] is not a valid page, so callers can treat a
 * null as "stay".
 */
fun archivePreviewPagerTarget(pagerSize: Int, position: Int, delta: Int): Int? {
    if (pagerSize <= 0 || position !in 0 until pagerSize) return null
    return (position + delta).takeIf { it in 0 until pagerSize }
}

/** Largest entry offered an in-archive text preview: 2 MiB of extracted text. */
const val ARCHIVE_TEXT_PREVIEW_MAX_BYTES: Long = 2L * 1024 * 1024

/** True when [entry] may be previewed as text: right type AND within the size cap. */
fun archiveEntryTextPreviewable(entry: ArchiveEntry): Boolean =
    archiveEntryTapAction(entry) == ArchiveEntryTapAction.PREVIEW_TEXT &&
        entry.sizeBytes <= ARCHIVE_TEXT_PREVIEW_MAX_BYTES

/** One planned download: the entry at [entryIndex], saved under [subFolderPath] ("" = flat). */
data class ArchiveDownloadStep(
    val entryIndex: Int,
    val entryName: String,
    val occurrence: Int,
    val subFolderPath: String = ""
)

/** The entry's file name (last path segment), as downloads and messages display it. */
fun archiveEntryDisplayName(entry: ArchiveEntry): String =
    entry.name.substringAfterLast('/').substringAfterLast('\\').ifBlank { entry.name }

/** The entry's in-archive parent folder path ("" at the archive root), '/'-separated. */
fun archiveEntryParentPath(entry: ArchiveEntry): String =
    entry.name.replace('\\', '/').substringBeforeLast('/', "")

/**
 * Plans downloads for directly selected file entries, in listing order, saved flat —
 * the same destination a single tap-download used before selection existed.
 * Directory and out-of-range indices are skipped.
 */
fun planArchiveDownloads(
    listing: List<ArchiveEntry>,
    entryIndices: Collection<Int>
): List<ArchiveDownloadStep> = entryIndices
    .distinct()
    .sorted()
    .mapNotNull { index ->
        val entry = listing.getOrNull(index) ?: return@mapNotNull null
        if (entry.isDirectory) return@mapNotNull null
        val occurrence = archiveEntryOccurrence(listing, index)
        if (occurrence < 0) null
        else ArchiveDownloadStep(index, entry.name, occurrence)
    }

/**
 * Every non-directory entry inside [folderPath] (an archive folder path as carried by the
 * tree, '/'-separated, no trailing slash), recursively, in listing order. Matching is by
 * path prefix on the normalized entry name, so nested folders are included and two
 * same-named folders in different parents never mix. The folder's own directory entry,
 * when the archive has one, is not a download and is excluded.
 */
fun expandArchiveFolder(listing: List<ArchiveEntry>, folderPath: String): List<Int> {
    val prefix = folderPath.replace('\\', '/').trim('/') + "/"
    return listing.indices.filter { index ->
        val entry = listing[index]
        !entry.isDirectory && entry.name.replace('\\', '/').startsWith(prefix)
    }
}

/**
 * Plans a folder download: every file under each folder in [folderPaths], each saved into
 * its in-archive parent folder so the folder structure is recreated under Downloads.
 * An entry reached through several selected folders is planned once.
 */
fun planArchiveFolderDownloads(
    listing: List<ArchiveEntry>,
    folderPaths: Collection<String>
): List<ArchiveDownloadStep> {
    val indices = folderPaths.flatMap { expandArchiveFolder(listing, it) }.distinct().sorted()
    return indices.mapNotNull { index ->
        val entry = listing.getOrNull(index) ?: return@mapNotNull null
        val occurrence = archiveEntryOccurrence(listing, index)
        if (occurrence < 0) null
        else ArchiveDownloadStep(
            entryIndex = index,
            entryName = entry.name,
            occurrence = occurrence,
            subFolderPath = archiveEntryParentPath(entry)
        )
    }
}

/**
 * Merges a direct file selection with folder selections into one plan. An entry covered
 * by a selected folder keeps the folder's structured destination; a directly selected
 * entry outside every selected folder is saved flat. One entry is never planned twice.
 */
fun planArchiveSelectionDownloads(
    listing: List<ArchiveEntry>,
    entryIndices: Collection<Int>,
    folderPaths: Collection<String>
): List<ArchiveDownloadStep> {
    val structured = planArchiveFolderDownloads(listing, folderPaths)
    val covered = structured.map { it.entryIndex }.toSet()
    val flat = planArchiveDownloads(listing, entryIndices.filter { it !in covered })
    return (structured + flat).sortedBy { it.entryIndex }
}

/** Toggle one entry in a selection set — the main browser's tap-to-toggle in selection mode. */
fun toggleArchiveSelection(selection: Set<Int>, entryIndex: Int): Set<Int> =
    if (entryIndex in selection) selection - entryIndex else selection + entryIndex

/** A selection over the archive tree: file entries by listing index, folders by path. */
data class ArchiveSelectionSet(
    val entryIndices: Set<Int> = emptySet(),
    val folderPaths: Set<String> = emptySet()
)

/**
 * The selection bar's Select all: every row in the current folder view — the
 * flattened [rows] the list is showing right now, so an active search filter or
 * collapsed folders bound it exactly like Transfers' "Select visible" bounds
 * its list — merged onto the current selection. File rows contribute their
 * listing index; folder rows contribute their path (a selected folder downloads
 * its whole contents, so rows inside a collapsed folder are covered through it).
 */
fun selectAllArchiveVisible(
    rows: List<ArchiveRowState>,
    current: ArchiveSelectionSet
): ArchiveSelectionSet {
    // Accumulate into mutable sets: rebuilding with `set + element` per row is
    // O(n²) in set copies (~11k visible rows froze the UI thread).
    val entries = current.entryIndices.toMutableSet()
    val folders = current.folderPaths.toMutableSet()
    for (row in rows) {
        val node = row.node
        if (node.isDirectory) {
            folders.add(node.path)
        } else {
            node.entryIndex?.let { entries.add(it) }
        }
    }
    return ArchiveSelectionSet(entries, folders)
}

/**
 * The main browser's range-selection rule (FilesViewModel.extendRangeSelection): a drag
 * paints [rangeIndices] onto the snapshot taken when the drag started — selecting when
 * [shouldSelect], deselecting otherwise — never onto the live set, so moving the finger
 * back shrinks the painted range again instead of accumulating.
 */
fun applyArchiveRangeSelection(
    base: Set<Int>,
    rangeIndices: Collection<Int>,
    shouldSelect: Boolean
): Set<Int> = if (shouldSelect) base + rangeIndices else base - rangeIndices.toSet()

/**
 * Search matching for one entry, mirroring the main view's search: a case-insensitive
 * substring match on the file name. A file also matches when any of its ancestor folder
 * names matches, because the app's folder search returns the folder itself and the tree
 * must keep the path to it reachable.
 */
fun archiveEntryMatchesQuery(entry: ArchiveEntry, query: String): Boolean {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return true
    val segments = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() }
    return segments.any { it.lowercase().contains(needle) }
}

/**
 * The set of listing indices visible under [query]: every matching entry, plus — so the
 * tree can render the path — nothing else needs adding here because folder rows are
 * derived from the included entries' paths by the tree builder.
 */
fun archiveFilterIndices(listing: List<ArchiveEntry>, query: String): Set<Int> =
    listing.indices.filter { archiveEntryMatchesQuery(listing[it], query) }.toSet()

/**
 * Selection pruning under an active filter, mirroring SearchViewModel: when the results
 * change, the selection is intersected with what still matches — a selection cannot
 * outlive the result set it was made from.
 */
fun pruneArchiveSelection(selection: Set<Int>, visibleIndices: Set<Int>): Set<Int> =
    selection intersect visibleIndices

/** One step of the ZIP viewer's back navigation, in the order back must consume them. */
enum class ArchiveBackStep { CLOSE_PREVIEW, CLEAR_SEARCH, EXIT_SELECTION, COLLAPSE_FOLDER, EXIT_VIEWER }

/**
 * The ZIP viewer's back priority, shared by the on-screen back arrow and the system
 * back button so the two can never drift apart: close an open in-zip preview first,
 * then clear an active search, then exit selection mode, then go up one in-zip folder
 * level (collapse the deepest expanded folder), and only leave the viewer when none
 * of those apply.
 */
fun archiveBackStep(
    hasPreview: Boolean,
    searchActive: Boolean,
    selectionActive: Boolean,
    hasExpandedFolder: Boolean
): ArchiveBackStep = when {
    hasPreview -> ArchiveBackStep.CLOSE_PREVIEW
    searchActive -> ArchiveBackStep.CLEAR_SEARCH
    selectionActive -> ArchiveBackStep.EXIT_SELECTION
    hasExpandedFolder -> ArchiveBackStep.COLLAPSE_FOLDER
    else -> ArchiveBackStep.EXIT_VIEWER
}

/**
 * The folder one back step collapses: the deepest expanded folder (most path
 * segments). Ties break on the path itself so the choice is deterministic for a
 * given expansion set. Null when nothing is expanded (the viewer is at the zip root).
 */
fun deepestExpandedArchiveFolder(expanded: Collection<String>): String? =
    expanded.maxWithOrNull(
        compareBy<String> { path -> path.count { it == '/' } }.thenBy { it }
    )
