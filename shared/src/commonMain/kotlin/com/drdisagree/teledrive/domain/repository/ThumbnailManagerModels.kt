package com.drdisagree.teledrive.domain.repository

import com.drdisagree.teledrive.domain.model.ThumbnailPolicy

enum class ThumbnailTargetType {
    FILE,
    FOLDER
}

data class ThumbnailManagerSummary(
    val totalBytes: Long = 0L,
    val cachedFiles: Int = 0,
    val pendingJobs: Int = 0,
    val unfiledBytes: Long = 0L,
    val unfiledCachedFiles: Int = 0,
    val orphanedBytes: Long = 0L,
    val orphanedCachedFiles: Int = 0
)

data class ThumbnailFolderUsage(
    val id: String,
    val name: String,
    val parentId: String?,
    val totalBytes: Long,
    val cachedFiles: Int,
    val directBytes: Long,
    val directCachedFiles: Int,
    val policy: ThumbnailPolicy = ThumbnailPolicy.INHERIT,
    val isTrashed: Boolean = false
)

data class ThumbnailFileUsage(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val category: String,
    val fileSizeBytes: Long,
    val thumbnailSizeBytes: Long?,
    val generatedAt: Long?,
    val lastAccessAt: Long?,
    val encrypted: Boolean?,
    val policy: ThumbnailPolicy,
    val isTrashed: Boolean = false
) {
    val isCached: Boolean
        get() = thumbnailSizeBytes != null
}


data class ThumbnailQueueItem(
    val fileId: String,
    val name: String,
    val mimeType: String,
    val fileSizeBytes: Long,
    val folderPath: String = "Drive root",
    val queuedAt: Long,
    val attempts: Int,
    val isForced: Boolean,
    val isProcessing: Boolean = false
) {
    val isFailed: Boolean get() = attempts >= 3
}
