package com.drdisagree.teledrive.presentation.transfers

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask

/**
 * Pure selection logic for the Transfers list (1.6.24, field note 3), shaped
 * exactly like the ZIP viewer's drag-selection helpers (report 54, Worker 2):
 * the drag gesture works in LazyColumn LIST indices, and this file is the
 * single place where list indices are translated to transfer ids.
 *
 * The screen builds a list parallel to its LazyColumn items — one slot per
 * item, the transfer for a row item and null for the toolbar, section
 * headers and "more" lines — so a drag range covering N list slots selects
 * exactly the transfers in those slots and nothing else.
 */

/** The transfer shown at LazyColumn item [listIndex], or null for a non-row item. */
fun transferAtListIndex(listRows: List<TransferTask?>, listIndex: Int): TransferTask? =
    listRows.getOrNull(listIndex)

/** Whether the row at [listIndex] is selected — the drag gesture's anchor test. */
fun transferDragAnchorSelected(
    listRows: List<TransferTask?>,
    listIndex: Int,
    selectedIds: Set<String>
): Boolean = transferAtListIndex(listRows, listIndex)?.id?.let { it in selectedIds } == true

/**
 * Paints one drag-select [listRange] onto the snapshot taken when the drag
 * started ([base]) — selecting when [shouldSelect], deselecting otherwise —
 * never onto the live set, so moving the pointer back shrinks the painted
 * range again instead of accumulating (the Files/archive rule).
 */
fun applyTransferDragRange(
    listRows: List<TransferTask?>,
    listRange: IntRange,
    base: Set<String>,
    shouldSelect: Boolean
): Set<String> {
    val ids = listRange.mapNotNull { transferAtListIndex(listRows, it)?.id }
    return if (shouldSelect) base + ids else base - ids.toSet()
}

/** The keyboard Space toggle: flips [transfer]'s membership in the selection set. */
fun toggleTransferSelection(selectedIds: Set<String>, transfer: TransferTask?): Set<String> {
    if (transfer == null) return selectedIds
    return if (transfer.id in selectedIds) {
        selectedIds - transfer.id
    } else {
        selectedIds + transfer.id
    }
}

/**
 * The saved local path a completed row's "Open with…" acts on (field note 6):
 * the row's own localPath, and only for a COMPLETED row. Regular downloads
 * resolve this path from their file row and ARCHIVE_DOWNLOAD rows carry the
 * path the reporter wrote at completion (both land on [TransferTask.localPath]
 * before the screen sees the row); anything else has nothing to open.
 */
fun transferOpenWithPath(transfer: TransferTask): String? =
    transfer.localPath?.takeIf { transfer.state == TransferState.COMPLETED }
