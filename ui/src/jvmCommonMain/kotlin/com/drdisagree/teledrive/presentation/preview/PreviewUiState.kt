package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.DriveFile

data class PreviewUiState(
    val files: List<DriveFile> = emptyList(),
    val initialIndex: Int = 0,
    val ready: Boolean = false,
    val closed: Boolean = false,
    val archiveEntryDownload: ArchiveEntryDownloadState? = null
)

/** Progress of a single-file download out of a remote zip, keyed by entry name for the row UI. */
data class ArchiveEntryDownloadState(
    val entryName: String,
    val status: Status
) {
    enum class Status { RUNNING, SUCCEEDED, FAILED }
}
