package com.drdisagree.teledrive.presentation.preview

internal class ArchiveNode(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    var sizeBytes: Long = 0,
    var compressedBytes: Long = 0,
    /** Index of this leaf in the full archive listing; null for folders and the root. */
    val entryIndex: Int? = null,
    val children: MutableList<ArchiveNode> = mutableListOf()
)

private const val SEPARATOR = "/"

/**
 * ZIP stores flat paths, so folders without their own entry are created from the paths below
 * them. Folders merge by path segment (there is one folder row per path), but file leaves are
 * NEVER merged: every listing entry becomes its own leaf carrying its listing index, so two
 * entries with the same name render as two rows and each tap can download its own entry.
 */
internal fun buildArchiveTree(entries: List<ArchiveEntry>): ArchiveNode {
    val root = ArchiveNode(path = "", name = "", isDirectory = true)
    entries.forEachIndexed { entryIndex, entry ->
        val segments = entry.name.split(SEPARATOR).filter { it.isNotEmpty() }
        if (segments.isEmpty()) return@forEachIndexed
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
                    entryIndex = entryIndex
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
    node.children.forEach { child ->
        val (childSize, childPacked) = aggregateSizes(child)
        size += childSize
        packed += childPacked
    }
    node.sizeBytes = size
    node.compressedBytes = packed
    return size to packed
}

internal fun countFolders(node: ArchiveNode): Int =
    node.children.sumOf { child ->
        if (child.isDirectory) 1 + countFolders(child) else 0
    }

internal fun flattenArchive(
    root: ArchiveNode,
    expanded: Set<String>,
    depth: Int = 0,
    out: MutableList<ArchiveRowState> = mutableListOf()
): List<ArchiveRowState> {
    // sortedWith is stable, so duplicate leaves keep their listing order.
    val sorted = root.children.sortedWith(
        compareByDescending<ArchiveNode> { it.isDirectory }
            .thenBy { it.name.lowercase() }
    )
    for (child in sorted) {
        val isOpen = child.isDirectory && child.path in expanded
        out.add(ArchiveRowState(child, depth, isOpen))
        if (isOpen) flattenArchive(child, expanded, depth + 1, out)
    }
    return out
}
