package com.drdisagree.teledrive.data.local.dao

import androidx.room.ColumnInfo

data class ThumbnailUnfiledAggregate(
    @ColumnInfo(name = "cachedFiles") val cachedFiles: Int,
    @ColumnInfo(name = "totalBytes") val totalBytes: Long
)
