package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.presentation.transfers.TransfersUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The transfer bubble's aggregation over the Transfers screen's state. */
class TransferBubbleSummaryTest {

    private val now = 1_000_000L

    private fun task(
        id: String,
        type: TransferType,
        state: TransferState,
        size: Long = 1_000L,
        transferred: Long = 0L,
        speed: Long = 0L,
        updatedAt: Long = now
    ) = TransferTask(
        id = id,
        type = type,
        fileId = null,
        displayName = id,
        sizeBytes = size,
        transferredBytes = transferred,
        state = state,
        priority = 0,
        errorMessage = null,
        speedBytesPerSecond = speed,
        createdAt = 0L,
        updatedAt = updatedAt,
        completedAt = null
    )

    @Test
    fun `empty state is the idle summary`() {
        val summary = summarizeTransfers(TransfersUiState(loading = false), now)
        assertFalse(summary.hasUnfinishedWork)
        assertEquals(1f, summary.overallProgress)
        assertEquals(0, summary.uploadingActive)
        assertEquals(0, summary.downloadingActive)
        assertEquals(0L, summary.uploadingSpeedBytesPerSecond)
        assertEquals(0L, summary.downloadingSpeedBytesPerSecond)
        assertEquals(0, summary.queuedCount)
        assertEquals(0L, summary.uploadedTotalBytes)
        assertEquals(0L, summary.downloadedTotalBytes)
        assertFalse(summary.anyRunning)
        assertFalse(summary.anyQueued)
    }

    @Test
    fun `uploads aggregate counts speeds and bytes`() {
        val state = TransfersUiState(
            active = listOf(
                task("u1", TransferType.UPLOAD, TransferState.RUNNING,
                    size = 100, transferred = 50, speed = 10),
                task("u2", TransferType.UPLOAD, TransferState.RUNNING,
                    size = 300, transferred = 150, speed = 20),
                task("u3", TransferType.UPLOAD, TransferState.QUEUED,
                    size = 200, transferred = 0)
            ),
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertTrue(summary.hasUnfinishedWork)
        assertEquals(2, summary.uploadingActive)
        assertEquals(30L, summary.uploadingSpeedBytesPerSecond)
        assertEquals(200L, summary.uploadingTransferredBytes)
        assertEquals(600L, summary.uploadingTotalBytes)
        assertEquals(0, summary.downloadingActive)
        assertEquals(1, summary.queuedCount)
        assertTrue(summary.anyRunning)
        assertTrue(summary.anyQueued)
        // Overall spans every unfinished task, queued included.
        assertEquals(200f / 600f, summary.overallProgress, 0.0001f)
        assertEquals(200f / 600f, summary.uploadingProgress, 0.0001f)
    }

    @Test
    fun `backup counts as outgoing and restore as incoming`() {
        val state = TransfersUiState(
            active = listOf(
                task("b1", TransferType.BACKUP, TransferState.RUNNING,
                    size = 100, transferred = 25, speed = 5),
                task("r1", TransferType.RESTORE, TransferState.RUNNING,
                    size = 100, transferred = 75, speed = 7)
            ),
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertEquals(1, summary.uploadingActive)
        assertEquals(5L, summary.uploadingSpeedBytesPerSecond)
        assertEquals(1, summary.downloadingActive)
        assertEquals(7L, summary.downloadingSpeedBytesPerSecond)
    }

    @Test
    fun `paused work counts toward totals and overall but not speeds`() {
        val state = TransfersUiState(
            active = listOf(
                task("d1", TransferType.DOWNLOAD, TransferState.RUNNING,
                    size = 100, transferred = 50, speed = 10)
            ),
            paused = listOf(
                task("d2", TransferType.DOWNLOAD, TransferState.PAUSED,
                    size = 300, transferred = 150, speed = 99)
            ),
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertEquals(1, summary.downloadingActive)
        // The paused task's stored rate never contributes.
        assertEquals(10L, summary.downloadingSpeedBytesPerSecond)
        assertEquals(200L, summary.downloadingTransferredBytes)
        assertEquals(400L, summary.downloadingTotalBytes)
        assertEquals(1, summary.pausedCount)
        assertEquals(200f / 400f, summary.overallProgress, 0.0001f)
    }

    @Test
    fun `stale running speed displays as zero at the screens threshold`() {
        val stale = task(
            "s1", TransferType.UPLOAD, TransferState.RUNNING,
            size = 100, transferred = 10, speed = 500,
            updatedAt = now - SPEED_STALE_AFTER_MS - 1
        )
        val boundary = task(
            "s2", TransferType.UPLOAD, TransferState.RUNNING,
            size = 100, transferred = 10, speed = 500,
            updatedAt = now - SPEED_STALE_AFTER_MS
        )
        assertEquals(0L, effectiveSpeedBytesPerSecond(stale, now))
        // The rule is strictly-greater, exactly like the screen's.
        assertEquals(500L, effectiveSpeedBytesPerSecond(boundary, now))

        val summary = summarizeTransfers(
            TransfersUiState(active = listOf(stale), loading = false), now
        )
        assertEquals(0L, summary.uploadingSpeedBytesPerSecond)
        // ...but the task still counts as an active upload.
        assertEquals(1, summary.uploadingActive)
    }

    @Test
    fun `queued only work has no speeds and zero-ish progress`() {
        val state = TransfersUiState(
            active = listOf(
                task("q1", TransferType.DOWNLOAD, TransferState.QUEUED,
                    size = 400, transferred = 0, speed = 42)
            ),
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertTrue(summary.hasUnfinishedWork)
        assertFalse(summary.anyRunning)
        assertTrue(summary.anyQueued)
        assertEquals(1, summary.queuedCount)
        assertEquals(0, summary.downloadingActive)
        assertEquals(0L, summary.downloadingSpeedBytesPerSecond)
        assertEquals(0f, summary.overallProgress)
    }

    @Test
    fun `finished totals split by direction using transferred bytes`() {
        val state = TransfersUiState(
            completed = listOf(
                task("c1", TransferType.UPLOAD, TransferState.COMPLETED,
                    size = 500, transferred = 500),
                task("c2", TransferType.BACKUP, TransferState.COMPLETED,
                    size = 300, transferred = 300),
                task("c3", TransferType.DOWNLOAD, TransferState.COMPLETED,
                    size = 700, transferred = 700),
                task("c4", TransferType.RESTORE, TransferState.COMPLETED,
                    size = 100, transferred = 100)
            ),
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertEquals(800L, summary.uploadedTotalBytes)
        assertEquals(800L, summary.downloadedTotalBytes)
        // Finished work alone is not unfinished work: the bubble idles.
        assertFalse(summary.hasUnfinishedWork)
        assertEquals(1f, summary.overallProgress)
    }

    @Test
    fun `zero size tasks do not break the overall fraction`() {
        val state = TransfersUiState(
            active = listOf(
                task("z1", TransferType.UPLOAD, TransferState.RUNNING,
                    size = 0, transferred = 0, speed = 3)
            ),
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertTrue(summary.hasUnfinishedWork)
        assertEquals(0f, summary.overallProgress)
    }
}
