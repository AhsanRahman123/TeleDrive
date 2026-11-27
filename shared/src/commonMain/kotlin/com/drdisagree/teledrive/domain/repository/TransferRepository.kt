package com.drdisagree.teledrive.domain.repository

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.domain.model.FailedRetryOutcome
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferSection
import kotlinx.coroutines.flow.Flow

interface TransferRepository {

    /** Bounded, because a backup can queue tens of thousands of transfers. */
    fun observeSection(section: TransferSection, limit: Int): Flow<List<TransferTask>>

    fun observeSectionCount(section: TransferSection): Flow<Int>

    fun observeActiveCount(): Flow<Int>

    fun observeActiveForFile(fileId: String): Flow<TransferTask?>

    fun observeActiveDownloads(): Flow<List<TransferTask>>

    suspend fun enqueueUpload(fileId: String, priority: Int = 0): AppResult<String>

    /**
     * A batch captures this generation before it starts importing. Cancel all advances the
     * generation so stale import work cannot add uploads after the cancellation has completed.
     */
    suspend fun uploadQueueGeneration(): Long

    /** Returns false when [generation] was invalidated by Cancel all. */
    suspend fun enqueueUploadForGeneration(fileId: String, generation: Long): AppResult<Boolean>

    suspend fun enqueuePendingUploads(): AppResult<Int>

    /**
     * Checks are answered once per run and rows written in batches, so a large scan avoids a round
     * trip per file.
     */
    suspend fun enqueueBackupBatch(fileIds: List<String>, sessionId: String): AppResult<Int>

    suspend fun enqueueDownload(fileId: String, priority: Int = 0): AppResult<String>

    suspend fun pause(id: String)

    suspend fun resume(id: String)

    suspend fun cancel(id: String)

    suspend fun cancelForFiles(fileIds: List<String>)

    suspend fun retry(id: String)

    suspend fun setPriority(id: String, priority: Int)

    /** Persist a user-defined order for the active/queued incoming download list. */
    suspend fun reorderDownloads(orderedIds: List<String>) = Unit

    /** Promote one incoming transfer to the front of the persistent download queue. */
    suspend fun moveDownloadToTop(id: String) = Unit

    /**
     * Removes transfer RECORDS by id (ported from PC 1.6.24, field note 3):
     * only terminal rows (COMPLETED / FAILED / CANCELLED) are deleted; live
     * rows are left untouched — cancelling those stays [cancel]'s job. The
     * files themselves are never deleted.
     */
    suspend fun deleteTransfers(ids: List<String>) = Unit

    /**
     * Retries the FAILED records among [ids] (ported from PC 1.6.24, field
     * note 3). Engine-backed records are re-queued through the engine;
     * report-only ARCHIVE_DOWNLOAD records cannot be retried (they retain no
     * archive or entry identity) and are counted as skipped in the outcome
     * instead of being silently re-failed.
     */
    suspend fun retryFailedTransfers(ids: List<String>): FailedRetryOutcome =
        FailedRetryOutcome(requeued = 0, skippedArchiveDownloads = 0)

    /** Retries every FAILED record in the table; see [retryFailedTransfers]. */
    suspend fun retryAllFailed(): FailedRetryOutcome =
        FailedRetryOutcome(requeued = 0, skippedArchiveDownloads = 0)

    /** Removes every FAILED record; returns how many were removed. */
    suspend fun removeAllFailed(): Int = 0

    suspend fun pauseAll()

    suspend fun resumeAll()

    suspend fun cancelAll()

    suspend fun clearFinished()

    suspend fun recoverOrphanedTransfers()

    /** Re-wakes the worker after runtime transfer settings change. */
    suspend fun wakeWorker() = Unit
}
