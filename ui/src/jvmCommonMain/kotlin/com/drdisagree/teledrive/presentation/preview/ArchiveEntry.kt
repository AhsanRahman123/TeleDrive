package com.drdisagree.teledrive.presentation.preview

data class ArchiveEntry(
    val name: String,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val isDirectory: Boolean
)

/**
 * The occurrence of the entry at [entryIndex] in [listing]: how many non-directory entries
 * with the same name come before it. Resolved purely by position, never by entry equality,
 * so two identical duplicate entries still get distinct occurrences (0, 1, ...). This is the
 * same counting rule RemoteArchiveService uses to pick an entry, so both sides agree.
 * Returns -1 when [entryIndex] is out of bounds.
 */
fun archiveEntryOccurrence(listing: List<ArchiveEntry>, entryIndex: Int): Int {
    val entry = listing.getOrNull(entryIndex) ?: return -1
    return listing.subList(0, entryIndex).count { it.name == entry.name && !it.isDirectory }
}
