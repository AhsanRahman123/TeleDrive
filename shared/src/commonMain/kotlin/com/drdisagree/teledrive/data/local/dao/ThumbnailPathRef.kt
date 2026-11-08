package com.drdisagree.teledrive.data.local.dao

import androidx.room.ColumnInfo

data class ThumbnailPathRef(
    @ColumnInfo(name = "fileId") val fileId: String,
    @ColumnInfo(name = "path") val path: String
)
