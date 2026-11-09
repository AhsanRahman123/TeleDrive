package com.drdisagree.teledrive.core.telegram

sealed interface TelegramDownloadEvent {

    data class Progress(
        val transferredBytes: Long,
        val totalBytes: Long,
        /** False while TDLib has accepted the request but has not started downloading it. */
        val isDownloadingActive: Boolean = true,
        /** Readable contiguous prefix used for speed only; total progress uses downloadedSize. */
        val speedSampleBytes: Long = transferredBytes
    ) : TelegramDownloadEvent

    data class Completed(val localPath: String, val sizeBytes: Long) : TelegramDownloadEvent
}
