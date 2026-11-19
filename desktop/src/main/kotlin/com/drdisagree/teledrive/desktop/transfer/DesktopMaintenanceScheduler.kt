package com.drdisagree.teledrive.desktop.transfer

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.transfer.MaintenanceScheduler
import com.drdisagree.teledrive.core.update.UpdateChecker
import com.drdisagree.teledrive.desktop.notifications.DesktopNotifications
import com.drdisagree.teledrive.desktop.resources.Res
import com.drdisagree.teledrive.desktop.resources.app_name
import com.drdisagree.teledrive.desktop.resources.notification_update_available
import com.drdisagree.teledrive.desktop.resources.notification_update_message
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

/**
 * Desktop counterpart of Android's WorkMaintenanceScheduler. There is no
 * WorkManager on desktop and no always-on service: scheduling is in-process,
 * armed at app start and on settings changes.
 *
 * Folder auto-backup was removed from the desktop app (2026-11-20, at the
 * user's request), so the backup parameters of [scheduleAll] are accepted
 * for interface parity with Android and deliberately ignored: this class
 * never starts a backup scan. What remains is the maintenance Android's
 * workers also run unconditionally:
 *
 * - cache-limit enforcement and expired-trash clearing at arm time and then
 *   daily (Android's CacheCleanupWorker / TrashCleanupWorker);
 * - update checks repeating daily, notifying like Android's
 *   UpdateCheckWorker.
 */
class DesktopMaintenanceScheduler(
    private val settingsRepository: SettingsRepository,
    private val updateChecker: UpdateChecker,
    private val notifications: DesktopNotifications,
    private val cacheRepository: CacheRepository,
    private val trashRepository: TrashRepository,
    dispatchers: DispatcherProvider
) : MaintenanceScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val lock = Any()
    private var updateJob: Job? = null
    private var cleanupJob: Job? = null

    internal var updateIntervalMs: Long = UPDATE_INTERVAL_MS
    internal var cleanupIntervalMs: Long = UPDATE_INTERVAL_MS

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
        // Desktop auto-backup is removed: backupEnabled and its companions
        // are ignored whatever the stored preferences say, so a watch-list
        // configured earlier can never arm a scan again. Only the cleanup
        // loop and the update check are (re)armed.
        synchronized(lock) {
            cleanupJob?.cancel()
            cleanupJob = scope.launch {
                runCleanup()
                while (isActive) {
                    delay(cleanupIntervalMs)
                    runCleanup()
                }
            }
        }
        scheduleUpdateCheck(updateChecks)
    }

    /** One cache/trash maintenance pass, with Android's worker semantics. */
    internal suspend fun runCleanup() {
        val prefs = settingsRepository.preferences.first()
        runCatching {
            cacheRepository.enforceLimit(prefs.maxCacheSizeMb.toLong() * 1024L * 1024L)
        }.onFailure { SafeLog.w(TAG, "Cache cleanup failed", it) }
        if (prefs.trashAutoClearDays > 0) {
            runCatching { trashRepository.clearExpired(prefs.trashAutoClearDays) }
                .onFailure { SafeLog.w(TAG, "Trash cleanup failed", it) }
        }
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
        const val UPDATE_INTERVAL_MS = 24L * HOUR_MS
    }
}
