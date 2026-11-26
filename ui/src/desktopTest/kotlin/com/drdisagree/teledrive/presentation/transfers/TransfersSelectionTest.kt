package com.drdisagree.teledrive.presentation.transfers

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.24 field note 3/6 pins for the Transfers selection + Open with logic:
 * the pure helpers the screen wires (drag-range translation in LazyColumn
 * list-index space, the Space toggle, the Open with path gate) and the
 * store's bulk-retry messages.
 */
class TransfersSelectionTest {

    private fun task(
        id: String,
        state: TransferState = TransferState.FAILED,
        localPath: String? = null
    ) = TransferTask(
        id = id,
        type = TransferType.DOWNLOAD,
        fileId = "file-$id",
        displayName = "$id.bin",
        sizeBytes = 100,
        transferredBytes = 0,
        state = state,
        priority = 0,
        errorMessage = null,
        speedBytesPerSecond = 0,
        createdAt = 1,
        updatedAt = 1,
        completedAt = null,
        localPath = localPath
    )

    // Slots parallel to the screen's LazyColumn: toolbar, header, a, b,
    // header, c — exactly the shape listRows builds.
    private val a = task("a")
    private val b = task("b")
    private val c = task("c")
    private val listRows: List<TransferTask?> = listOf(null, null, a, b, null, c)

    @Test
    fun `drag range selects exactly the rows it crosses`() {
        val painted = applyTransferDragRange(
            listRows, listRange = 2..5, base = emptySet(), shouldSelect = true
        )
        assertEquals(setOf("a", "b", "c"), painted)
    }

    @Test
    fun `drag range over only non-row slots selects nothing`() {
        val painted = applyTransferDragRange(
            listRows, listRange = 0..1, base = emptySet(), shouldSelect = true
        )
        assertEquals(emptySet<String>(), painted)
    }

    @Test
    fun `drag paints onto the drag-start snapshot, not the live set`() {
        // Base already holds "a" (selected before the drag). A select-drag
        // over b..c adds to the snapshot; a deselect-drag over a..b removes
        // exactly that range from the snapshot.
        val selected = applyTransferDragRange(
            listRows, listRange = 3..5, base = setOf("a"), shouldSelect = true
        )
        assertEquals(setOf("a", "b", "c"), selected)
        val deselected = applyTransferDragRange(
            listRows, listRange = 2..3, base = setOf("a", "b", "c"), shouldSelect = false
        )
        assertEquals(setOf("c"), deselected)
    }

    @Test
    fun `anchor test reads the row at the list index`() {
        assertTrue(transferDragAnchorSelected(listRows, 3, setOf("b")))
        assertFalse(transferDragAnchorSelected(listRows, 3, setOf("a")))
        assertFalse(transferDragAnchorSelected(listRows, 0, setOf("a")))
        assertFalse(transferDragAnchorSelected(listRows, 99, setOf("a")))
    }

    @Test
    fun `space toggle flips one row and ignores non-rows`() {
        assertEquals(setOf("a"), toggleTransferSelection(emptySet(), a))
        assertEquals(emptySet<String>(), toggleTransferSelection(setOf("a"), a))
        assertEquals(setOf("a"), toggleTransferSelection(setOf("a"), null))
    }

    @Test
    fun `open with path only for a completed row with a saved path`() {
        assertEquals(
            "/downloads/a.bin",
            transferOpenWithPath(task("a", TransferState.COMPLETED, "/downloads/a.bin"))
        )
        assertNull(transferOpenWithPath(task("a", TransferState.COMPLETED, null)))
        assertNull(transferOpenWithPath(task("a", TransferState.FAILED, "/downloads/a.bin")))
        assertNull(transferOpenWithPath(task("a", TransferState.RUNNING, "/downloads/a.bin")))
    }

    @Test
    fun `retry all message reports requeues and archive skips plainly`() {
        assertEquals(
            "Retrying 3 failed transfers.",
            retryAllFailedMessage(requeued = 3, skippedArchiveDownloads = 0)
        )
        assertEquals(
            "Retrying 1 failed transfer. " +
                "2 failed ZIP downloads can't be retried — download them again from their ZIP.",
            retryAllFailedMessage(requeued = 1, skippedArchiveDownloads = 2)
        )
        assertEquals(
            "1 failed ZIP download can't be retried — download it again from its ZIP.",
            retryAllFailedMessage(requeued = 0, skippedArchiveDownloads = 1)
        )
        assertNull(retryAllFailedMessage(requeued = 0, skippedArchiveDownloads = 0))
    }
}
