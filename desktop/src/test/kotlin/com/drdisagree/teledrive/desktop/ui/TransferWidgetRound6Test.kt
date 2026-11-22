package com.drdisagree.teledrive.desktop.ui

import androidx.compose.ui.graphics.Color
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferRemainingWork
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferWorkTotals
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.presentation.platform.TransferWidgetColor
import com.drdisagree.teledrive.presentation.transfers.TransfersStore
import com.drdisagree.teledrive.presentation.transfers.TransfersViewModel
import java.util.Collections
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Round 6 widget pins:
 *
 *  - The colour presets resolve to the exact accent values both shells
 *    draw with; APP_ACCENT resolves to null ("use the app accent").
 *  - The bottom strip's geometry: capped width, centred, panel above.
 *  - The structural fix: the Transfers screen's ViewModel and the
 *    widget bind the SAME [TransfersStore] instance — one uiState flow
 *    object, and a control issued through one surface lands on the
 *    repository exactly once (no dual-instance double-processing).
 */
class TransferWidgetRound6Test {

    @Test
    fun `app accent preset resolves to null and every other preset to its exact color`() {
        assertNull(widgetAccent(TransferWidgetColor.APP_ACCENT))
        assertEquals(Color(0xFFFF7000), widgetAccent(TransferWidgetColor.ORANGE)?.accent)
        assertEquals(Color(0xFF2563EB), widgetAccent(TransferWidgetColor.BLUE)?.accent)
        assertEquals(Color(0xFF16A34A), widgetAccent(TransferWidgetColor.GREEN)?.accent)
        assertEquals(Color(0xFF7C3AED), widgetAccent(TransferWidgetColor.PURPLE)?.accent)
        assertEquals(Color(0xFF4B5563), widgetAccent(TransferWidgetColor.GRAPHITE)?.accent)
        for (preset in TransferWidgetColor.entries) {
            if (preset == TransferWidgetColor.APP_ACCENT) continue
            val accent = widgetAccent(preset)
            assertNotNull(accent)
            assertEquals(Color.White, accent!!.onAccent)
        }
    }

    @Test
    fun `strip width is capped and shrinks with narrow windows`() {
        assertEquals(560f, TransferStripLayout.stripWidth(1920f), 0.001f)
        assertEquals(560f, TransferStripLayout.stripWidth(592f), 0.001f)
        assertEquals(468f, TransferStripLayout.stripWidth(500f), 0.001f)
    }

    @Test
    fun `strip and tab are centred and sit at the window bottom`() {
        val strip = TransferStripLayout.stripOffset(1000f, 800f)
        assertEquals((1000f - 560f) / 2f, strip.x, 0.001f)
        assertEquals(
            800f - TransferStripLayout.BOTTOM_MARGIN_DP - TransferStripLayout.BAR_HEIGHT_DP,
            strip.y,
            0.001f
        )
        val tab = TransferStripLayout.tabOffset(1000f, 800f)
        assertEquals((1000f - TransferStripLayout.TAB_WIDTH_DP) / 2f, tab.x, 0.001f)
        assertEquals(800f - TransferStripLayout.TAB_HEIGHT_DP, tab.y, 0.001f)
    }

    @Test
    fun `strip panel opens above the bar and stays inside the window`() {
        val panel = TransferStripLayout.panelOffset(
            panelWidth = 560f,
            panelHeight = 400f,
            windowWidth = 1000f,
            windowHeight = 800f
        )
        val strip = TransferStripLayout.stripOffset(1000f, 800f)
        assertTrue(panel.y + 400f <= strip.y)
        assertTrue(panel.x >= TransferBubbleLayout.PANEL_MARGIN_DP)
        assertTrue(panel.x + 560f <= 1000f - TransferBubbleLayout.PANEL_MARGIN_DP)
    }

    private class RecordingTransferRepository : TransferRepository {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val active = MutableStateFlow<List<TransferTask>>(emptyList())

