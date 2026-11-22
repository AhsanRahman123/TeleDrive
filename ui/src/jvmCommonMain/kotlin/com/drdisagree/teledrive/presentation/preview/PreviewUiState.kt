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
    val archiveListings: Map<String, List<ArchiveEntry>> = emptyMap()
)
