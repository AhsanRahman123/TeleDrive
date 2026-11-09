package com.drdisagree.teledrive.core.transfer

sealed interface PartDownloadEvent {
    data class Progress(
        val transferredBytes: Long,
        val isDownloadingActive: Boolean = true
    ) : PartDownloadEvent
    data class Joining(val partIndex: Int) : PartDownloadEvent
    data class Completed(val localPath: String) : PartDownloadEvent
}
