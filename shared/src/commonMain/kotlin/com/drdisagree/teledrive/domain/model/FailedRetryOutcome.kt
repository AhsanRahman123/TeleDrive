package com.drdisagree.teledrive.domain.model

/**
 * The result of a bulk retry over FAILED transfers (ported from PC 1.6.24,
 * field note 3).
 *
 * [requeued] counts engine-backed records that were honestly re-queued for the
 * transfer engine. [skippedArchiveDownloads] counts report-only
 * ARCHIVE_DOWNLOAD records: a failed archive record retains only its display
 * name and size — no archive file id, no entry name/occurrence — so there is
 * nothing an extraction could be re-run from. Those records are never
 * re-queued or silently re-failed; the caller reports the skip so the user
 * knows to download the file again from its ZIP.
 */
data class FailedRetryOutcome(
    val requeued: Int,
    val skippedArchiveDownloads: Int
)
