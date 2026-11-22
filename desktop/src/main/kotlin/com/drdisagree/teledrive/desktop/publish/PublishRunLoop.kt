package com.drdisagree.teledrive.desktop.publish

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.publish.PublishRunOutcome
import com.drdisagree.teledrive.core.publish.PublishRunState
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One publish attempt: drains the outbox once, honouring [isStopped] between batches.
 * [onWaiting] reports a rate-limit wait in progress (seconds left, once a
 * second; 0 when a wait ends) so the loop can show the wait and a "Sync
 * now" press can cut it short (PC-35).
 */
internal fun interface PublishDrain {
    suspend fun run(
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit,
        onWaiting: suspend (secondsLeft: Int) -> Unit,
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
 *
 * PC-35 addendum — the three guarantees the frozen "0 of 144" banner
 * broke, now held by construction:
 *
 * - **Cancel lands promptly from any point in a run.** [cancel] also
 *   cancels the drain's own job: every wait on the drain path (the
 *   drainer's waits, the pacer's flood sleep, the client's retry
 *   delays) is a cancellable delay, so the run stops within seconds
 *   instead of whenever the current wait happens to notice the flag.
 * - **"Sync now" is never a dead button.** During a run it owes one
 *   more pass (as before); during a rate-limit WAIT it additionally
 *   cancels the drain job, cutting the wait short — the loop lands in
 *   its retry path with the rerun flag set, so the retry starts at
 *   once. A drain that is actively publishing is not interrupted.
 * - **No run can hang in Running.** Each drain runs under a
 *   no-progress watchdog: if it reports neither progress, a failure,
 *   nor a waiting countdown for the watchdog window, the drain job is
 *   cancelled and the run ends in [PublishRunState.WaitingRetry] —
 *   the last-resort bound behind the drainer's per-file budgets, so
 *   no unforeseen wait anywhere in the path can freeze the banner.
 */
internal class PublishRunLoop(
    private val drain: PublishDrain,
    private val scope: CoroutineScope,
    private val initialRetryDelaySeconds: Int = RETRY_DELAY_SECONDS,
    private val maxRetryDelaySeconds: Int = MAX_RETRY_DELAY_SECONDS,
    private val watchdogTimeoutSeconds: Int = WATCHDOG_TIMEOUT_SECONDS,
    private val clock: () -> Instant = { Instant.now() },
    /**
     * Seconds left in the pacer's account-wide flood wait (PC-36).
     * Since PC-36 a pacer park ticks [PublishDrain]'s waiting report
     * like any other wait, so the watchdog should never fire for one;
     * if it ever does fire while a flood wait is in effect, the run's
     * WaitingRetry reason names the flood wait — the truth — instead
     * of the bare "no progress" text.
     */
    private val floodWaitSeconds: () -> Int = { 0 }
) {

    private val _runState = MutableStateFlow<PublishRunState>(PublishRunState.Idle)
    val runState: StateFlow<PublishRunState> = _runState.asStateFlow()

    /**
     * The last FINISHED run's outcome and finish time (PC-33).
     * Recorded only at the loop's terminal transitions — a run that
     * is still going or waiting to retry has not finished and leaves
     * the previous outcome untouched, so a Failed/Stopped outcome
     * stays on record (and on the Home card) until a later drain
     * completes and replaces it.
     */
    private val _lastOutcome = MutableStateFlow<PublishRunOutcome?>(null)
    val lastOutcome: StateFlow<PublishRunOutcome?> = _lastOutcome.asStateFlow()

    private val _lastFinishedAt = MutableStateFlow<Instant?>(null)
    val lastFinishedAt: StateFlow<Instant?> = _lastFinishedAt.asStateFlow()

    private fun recordOutcome(outcome: PublishRunOutcome) {
        _lastOutcome.value = outcome
        _lastFinishedAt.value = clock()
    }

    /**
     * The one-line reason a finished (or retry-waiting) run shows.
     *
     * PC-37: files whose Telegram copy turned out unrecoverable are
     * named — a bare count would leave the user guessing which files
     * are gone, and the whole point of the remote-missing mark is that
     * the loss is never silent. Up to three names are spelled out and
     * the rest counted; any other failure's Telegram message is
     * appended (or used alone when nothing went missing), preserving
     * the pre-PC-37 behaviour for ordinary rejections.
     */
    private fun failureReason(failures: List<Pair<String, AppError>>): String? {
        val missing = failures.mapNotNull { it.second as? AppError.RemoteMissing }
        val otherMessage = failures
            .firstOrNull { it.second !is AppError.RemoteMissing }
            ?.second?.let { (it as? AppError.TelegramError)?.message }
        if (missing.isEmpty()) return otherMessage
        val names = missing.take(3).joinToString(", ") { it.fileName }
        val more = if (missing.size > 3) " (+${missing.size - 3} more)" else ""
        val base = "Telegram copy missing for: $names$more"
        return if (otherMessage != null) "$base; also: $otherMessage" else base
    }

    private val stopRequested = AtomicBoolean(false)
    private val rerunRequested = AtomicBoolean(false)
    private val loopActive = AtomicBoolean(false)

    /** The in-flight drain's job, so [cancel] / a wait-cutting [kick] can stop it (PC-35). */
    private val activeDrainJob = AtomicReference<Job?>(null)

    /** True while the drain's latest report is a rate-limit wait (PC-35). */
    private val drainWaiting = AtomicBoolean(false)

    private val wakeLock = Any()
    private var wakeSignal = CompletableDeferred<Unit>()

    fun kick() {
        stopRequested.set(false)
        if (loopActive.compareAndSet(false, true)) {
            scope.launch { runLoop() }
        } else {
            rerunRequested.set(true)
            signalWake()
            // PC-35: a press while the drain is parked in a rate-limit
            // wait is an explicit "try again now" — cut the wait short
            // instead of only owing a pass after a drain that may not
            // end for minutes. The cancelled drain lands the loop in
            // its retry path, and the rerun flag above makes that
            // retry start at once. A drain that is actively
            // publishing is NOT interrupted; the rerun flag covers it.
            if (drainWaiting.get()) {
                activeDrainJob.get()?.cancel()
            }
        }
    }

    fun cancel() {
        if (!loopActive.get()) return
        stopRequested.set(true)
        signalWake()
        // PC-35: cancel the drain itself, not just the flag. The flag
        // is only observed between waits, and a drain parked inside
        // the pacer or the client's retry delay could sit out a long
        // flood wait before noticing it. Every wait on the drain path
        // is a cancellable delay, so this stops the run promptly from
        // any point; rows already sealed keep their cleared flags and
        // the rest stay pending, exactly as with the cooperative stop.
        activeDrainJob.get()?.cancel()
    }

    private fun signalWake() {
        synchronized(wakeLock) {
            if (!wakeSignal.isCompleted) wakeSignal.complete(Unit)
        }
    }

    /**
     * Runs one drain as a child job of the loop (PC-35), wiring its
     * reports into the run state and standing a no-progress watchdog
     * over it. Returns the drain's own verdict; a drain child cancelled
     * by [cancel], a wait-cutting [kick] or the watchdog reads as a
     * failed drain — the caller sorts out which from the flags — while
     * cancellation of the loop itself (shutdown) propagates.
     */
    private suspend fun runDrain(
        failures: MutableList<Pair<String, AppError>>
    ): Boolean = coroutineScope {
        val lastActivityMs = AtomicLong(System.currentTimeMillis())
        val lastPublished = AtomicInteger(0)
        val lastTotal = AtomicInteger(0)
        val watchdogFired = AtomicBoolean(false)
        val watchdogReason = AtomicReference<String?>(null)
        val child = async {
            drain.run(
                isStopped = { stopRequested.get() },
                onProgress = { published, total ->
                    lastActivityMs.set(System.currentTimeMillis())
                    lastPublished.set(published)
                    lastTotal.set(total)
                    drainWaiting.set(false)
                    _runState.value = PublishRunState.Running(published, total)
                },
                onWaiting = { secondsLeft ->
                    lastActivityMs.set(System.currentTimeMillis())
                    if (secondsLeft > 0) {
                        drainWaiting.set(true)
                        _runState.value = PublishRunState.Running(
                            published = lastPublished.get(),
                            total = lastTotal.get(),
                            waitingSecondsLeft = secondsLeft
                        )
                    } else {
                        drainWaiting.set(false)
                        _runState.value = PublishRunState.Running(
                            published = lastPublished.get(),
                            total = lastTotal.get()
                        )
                    }
                },
                onFailure = { fileId, error ->
                    lastActivityMs.set(System.currentTimeMillis())
                    failures += fileId to error
                }
            )
        }
        activeDrainJob.set(child)
        val watchdog = launch {
            while (child.isActive) {
                delay(WATCHDOG_POLL_SECONDS.seconds)
                if (System.currentTimeMillis() - lastActivityMs.get() >
                    watchdogTimeoutSeconds * 1000L
                ) {
                    // PC-36: log the idle age and the live flood
                    // deadline, and let the run's reason name a flood
                    // wait when one is in effect — the next field
                    // report should be diagnosable from this one line.
                    val idleSeconds =
                        (System.currentTimeMillis() - lastActivityMs.get()) / 1000L
                    val floodLeft = floodWaitSeconds()
                    SafeLog.w(
                        TAG,
                        "Publish drain made no progress for " +
                            "${watchdogTimeoutSeconds}s; ending the run " +
                            "(idle ${idleSeconds}s, flood wait ${floodLeft}s left)"
                    )
                    watchdogReason.set(
                        if (floodLeft > 0) {
                            "Telegram asked us to slow down — a flood wait " +
                                "of ${floodLeft}s was still in effect"
                        } else {
                            "Sync made no progress for ${watchdogTimeoutSeconds}s"
                        }
                    )
                    watchdogFired.set(true)
                    child.cancel()
                    break
                }
            }
        }
        try {
            child.await()
        } catch (e: CancellationException) {
            if (!isActive) throw e
            if (watchdogFired.get()) {
                throw IllegalStateException(
                    watchdogReason.get()
                        ?: "Sync made no progress for ${watchdogTimeoutSeconds}s"
                )
            }
            false
        } finally {
            watchdog.cancel()
            activeDrainJob.compareAndSet(child, null)
            drainWaiting.set(false)
        }
    }

    /** Waits out a backoff delay, returning early when [kick] or [cancel] lands. */
    private suspend fun awaitWakeOrTimeout(seconds: Int) {
        // Await the CURRENT signal — the very deferred signalWake()
        // completes. (An earlier version swapped in a fresh deferred
        // before awaiting the old one, so a kick completed the new
        // deferred while the loop slept on the old one: the wake was
        // lost and the loop slept out the whole backoff. The
        // PublishRunLoopTest re-kick case caught exactly that.)
        val signal = synchronized(wakeLock) { wakeSignal }
        if (!signal.isCompleted) {
            withTimeoutOrNull(seconds.seconds) { signal.await() }
        }
        // Retire the consumed signal so the next wait starts fresh. A
        // kick that lands after the wait already ended is not lost: it
        // also set the rerun flag, and the loop drains again right
        // after any wait ends.
        synchronized(wakeLock) {
            if (wakeSignal === signal && signal.isCompleted) {
                wakeSignal = CompletableDeferred()
            }
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
                var drainError: Throwable? = null
                val drained = try {
                    runDrain(failures)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A drain that throws (a locked database, a DAO
                    // failure) must surface like any other failed drain
                    // — never kill the loop with the state stuck at
                    // Running and nothing anywhere explaining the stop
                    // (second-pass finding, round 7).
                    SafeLog.w(TAG, "Publish drain threw; treating as a failed run", e)
                    drainError = e
                    false
                }
                when {
                    stopRequested.get() -> {
                        _runState.value = PublishRunState.Stopped
                        recordOutcome(PublishRunOutcome.Stopped)
                        return
                    }
                    drained && failures.isEmpty() -> {
                        if (rerunRequested.get()) continue
                        _runState.value = PublishRunState.Idle
                        recordOutcome(PublishRunOutcome.Completed)
                        return
                    }
                    drained -> {
                        if (rerunRequested.get()) continue
                        val reason = failureReason(failures)
                        _runState.value = PublishRunState.Failed(
                            failedFiles = failures.size,
                            reason = reason
                        )
                        recordOutcome(
                            PublishRunOutcome.Failed(
                                failedFiles = failures.size,
                                reason = reason
                            )
                        )
                        return
                    }
                    else -> {
                        _runState.value = PublishRunState.WaitingRetry(
                            reason = drainError?.message ?: failureReason(failures)
                        )
                        // If a "Sync now" press landed during the run
                        // (it cut a wait short, or simply asked for
                        // another pass), it already completed the wake
                        // signal, so this wait returns at once and the
                        // retry starts immediately (PC-35).
                        awaitWakeOrTimeout(delaySeconds)
                        if (stopRequested.get()) {
                            _runState.value = PublishRunState.Stopped
                            recordOutcome(PublishRunOutcome.Stopped)
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
        const val TAG = "PublishRunLoop"
        const val RETRY_DELAY_SECONDS = 30
        const val MAX_RETRY_DELAY_SECONDS = 300
        const val WATCHDOG_TIMEOUT_SECONDS = 180
        const val WATCHDOG_POLL_SECONDS = 1
    }
}
