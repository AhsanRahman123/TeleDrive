package com.drdisagree.teledrive.presentation.transfers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        ids.forEach { transferRepository.retry(it) }
    }

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
