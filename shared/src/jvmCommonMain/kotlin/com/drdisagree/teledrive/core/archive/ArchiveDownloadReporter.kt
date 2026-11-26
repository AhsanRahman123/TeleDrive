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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
     * a row that is still RUNNING becomes CANCELLED here.
     */
    suspend fun ownerStopped(recordId: String?)
}

/** Reporter for surfaces that do not write Transfers history (desktop this round). */
object NoOpArchiveDownloadReporter : ArchiveDownloadReporter {
    override suspend fun begin(displayName: String, sizeBytes: Long): String? = null
    override fun attach(recordId: String?, job: Job) = Unit
    override fun progress(recordId: String?, transferredBytes: Long) = Unit
    override suspend fun complete(recordId: String?, localPath: String) = Unit
    override suspend fun fail(recordId: String?, message: String?) = Unit
    override suspend fun ownerStopped(recordId: String?) = Unit
}

/**
 * The honest archive speed (vc181 A2): a cumulative average over the whole
 * extraction — total bytes over total elapsed since begin() — published only
 * once a full second has elapsed (0 before that, which the UI renders as no
 * speed). The old per-write window divided a 256 KiB inflater burst by the
 * 1–8 ms the burst took to decompress and displayed up to 250 MB/s; a
 * cumulative average can never exceed the true average rate.
 */
object ArchiveProgressMath {

    const val SPEED_FLOOR_MS = 1_000L

    fun speedBytesPerSecond(transferredBytes: Long, startedAtMillis: Long, nowMillis: Long): Long {
        val elapsed = nowMillis - startedAtMillis
        if (elapsed < SPEED_FLOOR_MS || transferredBytes <= 0L) return 0L
        return transferredBytes * 1_000L / elapsed
    }
}

class ArchiveDownloadTracker(
    private val transferDao: TransferDao,
    private val jobs: ArchiveExtractionJobs
) : ArchiveDownloadReporter {

    /**
     * Test seam (vc181 A2): the clock the throttle and speed math read. Production
     * uses the system clock. Integration note (vc181): the seam is a settable
     * property, not a secondary constructor — a Function0 constructor parameter
     * made Koin's singleOf binding overload-ambiguous and tripped the
     * AppModulesTest Koin verify reflection over this class.
     */
    var nowMillis: () -> Long = System::currentTimeMillis

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Last WRITTEN progress sample per record; the throttle compares against it. */
    private data class ProgressSample(
        val sizeBytes: Long,
        var lastWrittenBytes: Long,
        var lastWrittenAtMillis: Long,
        /** Immutable begin() timestamp; the cumulative-average speed divides by it. */
        val startedAtMillis: Long
    )

    private val samples = ConcurrentHashMap<String, ProgressSample>()

    /** Serializes progress writes per record so launched writes cannot land out of order. */
    private val writeMutexes = ConcurrentHashMap<String, Mutex>()

    override suspend fun begin(displayName: String, sizeBytes: Long): String? {
        val now = nowMillis()
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
            samples[id] = ProgressSample(sizeBytes, 0L, now, startedAtMillis = now)
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
        val now = nowMillis()
        val sample = samples.getOrPut(recordId) {
            // No begin() sample (should not happen): last write at the epoch, so
            // the first progress call flushes immediately.
            ProgressSample(
                sizeBytes = -1L,
                lastWrittenBytes = 0L,
                lastWrittenAtMillis = 0L,
                startedAtMillis = now
            )
        }
        val speed: Long
        synchronized(sample) {
            // Non-advancing samples carry no new information. Dropping them here,
            // before any launch, makes the launched write sequence strictly
            // increasing by construction — the old code launched one unordered
            // coroutine per sample, so a smaller value could land after a larger
            // one and the displayed progress jumped backwards.
            if (transferredBytes <= sample.lastWrittenBytes) return
            val elapsed = now - sample.lastWrittenAtMillis
            val advanced = transferredBytes - sample.lastWrittenBytes
            val shouldWrite = elapsed >= PROGRESS_MIN_INTERVAL_MS ||
                advanced >= PROGRESS_MIN_ADVANCE_BYTES ||
                (sample.sizeBytes >= 0 && transferredBytes == sample.sizeBytes)
            if (!shouldWrite) return
            speed = ArchiveProgressMath.speedBytesPerSecond(
                transferredBytes,
                sample.startedAtMillis,
                now
            )
            sample.lastWrittenBytes = transferredBytes
            sample.lastWrittenAtMillis = now
        }
        val mutex = writeMutexes.getOrPut(recordId) { Mutex() }
        writeScope.launch {
            // Serialized per record; together with the drop above and the DAO's
            // MAX guard, a written row's transferredBytes can never regress.
            mutex.withLock {
                try {
                    transferDao.updateProgress(recordId, transferredBytes, speed, now)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    // Progress is best-effort; the row's terminal write still lands.
                }
            }
        }
    }

    override suspend fun complete(recordId: String?, localPath: String) {
        if (recordId == null) return
        try {
            val row = transferDao.byId(recordId)
            if (row != null && row.state == TransferState.RUNNING) {
                transferDao.setLocalPath(recordId, localPath)
                transferDao.setCompleted(recordId, nowMillis())
            }
            // Any other state was written by the transfer repository first
            // (pause/cancel from Transfers) and is left untouched.
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Best-effort: the file is saved either way.
        } finally {
            jobs.unregister(recordId)
            samples.remove(recordId)
            writeMutexes.remove(recordId)
        }
    }

    override suspend fun fail(recordId: String?, message: String?) {
        if (recordId == null) return
        try {
            val row = transferDao.byId(recordId)
            if (row != null && row.state == TransferState.RUNNING) {
                transferDao.setFailed(
                    recordId,
                    TransferState.FAILED,
                    message,
                    nowMillis()
                )
            }
            // Any other state was written by the transfer repository first
            // (pause/cancel from Transfers) and is left untouched.
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Best-effort history write.
        } finally {
            jobs.unregister(recordId)
            samples.remove(recordId)
            writeMutexes.remove(recordId)
        }
    }

    override suspend fun ownerStopped(recordId: String?) {
        if (recordId == null) return
        try {
            val row = transferDao.byId(recordId)
            if (row != null && row.state == TransferState.RUNNING) {
                transferDao.setState(
                    recordId,
                    TransferState.CANCELLED,
                    nowMillis()
                )
            }
            // Any other state was written by the transfer repository first
            // (pause/cancel from Transfers) and is left untouched.
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // Best-effort history write.
        } finally {
            jobs.unregister(recordId)
            samples.remove(recordId)
            writeMutexes.remove(recordId)
        }
    }

    private companion object {
        const val PROGRESS_MIN_INTERVAL_MS = 250L
        const val PROGRESS_MIN_ADVANCE_BYTES = 256L * 1024
    }
}
