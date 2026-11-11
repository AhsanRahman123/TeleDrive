package com.drdisagree.teledrive.core.telegram

/** [fileId] is TDLib's session-scoped id. */
data class TelegramFileInfo(
    val fileId: Int,
    val sizeBytes: Long,
    val localPath: String?,
    val isDownloadingCompleted: Boolean,
    val isDownloadingActive: Boolean,
    /** TDLib's localFile.canBeDeleted flag; false means the backing local file must be preserved. */
    val canBeDeleted: Boolean = false,
    val downloadOffset: Long,
    val downloadedPrefixSize: Long
)
