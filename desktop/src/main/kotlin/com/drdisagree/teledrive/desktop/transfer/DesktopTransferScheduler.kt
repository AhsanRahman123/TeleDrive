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
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

class DesktopTransferScheduler(
    private val drainer: TransferQueueDrainer,
    dispatchers: DispatcherProvider
) : TransferScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val draining = AtomicBoolean(false)
    private val requested = AtomicBoolean(false)
    @Volatile
    private var drainJob: Job? = null

    override fun kick(allowMetered: Boolean) {
        requested.set(true)
        if (!draining.compareAndSet(false, true)) return
        val job = scope.launch {
            try {
                while (requested.getAndSet(false)) {
                    while (drainer.drain(
                            isStopped = { false },
                            onTerminalFailure = { }
                        ) == TransferDrainResult.INTERRUPTED
                    ) {
                        delay(RETRY_DELAY_SECONDS.seconds)
                    }
                }
            } finally {
                draining.set(false)
            }
            if (requested.get()) kick(allowMetered)
        }
        drainJob = job
    }

    override fun rekick(allowMetered: Boolean) {
        kick(allowMetered)
    }

    override suspend fun pause() {
        requested.set(false)
        val active = drainJob
        active?.cancelAndJoin()
        if (drainJob === active) drainJob = null
    }

    private companion object {
        const val RETRY_DELAY_SECONDS = 15
    }
}
