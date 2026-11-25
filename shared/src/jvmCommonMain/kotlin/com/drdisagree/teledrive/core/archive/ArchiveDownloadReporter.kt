package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reports in-ZIP downloads into Transfers history as report-only
 * [TransferType.ARCHIVE_DOWNLOAD] rows.
 *
 * The extraction itself is owned by the archive viewer (PreviewViewModel); this
 * surface only mirrors its lifecycle into a transfer row. Every method tolerates
 * a null record id as a no-op, so an untracked download (see
 * [NoOpArchiveDownloadReporter], and [begin] returning null when its insert
 * fails) flows through the exact same call sites.
 */
interface ArchiveDownloadReporter {

    /** Starts a RUNNING history record; null means this download is untracked. */
    suspend fun begin(displayName: String, sizeBytes: Long): String?

    /** Registers the extraction [job] so Transfers can stop this record. */
    fun attach(recordId: String?, job: Job)

    /** Non-suspend progress callback; implementations throttle the actual writes. */
    fun progress(recordId: String?, transferredBytes: Long)

    suspend fun complete(recordId: String?, localPath: String)

    suspend fun fail(recordId: String?, message: String?)

    /**
     * The extraction's owner stopped (its coroutine was cancelled). The transfer
     * repository may already have written PAUSED/CANCELLED for this record; only
     * a row that is still RUNNING is terminated here.
     *
     * [cause] distinguishes WHY the extraction stopped (1.6.22): a stop initiated
     * through [ArchiveExtractionJobs] (the user pausing/cancelling from Transfers)
     * keeps the CANCELLED outcome; any other cancellation — above all an internal
     * [TimeoutCancellationException] from the byte source, which nobody asked
     * for — is a failure and lands FAILED. A null [cause] keeps the historical
     * CANCELLED outcome for callers that cannot say why they stopped.
     */
    suspend fun ownerStopped(recordId: String?, cause: CancellationException? = null)
}

/** Reporter for surfaces that do not write Transfers history (desktop this round). */
object NoOpArchiveDownloadReporter : ArchiveDownloadReporter {
    override suspend fun begin(displayName: String, sizeBytes: Long): String? = null
    override fun attach(recordId: String?, job: Job) = Unit
    override fun progress(recordId: String?, transferredBytes: Long) = Unit
    override suspend fun complete(recordId: String?, localPath: String) = Unit
    override suspend fun fail(recordId: String?, message: String?) = Unit
    override suspend fun ownerStopped(recordId: String?, cause: CancellationException?) = Unit
}

