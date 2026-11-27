package com.drdisagree.teledrive.presentation.transfers

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.presentation.platform.TransferWidgetColor
import com.drdisagree.teledrive.presentation.platform.TransferWidgetStyle
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The Android transfer widget's pure logic (item 3): summary math over the Transfers
 * screen's own state, layout clamping/sanitizing, the drag-session persist-once /
 * restore-on-cancel contract, and the DataStore-backed preference store.
 */
class TransferBubbleLogicTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun task(
        id: String,
        type: TransferType,
        state: TransferState,
        size: Long = 100,
        transferred: Long = 0,
        speed: Long = 0,
        updatedAt: Long = 0
    ) = TransferTask(
        id = id,
        type = type,
        fileId = "file-$id",
        displayName = id,
        sizeBytes = size,
        transferredBytes = transferred,
        state = state,
        priority = 0,
        errorMessage = null,
        speedBytesPerSecond = speed,
        createdAt = 0,
        updatedAt = updatedAt,
        completedAt = null
    )

    @Test
    fun `summary counts directions, queue and stale speeds like the transfers screen`() {
        val now = 100_000L
        val state = TransfersUiState(
            active = listOf(
                task("up", TransferType.UPLOAD, TransferState.RUNNING, 200, 50, speed = 10, updatedAt = now),
                task("down", TransferType.DOWNLOAD, TransferState.RUNNING, 300, 150, speed = 20, updatedAt = now),
                task("stale", TransferType.DOWNLOAD, TransferState.RUNNING, 100, 10, speed = 99, updatedAt = now - 60_000),
                task("queued", TransferType.UPLOAD, TransferState.QUEUED, 100, 0)
            ),
            paused = listOf(task("paused", TransferType.DOWNLOAD, TransferState.PAUSED, 100, 40)),
            completed = listOf(
                task("doneUp", TransferType.UPLOAD, TransferState.COMPLETED, 500, 500),
                task("doneDown", TransferType.DOWNLOAD, TransferState.COMPLETED, 700, 700)
            ),
            activeTotal = 4,
            pausedTotal = 1,
            completedTotal = 2,
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertTrue(summary.hasUnfinishedWork)
        assertEquals(1, summary.uploadingActive)
        assertEquals(2, summary.downloadingActive)
        assertEquals(10L, summary.uploadingSpeedBytesPerSecond)
        // The stale running task contributes 0 speed, exactly like the Transfers screen.
        assertEquals(20L, summary.downloadingSpeedBytesPerSecond)
        assertEquals(1, summary.queuedCount)
        assertEquals(1, summary.pausedCount)
        assertEquals(500L, summary.uploadedTotalBytes)
        assertEquals(700L, summary.downloadedTotalBytes)
        assertTrue(summary.anyRunning)
        assertTrue(summary.anyQueued)
        assertTrue(summary.anyCancellable)
        // Unfinished: 200+300+100+100 active + 100 paused = 800 total, 250 transferred.
        assertEquals(250f / 800f, summary.overallProgress, 0.0001f)
    }

    @Test
    fun `archive download rows count as downloads, not uploads`() {
        // vc181 A2: ARCHIVE_DOWNLOAD is report-only with isIncoming == false, so the
        // old predicate bucketed it as uploading. It must land in the downloading
        // bucket while active and in downloadedTotalBytes when completed.
        val now = 100_000L
        val state = TransfersUiState(
            active = listOf(
                task("arch", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING, 400, 100, speed = 30, updatedAt = now),
                task("up", TransferType.UPLOAD, TransferState.RUNNING, 200, 50, speed = 10, updatedAt = now)
            ),
            completed = listOf(
                task("archDone", TransferType.ARCHIVE_DOWNLOAD, TransferState.COMPLETED, 900, 900),
                task("upDone", TransferType.UPLOAD, TransferState.COMPLETED, 500, 500)
            ),
            activeTotal = 2,
            pausedTotal = 0,
            completedTotal = 2,
            loading = false
        )
        val summary = summarizeTransfers(state, now)
        assertEquals(1, summary.downloadingActive)
        assertEquals(1, summary.uploadingActive)
        assertEquals(30L, summary.downloadingSpeedBytesPerSecond)
        assertEquals(10L, summary.uploadingSpeedBytesPerSecond)
        assertEquals(100L, summary.downloadingTransferredBytes)
        assertEquals(400L, summary.downloadingTotalBytes)
        assertEquals(50L, summary.uploadingTransferredBytes)
        assertEquals(200L, summary.uploadingTotalBytes)
        assertEquals(900L, summary.downloadedTotalBytes)
        assertEquals(500L, summary.uploadedTotalBytes)
        // Overall progress is direction-neutral and unchanged by the re-bucket.
        assertEquals(150f / 600f, summary.overallProgress, 0.0001f)
    }

    @Test
    fun `summary of an idle state is complete and not cancellable`() {
        val summary = summarizeTransfers(TransfersUiState(loading = false), nowMillis = 0)
        assertFalse(summary.hasUnfinishedWork)
        assertEquals(1f, summary.overallProgress, 0.0001f)
        assertFalse(summary.anyCancellable)
    }

    @Test
    fun `tapping either widget style opens the full transfers screen`() {
        // vc186 A6: one tap contract for both shells — the bubble and the
        // bottom strip route the same shared tap handler in
        // TransferWidgetOverlay, which switches on this decision.
        assertEquals(
            WidgetTapTarget.TRANSFERS_SCREEN,
            widgetTapTarget(TransferWidgetStyle.BUBBLE)
        )
        assertEquals(
            WidgetTapTarget.TRANSFERS_SCREEN,
            widgetTapTarget(TransferWidgetStyle.BOTTOM_STRIP)
        )
    }

    @Test
    fun `visibility priority is switch, then fullscreen, then user hide`() {
        assertEquals(BubbleVisibility.GONE, bubbleVisibility(false, false, false))
        assertEquals(BubbleVisibility.GONE, bubbleVisibility(true, true, false))
        assertEquals(BubbleVisibility.TAB, bubbleVisibility(true, false, true))
        assertEquals(BubbleVisibility.BUBBLE, bubbleVisibility(true, false, false))
    }

    @Test
    fun `default offset sits above the fab and inside the window`() {
        val offset = TransferBubbleLayout.defaultOffset(400f, 800f)
        assertTrue(offset.x >= 0 && offset.y >= 0)
        assertTrue(offset.x + TransferBubbleLayout.BUBBLE_SIZE_DP <= 400f)
        assertTrue(offset.y + TransferBubbleLayout.BUBBLE_SIZE_DP <= 800f)
        // Clears the FAB: bubble bottom is at least the clearance above the FAB's top.
        val bubbleBottomFromBottom = 800f - (offset.y + TransferBubbleLayout.BUBBLE_SIZE_DP)
        assertTrue(bubbleBottomFromBottom >= TransferBubbleLayout.fabTopFromBottomDp)
    }

    @Test
    fun `clamp and movedBy never leave the window`() {
        val clamped = TransferBubbleLayout.clampOffset(-50f, 10_000f, 400f, 800f)
        assertEquals(0f, clamped.x, 0.001f)
        assertEquals(800f - TransferBubbleLayout.BUBBLE_SIZE_DP, clamped.y, 0.001f)
        val moved = TransferBubbleLayout.movedBy(clamped, -100f, 100f, 400f, 800f)
        assertEquals(0f, moved.x, 0.001f)
        assertEquals(clamped.y, moved.y, 0.001f)
    }

    @Test
    fun `sanitize heals corrupt or off-window positions to the default`() {
        val healed = TransferBubbleLayout.sanitizePosition(BubbleOffset(Float.NaN, 10f), 400f, 800f)
        assertEquals(TransferBubbleLayout.defaultOffset(400f, 800f), healed)
        val offWindow = TransferBubbleLayout.sanitizePosition(BubbleOffset(5_000f, 10f), 400f, 800f)
        assertEquals(TransferBubbleLayout.defaultOffset(400f, 800f), offWindow)
        // Partly inside: clamped into view, not reset.
        val partly = TransferBubbleLayout.sanitizePosition(BubbleOffset(390f, 10f), 400f, 800f)
        assertEquals(400f - TransferBubbleLayout.BUBBLE_SIZE_DP, partly.x, 0.001f)
    }

    @Test
    fun `drag session persists once on complete and restores on cancel`() {
        val session = BubbleDragSession()
        // Completing without a drag persists nothing.
        assertNull(session.complete(BubbleOffset(1f, 1f)))
        session.begin(BubbleOffset(10f, 10f))
        assertTrue(session.active)
        assertEquals(BubbleOffset(50f, 60f), session.complete(BubbleOffset(50f, 60f)))
        // A second complete is a no-op: the position persists exactly once.
        assertNull(session.complete(BubbleOffset(70f, 70f)))
        // Cancel returns the pre-drag offset and ends the session.
        session.begin(BubbleOffset(20f, 20f))
        assertEquals(BubbleOffset(20f, 20f), session.cancel())
        assertFalse(session.active)
        assertNull(session.cancel())
    }

    @Test
    fun `strip layout centres the bar and caps its width`() {
        assertEquals(360f, TransferStripLayout.stripWidth(392f), 0.001f)
        assertEquals(TransferStripLayout.MAX_WIDTH_DP, TransferStripLayout.stripWidth(1200f), 0.001f)
        val offset = TransferStripLayout.stripOffset(400f, 800f)
        assertEquals((400f - TransferStripLayout.stripWidth(400f)) / 2f, offset.x, 0.001f)
        assertEquals(
            800f - TransferStripLayout.BOTTOM_MARGIN_DP - TransferStripLayout.BAR_HEIGHT_DP,
            offset.y,
            0.001f
        )
    }

    @Test
    fun `color presets resolve like windows`() {
        assertNull(widgetAccent(TransferWidgetColor.APP_ACCENT))
        assertEquals(
            androidx.compose.ui.graphics.Color(0xFFFF7000),
            widgetAccent(TransferWidgetColor.ORANGE)!!.accent
        )
    }

    @Test
    fun `store round-trips switch, style, color and position through datastore`() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) {
                File(folder.newFolder(), "settings.preferences_pb").toOkioPath()
            }
            val store = AndroidTransferBubbleStore(dataStore, scope)
            assertTrue(store.isEnabled())
            assertEquals(TransferWidgetStyle.BUBBLE, store.widgetStyle())

            store.setEnabled(false)
            store.setWidgetStyle(TransferWidgetStyle.BOTTOM_STRIP)
            store.setWidgetColor(TransferWidgetColor.PURPLE)
            store.savePosition(123f, 456f)

            withTimeout(5_000) {
                store.enabled.first { !it }
                store.style.first { it == TransferWidgetStyle.BOTTOM_STRIP }
                store.color.first { it == TransferWidgetColor.PURPLE }
                store.position.first { it == BubbleOffset(123f, 456f) }
            }
            // A fresh store over the same DataStore sees the persisted values.
            val fresh = AndroidTransferBubbleStore(dataStore, scope)
            withTimeout(5_000) {
                fresh.style.first { it == TransferWidgetStyle.BOTTOM_STRIP }
                fresh.position.first { it == BubbleOffset(123f, 456f) }
            }
            assertEquals(TransferWidgetStyle.BOTTOM_STRIP, fresh.widgetStyle())
            assertEquals(BubbleOffset(123f, 456f), fresh.loadPosition())
        } finally {
            scope.cancel()
        }
    }
}
