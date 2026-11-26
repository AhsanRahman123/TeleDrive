package com.drdisagree.teledrive.presentation.transfers

import com.drdisagree.teledrive.core.transfer.EtaEstimate
import com.drdisagree.teledrive.core.transfer.TransferEtaEstimator
import com.drdisagree.teledrive.core.transfer.overallEta
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * The ONE app-level holder of transfers UI state (round 6, PC-26).
 *
 * Until now every consumer built its own [TransfersViewModel]: the
 * Transfers screen got one from Koin's view-model scope and the desktop
 * transfer bubble constructed a second one by hand. Two state holders
 * over one engine meant two independent control scopes racing the same
 * repository — pause/cancel from one surface could be overtaken by the
 * other's in-flight calls, and the two surfaces could disagree about
 * the queue they claimed to mirror.
 *
 * This store is a Koin single: the screen's ViewModel and every desktop
 * widget bind to the SAME instance, so there is exactly one [uiState]
 * flow and one control surface for the whole app. The projection logic
 * is the ViewModel's original combine, moved unchanged — same section
 * flows, same ordering, same 200-row section cap the screen has always
 * lived with — only its lifetime changed (app scope instead of a
 * screen's view-model scope).
 */
class TransfersStore(
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {

    val uploadConcurrency: StateFlow<Int> = settingsRepository.preferences
        .map { it.uploadConcurrency.coerceIn(1, 6) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 2)

    val downloadConcurrency: StateFlow<Int> = settingsRepository.preferences
        .map { it.downloadConcurrency.coerceIn(1, 6) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 2)

    /** The Transfers sort memory, kept in preferences (DataStore) like the concurrency. */
    val sortOrder: StateFlow<String> = settingsRepository.preferences
        .map { it.transfersSortOrder }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), "PRIORITY")

    fun setSortOrder(value: String) = launch {
        settingsRepository.update { it.copy(transfersSortOrder = value) }
    }

    fun setUploadConcurrency(value: Int) = launch {
        settingsRepository.update { it.copy(uploadConcurrency = value.coerceIn(1, 6)) }
        transferRepository.wakeWorker()
    }

    fun setDownloadConcurrency(value: Int) = launch {
        settingsRepository.update { it.copy(downloadConcurrency = value.coerceIn(1, 6)) }
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
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), TransfersUiState())

    // PC-39 ETA: each direction's estimator is fed the direction's
    // cumulative completed totals about once a second (the ticker keeps
    // snapshots flowing even when the table is quiet, so a stall decays
    // the measured rate instead of freezing a stale ETA on screen).
    private val uploadEtaEstimator = TransferEtaEstimator()
    private val downloadEtaEstimator = TransferEtaEstimator()

    val transferEtas: StateFlow<TransferEtas> = combine(
        transferRepository.observeCompletedTotals(incoming = false),
        transferRepository.observeCompletedTotals(incoming = true),
        transferRepository.observeRemainingWork(incoming = false),
        transferRepository.observeRemainingWork(incoming = true),
        flow {
            while (true) {
                emit(Unit)
                delay(ETA_SAMPLE_MS)
            }
        }
    ) { upDone, downDone, upLeft, downLeft, _ ->
        val now = System.currentTimeMillis()
        uploadEtaEstimator.onSnapshot(now, upDone.files, upDone.bytes)
        downloadEtaEstimator.onSnapshot(now, downDone.files, downDone.bytes)
        val upload = uploadEtaEstimator.estimate(
            nowMs = now,
            remainingFiles = upLeft.files,
            remainingBytes = upLeft.bytes,
            running = upLeft.running
        )
        val download = downloadEtaEstimator.estimate(
            nowMs = now,
            remainingFiles = downLeft.files,
            remainingBytes = downLeft.bytes,
            running = downLeft.running
        )
        TransferEtas(upload = upload, download = download, overall = overallEta(upload, download))
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), TransferEtas())

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

    fun pauseAll() = launch { transferRepository.pauseAll() }

    fun resumeAll() = launch { transferRepository.resumeAll() }

    fun cancelAll() = launch { transferRepository.cancelAll() }

    fun clearFinished() = launch { transferRepository.clearFinished() }

    /**
     * One-line outcomes the screen must SHOW, never swallow (field notes 3
     * and 6): bulk-retry results and the archive-retry skip. The screen
     * collects this into its snackbar.
     */
    private val _userMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val userMessages: SharedFlow<String> = _userMessages.asSharedFlow()

    private fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    companion object {
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

        /** ETA sampling cadence: fast enough to feel live, slow enough to be free. */
        const val ETA_SAMPLE_MS = 1_000L
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

/** Both directions' ETAs plus the combined one (PC-39); see [TransferEtaEstimator]. */
data class TransferEtas(
    val upload: EtaEstimate = EtaEstimate.Hidden,
    val download: EtaEstimate = EtaEstimate.Hidden,
    val overall: EtaEstimate = EtaEstimate.Hidden
)
