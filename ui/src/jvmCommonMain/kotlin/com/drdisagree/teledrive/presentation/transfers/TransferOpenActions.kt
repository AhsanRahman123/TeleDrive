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
 *   because the in-app preview resolves a file row by id.
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
    if (transfer.fileId != null) actions += TransferOpenAction.OPEN_IN_APP
    if (fileOpenerAvailable &&
        !transfer.localPath.isNullOrBlank() &&
        (transfer.type.isIncoming || transfer.type.isReportOnly)
    ) {
        actions += TransferOpenAction.OPEN_WITH
    }
    return actions
}
