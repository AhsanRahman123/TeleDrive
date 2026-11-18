package com.drdisagree.teledrive.domain.model

data class FileQuerySpec(
    val chatId: Long? = null,
    val folderId: String? = null,
    val filterByFolder: Boolean = false,
    val categories: List<FileCategory> = emptyList(),
    val nameQuery: String? = null,
    val extension: String? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val modifiedAfter: Long? = null,
    val modifiedBefore: Long? = null,
    val backedUpOnly: Boolean = false,
    val notBackedUpOnly: Boolean = false,
    val favoritesOnly: Boolean = false,
    val availableOfflineOnly: Boolean = false,
    /** Only files with no generated thumbnail cached (no row in the thumbnails table). */
    val missingThumbnailOnly: Boolean = false,
    /** Only files with a generated thumbnail cached (a row exists in the thumbnails table). */
    val withThumbnailOnly: Boolean = false,
    val hiddenOnly: Boolean = false,
    val archivedOnly: Boolean = false,
    val showHidden: Boolean = false,
    val showArchived: Boolean = false,
    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING
)
