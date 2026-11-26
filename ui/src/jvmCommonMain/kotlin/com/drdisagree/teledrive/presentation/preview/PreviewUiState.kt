package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.DriveFile

data class PreviewUiState(
    val files: List<DriveFile> = emptyList(),
    val initialIndex: Int = 0,
    val ready: Boolean = false,
    val closed: Boolean = false,
    /** Listing index of the entry currently being extracted from a remote ZIP, if any. */
    val archiveEntryBusyIndex: Int? = null,
    /** Latest remote-ZIP listing per file id, so an entry tap can resolve duplicate-name occurrence. */
    val archiveListings: Map<String, List<ArchiveEntry>> = emptyMap(),
    /** An extracted archive entry being viewed/played/read in place, if any. */
    val archivePreview: ArchiveEntryPreview? = null,
    /** Progress of a multi-entry archive download: finished steps out of the plan total. */
    val archiveBatchProgress: ArchiveBatchProgress? = null,
    /**
     * In-ZIP download requests accepted but not yet started (1.6.24 note 2): they
     * run one at a time behind the running request, so the pane can account for
     * them instead of the requests vanishing into a disabled button.
     */
    val archiveQueuedDownloads: Int = 0
)

/** An archive entry extracted to a verified temp file (or read from one) for in-place viewing. */
sealed interface ArchiveEntryPreview {
    val name: String

    data class Image(override val name: String, val path: String) : ArchiveEntryPreview
    data class Audio(override val name: String, val path: String) : ArchiveEntryPreview
    data class Text(override val name: String, val text: String) : ArchiveEntryPreview
}

data class ArchiveBatchProgress(val done: Int, val total: Int)
