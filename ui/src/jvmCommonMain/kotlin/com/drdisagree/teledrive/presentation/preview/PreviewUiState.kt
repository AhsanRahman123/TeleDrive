package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.DriveFile

data class PreviewUiState(
    val files: List<DriveFile> = emptyList(),
    val initialIndex: Int = 0,
    val ready: Boolean = false,
    val closed: Boolean = false,
    val archiveEntryDownload: ArchiveEntryDownloadState? = null,
    /** An extracted archive entry being viewed/played/read in place, if any. */
    val archivePreview: ArchiveEntryPreview? = null,
    /** Progress of a multi-entry archive download: finished steps out of the plan total. */
    val archiveBatchProgress: ArchiveBatchProgress? = null
)

/** Progress of a single-file download out of a remote zip, keyed by entry name for the row UI. */
data class ArchiveEntryDownloadState(
    val entryName: String,
    val status: Status
) {
    enum class Status { RUNNING, SUCCEEDED, FAILED }
}

/** An archive entry extracted to a verified temp file (or read from one) for in-place viewing. */
sealed interface ArchiveEntryPreview {
    val name: String

    data class Image(override val name: String, val path: String) : ArchiveEntryPreview
    data class Audio(override val name: String, val path: String) : ArchiveEntryPreview
    data class Text(override val name: String, val text: String) : ArchiveEntryPreview
}

data class ArchiveBatchProgress(val done: Int, val total: Int)
