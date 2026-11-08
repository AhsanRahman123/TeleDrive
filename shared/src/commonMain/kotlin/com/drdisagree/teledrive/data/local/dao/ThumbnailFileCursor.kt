package com.drdisagree.teledrive.data.local.dao

import androidx.room.ColumnInfo

data class ThumbnailFileCursor(
    @ColumnInfo(name = "fileId") val fileId: String,
    @ColumnInfo(name = "addedAt") val addedAt: Long
)
