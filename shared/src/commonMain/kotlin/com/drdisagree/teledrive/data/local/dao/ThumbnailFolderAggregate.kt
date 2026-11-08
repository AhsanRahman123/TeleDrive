package com.drdisagree.teledrive.data.local.dao

import androidx.room.ColumnInfo

data class ThumbnailFolderAggregate(
    @ColumnInfo(name = "folderId") val folderId: String?,
    @ColumnInfo(name = "cachedFiles") val cachedFiles: Int,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long
)
