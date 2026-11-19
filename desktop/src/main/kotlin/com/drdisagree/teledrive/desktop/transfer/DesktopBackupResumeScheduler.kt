package com.drdisagree.teledrive.desktop.transfer

import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.transfer.BackupResumeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Desktop counterpart of Android's WorkBackupResumeScheduler. The drainer
 * asks for a resume when a finished drain left backups gate-held; on desktop
 * the power/network gates never hold (always charging, always unmetered), so
 * the Android worker's constraint wait collapses to a short delayed kick of
 * the transfer queue. Like WorkManager's REPLACE policy, a newer request
 * replaces a still-pending one.
 *
 * The kick target is wired after the Koin graph exists (see Main): the
 * scheduler cannot take TransferScheduler as a constructor dependency
 * without a cycle (drainer -> resume scheduler -> transfer scheduler ->
 * drainer).
 */
class DesktopBackupResumeScheduler(
    dispatchers: DispatcherProvider
) : BackupResumeScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

    @Volatile
    private var pending: Job? = null

    /** Set once by Main to TransferScheduler::kick; null-safe until wired. */
    @Volatile
    var kickAction: ((allowMetered: Boolean) -> Unit)? = null

    internal var resumeDelayMs: Long = RESUME_DELAY_MS

    @Synchronized
    override fun resumeWhen(chargingOnly: Boolean, wifiOnly: Boolean) {
        pending?.cancel()
        pending = scope.launch {
            delay(resumeDelayMs)
            kickAction?.invoke(true)
        }
    }

    internal companion object {
        const val RESUME_DELAY_MS = 30_000L
    }
}