        override fun observeSection(
            section: TransferSection,
            limit: Int
        ): Flow<List<TransferTask>> =
            if (section == TransferSection.ACTIVE) active else flowOf(emptyList())

        override fun observeSectionCount(section: TransferSection): Flow<Int> = flowOf(0)

        override fun observeActiveCount(): Flow<Int> = flowOf(0)

        override fun observeCompletedTotals(incoming: Boolean) = flowOf(TransferWorkTotals(0, 0))

        override fun observeRemainingWork(incoming: Boolean) = flowOf(TransferRemainingWork(0, 0, 0))

        override fun observeActiveForFile(fileId: String): Flow<TransferTask?> = flowOf(null)

        override fun observeActiveDownloads(): Flow<List<TransferTask>> = flowOf(emptyList())

        override suspend fun enqueueUpload(fileId: String, priority: Int): AppResult<String> =
            AppResult.Success(fileId)

        override suspend fun uploadQueueGeneration(): Long = 0L

        override suspend fun enqueueUploadForGeneration(
            fileId: String,
            generation: Long
        ): AppResult<Boolean> = AppResult.Success(true)

        override suspend fun enqueuePendingUploads(): AppResult<Int> = AppResult.Success(0)

        override suspend fun enqueueBackupBatch(
            fileIds: List<String>,
            sessionId: String
        ): AppResult<Int> = AppResult.Success(0)

        override suspend fun enqueueDownload(
            fileId: String,
            priority: Int
        ): AppResult<String> = AppResult.Success(fileId)

        override suspend fun pause(id: String) {
            calls += "pause:$id"
        }

        override suspend fun resume(id: String) {
            calls += "resume:$id"
        }

        override suspend fun cancel(id: String) {
            calls += "cancel:$id"
        }

        override suspend fun cancelForFiles(fileIds: List<String>) {
            calls += "cancelForFiles"
        }

        override suspend fun retry(id: String) {
            calls += "retry:$id"
        }

        override suspend fun setPriority(id: String, priority: Int) {
            calls += "setPriority:$id"
        }

        override suspend fun pauseAll() {
            calls += "pauseAll"
        }

        override suspend fun resumeAll() {
            calls += "resumeAll"
        }

        override suspend fun cancelAll() {
            calls += "cancelAll"
        }

        override suspend fun clearFinished() {
            calls += "clearFinished"
        }

        override suspend fun recoverOrphanedTransfers() = Unit
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val prefs = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> = prefs

        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            prefs.value = transform(prefs.value)
        }

        override suspend fun getTelegramCredentials(): TelegramCredentials? = null

        override suspend fun setTelegramCredentials(credentials: TelegramCredentials) = Unit

        override suspend fun hasStoredTelegramCredentials(): Boolean = false

        override suspend fun clearTelegramCredentials() = Unit
    }

    @Test
    fun `screen and widget share one store so controls land exactly once`() {
        val repository = RecordingTransferRepository()
        val store = TransfersStore(repository, FakeSettingsRepository())
        // The screen's ViewModel and the widget both wrap the same store.
        val screenViewModel = TransfersViewModel(store)
        val widgetViewModel = TransfersViewModel(store)

        assertSame(
            "both surfaces must expose the very same uiState flow",
            screenViewModel.uiState,
            widgetViewModel.uiState
        )
        assertSame(store.uiState, screenViewModel.uiState)

        widgetViewModel.cancel("task-9")
        awaitCalls(repository.calls, listOf("cancel:task-9"))

        screenViewModel.pause("task-9")
        awaitCalls(repository.calls, listOf("cancel:task-9", "pause:task-9"))

        assertEquals(
            "each control lands on the repository exactly once",
            listOf("cancel:task-9", "pause:task-9"),
            repository.calls.toList()
        )
    }

    /** The store launches on its own scope; poll until the calls land. */
    private fun awaitCalls(calls: List<String>, expected: List<String>) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (calls.toList() == expected) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for $expected; recorded so far: $calls")
    }
}
