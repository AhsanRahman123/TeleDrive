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

/**
 * The listing indices a flat (non-tree) archive view shows under [query]: every match from
 * [archiveFilterIndices], plus each directory entry that CONTAINS a match — a folder row
 * stays visible when its own name matches (it is then itself a match) or when any entry
 * under its path matches, so matches remain reachable through their folder.
 */
fun archiveVisibleIndices(listing: List<ArchiveEntry>, query: String): Set<Int> {
    val matches = archiveFilterIndices(listing, query)
    if (query.isBlank()) return listing.indices.toSet()
    val visible = matches.toMutableSet()
    listing.forEachIndexed { index, entry ->
        if (entry.isDirectory && index !in visible) {
            val prefix = entry.name.replace('\\', '/').trim('/') + "/"
            if (matches.any { listing[it].name.replace('\\', '/').startsWith(prefix) }) {
                visible += index
            }
        }
    }
    return visible
}

/**
 * Orders [indices] (listing indices) for display, mirroring the main file browser and the
 * Android archive tree's sibling ordering: folders first; within each group the chosen key
 * applies — NAME case-insensitively, SIZE by byte size, TYPE by mime type string, and
 * DATE_MODIFIED by entry time with undated entries (0) AFTER dated ones in BOTH
 * directions. The sort is stable, so equal keys keep listing order, and because it only
 * reorders indices, selection/preview/download identity is sort-proof.
 */
fun sortArchiveIndices(
    listing: List<ArchiveEntry>,
    indices: List<Int>,
    field: FileSortField,
    direction: SortDirection
): List<Int> {
    val ascending = direction == SortDirection.ASCENDING
    val keyComparator = Comparator<Int> { a, b ->
        val entryA = listing[a]
        val entryB = listing[b]
        when (field) {
            FileSortField.NAME -> {
                val result = entryA.name.lowercase().compareTo(entryB.name.lowercase())
                if (ascending) result else -result
            }
            FileSortField.SIZE -> {
                val result = entryA.sizeBytes.compareTo(entryB.sizeBytes)
                if (ascending) result else -result
            }
            FileSortField.DATE_MODIFIED -> {
                val aKnown = entryA.modifiedAtMillis > 0
                val bKnown = entryB.modifiedAtMillis > 0
                when {
                    aKnown && bKnown -> {
                        val result = entryA.modifiedAtMillis.compareTo(entryB.modifiedAtMillis)
                        if (ascending) result else -result
                    }
                    aKnown -> -1 // dated entries always before undated ones
                    bKnown -> 1
                    else -> 0
                }
            }
            FileSortField.TYPE -> {
                val result = archiveTypeKey(entryA).compareTo(archiveTypeKey(entryB))
                if (ascending) result else -result
            }
            else -> {
                val result = entryA.name.lowercase().compareTo(entryB.name.lowercase())
                if (ascending) result else -result
            }
        }
    }
    return indices.sortedWith(
        compareByDescending<Int> { listing[it].isDirectory }.then(keyComparator)
    )
}

private fun archiveTypeKey(entry: ArchiveEntry): String =
    if (entry.isDirectory) "" else MimeTypes.fromFileName(entry.name)
