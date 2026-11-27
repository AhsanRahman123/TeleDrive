package com.drdisagree.teledrive.presentation.transfers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bulk-failed message wording, ported from PC 1.6.24's
 * TransfersSelectionTest (only the message case: the drag-select and
 * open-with cases in that file pin PC-only field notes 3a/6, which are not
 * part of this port). The builders live in TransfersViewModel.kt on this
 * tree — there is no separate TransfersStore here.
 */
class TransfersBulkMessagesTest {

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

    @Test
    fun `archive skip message singular and plural`() {
        assertEquals(
            "1 failed ZIP download can't be retried — download it again from its ZIP.",
            archiveRetrySkipMessage(1)
        )
        assertEquals(
            "4 failed ZIP downloads can't be retried — download them again from their ZIP.",
            archiveRetrySkipMessage(4)
        )
    }
}
