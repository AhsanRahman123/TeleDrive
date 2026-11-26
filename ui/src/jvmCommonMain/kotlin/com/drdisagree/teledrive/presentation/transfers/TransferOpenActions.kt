package com.drdisagree.teledrive.presentation.transfers

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask

/** The "open" actions a finished Transfers row's menu can offer, in menu order. */
enum class TransferOpenAction {
    /** Open the file in the app's own viewer/player (the Files/preview machinery). */
    OPEN_IN_APP,

    /** Hand the local file to the Android system chooser for other apps. */
    OPEN_WITH
}

/**
 * Which open actions a transfer row may show. Only a COMPLETED record with a
 * usable target gets any:
 *
 * - [TransferOpenAction.OPEN_IN_APP] needs the drive [TransferTask.fileId],
 *   because the in-app preview resolves a file row by id — or, for a
 *   report-only archive row, a present [TransferTask.localPath], because the
 *   local preview (vc181) resolves the saved file by path instead.
 * - [TransferOpenAction.OPEN_WITH] needs a platform file opener and a present
 *   [TransferTask.localPath], and is offered for downloads — including
 *   report-only ARCHIVE_DOWNLOAD records, which carry a localPath on completion
 *   but have no fileId and are not [com.drdisagree.teledrive.domain.model.TransferType.isIncoming].
 *   Gating on `isIncoming` alone (the vc179 wiring) is what dropped both open
 *   actions from archive-download rows, leaving their menu with only Details.
 */
fun completedTransferOpenActions(
    transfer: TransferTask,
    fileOpenerAvailable: Boolean
): Set<TransferOpenAction> {
    if (transfer.state != TransferState.COMPLETED) return emptySet()
    val actions = mutableSetOf<TransferOpenAction>()
    if (transfer.fileId != null || transfer.hasLocalPreviewTarget()) {
        actions += TransferOpenAction.OPEN_IN_APP
    }
    if (fileOpenerAvailable &&
        !transfer.localPath.isNullOrBlank() &&
        (transfer.type.isIncoming || transfer.type.isReportOnly)
    ) {
        actions += TransferOpenAction.OPEN_WITH
    }
    return actions
}

/**
 * Whether a completed row without a drive file id can still be opened in the
 * app's own viewer from the local file it carries: exactly the report-only
 * archive rows (and any incoming row in the same shape). Uploads are excluded —
 * their localPath is the source file, not a downloaded copy to preview from
 * Transfers.
 */
internal fun TransferTask.hasLocalPreviewTarget(): Boolean =
    state == TransferState.COMPLETED &&
        fileId == null &&
        !localPath.isNullOrBlank() &&
        (type.isIncoming || type.isReportOnly)

/** Where tapping a Transfers row should go. Pure, so the matrix is host-testable. */
sealed interface TransferRowTapTarget {
    /** Normal row: the existing in-app preview route, resolved by drive file id. */
    data class InApp(val fileId: String) : TransferRowTapTarget

    /** Archive (report-only) row: the in-app local preview, resolved by saved path. */
    data class LocalPreview(val localPath: String, val displayName: String) : TransferRowTapTarget

    /** Row tap does nothing, exactly as before vc181. */
    data object None : TransferRowTapTarget
}

/**
 * The row-tap target for [transfer]: COMPLETED rows only. A row with a
 * [TransferTask.fileId] keeps its exact existing route; a row without one but
 * with a usable local path opens that path in the local preview; everything
 * else (non-completed rows, uploads without a fileId, blank path) is [None].
 */
fun completedTransferRowTap(transfer: TransferTask): TransferRowTapTarget {
    if (transfer.state != TransferState.COMPLETED) return TransferRowTapTarget.None
    transfer.fileId?.let { return TransferRowTapTarget.InApp(it) }
    if (transfer.hasLocalPreviewTarget()) {
        return TransferRowTapTarget.LocalPreview(
            localPath = transfer.localPath.orEmpty(),
            displayName = transfer.displayName
        )
    }
    return TransferRowTapTarget.None
}
