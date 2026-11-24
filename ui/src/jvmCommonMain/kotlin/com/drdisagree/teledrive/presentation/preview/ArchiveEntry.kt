package com.drdisagree.teledrive.presentation.preview

data class ArchiveEntry(
    val name: String,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val isDirectory: Boolean,
    /** Entry last-modified time as epoch millis, or 0 when the archive carries none. */
    val modifiedAtMillis: Long = 0L
)

/**
 * The DOS date/time fields inside a ZIP cannot express a date before 1980-01-01 (the DOS
 * epoch), so a real entry timestamp is never below this floor. Anything smaller is a
 * broken conversion upstream (for example a Unix-seconds value read as milliseconds,
 * which lands in January 1970), never a real date.
 */
const val ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS: Long = 315_532_800_000L

/**
 * Normalizes a raw entry timestamp at the point an [ArchiveEntry] is built: values below
 * the DOS-epoch floor (and negatives) become 0, the established "unknown" value that the
 * row display hides and the sort places last. Values at or above the floor pass through.
 */
fun normalizeArchiveEntryModifiedAt(modifiedAtMillis: Long): Long =
    if (modifiedAtMillis < ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS) 0L else modifiedAtMillis

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
