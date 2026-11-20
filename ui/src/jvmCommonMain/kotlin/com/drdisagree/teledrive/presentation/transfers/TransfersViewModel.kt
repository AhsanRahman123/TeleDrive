package com.drdisagree.teledrive.presentation.transfers

import androidx.lifecycle.ViewModel
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import kotlinx.coroutines.flow.StateFlow

/**
 * The Transfers screen's ViewModel — a thin lifecycle wrapper over the
 * app-level [TransfersStore] (round 6, PC-26). All state and every
 * control live in the store, which is a Koin single: the screen and the
 * desktop transfer widgets observe and control the SAME instance, so
 * the two surfaces can never hold divergent transfer state or race
 * each other's commands. The two-repository constructor remains for
 * tests and direct constructions; it builds a private store, exactly
 * like the pre-round-6 ViewModel behaved.
 */
class TransfersViewModel(
    private val store: TransfersStore
) : ViewModel() {

    constructor(
        transferRepository: TransferRepository,
        settingsRepository: SettingsRepository
    ) : this(TransfersStore(transferRepository, settingsRepository))

    val transferConcurrency: StateFlow<Int> = store.transferConcurrency

    fun setTransferConcurrency(value: Int) = store.setTransferConcurrency(value)

    val uiState: StateFlow<TransfersUiState> = store.uiState

    fun pause(id: String) = store.pause(id)

    fun resume(id: String) = store.resume(id)

    fun cancel(id: String) = store.cancel(id)

    fun retry(id: String) = store.retry(id)

    fun setPriority(id: String, priority: Int) = store.setPriority(id, priority)

    fun reorderDownloads(orderedIds: List<String>) = store.reorderDownloads(orderedIds)

    fun moveDownloadToTop(id: String) = store.moveDownloadToTop(id)

    fun pauseSelected(ids: List<String>) = store.pauseSelected(ids)

    fun resumeSelected(ids: List<String>) = store.resumeSelected(ids)

    fun cancelSelected(ids: List<String>) = store.cancelSelected(ids)

    fun retrySelected(ids: List<String>) = store.retrySelected(ids)

    fun pauseAll() = store.pauseAll()

    fun resumeAll() = store.resumeAll()

    fun cancelAll() = store.cancelAll()

    fun clearFinished() = store.clearFinished()
}
