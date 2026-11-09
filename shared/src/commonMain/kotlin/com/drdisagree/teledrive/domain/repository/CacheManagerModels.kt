package com.drdisagree.teledrive.domain.repository

/** A cache inventory row. One file may own several entries (preview, stream, or temporary). */
data class CacheEntryUsage(
    val path: String,
    val fileId: String?,
    val type: String,
    val sizeBytes: Long,
    val encrypted: Boolean,
    val createdAt: Long,
    val lastAccessAt: Long,
    val name: String,
    val mimeType: String,
    val category: String,
    val folderId: String?,
    val folderPath: String
)

/** A folder row with cache usage aggregated across its complete descendant tree. */
data class CacheFolderUsage(
    val id: String,
    val name: String,
    val parentId: String?,
    val totalBytes: Long,
    val entryCount: Int
)

data class CacheManagerSummary(
    val totalBytes: Long = 0L,
    val entryCount: Int = 0,
    val previewBytes: Long = 0L,
    val streamBytes: Long = 0L,
    val tempBytes: Long = 0L,
    val untrackedBytes: Long = 0L
)

data class CacheRemovalResult(
    val removedEntries: Int = 0,
    val removedBytes: Long = 0L
)
