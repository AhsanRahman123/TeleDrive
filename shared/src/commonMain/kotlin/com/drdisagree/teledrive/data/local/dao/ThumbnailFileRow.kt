package com.drdisagree.teledrive.data.local.dao

import androidx.room.ColumnInfo

data class ThumbnailFileRow(
    @ColumnInfo(name = "fileId") val fileId: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "mimeType") val mimeType: String,
    @ColumnInfo(name = "category") val category: String,
    @ColumnInfo(name = "fileSizeBytes") val fileSizeBytes: Long,
    @ColumnInfo(name = "folderId") val folderId: String?,
    @ColumnInfo(name = "thumbnailSizeBytes") val thumbnailSizeBytes: Long?,
    @ColumnInfo(name = "generatedAt") val generatedAt: Long?,
    @ColumnInfo(name = "lastAccessAt") val lastAccessAt: Long?,
    @ColumnInfo(name = "encrypted") val encrypted: Boolean?
)
