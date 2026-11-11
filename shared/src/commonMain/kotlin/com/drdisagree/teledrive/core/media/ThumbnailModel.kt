package com.drdisagree.teledrive.core.media

data class ThumbnailModel(
    val fileId: String,
    /** Changes when a background worker produces a new cached preview. */
    val revision: Long = 0L
)

fun thumbnailCacheKey(fileId: String, revision: Long = 0L): String =
    "thumb:$fileId:$revision"