class ArchiveDownloadTracker(
    private val transferDao: TransferDao,
    private val jobs: ArchiveExtractionJobs
) : ArchiveDownloadReporter {

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last WRITTEN progress sample per record; the throttle compares against it. */
    private data class ProgressSample(
        val sizeBytes: Long,
        var lastWrittenBytes: Long,
        var lastWrittenAtMillis: Long
    )

    private val samples = ConcurrentHashMap<String, ProgressSample>()

    override suspend fun begin(displayName: String, sizeBytes: Long): String? {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val entity = TransferEntity(
            id = id,
            type = TransferType.ARCHIVE_DOWNLOAD,
            fileId = null,
            displayName = displayName,
            localPath = null,
            chatId = null,
            messageId = null,
            remoteFileId = null,
            telegramFileId = null,
            sizeBytes = sizeBytes,
            transferredBytes = 0,
            state = TransferState.RUNNING,
            createdAt = now,
            updatedAt = now
        )
        return try {
            transferDao.upsert(entity)
            samples[id] = ProgressSample(sizeBytes, 0L, now)
            id
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // A history failure must never block the download itself.
            null
        }
    }

    override fun attach(recordId: String?, job: Job) {
        if (recordId == null) return
        jobs.register(recordId, job)
    }

    override fun progress(recordId: String?, transferredBytes: Long) {
        if (recordId == null) return
        val sample = samples.getOrPut(recordId) {
            // No begin() sample (should not happen): last write at the epoch, so
            // the first progress call flushes immediately.
            ProgressSample(sizeBytes = -1L, lastWrittenBytes = 0L, lastWrittenAtMillis = 0L)
        }
        val now = System.currentTimeMillis()
        val shouldWrite: Boolean
        val speed: Long
        synchronized(sample) {
            val elapsed = now - sample.lastWrittenAtMillis
            val advanced = transferredBytes - sample.lastWrittenBytes
            shouldWrite = elapsed >= PROGRESS_MIN_INTERVAL_MS ||
                advanced >= PROGRESS_MIN_ADVANCE_BYTES ||
                (sample.sizeBytes >= 0 && transferredBytes == sample.sizeBytes)
            if (shouldWrite) {
                speed = if (elapsed > 0 && advanced > 0) advanced * 1000L / elapsed else 0L
                sample.lastWrittenBytes = transferredBytes
                sample.lastWrittenAtMillis = now
            } else {
                speed = 0L
            }
        }
        if (!shouldWrite) return
        writeScope.launch {
            try {
                transferDao.updateProgress(recordId, transferredBytes, speed, now)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                // Progress is best-effort; the row's terminal write still lands.
            }
        }
    }

    override suspend fun complete(recordId: String?, localPath: String) {
        if (recordId == null) return
        // Terminal writes are cancellation-proof (1.6.22): they are usually invoked
        // from the extraction coroutine, and when that coroutine has been cancelled
        // a plain suspend DAO call would throw CancellationException before writing,
        // leaving the row RUNNING forever. NonCancellable lets the write land.
        withContext(NonCancellable) {
            try {
                val row = transferDao.byId(recordId)
                if (row != null && row.state == TransferState.RUNNING) {
                    transferDao.setLocalPath(recordId, localPath)
                    transferDao.setCompleted(recordId, System.currentTimeMillis())
                }
                // Any other state was written by the transfer repository first
                // (pause/cancel from Transfers) and is left untouched.
            } catch (failure: Exception) {
                // Best-effort: the file is saved either way.
            } finally {
                jobs.unregister(recordId)
                jobs.consumeRegistryCancel(recordId)
                samples.remove(recordId)
            }
        }
    }

    override suspend fun fail(recordId: String?, message: String?) {
        if (recordId == null) return
        withContext(NonCancellable) {
            try {
                val row = transferDao.byId(recordId)
                if (row != null && row.state == TransferState.RUNNING) {
                    transferDao.setFailed(
                        recordId,
                        TransferState.FAILED,
                        message,
                        System.currentTimeMillis()
                    )
                }
                // Any other state was written by the transfer repository first
                // (pause/cancel from Transfers) and is left untouched.
            } catch (failure: Exception) {
                // Best-effort history write.
            } finally {
                jobs.unregister(recordId)
                jobs.consumeRegistryCancel(recordId)
                samples.remove(recordId)
            }
        }
    }

    override suspend fun ownerStopped(recordId: String?, cause: CancellationException?) {
        if (recordId == null) return
        withContext(NonCancellable) {
            try {
                val row = transferDao.byId(recordId)
                if (row != null && row.state == TransferState.RUNNING) {
                    val registryStop = jobs.wasCancelledViaRegistry(recordId)
                    when {
                        // The repository may already have written PAUSED/CANCELLED
                        // for a registry stop; a still-RUNNING row becomes CANCELLED.
                        registryStop || cause == null ->
                            transferDao.setState(
                                recordId,
                                TransferState.CANCELLED,
                                System.currentTimeMillis()
                            )
                        // Nobody asked for this stop: an internal timeout/error
                        // cancelled the extraction. That is a failure.
                        else -> transferDao.setFailed(
                            recordId,
                            TransferState.FAILED,
                            if (cause is TimeoutCancellationException) {
                                "Telegram did not deliver the file in time. Try again."
                            } else {
                                "The extraction stopped unexpectedly. Try again."
                            },
                            System.currentTimeMillis()
                        )
                    }
                }
            } catch (failure: Exception) {
                // Best-effort history write.
            } finally {
                jobs.unregister(recordId)
                jobs.consumeRegistryCancel(recordId)
                samples.remove(recordId)
            }
        }
    }

    private companion object {
        const val PROGRESS_MIN_INTERVAL_MS = 250L
        const val PROGRESS_MIN_ADVANCE_BYTES = 256L * 1024
    }
}
