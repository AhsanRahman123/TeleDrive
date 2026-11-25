package com.drdisagree.teledrive.core.archive

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job

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
}
