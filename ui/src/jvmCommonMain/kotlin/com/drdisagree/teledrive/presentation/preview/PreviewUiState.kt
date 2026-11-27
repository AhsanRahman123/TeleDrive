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
    /**
     * Legacy vc182 video-queue fields, kept in sync with the unified sequence
     * in vc183: while any in-zip preview is open this holds the unified
     * sequence ([archivePreviewEntries]) and the position below holds
     * [archivePreviewPosition], so existing readers of the pair keep working.
     * New code should read [archivePreviewEntries]/[archivePreviewPosition].
     */
    val archiveVideoQueue: List<Int> = emptyList(),
    /** Position within [archiveVideoQueue]; mirrors [archivePreviewPosition] in vc183. */
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

    /**
     * A video page the pager has settled on but that has NOT been extracted
     * (vc183): settling/swiping publishes only this state — no extraction, no
     * free-space preflight, no temp file. Extraction (with the vc181
     * preflight) runs only when the user taps Play on this page, which is the
     * deliberate path a direct list tap also takes. [sizeBytes] is the
     * listing's exact uncompressed size, shown so the cost is visible before
     * the tap.
     */
    data class VideoPlaceholder(
        override val name: String,
        override val entryIndex: Int,
        val sizeBytes: Long
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

/**
 * Whether a finished/failed in-zip preview load may publish anything at all
 * (vc183, false-corruption fix): only the load still registered as the
 * current one may. A superseded load — cancelled because a newer step, a
 * swipe back to the published page, or dismissal replaced it — publishes
 * NOTHING: no preview, no step-failure resync, and no error message. Before
 * this guard, a superseded extraction whose cancellation had been re-wrapped
 * as an ArchiveException(CORRUPT) by the archive reader landed in the view
 * model's unconditional ArchiveException branch and reported "archive is
 * corrupted" (plus a pager snap-back) for an entry that opens fine when
 * tapped individually — individual open has no predecessor to supersede, so
 * it never produced that verdict. Pure, so the contract is host-testable.
 */
fun archivePreviewLoadMayPublish(isCurrentLoad: Boolean): Boolean = isCurrentLoad
