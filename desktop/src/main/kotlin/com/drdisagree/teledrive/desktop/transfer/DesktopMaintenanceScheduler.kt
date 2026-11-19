package com.drdisagree.teledrive.desktop.transfer

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.telegram.TelegramAuthState
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.update.UpdateChecker
import com.drdisagree.teledrive.desktop.notifications.DesktopNotifications
import com.drdisagree.teledrive.desktop.resources.Res
import com.drdisagree.teledrive.desktop.resources.app_name
import com.drdisagree.teledrive.desktop.resources.notification_update_available
import com.drdisagree.teledrive.desktop.resources.notification_update_message
import com.drdisagree.teledrive.domain.model.BackupTrigger
import com.drdisagree.teledrive.domain.repository.BackupRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TelegramAuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.getString

/**
 * Desktop counterpart of Android's WorkMaintenanceScheduler. There is no
 * WorkManager on desktop and no always-on service: scheduling is in-process,
 * so scans run while the app is open, plus a catch-up scan each time
 * scheduling is (re)armed — app start and every settings change, which is
 * when Main/Settings call in. Mirrors the Android workers' semantics:
 *
 * - the periodic scan repeats every [scheduleAll] interval with
 *   [BackupTrigger.SCHEDULED];
 * - a lighter sweep repeats hourly with [BackupTrigger.AUTOMATIC] (Android's
 *   MediaSweepWorker cadence; desktop has no media-store trigger, so the
 *   sweep is also what notices changed files between periodic scans);
 * - every scan first waits for the Telegram session to reach Ready, exactly
 *   like the Android workers, and quietly skips the cycle otherwise;
 * - update checks repeat daily and notify like Android's UpdateCheckWorker.
 *
 * Wi-Fi/charging constraints do not gate anything here: the desktop network
 * and power monitors always report unmetered/charging, so Android's
 * constraint checks would never hold a desktop scan.
 */
class DesktopMaintenanceScheduler(
    private val backupRepository: BackupRepository,
    private val telegramAuthRepository: TelegramAuthRepository,
    private val settingsRepository: SettingsRepository,
    private val updateChecker: UpdateChecker,
    private val notifications: DesktopNotifications,
    dispatchers: DispatcherProvider
) : MaintenanceScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val lock = Any()
    private var backupJob: Job? = null
    private var sweepJob: Job? = null
    private var updateJob: Job? = null

    internal var sweepIntervalMs: Long = SWEEP_INTERVAL_MS
    internal var updateIntervalMs: Long = UPDATE_INTERVAL_MS
    internal var authWaitMs: Long = AUTH_WAIT_MS

    override fun scheduleUpdateCheck(enabled: Boolean) {
        synchronized(lock) {
            updateJob?.cancel()
            updateJob = if (enabled) launchUpdateLoop() else null
        }
    }

    override fun scheduleAll(
        backupEnabled: Boolean,
        backupIntervalHours: Int,
        wifiOnly: Boolean,
        chargingOnly: Boolean,
        instantBackup: Boolean,
        updateChecks: Boolean
    ) {
        synchronized(lock) {
            backupJob?.cancel()
            backupJob = null
            sweepJob?.cancel()
            sweepJob = null
            if (backupEnabled) {
                val intervalMs = backupIntervalHours.coerceAtLeast(1) * HOUR_MS
                backupJob = scope.launch {
                    // Catch-up scan first (app start / settings change), then periodic.
                    runBackupScan(BackupTrigger.SCHEDULED)
                    while (isActive) {
                        delay(intervalMs)
                        runBackupScan(BackupTrigger.SCHEDULED)
                    }
                }
                sweepJob = scope.launch {
                    while (isActive) {
                        delay(sweepIntervalMs)
                        if (settingsRepository.preferences.first().autoBackupEnabled) {
                            runBackupScan(BackupTrigger.AUTOMATIC)
                        }
                    }
                }
            }
        }
        scheduleUpdateCheck(updateChecks)
    }

    /** One scan cycle; returns false when the session is not Ready and the cycle is skipped. */
    internal suspend fun runBackupScan(trigger: BackupTrigger): Boolean {
        if (!awaitReady()) return false
        return when (val result = backupRepository.startBackup(trigger)) {
            is AppResult.Success -> true
            is AppResult.Failure -> {
                SafeLog.d(TAG, "Scheduled backup scan skipped: ${result.error}")
                false
            }
        }
    }

    private suspend fun awaitReady(): Boolean {
        val state = withTimeoutOrNull(authWaitMs) {
            telegramAuthRepository.authState.first {
                it != TelegramAuthState.Uninitialized && it != TelegramAuthState.Initializing
            }
        }
        return state == TelegramAuthState.Ready
    }

    private fun launchUpdateLoop(): Job = scope.launch {
        while (isActive) {
            delay(updateIntervalMs)
            runCatching {
                val release = updateChecker.newerRelease() ?: return@runCatching
                notifications.notifyUpdate(
                    title = getString(Res.string.notification_update_available),
                    message = getString(
                        Res.string.notification_update_message,
                        getString(Res.string.app_name),
                        release.version
                    )
                )
            }.onFailure { SafeLog.w(TAG, "Update check failed", it) }
        }
    }

    internal companion object {
        const val TAG = "DesktopMaintenanceScheduler"
        const val HOUR_MS = 3_600_000L
        const val SWEEP_INTERVAL_MS = 60L * 60L * 1000L
        const val UPDATE_INTERVAL_MS = 24L * HOUR_MS
        const val AUTH_WAIT_MS = 60_000L
    }
}
