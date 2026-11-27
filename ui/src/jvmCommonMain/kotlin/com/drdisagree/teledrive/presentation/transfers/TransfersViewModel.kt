package com.drdisagree.teledrive.presentation.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransfersViewModel(
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    val transferConcurrency: StateFlow<Int> = settingsRepository.preferences
        .map { it.transferConcurrency.coerceIn(1, 6) }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 2)

    fun setTransferConcurrency(value: Int) = launch {
        settingsRepository.update { it.copy(transferConcurrency = value.coerceIn(1, 6)) }
        transferRepository.wakeWorker()
    }

    val sortOrder: StateFlow<String> = settingsRepository.preferences
        .map { it.transfersSortOrder }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "PRIORITY")

    fun setSortOrder(value: String) = launch {
        settingsRepository.update { it.copy(transfersSortOrder = value) }
    }

    private val rows = combine(
        transferRepository.observeSection(TransferSection.ACTIVE, SECTION_LIMIT),
        transferRepository.observeSection(TransferSection.PAUSED, SECTION_LIMIT),
        transferRepository.observeSection(TransferSection.FAILED, SECTION_LIMIT),
        transferRepository.observeSection(TransferSection.COMPLETED, SECTION_LIMIT)
    ) { active, paused, failed, completed ->
        val seen = mutableSetOf<String>()
        listOf(
            active.sortedWith(TRANSFER_ORDER).filterUnseen(seen),
            paused.sortedWith(TRANSFER_ORDER).filterUnseen(seen),
            failed.sortedWith(TRANSFER_ORDER).filterUnseen(seen),
            completed.sortedWith(FINISHED_ORDER).filterUnseen(seen)
        )
    }

    private fun List<TransferTask>.filterUnseen(seen: MutableSet<String>): List<TransferTask> =
        filter { seen.add(it.id) }

    private val totals = combine(
        transferRepository.observeSectionCount(TransferSection.ACTIVE),
        transferRepository.observeSectionCount(TransferSection.PAUSED),
        transferRepository.observeSectionCount(TransferSection.FAILED),
        transferRepository.observeSectionCount(TransferSection.COMPLETED)
    ) { counts -> counts.toList() }

    val uiState: StateFlow<TransfersUiState> = combine(rows, totals) { sections, counts ->
        TransfersUiState(
            active = sections[0],
            paused = sections[1],
            failed = sections[2],
            completed = sections[3],
            activeTotal = counts[0],
            pausedTotal = counts[1],
            failedTotal = counts[2],
            completedTotal = counts[3],
            loading = false
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransfersUiState())

    fun pause(id: String) = launch { transferRepository.pause(id) }

    fun resume(id: String) = launch { transferRepository.resume(id) }

    fun cancel(id: String) = launch { transferRepository.cancel(id) }

    fun retry(id: String) = launch { transferRepository.retry(id) }

    fun setPriority(id: String, priority: Int) =
        launch { transferRepository.setPriority(id, priority) }

    fun reorderDownloads(orderedIds: List<String>) =
        launch { transferRepository.reorderDownloads(orderedIds) }

    fun moveDownloadToTop(id: String) =
        launch { transferRepository.moveDownloadToTop(id) }

    fun pauseSelected(ids: List<String>) = launch {
        ids.forEach { transferRepository.pause(it) }
    }

    fun resumeSelected(ids: List<String>) = launch {
        ids.forEach { transferRepository.resume(it) }
    }

    fun cancelSelected(ids: List<String>) = launch {
        ids.forEach { transferRepository.cancel(it) }
    }

    fun retrySelected(ids: List<String>) = launch {
        // Bulk retry goes through the repository's per-type dispatch (ported
        // from PC 1.6.24, field note 3): engine rows re-queue; report-only
        // ARCHIVE_DOWNLOAD rows are skipped and the skip is SHOWN, because a
        // failed archive record retains no archive/entry identity to retry
        // from — the user downloads the file again from its ZIP.
        val outcome = transferRepository.retryFailedTransfers(ids)
        if (outcome.skippedArchiveDownloads > 0) {
            _userMessages.emit(archiveRetrySkipMessage(outcome.skippedArchiveDownloads))
        }
    }

    /** Removes the selected terminal records (field note 3: selected FAILED rows). */
    fun removeSelected(ids: List<String>) = launch {
        transferRepository.deleteTransfers(ids)
    }

    /** "Retry all" for the failed group: engine rows re-queue, archive rows are reported as skipped. */
    fun retryAllFailed() = launch {
        val outcome = transferRepository.retryAllFailed()
        val message = retryAllFailedMessage(outcome.requeued, outcome.skippedArchiveDownloads)
        if (message != null) _userMessages.emit(message)
    }

    /** "Remove all" for the failed group: every FAILED record is removed. */
    fun removeAllFailed() = launch {
        val removed = transferRepository.removeAllFailed()
        if (removed > 0) {
            _userMessages.emit(
                if (removed == 1) "Removed 1 failed transfer."
                else "Removed $removed failed transfers."
            )
        }
    }

    /**
     * One-line outcomes the screen must SHOW, never swallow (field note 3):
     * bulk-retry results and the archive-retry skip. The screen collects this
     * into its snackbar.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    fun pauseAll() = launch { transferRepository.pauseAll() }

    fun resumeAll() = launch { transferRepository.resumeAll() }

    fun cancelAll() = launch { transferRepository.cancelAll() }

    fun clearFinished() = launch { transferRepository.clearFinished() }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }


    private companion object {
        /**
         * Capped: reading tens of thousands of rows into one cursor overflows it while workers
         * write progress to the table.
         */
        const val SECTION_LIMIT = 200

        /**
         * One order for every section, so bulk actions move rows between sections without
         * reshuffling them.
         */
        val TRANSFER_ORDER: Comparator<TransferTask> =
            compareByDescending<TransferTask> { it.progress }.thenBy { it.createdAt }

        val FINISHED_ORDER: Comparator<TransferTask> =
            compareByDescending<TransferTask> { it.completedAt ?: it.updatedAt }
    }
}

/**
 * The plain message for skipped archive downloads in a bulk retry (field
 * note 3): a failed in-ZIP download retains no archive or entry identity, so
 * it cannot be retried — the user downloads it again from its ZIP.
 */
internal fun archiveRetrySkipMessage(skipped: Int): String =
    if (skipped == 1) {
        "1 failed ZIP download can't be retried — download it again from its ZIP."
    } else {
        "$skipped failed ZIP downloads can't be retried — download them again from their ZIP."
    }

/** The one-line result of "Retry all failed"; null when there was nothing to report. */
internal fun retryAllFailedMessage(requeued: Int, skippedArchiveDownloads: Int): String? {
    val parts = mutableListOf<String>()
    if (requeued > 0) {
        parts += if (requeued == 1) "Retrying 1 failed transfer." else "Retrying $requeued failed transfers."
    }
    if (skippedArchiveDownloads > 0) {
        parts += archiveRetrySkipMessage(skippedArchiveDownloads)
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}
