package com.drdisagree.teledrive.core.transfer

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager

class WorkTransferScheduler(
    private val context: Context,
    private val drainer: TransferQueueDrainer
) : TransferScheduler {

    /**
     * A request keeps its constraints for life, so anything not running is replaced, or Wi-Fi only
     * work would keep waiting after mobile data is allowed.
     *
     * While the drainer's Pause-all hold is set, kicks are dropped entirely:
     * Pause all must hold the whole queue until an explicit resume, so a new
     * enqueue, a wake, or a backup-resume must not restart the worker behind
     * the user's back (vc184 A2; the desktop scheduler's globallyPaused latch
     * is the same contract on that platform).
     */
    override fun kick(allowMetered: Boolean) {
        if (drainer.isPauseHeld()) return
        val workManager = WorkManager.getInstance(context)
        val pending = workManager.getWorkInfosForUniqueWork(TransferQueueWorker.UNIQUE_NAME)
        pending.addListener(
            {
                val states = runCatching { pending.get() }
                    .getOrNull()
                    .orEmpty()
                    .map { it.state }
                enqueue(
                    allowMetered = allowMetered,
                    policy = when {
                        WorkInfo.State.BLOCKED in states -> ExistingWorkPolicy.KEEP
                        WorkInfo.State.RUNNING in states -> ExistingWorkPolicy.APPEND_OR_REPLACE
                        else -> ExistingWorkPolicy.REPLACE
                    },
                    expedited = true
                )
            },
            Runnable::run
        )
    }

    override fun rekick(allowMetered: Boolean) {
        if (drainer.isPauseHeld()) return
        enqueue(allowMetered, ExistingWorkPolicy.REPLACE, expedited = false)
    }

    /**
     * Pause all, at the scheduler level (vc184 A2): set the drainer's hold and
     * stop the running worker. Previously this was the interface's no-op
     * default, so nothing at the engine level ever latched — the repository's
     * row writes were the whole of Pause all. Cancelling the unique work also
     * ends any worker chained behind the running one by earlier kicks. Rows
     * were already written PAUSED by the repository before this is called, and
     * the cancelled drain's recovery only requeues RUNNING rows, so nothing
     * here can resurrect a paused row.
     */
    override suspend fun pause() {
        drainer.setPauseHold(true)
        WorkManager.getInstance(context).cancelUniqueWork(TransferQueueWorker.UNIQUE_NAME)
    }

    /** Explicit resume: clear the hold first, then restart the worker. */
    override fun resume(allowMetered: Boolean) {
        drainer.setPauseHold(false)
        rekick(allowMetered)
    }

    override fun isPauseHeld(): Boolean = drainer.isPauseHeld()

    private fun enqueue(
        allowMetered: Boolean,
        policy: ExistingWorkPolicy,
        expedited: Boolean
    ) {
        val request = OneTimeWorkRequestBuilder<TransferQueueWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(
                        if (allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED
                    )
                    .build()
            )
            .apply {
                if (expedited) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TransferQueueWorker.UNIQUE_NAME,
            policy,
            request
        )
    }
}
