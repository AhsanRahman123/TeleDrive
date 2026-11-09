package com.drdisagree.teledrive.desktop.transfer

import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.transfer.TransferDrainResult
import com.drdisagree.teledrive.core.transfer.TransferQueueDrainer
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class DesktopTransferScheduler(
    private val drainer: TransferQueueDrainer,
    dispatchers: DispatcherProvider
) : TransferScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val draining = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    private val stateLock = Any()
    @Volatile
    private var globallyPaused = false
    @Volatile
    private var drainJob: Job? = null

    override fun kick(allowMetered: Boolean) {
        synchronized(stateLock) {
            if (globallyPaused) return
            requested.set(true)
            if (!draining.compareAndSet(false, true)) return

            // Lazy start ensures the Job is recorded before its finally block can run.
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (requested.getAndSet(false) && !globallyPaused) {
                        while (
                            !globallyPaused &&
                            drainer.drain(
                                isStopped = { globallyPaused },
                                onTerminalFailure = { }
                            ) == TransferDrainResult.INTERRUPTED
                        ) {
                            if (!globallyPaused) delay(RETRY_DELAY_SECONDS.seconds)
                        }
                    }
                } finally {
                    draining.set(false)
                }
                if (requested.get() && !globallyPaused) kick(allowMetered)
            }
            drainJob = job
            job.start()
        }
    }

    override fun rekick(allowMetered: Boolean) {
        kick(allowMetered)
    }

    override suspend fun pause() {
        val active = synchronized(stateLock) {
            globallyPaused = true
            requested.set(false)
            drainJob
        }
        active?.cancelAndJoin()
        synchronized(stateLock) {
            if (drainJob === active) drainJob = null
            draining.set(false)
        }
    }

    override fun resume(allowMetered: Boolean) {
        synchronized(stateLock) { globallyPaused = false }
        kick(allowMetered)
    }

    private companion object {
        const val RETRY_DELAY_SECONDS = 15
    }
}
