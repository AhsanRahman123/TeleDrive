package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.network.NetworkStatus
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Transient failures retry with exponential backoff; rate limits wait the server-given delay. */
class TransferQueueDrainer(
    private val transferDao: TransferDao,
    private val fileDao: FileDao,
    private val transferExecutor: TransferExecutor,
    private val backupSessionTracker: BackupSessionTracker,
    private val settingsRepository: SettingsRepository,
    private val networkMonitor: NetworkMonitor,
    private val powerMonitor: PowerMonitor,
    private val backupResumeScheduler: BackupResumeScheduler
) {

    private val claimLock = Mutex()
    private val executing = mutableMapOf<String, TransferType>()

    /**
     * PC-39 (1.6.17): uploads and downloads drain through independent lanes,
     * each bounded by its own live user setting ([TransferConcurrency]), so
     * the two directions run truly simultaneously and can never starve each
     * other. Before, one shared pool capped the SUM of both directions.
     * Backups ride the upload lane: they create messages and contend for
     * the same pacer creation lane as uploads.
     *
     * The gate is an ACTIVE COUNT, not a slot index: a lane starts a new
     * transfer only while its running count is below the lane's current
     * limit. That is what makes the setting safe to change mid-transfer —
     * raising it lets waiting slots claim at once (in-flight transfers are
     * never restarted), and lowering it cannot strand the lane above the
     * new limit, because no slot may claim while the count is at or above
     * it; in-flight transfers simply finish.
     */
    private enum class Lane {
        UPLOAD,
        DOWNLOAD
    }

    private sealed interface ClaimResult {
        data class Claimed(val id: String) : ClaimResult
        object QueueEmpty : ClaimResult
        object LimitReached : ClaimResult
    }

    suspend fun drain(
        isStopped: () -> Boolean,
        onTerminalFailure: suspend (TransferEntity) -> Unit
    ): TransferDrainResult {
        val interrupted = AtomicBoolean(false)
        val backupsHeld = AtomicBoolean(false)

        coroutineScope {
            for (lane in Lane.entries) {
                repeat(TransferConcurrency.MAX_SIMULTANEOUS) { _ ->
                    launch {
                        laneLoop(lane, isStopped, onTerminalFailure, interrupted, backupsHeld)
                    }
                }
            }
        }
        if (backupsHeld.get()) scheduleResume()
        if (interrupted.get()) {
            withContext(NonCancellable) {
                transferDao.requeueRunning()
                // Report-only archive rows are excluded from requeueRunning;
                // an interrupted drain fails them instead (vc179 clause 2).
                transferDao.failInterruptedArchiveDownloads(System.currentTimeMillis())
            }
            return TransferDrainResult.INTERRUPTED
        }
        return TransferDrainResult.COMPLETED
    }

    private suspend fun laneLoop(
        lane: Lane,
        isStopped: () -> Boolean,
        onTerminalFailure: suspend (TransferEntity) -> Unit,
        interrupted: AtomicBoolean,
        backupsHeld: AtomicBoolean
    ) {
        while (true) {
            if (isStopped()) {
                interrupted.set(true)
                return
            }
            val prefs = settingsRepository.preferences.first()
            val status = networkMonitor.currentStatus()
            val blocked = status == NetworkStatus.UNAVAILABLE ||
                    (status == NetworkStatus.METERED && !prefs.allowMeteredTransfers)
            if (blocked) {
                interrupted.set(true)
                return
            }
            // Only the upload lane carries backups, so only it consults the gate.
            val hold = if (lane == Lane.UPLOAD) {
                BackupGate.hold(prefs, status, powerMonitor.isCharging())
            } else {
                null
            }
            val limit = limitOf(lane, prefs)
            when (val claim = tryClaim(lane, includeBackups = hold == null, limit = limit)) {
                is ClaimResult.Claimed -> {
                    try {
                        runTransfer(claim.id, prefs.transferRetryCount, onTerminalFailure)
                    } finally {
                        withContext(NonCancellable) {
                            claimLock.withLock { executing.remove(claim.id) }
                        }
                    }
                }

                ClaimResult.LimitReached -> awaitLaneOpening(lane, limit)

                ClaimResult.QueueEmpty -> {
                    if (lane == Lane.UPLOAD && hold != null &&
                        transferDao.hasQueued(TransferType.BACKUP)
                    ) {
                        backupsHeld.set(true)
                    }
                    if (anyBusy()) {
                        delay(IDLE_POLL_MS.milliseconds)
                        continue
                    }
                    return
                }
            }
        }
    }

    /**
     * Waits while this lane is at its limit. Wakes the moment the user
     * raises the lane's limit above [limit] (the preferences flow emits),
     * and otherwise re-checks after a short interval — a finishing
     * transfer may have brought the active count below the limit.
     */
    private suspend fun awaitLaneOpening(lane: Lane, limit: Int) {
        withTimeoutOrNull(LIMIT_RECHECK_MS.milliseconds) {
            settingsRepository.preferences.first { limitOf(lane, it) > limit }
        }
    }

    suspend fun hasRunnableWork(): Boolean {
        val prefs = settingsRepository.preferences.first()
        val hold = currentHold(prefs)
        val uploadRunnable = nextClaimable(Lane.UPLOAD, includeBackups = hold == null) != null
        val downloadRunnable = nextClaimable(Lane.DOWNLOAD, includeBackups = true) != null
        if (uploadRunnable || downloadRunnable) return true
        if (hold != null && transferDao.hasQueued(TransferType.BACKUP)) scheduleResume()
        return false
    }

    private fun currentHold(prefs: UserPreferences) =
        BackupGate.hold(prefs, networkMonitor.currentStatus(), powerMonitor.isCharging())

    private suspend fun scheduleResume() {
        val prefs = settingsRepository.preferences.first()
        backupResumeScheduler.resumeWhen(prefs.backupChargingOnly, prefs.backupWifiOnly)
    }

    private fun limitOf(lane: Lane, prefs: UserPreferences): Int = when (lane) {
        Lane.UPLOAD -> TransferConcurrency.uploadLimit(prefs)
        Lane.DOWNLOAD -> TransferConcurrency.downloadLimit(prefs)
    }

    private fun laneTypes(lane: Lane, includeBackups: Boolean): List<TransferType> = when (lane) {
        Lane.UPLOAD ->
            if (includeBackups) {
                listOf(TransferType.UPLOAD, TransferType.BACKUP)
            } else {
                listOf(TransferType.UPLOAD)
            }

        Lane.DOWNLOAD -> listOf(TransferType.DOWNLOAD, TransferType.RESTORE)
    }

    private fun laneOf(type: TransferType): Lane = when (type) {
        TransferType.UPLOAD, TransferType.BACKUP -> Lane.UPLOAD
        TransferType.DOWNLOAD, TransferType.RESTORE -> Lane.DOWNLOAD
        // Report-only rows are never claimed (laneTypes never lists them),
        // so this arm is unreachable for executing transfers; it exists only
        // to keep the when exhaustive over the vc179 type.
        TransferType.ARCHIVE_DOWNLOAD -> Lane.DOWNLOAD
    }

    private suspend fun nextClaimable(
        lane: Lane,
        includeBackups: Boolean,
        skip: Set<String> = emptySet()
    ): TransferEntity? {
        val types = laneTypes(lane, includeBackups)
        return transferDao.nextQueuedOf(types, skip.size + 1).firstOrNull { it.id !in skip }
    }

    /**
     * Claims the lane's next queued transfer iff the lane's active count is
     * below [limit]. The count check and the claim happen under the one
     * [claimLock], so two slots racing a single free place cannot both
     * start — and a limit lowered a moment ago is honoured by the very
     * next claim, whichever slot makes it.
     */
    private suspend fun tryClaim(
        lane: Lane,
        includeBackups: Boolean,
        limit: Int
    ): ClaimResult = claimLock.withLock {
        val next = nextClaimable(
            lane = lane,
            includeBackups = includeBackups,
            skip = executing.keys
        ) ?: return@withLock ClaimResult.QueueEmpty
        val activeInLane = executing.values.count { laneOf(it) == lane }
        if (activeInLane >= limit) return@withLock ClaimResult.LimitReached
        transferDao.setState(next.id, TransferState.RUNNING, System.currentTimeMillis())
        executing[next.id] = next.type
        ClaimResult.Claimed(next.id)
    }

    /**
     * A slot idles out only when NOTHING is executing in either lane — the same
     * liveness shape as the old shared pool, so work enqueued mid-drain (in
     * either direction) is picked up within the idle-poll interval instead of
     * waiting for the whole drain to end.
     */
    private suspend fun anyBusy(): Boolean = claimLock.withLock { executing.isNotEmpty() }

    private suspend fun runTransfer(
        transferId: String,
        maxRetries: Int,
        onTerminalFailure: suspend (TransferEntity) -> Unit
    ) {
        var attempt = 0
        while (true) {
            val current = transferDao.byId(transferId) ?: return
            if (current.state != TransferState.RUNNING) return

            when (val outcome = transferExecutor.execute(current)) {
                is TransferOutcome.Completed -> {
                    refreshSession(current.backupSessionId)
                    return
                }

                is TransferOutcome.Paused -> {
                    // Pause already changed the row to PAUSED. Do not write PAUSED again:
                    // Resume all may have changed it back to QUEUED while the old native
                    // request was winding down. Leaving QUEUED lets this slot pick it up again.
                    return
                }

                is TransferOutcome.Canceled -> {
                    markState(transferId, TransferState.CANCELLED)
                    refreshSession(current.backupSessionId)
                    return
                }

                is TransferOutcome.Failed -> {
                    attempt++
                    if (attempt > maxRetries) {
                        withContext(NonCancellable) {
                            transferDao.setFailed(
                                transferId,
                                TransferState.FAILED,
                                outcome.message,
                                System.currentTimeMillis()
                            )
                            current.fileId?.let { fileId ->
                                fileDao.setBackupStateIfLocalOnly(fileId, BackupState.FAILED)
                            }
                            onTerminalFailure(current)
                        }
                        refreshSession(current.backupSessionId)
                        return
                    }
                    val backoffSeconds = outcome.retryAfterSeconds
                        ?: (BASE_BACKOFF_SECONDS shl (attempt - 1)).coerceAtMost(MAX_BACKOFF_SECONDS)
                    delay((backoffSeconds * 1000L).milliseconds)
                }
            }
        }
    }

    private suspend fun refreshSession(sessionId: String?) {
        withContext(NonCancellable) { backupSessionTracker.refresh(sessionId) }
    }

    private suspend fun markState(transferId: String, state: TransferState) {
        withContext(NonCancellable) {
            transferDao.setState(transferId, state, System.currentTimeMillis())
        }
    }

    private companion object {
        const val LIMIT_RECHECK_MS = 250L
        const val IDLE_POLL_MS = 1_000L
        const val BASE_BACKOFF_SECONDS = 2
        const val MAX_BACKOFF_SECONDS = 300
    }
}
