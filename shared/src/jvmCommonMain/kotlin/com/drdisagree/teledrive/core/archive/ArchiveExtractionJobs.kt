package com.drdisagree.teledrive.core.archive

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

/**
 * Thread-safe registry of the in-flight archive-extraction jobs, keyed by the
 * Transfers-history record id the extraction reports into.
 *
 * The archive viewer owns the extractions; the transfer repository only needs a
 * handle it can cancel when the user pauses/cancels the record from Transfers.
 * Registration removes itself when the job completes, so a finished extraction
 * never lingers as a cancellable target.
 *
 * The registry also remembers WHICH stops it initiated ([cancel]): the download
 * tracker uses that to tell a user stop from Transfers (record keeps its
 * PAUSED/CANCELLED semantics) apart from a cancellation nobody asked for — an
 * internal timeout or error inside the extraction — which must land FAILED.
 */
class ArchiveExtractionJobs {

    private val jobs = ConcurrentHashMap<String, Job>()

    /** Ids whose current job was cancelled through [cancel] (a Transfers stop). */
    private val registryCancelled = ConcurrentHashMap.newKeySet<String>()

    fun register(id: String, job: Job) {
        registryCancelled.remove(id)
        jobs[id] = job
        job.invokeOnCompletion {
            // Only remove our own registration: a newer job may already have
            // been registered under the same id.
            jobs.remove(id, job)
        }
    }

    fun unregister(id: String) {
        jobs.remove(id)
    }

    /** Cancels the job registered under [id]; true when one was registered. */
    fun cancel(id: String): Boolean {
        val job = jobs[id] ?: return false
        // Mark BEFORE cancelling: the job's completion handlers (which report the
        // stop to the tracker) can run synchronously from here and must already
        // see that this stop came from the registry.
        registryCancelled += id
        job.cancel()
        return true
    }

    /**
     * True (once) if the stop of [id] was initiated through [cancel]. Consuming the
     * mark keeps a later, unrelated extraction under a recycled id unaffected.
     */
    fun consumeRegistryCancel(id: String): Boolean = registryCancelled.remove(id)

    /** Whether [id]'s stop was registry-initiated, without consuming the mark. */
    fun wasCancelledViaRegistry(id: String): Boolean = id in registryCancelled

    fun activeIds(): Set<String> = jobs.keys.toSet()

    // ------------------------------------------------------------------
    // Bulk queue control (1.6.25, item 1): Transfers-level Pause all /
    // Cancel all must reach the archive viewer's download QUEUE, not only
    // the extraction that happens to be running.
    //
    // Why this exists: queued in-ZIP requests have no Transfers record and
    // no registered job yet (records are created only when a request starts,
    // report 54 note 2), so the repository's bulk actions — which iterate
    // records / registered jobs — could not see them at all. Worse, the one
    // running extraction's per-record stop was (correctly, for a per-record
    // stop) treated by the queue as "continue with the next request", so
    // Pause all paused one file and the next queued file started anyway,
    // and Cancel all cancelled one file and the rest of the batch ran.
    //
    // The control state therefore lives here, in the app-singleton registry
    // both sides already share, so it outlives the archive pane / viewer:
    // the transfer repository writes it, the viewer's queue processor reads
    // and waits on it.
    // ------------------------------------------------------------------

    /** Bumped by every bulk cancel; a queue run that observes a change aborts. */
    private val bulkCancelEpochCounter = AtomicLong(0)

    /** True while a bulk pause holds the archive download queue. */
    private val queueHeldFlow = MutableStateFlow(false)

    /** The current bulk-cancel epoch; see [requestBulkCancel]. */
    fun bulkCancelEpoch(): Long = bulkCancelEpochCounter.get()

    /**
     * Bulk cancel (Transfers Cancel all): every queue run that started under
     * an older epoch must drop its queued requests and abort its remaining
     * steps. Also releases the queue hold, so a processor parked by a bulk
     * pause wakes up, observes the epoch change, and aborts instead of
     * waiting forever. Idempotent: pressing Cancel all again just bumps the
     * epoch again, which any run started since then also observes.
     */
    fun requestBulkCancel() {
        bulkCancelEpochCounter.incrementAndGet()
        queueHeldFlow.value = false
    }

    /** Bulk pause (Transfers Pause all): hold the queue after the running step stops. */
    fun holdQueue() {
        queueHeldFlow.value = true
    }

    /** Bulk resume (Transfers Resume all): release a bulk-pause hold. */
    fun releaseQueue() {
        queueHeldFlow.value = false
    }

    /** Whether the archive download queue is currently held by a bulk pause. */
    fun queueHeld(): Boolean = queueHeldFlow.value

    /**
     * Suspends while the queue is held by a bulk pause. Cancellable, so a
     * processor being torn down (or a step job stopped from Transfers) does
     * not park forever.
     */
    suspend fun awaitQueueReleased() {
        queueHeldFlow.first { held -> !held }
    }
}
