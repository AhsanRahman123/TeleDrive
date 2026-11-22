package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection

internal class ArchiveNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    var sizeBytes: Long = 0,
    var compressedBytes: Long = 0,
    /** Index of this leaf in the full archive listing; null for folders and the root. */
    val entryIndex: Int? = null,
    /** Leaf entry time (epoch millis, 0 = none); folders aggregate the newest child time. */
    var modifiedAtMillis: Long = 0,
    val children: MutableList<ArchiveNode> = mutableListOf()
)

/** An archive entry paired with its index in the full listing. */
internal data class IndexedArchiveEntry(val entryIndex: Int, val entry: ArchiveEntry)

private const val SEPARATOR = "/"

/**
 * ZIP stores flat paths, so folders without their own entry are created from the paths below
 * them. Folders merge by path segment (there is one folder row per path), but file leaves are
 * NEVER merged: every listing entry becomes its own leaf carrying its listing index, so two
 * entries with the same name render as two rows and each tap can download its own entry.
 */
internal fun buildArchiveTree(entries: List<ArchiveEntry>): ArchiveNode =
    buildArchiveTreeIndexed(entries.mapIndexed { index, entry -> IndexedArchiveEntry(index, entry) })

internal fun buildArchiveTreeIndexed(indexed: List<IndexedArchiveEntry>): ArchiveNode {
    val root = ArchiveNode(path = "", name = "", isDirectory = true)
    indexed.forEach { (entryIndex, entry) ->
        val segments = entry.name.split(SEPARATOR).filter { it.isNotEmpty() }
        if (segments.isEmpty()) return@forEach
        var current = root
        segments.forEachIndexed { index, segment ->
            val leaf = index == segments.lastIndex && !entry.isDirectory
            val path = if (current.path.isEmpty()) segment else current.path + SEPARATOR + segment
            val child = if (leaf) {
                ArchiveNode(
                    path = path,
                    name = segment,
                    isDirectory = false,
                    sizeBytes = entry.sizeBytes,
                    compressedBytes = entry.compressedBytes,
                    entryIndex = entryIndex,
                    modifiedAtMillis = entry.modifiedAtMillis
                ).also { current.children += it }
            } else {
                current.children.firstOrNull { it.isDirectory && it.name == segment }
                    ?: ArchiveNode(path = path, name = segment, isDirectory = true)
                        .also { current.children += it }
            }
            current = child
        }
    }
    aggregateSizes(root)
    return root
}

private fun aggregateSizes(node: ArchiveNode): Pair<Long, Long> {
    if (!node.isDirectory) return node.sizeBytes to node.compressedBytes
    var size = 0L
    var packed = 0L
    var newest = 0L
    node.children.forEach { child ->
        val (childSize, childPacked) = aggregateSizes(child)
        size += childSize
        packed += childPacked
        if (child.modifiedAtMillis > newest) newest = child.modifiedAtMillis
    }
    node.sizeBytes = size
    node.compressedBytes = packed
    node.modifiedAtMillis = newest
    return size to packed
}

internal fun countFolders(node: ArchiveNode): Int =
    node.children.sumOf { child ->
        if (child.isDirectory) 1 + countFolders(child) else 0
    }

/** Every folder path in the tree (used to render a filtered tree fully expanded). */
internal fun allFolderPaths(node: ArchiveNode, out: MutableSet<String> = mutableSetOf()): Set<String> {
    node.children.forEach { child ->
        if (child.isDirectory) {
            out += child.path
            allFolderPaths(child, out)
        }
    }
    return out
}

/**
 * Sibling ordering for the archive tree, mirroring the main file browser: folders stay
 * first (the browser renders its folder block before its file block), and within each
 * group the chosen key applies. NAME compares case-insensitively (the browser's
 * COLLATE NOCASE), SIZE uses the (aggregated) byte size, TYPE the mime type string the
 * browser sorts by, and DATE_MODIFIED the entry time — with one placement rule for
 * missing timestamps: entries with no time (0) always sort AFTER dated entries in both
 * directions, so they never masquerade as 1980 files at the front of an ascending sort.
 * The sort is stable, so equal keys keep listing order.
 */
