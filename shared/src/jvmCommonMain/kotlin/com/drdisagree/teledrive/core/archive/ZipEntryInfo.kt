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
    val isEncrypted: Boolean
)
