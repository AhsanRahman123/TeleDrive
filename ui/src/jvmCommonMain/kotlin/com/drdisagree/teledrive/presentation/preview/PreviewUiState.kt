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
    /** The in-zip preview pager sequence: listing indices of the archive's previewable entries, in list order. */
    val archivePreviewEntries: List<Int> = emptyList(),
    /** Position of [archivePreview] within [archivePreviewEntries]; -1 when no preview is open. */
    val archivePreviewPosition: Int = -1,
    /** The ZIP file (DriveFile id) the open in-zip preview belongs to, so a swipe can extract the next entry from the same archive. */
    val archivePreviewFileId: String? = null,
    /** Listing indices of the archive's video entries in display order, for the open in-zip video's SkipPrevious/SkipNext stepping (vc182); empty unless a video preview is open. */
    val archiveVideoQueue: List<Int> = emptyList(),
    /** Position of the open in-zip video within [archiveVideoQueue]; -1 when no video preview is open. */
    val archiveVideoQueuePosition: Int = -1,
    /** A pager step is extracting its entry; the current page stays up (or shows loading) until it lands. */
    val archivePreviewLoading: Boolean = false,
    /**
     * Monotonic count of in-zip pager steps that failed or were refused. A failed step
     * never moves the published preview or [archivePreviewPosition], so this counter is
     * the only changing signal the pager can key its snap-back on: without it the pager
     * stays settled on the unpublished page, showing its loading state forever.
     */
    val archivePreviewResyncCount: Int = 0,
    /** Progress of a multi-entry archive download: finished steps out of the plan total. */
    val archiveBatchProgress: ArchiveBatchProgress? = null,
    /**
     * Accepted-but-not-started in-ZIP download requests (ported from PC
     * 1.6.24 note 2): downloads queue behind the running extraction instead
     * of being dropped, and the pane header shows how many are waiting.
     */
    val archiveQueuedDownloads: Int = 0
)

/** An archive entry extracted to a verified temp file (or read from one) for in-place viewing. */
sealed interface ArchiveEntryPreview {
    val name: String

    /** Listing index of the entry this preview was extracted from (its pager identity). */
    val entryIndex: Int

    data class Image(
        override val name: String,
        val path: String,
        override val entryIndex: Int
    ) : ArchiveEntryPreview

    data class Audio(
        override val name: String,
        val path: String,
        override val entryIndex: Int
    ) : ArchiveEntryPreview

    /** A video entry extracted whole to a verified temp file, played by the existing player. */
    data class Video(
        override val name: String,
        val path: String,
        override val entryIndex: Int
    ) : ArchiveEntryPreview

    data class Text(
        override val name: String,
        val text: String,
        override val entryIndex: Int,
        /** Render with the Markdown renderer (in-zip parity with normal .md files). */
        val isMarkdown: Boolean = false,
        /** Render in the safe HTML viewer instead of as plain text. */
        val isHtml: Boolean = false,
        /** The extracted text was cut at the preview budget. */
        val truncated: Boolean = false
    ) : ArchiveEntryPreview

    /** An extracted PDF entry, rendered by the same PdfPreview as a normal PDF file. */
    data class Pdf(
        override val name: String,
        val path: String,
        override val entryIndex: Int
    ) : ArchiveEntryPreview
}

data class ArchiveBatchProgress(val done: Int, val total: Int)

/**
 * The target of the in-zip viewer's Info sheet (vc182): the listing entry being
 * viewed, plus the containing archive's display name and format label. Built from
 * the listing entry itself — never a fabricated DriveFile, which would invent
 * Drive-side metadata (backup state, chat ids, …) an archive entry does not have.
 */
data class ArchiveEntryInfo(
    val entry: ArchiveEntry,
    val archiveName: String,
    val archiveFormat: String
)

/**
 * The state after an in-zip pager step failed or was refused: the published preview
 * and its position stay exactly as they were — only [PreviewUiState.archivePreviewResyncCount]
 * advances, so the pager's snap-back effect (keyed on position + this count) re-runs
 * and scrolls back to the published page. Kept pure so the failure-resync contract is
 * host-testable without the view model.
 */
fun PreviewUiState.withArchivePreviewStepFailure(): PreviewUiState =
    copy(archivePreviewResyncCount = archivePreviewResyncCount + 1)
