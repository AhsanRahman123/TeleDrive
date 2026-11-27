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

/**
 * What a tap on a file row does in the SHARED routing. Video is deliberately
 * DOWNLOAD here: this decision is shared with Android, whose in-zip video
 * arrives on its own line. The desktop pane intercepts video taps before this
 * routing and plays them in the desktop player instead (1.6.24 note 9, via
 * PreviewViewModel.previewArchiveVideoEntry); the per-entry Download action
 * stays available for video entries on both platforms.
 */
enum class ArchiveEntryTapAction {
    PREVIEW_IMAGE, PLAY_AUDIO, PREVIEW_TEXT, PREVIEW_PDF, PREVIEW_DOCUMENT, DOWNLOAD
}

fun archiveEntryTapAction(entry: ArchiveEntry): ArchiveEntryTapAction {
    if (entry.isDirectory) return ArchiveEntryTapAction.DOWNLOAD
    val mimeType = MimeTypes.fromFileName(entry.name)
    return when {
        MimeTypes.isImage(mimeType) -> ArchiveEntryTapAction.PREVIEW_IMAGE
        MimeTypes.isAudio(mimeType) -> ArchiveEntryTapAction.PLAY_AUDIO
        MimeTypes.isPdf(mimeType) -> ArchiveEntryTapAction.PREVIEW_PDF
        // EPUB and the OOXML trio extract to readable text from the extracted temp file.
        MimeTypes.isEpub(mimeType) || MimeTypes.isOoxml(mimeType) ->
            ArchiveEntryTapAction.PREVIEW_DOCUMENT
        // The app's own text classification (MimeTypes.isText) decides what counts as text.
        MimeTypes.isText(mimeType) -> ArchiveEntryTapAction.PREVIEW_TEXT
        // Video and everything else stay download-only in this shared routing
        // (the desktop pane intercepts video taps before it, as before).
        else -> ArchiveEntryTapAction.DOWNLOAD
    }
}

/** Largest entry offered an in-archive text preview: 2 MiB of extracted text. */
const val ARCHIVE_TEXT_PREVIEW_MAX_BYTES: Long = 2L * 1024 * 1024

/** Largest entry offered an in-archive PDF preview: 50 MiB of extracted PDF. */
const val ARCHIVE_PDF_PREVIEW_MAX_BYTES: Long = 50L * 1024 * 1024

/** True when [entry] may be previewed as a PDF: right type AND within the size cap. */
fun archiveEntryPdfPreviewable(entry: ArchiveEntry): Boolean =
    archiveEntryTapAction(entry) == ArchiveEntryTapAction.PREVIEW_PDF &&
        entry.sizeBytes <= ARCHIVE_PDF_PREVIEW_MAX_BYTES

/** Largest entry offered an in-archive document (EPUB/OOXML) text extraction: 20 MiB. */
const val ARCHIVE_DOCUMENT_PREVIEW_MAX_BYTES: Long = 20L * 1024 * 1024

/** True when [entry] may be extracted as document text: right type AND within the cap. */
fun archiveEntryDocumentPreviewable(entry: ArchiveEntry): Boolean =
    archiveEntryTapAction(entry) == ArchiveEntryTapAction.PREVIEW_DOCUMENT &&
        entry.sizeBytes <= ARCHIVE_DOCUMENT_PREVIEW_MAX_BYTES

/**
 * Whether [entry] can be previewed in place at all under the caps: image and
 * audio always; text, PDF and document entries only within their caps.
 */
fun archiveEntryPreviewable(entry: ArchiveEntry): Boolean =
    when (archiveEntryTapAction(entry)) {
        ArchiveEntryTapAction.PREVIEW_IMAGE,
        ArchiveEntryTapAction.PLAY_AUDIO -> true
        ArchiveEntryTapAction.PREVIEW_TEXT -> archiveEntryTextPreviewable(entry)
        ArchiveEntryTapAction.PREVIEW_PDF -> archiveEntryPdfPreviewable(entry)
        ArchiveEntryTapAction.PREVIEW_DOCUMENT -> archiveEntryDocumentPreviewable(entry)
        ArchiveEntryTapAction.DOWNLOAD -> false
    }

/**
 * Natural ordering for archive paths: digit runs compare by numeric value, so
 * `page2.jpg` sorts before `page10.jpg` (the archive list's plain lexicographic
 * NAME sort gives 1, 10, 11, 2, … — unusable for comic pages). Non-digit runs
 * compare case-insensitively. Digit runs compare by stripped-of-leading-zeros
 * length, then lexicographically, so absurdly long runs cannot overflow.
 * Numerically equal runs (zero-padded variants like `001.jpg` vs `1.jpg`) fall
 * through to the remaining characters; the caller adds a listing-index
 * tie-break, so the overall order is always deterministic.
 */
fun compareNaturalPaths(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var endA = i
            while (endA < a.length && a[endA].isDigit()) endA++
            var endB = j
            while (endB < b.length && b[endB].isDigit()) endB++
            val runA = a.substring(i, endA).trimStart('0')
            val runB = b.substring(j, endB).trimStart('0')
            if (runA.length != runB.length) return runA.length - runB.length
            val byDigits = runA.compareTo(runB)
            if (byDigits != 0) return byDigits
            i = endA
            j = endB
        } else {
            val byChar = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (byChar != 0) return byChar
            i++
            j++
        }
    }
    return (a.length - i) - (b.length - j)
}

/**
 * The CBZ comic pager sequence: the LISTING INDICES of the archive's page
 * entries, in reading order. Pages are IMAGE entries only, filtered by image
 * mime explicitly — a ZIP pager's text rule would turn `ComicInfo.xml` (XML
 * counts as text) into a page, and `.txt`/`.nfo`/`Thumbs.db`/nested archives
 * are not pages either. Order is the natural sort of the full normalized
 * path, so flat books and one-folder books both read 1, 2, …, 10; ties (and
 * duplicate names) break on the listing index, so every page keeps its own
 * listing-index identity for extraction. Ported from Android unchanged.
 */
