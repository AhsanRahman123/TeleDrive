package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferRemainingWork
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferWorkTotals
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.presentation.transfers.TransfersViewModel
import java.util.Collections
import javax.swing.SwingUtilities
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The bubble's controls are the Transfers screen's controls: the panel
 * calls [TransfersViewModel.pause] / resume / cancel / pauseAll /
 * resumeAll — the same public functions the screen's rows and top bar
 * call. This test builds the real ViewModel over a recording repository
 * and proves each of those calls lands on the matching repository
 * operation, with the matching id — no parallel control path exists.
 */
class TransferBubbleControlWiringTest {

    private class RecordingTransferRepository : TransferRepository {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())

        override fun observeSection(
            section: TransferSection,
            limit: Int
        ): Flow<List<TransferTask>> = flowOf(emptyList())

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
    fun `panel control calls reach the matching repository operations`() {
        val repository = RecordingTransferRepository()
        val viewModel = TransfersViewModel(repository, FakeSettingsRepository())

        viewModel.pause("task-1")
        awaitRecorded(repository.calls, "pause:task-1")

        viewModel.resume("task-2")
        awaitRecorded(repository.calls, "resume:task-2")

        viewModel.cancel("task-3")
        awaitRecorded(repository.calls, "cancel:task-3")

        viewModel.pauseAll()
        awaitRecorded(repository.calls, "pauseAll")

        viewModel.resumeAll()
        awaitRecorded(repository.calls, "resumeAll")

        viewModel.cancelAll()
        awaitRecorded(repository.calls, "cancelAll")

        assertEquals(
            listOf(
                "pause:task-1", "resume:task-2", "cancel:task-3",
                "pauseAll", "resumeAll", "cancelAll"
            ),
            repository.calls.toList()
        )
    }

    /**
     * The ViewModel launches on the Swing main dispatcher (desktop); pump
     * the event queue until the launched call lands, with a hard deadline.
     */
    private fun awaitRecorded(calls: List<String>, expected: String) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (calls.contains(expected)) return
            SwingUtilities.invokeAndWait { }
            Thread.sleep(10)
        }
        fail("Timed out waiting for '$expected'; recorded so far: $calls")
    }
}