internal fun archiveNodeComparator(
    field: FileSortField,
    direction: SortDirection
): Comparator<ArchiveNode> {
    val ascending = direction == SortDirection.ASCENDING
    return Comparator { a, b ->
        when (field) {
            FileSortField.NAME -> {
                val result = a.name.lowercase().compareTo(b.name.lowercase())
                if (ascending) result else -result
            }
            FileSortField.SIZE -> {
                val result = a.sizeBytes.compareTo(b.sizeBytes)
                if (ascending) result else -result
            }
            FileSortField.DATE_MODIFIED -> {
                val aKnown = a.modifiedAtMillis > 0
                val bKnown = b.modifiedAtMillis > 0
                when {
                    aKnown && bKnown -> {
                        val result = a.modifiedAtMillis.compareTo(b.modifiedAtMillis)
                        if (ascending) result else -result
                    }
                    aKnown -> -1 // dated entries always before undated ones
                    bKnown -> 1
                    else -> 0
                }
            }
            FileSortField.TYPE -> {
                val result = archiveNodeTypeKey(a).compareTo(archiveNodeTypeKey(b))
                if (ascending) result else -result
            }
            // The archive menu offers only the keys above; anything else falls back to name.
            else -> {
                val result = a.name.lowercase().compareTo(b.name.lowercase())
                if (ascending) result else -result
            }
        }
    }
}

private fun archiveNodeTypeKey(node: ArchiveNode): String =
    if (node.isDirectory) "" else MimeTypes.fromFileName(node.name)

internal fun flattenArchive(
    root: ArchiveNode,
    expanded: Set<String>,
    sortField: FileSortField = FileSortField.NAME,
    sortDirection: SortDirection = SortDirection.ASCENDING,
    depth: Int = 0,
    out: MutableList<ArchiveRowState> = mutableListOf()
): List<ArchiveRowState> {
    // sortedWith is stable, so duplicate leaves keep their listing order under equal keys.
    val comparator = archiveNodeComparator(sortField, sortDirection)
    val sorted = root.children.sortedWith(
        compareByDescending<ArchiveNode> { it.isDirectory }
            .then(comparator)
    )
    for (child in sorted) {
        val isOpen = child.isDirectory && child.path in expanded
        out.add(ArchiveRowState(child, depth, isOpen))
        if (isOpen) flattenArchive(child, expanded, sortField, sortDirection, depth + 1, out)
    }
    return out
}

/**
 * Number of LazyColumn items rendered before the first archive row: the header card.
 * The drag-select helper works in LazyColumn list indices, so list index i maps to
 * rows[i - ARCHIVE_LIST_HEADER_ITEMS]; the trailing hint item maps to no row at all.
 */
internal const val ARCHIVE_LIST_HEADER_ITEMS = 1

/** The rendered row at a LazyColumn [listIndex], or null for the header/hint items. */
internal fun archiveRowAtListIndex(rows: List<ArchiveRowState>, listIndex: Int): ArchiveRowState? =
    rows.getOrNull(listIndex - ARCHIVE_LIST_HEADER_ITEMS)

/** Whether the row at LazyColumn [listIndex] is currently selected (drag anchor check). */
internal fun archiveDragAnchorSelected(
    rows: List<ArchiveRowState>,
    listIndex: Int,
    selectedEntries: Set<Int>,
    selectedFolders: Set<String>
): Boolean {
    val row = archiveRowAtListIndex(rows, listIndex) ?: return false
    return if (row.node.isDirectory) {
        row.node.path in selectedFolders
    } else {
        row.node.entryIndex?.let { it in selectedEntries } == true
    }
}

/** A selection after a drag-select range has been painted onto the drag-start snapshot. */
internal data class ArchiveDragSelection(
    val entries: Set<Int>,
    val folders: Set<String>
)

/**
 * Paints one drag-select [listRange] (LazyColumn list indices) onto the snapshot taken
 * when the drag started — the single place where list indices are translated to rows.
 * Folder rows and file rows go through the exact same translation: a range covering
 * exactly one row selects exactly that row's identity (listing index for a file, path
 * for a folder), a range covering N rows selects exactly those N rows, and the header
 * and hint indices at the ends of the list select nothing.
 */
internal fun applyArchiveDragRange(
    rows: List<ArchiveRowState>,
    listRange: IntRange,
    baseEntries: Set<Int>,
    baseFolders: Set<String>,
    shouldSelect: Boolean
): ArchiveDragSelection {
    val entryIndices = mutableListOf<Int>()
    val folderPaths = mutableListOf<String>()
    for (listIndex in listRange) {
        val row = archiveRowAtListIndex(rows, listIndex) ?: continue
        if (row.node.isDirectory) {
            folderPaths += row.node.path
        } else {
            row.node.entryIndex?.let { entryIndices += it }
        }
    }
    return ArchiveDragSelection(
        entries = applyArchiveRangeSelection(baseEntries, entryIndices, shouldSelect),
        folders = if (shouldSelect) {
            baseFolders + folderPaths
        } else {
            baseFolders - folderPaths.toSet()
        }
    )
}
