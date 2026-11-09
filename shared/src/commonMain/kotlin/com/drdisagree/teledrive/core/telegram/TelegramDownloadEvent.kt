package com.drdisagree.teledrive.core.telegram

sealed interface TelegramDownloadEvent {

    data class Progress(
        val transferredBytes: Long,
        val totalBytes: Long,
        val isDownloadingActive: Boolean = true
    ) : TelegramDownloadEvent

    data class Completed(val localPath: String, val sizeBytes: Long) : TelegramDownloadEvent
}
