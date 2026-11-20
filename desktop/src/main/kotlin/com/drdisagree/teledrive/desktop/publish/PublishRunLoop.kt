package com.drdisagree.teledrive.desktop.publish

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.publish.PublishRunState
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One publish attempt: drains the outbox once, honouring [isStopped] between batches. */
internal fun interface PublishDrain {
    suspend fun run(
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit,
        onFailure: suspend (fileId: String, error: AppError) -> Unit
    ): Boolean
}

/**
 * The publish scheduler's run loop (PC-32), split from
 * [DesktopPublishScheduler] so the whole kick / retry / cancel contract
 * is testable without a database or Telegram.
 *
 * The pre-PC-32 loop had two fatal properties: `kick()` was dropped
 * whenever a loop was already alive, and a drain that could never
 * succeed (the local-only head block) kept that loop alive forever —
 * so "Sync now" was a no-op for the life of the process and nothing
 * about the failure was visible anywhere. This loop instead:
 *
 * - treats every [kick] as owed work: during a run it requests exactly
 *   one more pass; during a backoff wait it wakes the loop to retry
 *   NOW; after a cancel it starts fresh;
 * - retries a failed drain with backoff (30 s → 5 min) for as long as
 *   the app runs, like before — but publishes its state the whole time
 *   ([PublishRunState.WaitingRetry] carries the last failure's message);
 * - supports cooperative [cancel]: the drain checks the stop flag
 *   between batches, published rows keep their cleared flags, the rest
 *   stay pending, and the state lands on [PublishRunState.Stopped];
 * - surfaces a drain that completed with permanently rejected files as
 *   [PublishRunState.Failed] instead of pretending success.
 */
internal class PublishRunLoop(
    private val drain: PublishDrain,
    private val scope: CoroutineScope,
    private val initialRetryDelaySeconds: Int = RETRY_DELAY_SECONDS,
    private val maxRetryDelaySeconds: Int = MAX_RETRY_DELAY_SECONDS
) {

    private val _runState = MutableStateFlow<PublishRunState>(PublishRunState.Idle)
    val runState: StateFlow<PublishRunState> = _runState.asStateFlow()

    private val stopRequested = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val loopActive = AtomicBoolean(false)

    private val wakeLock = Any()
    private var wakeSignal = CompletableDeferred<Unit>()

    fun kick() {
        stopRequested.set(false)
        if (loopActive.compareAndSet(false, true)) {
            scope.launch { runLoop() }
        } else {
            rerunRequested.set(true)
            signalWake()
        }
    }

    fun cancel() {
        if (!loopActive.get()) return
        stopRequested.set(true)
        signalWake()
    }

    private fun signalWake() {
        synchronized(wakeLock) {
            if (!wakeSignal.isCompleted) wakeSignal.complete(Unit)
        }
    }

    /** Waits out a backoff delay, returning early when [kick] or [cancel] lands. */
    private suspend fun awaitWakeOrTimeout(seconds: Int) {
        // Consume the current signal and install the next one, so a kick
        // that arrives between two waits is never lost.
        val signal = synchronized(wakeLock) {
            val current = wakeSignal
            wakeSignal = CompletableDeferred()
            current
        }
        if (!signal.isCompleted) {
            withTimeoutOrNull(seconds.seconds) { signal.await() }
        }
    }

    private suspend fun runLoop() {
        try {
            var delaySeconds = initialRetryDelaySeconds
            while (true) {
                rerunRequested.set(false)
                val failures =
                    Collections.synchronizedList(mutableListOf<Pair<String, AppError>>())
                _runState.value = PublishRunState.Running(0, 0)
                val drained = drain.run(
                    isStopped = { stopRequested.get() },
                    onProgress = { published, total ->
                        _runState.value = PublishRunState.Running(published, total)
                    },
                    onFailure = { fileId, error -> failures += fileId to error }
                )
                when {
                    stopRequested.get() -> {
                        _runState.value = PublishRunState.Stopped
                        return
                    }
                    drained && failures.isEmpty() -> {
                        if (rerunRequested.get()) continue
                        _runState.value = PublishRunState.Idle
                        return
                    }
                    drained -> {
                        if (rerunRequested.get()) continue
                        _runState.value = PublishRunState.Failed(
                            failedFiles = failures.size,
                            reason = (failures.first().second as? AppError.TelegramError)?.message
                        )
                        return
                    }
                    else -> {
                        _runState.value = PublishRunState.WaitingRetry(
                            reason = (failures.lastOrNull()?.second as? AppError.TelegramError)
                                ?.message
                        )
                        awaitWakeOrTimeout(delaySeconds)
                        if (stopRequested.get()) {
                            _runState.value = PublishRunState.Stopped
                            return
                        }
                        delaySeconds = minOf(delaySeconds * 2, maxRetryDelaySeconds)
                    }
                }
            }
        } finally {
            loopActive.set(false)
            // A kick that landed while the loop was on its way out found
            // the loop still marked active and only set the rerun flag:
            // honour it now instead of dropping the press.
            if (rerunRequested.getAndSet(false) && !stopRequested.get()) {
                kick()
            }
        }
    }

    private companion object {
        const val RETRY_DELAY_SECONDS = 30
        const val MAX_RETRY_DELAY_SECONDS = 300
    }
}