fun comicPageSequence(listing: List<ArchiveEntry>): List<Int> =
    listing.indices
        .filter { index ->
            val entry = listing[index]
            // macOS resource-fork junk carries real image extensions, so the
            // mime filter alone cannot keep it out of the page sequence.
            val segments = entry.name.replace('\\', '/').split('/')
            !entry.isDirectory && "__MACOSX" !in segments &&
                MimeTypes.isImage(MimeTypes.fromFileName(entry.name))
        }
        .sortedWith { first, second ->
            val byPath = compareNaturalPaths(
                listing[first].name.replace('\\', '/'),
                listing[second].name.replace('\\', '/')
            )
            if (byPath != 0) byPath else first - second
        }

/**
 * The listing indices an in-zip photo pager steps through, in DISPLAYED order:
 * the image entries among [displayedIndices] (listing indices in the order the
 * viewer currently displays them — tree, sort and filter already applied by the
 * caller). Paging never leaves the displayed set, so a photo in a collapsed or
 * filtered-out folder is not a pager stop, exactly like Android's zip pager
 * moving through the photos of the folder on screen.
 */
fun archiveImagePagerIndices(
    listing: List<ArchiveEntry>,
    displayedIndices: List<Int>
): List<Int> = displayedIndices.filter { index ->
    val entry = listing.getOrNull(index)
    entry != null && !entry.isDirectory &&
        archiveEntryTapAction(entry) == ArchiveEntryTapAction.PREVIEW_IMAGE
}

/**
 * The pager neighbour of [currentIndex] within [pagerIndices] for [step]
 * (-1 = previous, +1 = next), or null at either end / when the current entry
 * is not a pager stop. No wrap-around: the ends are hard stops, like the real
 * photo viewer's pager.
 */
fun archiveImagePagerNeighbour(
    pagerIndices: List<Int>,
    currentIndex: Int,
    step: Int
): Int? {
    val position = pagerIndices.indexOf(currentIndex)
    if (position < 0) return null
    return pagerIndices.getOrNull(position + step)
}

/** Headroom kept free beyond an in-archive video's own temp file. */
const val ARCHIVE_VIDEO_SPACE_MARGIN_BYTES: Long = 16L * 1024 * 1024

/**
 * The free-space preflight for in-archive video (Android vc181/vc182): the
 * entry's exact uncompressed size plus a fixed margin must fit in [freeBytes]
 * BEFORE extraction starts, so a disk-full mid-extraction is never misreported
 * as archive corruption. Subtraction-first so no size can overflow it.
 */
fun archiveVideoFitsInFreeSpace(entrySizeBytes: Long, freeBytes: Long): Boolean =
    entrySizeBytes >= 0 &&
        freeBytes - entrySizeBytes >= ARCHIVE_VIDEO_SPACE_MARGIN_BYTES

/**
 * Whether [entry] can be stepped to in the UNIFIED in-zip preview sequence
 * (PC 1.6.25 item 7): every type the desktop in-zip viewer can open in place —
 * image, audio, text within its preview cap, PDF and document entries within
 * their caps, and video (extract-then-play with
 * the free-space preflight). Download-only entries are skipped, never shown as
 * placeholders. Identity is the listing index throughout.
 */
fun archiveEntryUnifiedPreviewable(entry: ArchiveEntry): Boolean {
    if (entry.isDirectory) return false
    return when (archiveEntryTapAction(entry)) {
        ArchiveEntryTapAction.PREVIEW_IMAGE,
        ArchiveEntryTapAction.PLAY_AUDIO -> true
        ArchiveEntryTapAction.PREVIEW_TEXT -> archiveEntryTextPreviewable(entry)
        // PDF and document (EPUB/OOXML) entries joined the in-zip viewer with
        // the viewers merge: both publish a preview (Pdf / extracted Text)
        // carrying their listing index, so both are unified-sequence stops,
        // each within its own preview cap.
        ArchiveEntryTapAction.PREVIEW_PDF -> archiveEntryPdfPreviewable(entry)
        ArchiveEntryTapAction.PREVIEW_DOCUMENT -> archiveEntryDocumentPreviewable(entry)
        ArchiveEntryTapAction.DOWNLOAD ->
            // Video is DOWNLOAD in the shared tap routing (the desktop pane
            // intercepts it for its player); it IS a unified-sequence stop.
            com.drdisagree.teledrive.core.files.MimeTypes.isVideo(
                com.drdisagree.teledrive.core.files.MimeTypes.fromFileName(entry.name)
            )
    }
}

/**
 * The unified in-zip stepping sequence: the previewable entries among
 * [displayedIndices] (listing indices in the order the viewer currently
 * displays them — tree, sort and filter already applied by the caller), in
 * display order, across ALL previewable types. Stepping never leaves the
 * displayed set.
 */
fun archiveUnifiedPreviewIndices(
    listing: List<ArchiveEntry>,
    displayedIndices: List<Int>
): List<Int> = displayedIndices.filter { index ->
    val entry = listing.getOrNull(index)
    entry != null && archiveEntryUnifiedPreviewable(entry)
}

/**
 * The unified-sequence neighbour of [currentIndex] for [step] (-1/+1), or null
 * at either end / when the current entry is not a sequence stop. No wrap.
 */
fun archiveUnifiedPreviewNeighbour(
    sequence: List<Int>,
    currentIndex: Int,
    step: Int
): Int? = archiveImagePagerNeighbour(sequence, currentIndex, step)

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
