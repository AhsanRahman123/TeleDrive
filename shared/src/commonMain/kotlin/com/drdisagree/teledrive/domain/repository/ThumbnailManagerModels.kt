package com.drdisagree.teledrive.domain.repository

import com.drdisagree.teledrive.domain.model.ThumbnailPolicy

enum class ThumbnailTargetType {
    FILE,
    FOLDER
}

/** Inventory filters are applied by Room before paging, not in the UI. */
enum class ThumbnailCacheFilter {
    ALL,
    GENERATED,
    MISSING
}

/** INHERITED means there is no explicit file-level override; effective behavior is shown separately. */
enum class ThumbnailPolicyFilter {
    ALL,
    INHERITED,
    EXPLICIT_AUTOMATIC,
    EXPLICIT_MANUAL_ONLY
}

/** Resolved behavior after applying a file rule, then its nearest folder rule, then the default. */
enum class ThumbnailEffectivePolicyFilter {
    ALL,
    AUTOMATIC,
    MANUAL_ONLY
}

enum class ThumbnailFileSort {
    THUMBNAIL_LARGEST,
    THUMBNAIL_SMALLEST,
    SOURCE_LARGEST,
    SOURCE_SMALLEST,
    GENERATED_NEWEST,
    LAST_VIEWED_OLDEST,
    NAME_ASC,
    NAME_DESC
}

/** Queue ordering is presentation-only; it never changes job priority or execution order. */
enum class ThumbnailQueueSort {
    OLDEST_FIRST,
    NEWEST_FIRST,
    NAME_ASC,
    NAME_DESC,
    SOURCE_LARGEST,
    SOURCE_SMALLEST,
    FAILED_FIRST
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
    /** Folder-level rule; INHERIT means defer to the closest parent rule. */
    val policy: ThumbnailPolicy = ThumbnailPolicy.INHERIT,
    /** Effective behavior after resolving this folder and its ancestors. */
    val effectivePolicy: ThumbnailPolicy = policy,
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
    /** Explicit file rule. INHERIT means use the nearest folder rule or the default. */
    val policy: ThumbnailPolicy,
    /** Actual behavior after resolving the file and all ancestor folder rules. */
    val effectivePolicy: ThumbnailPolicy = policy,
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
    val isProcessing: Boolean = false,
    val isPaused: Boolean = false
) {
    val isFailed: Boolean get() = attempts >= 3
}
