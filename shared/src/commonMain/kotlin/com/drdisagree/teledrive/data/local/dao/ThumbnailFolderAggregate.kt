package com.drdisagree.teledrive.data.local.dao

import androidx.room.ColumnInfo

data class ThumbnailFolderAggregate(
    @ColumnInfo(name = "folderId") val folderId: String?,
    @ColumnInfo(name = "cachedFiles") val cachedFiles: Int,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long
)

/** Per-folder inventory of preview-eligible image and video files. */
data class ThumbnailFolderStatusAggregate(
    @ColumnInfo(name = "folderId") val folderId: String?,
    @ColumnInfo(name = "eligibleFiles") val eligibleFiles: Int,
    @ColumnInfo(name = "generatedFiles") val generatedFiles: Int
)
