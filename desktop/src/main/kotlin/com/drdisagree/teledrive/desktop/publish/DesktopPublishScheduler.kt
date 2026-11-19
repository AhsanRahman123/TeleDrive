package com.drdisagree.teledrive.desktop.publish

import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.publish.PublishScheduler
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DesktopPublishScheduler(
    private val drainer: PublishOutboxDrainer,
    dispatchers: DispatcherProvider
) : PublishScheduler {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val draining = AtomicBoolean(false)

    override fun kick() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                // No attempt cap: pending publishes (e.g. a folder move whose
                // caption edits were rejected) must keep retrying while the
                // app runs instead of being silently dropped after 10 tries.
                // Backoff grows 30s -> 5min so a persistent failure is cheap.
                var delaySeconds = RETRY_DELAY_SECONDS
                while (!drainer.drain(isStopped = { false })) {
                    delay(delaySeconds.seconds)
                    delaySeconds = minOf(delaySeconds * 2, MAX_RETRY_DELAY_SECONDS)
                }
            } finally {
                draining.set(false)
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_SECONDS = 30
        const val MAX_RETRY_DELAY_SECONDS = 300
    }
}
