package com.drdisagree.teledrive.core.telegram

sealed interface TelegramDownloadEvent {

    data class Progress(
        val transferredBytes: Long,
        val totalBytes: Long,
        /** Bytes in the readable contiguous prefix; used only for throughput measurement. */
        val speedSampleBytes: Long = transferredBytes
    ) : TelegramDownloadEvent

    data class Completed(val localPath: String, val sizeBytes: Long) : TelegramDownloadEvent
}
