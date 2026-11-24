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
 * Interprets zip4j's `FileHeader.lastModifiedTime` for the local listing path.
 *
 * Proven against zip4j on a hand-built zip (DesktopArchiveDateTest probes): for an entry
 * whose DOS date is zero, zip4j yields 0; for an entry with a real DOS date and no
 * timestamp extra field, zip4j yields **the packed DOS dword itself** —
 * `(dosDate shl 16) or dosTime` — as the "millis" value. Read as epoch millis that lands
 * in January 1970 (a 2024 date packs to 1_489_986_496, i.e. 1970-01-18), which is exactly
 * the garbage date the field test showed. So: 0 stays 0 (unknown), a value at or above
 * the DOS-epoch floor is a real timestamp and passes through, and a positive sub-floor
 * value is decoded back as the packed DOS pair it actually is, using the same UTC
 * interpretation as the shared reader's DOS conversion. A packed pair whose fields are
 * out of range decodes to 0 (unknown), never to a garbage date.
 */
fun archiveEntryModifiedAtFromZip4j(rawMillis: Long): Long {
    if (rawMillis <= 0L) return 0L
    if (rawMillis >= ARCHIVE_ENTRY_DOS_EPOCH_FLOOR_MILLIS) return rawMillis
    val dosTime = (rawMillis and 0xFFFF).toInt()
    val dosDate = ((rawMillis shr 16) and 0xFFFF).toInt()
    if (dosDate == 0) return 0L
    val day = dosDate and 0x1F
    val month = (dosDate shr 5) and 0x0F
    val year = ((dosDate shr 9) and 0x7F) + 1980
    val second = (dosTime and 0x1F) * 2
    val minute = (dosTime shr 5) and 0x3F
    val hour = (dosTime shr 11) and 0x1F
    if (month !in 1..12 || day !in 1..31 || minute > 59 || hour > 23 || second > 59) return 0L
    return runCatching {
        java.time.LocalDateTime.of(year, month, day, hour, minute, second)
            .toEpochSecond(java.time.ZoneOffset.UTC) * 1000L
    }.getOrDefault(0L)
}

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
