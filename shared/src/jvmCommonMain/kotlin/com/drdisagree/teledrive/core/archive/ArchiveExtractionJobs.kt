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
 */
class ArchiveExtractionJobs {

    private val jobs = ConcurrentHashMap<String, Job>()

    fun register(id: String, job: Job) {
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
        job.cancel()
        return true
    }

    fun activeIds(): Set<String> = jobs.keys.toSet()
}
