package com.drdisagree.teledrive.core.archive

/** One central-directory record of a zip archive. Sizes and offsets are the real (ZIP64-aware) values. */
data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val method: Int,
    val crc32: Long,
    val localHeaderOffset: Long,
    val isEncrypted: Boolean,
    /**
     * The entry's last-modified time from the central directory's DOS date/time fields,
     * as epoch milliseconds (UTC interpretation of the timezone-less DOS value), or 0 when
     * the archive carries no valid timestamp for this entry.
     */
    val modifiedAtMillis: Long = 0L
)

/**
 * Converts a ZIP DOS date/time pair to epoch milliseconds, reading the DOS value as UTC
 * (DOS timestamps carry no timezone; a fixed interpretation keeps ordering stable across
 * devices). Returns 0 for the all-zero "no timestamp" value and for out-of-range fields,
 * so callers can treat 0 as "unknown".
 */
internal fun dosDateTimeToEpochMillis(dosTime: Int, dosDate: Int): Long {
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
