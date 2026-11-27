package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramLimits
import com.drdisagree.teledrive.core.transfer.BackupSessionTracker
import com.drdisagree.teledrive.core.transfer.FileParts
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.core.transfer.UploadBatchGeneration
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.mapper.toDomain
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FailedRetryOutcome
import com.drdisagree.teledrive.domain.model.TransferSection
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class TransferRepositoryImpl(
    private val transferDao: TransferDao,
    private val fileDao: FileDao,
    private val telegramClient: TelegramClient,
    private val transferScheduler: TransferScheduler,
    private val settingsRepository: SettingsRepository,
    private val validateUpload: ValidateUploadUseCase,
    private val storageInspector: StorageInspector,
    private val backupSessionTracker: BackupSessionTracker,
    private val archiveExtractionJobs: ArchiveExtractionJobs
) : TransferRepository {

    private val enqueueLock = Mutex()
    private val workerControlLock = Mutex()
    private val uploadBatchGeneration = UploadBatchGeneration()

    private sealed interface EnqueueOutcome {
        data class Enqueued(val id: String) : EnqueueOutcome
        data class Failed(val error: AppError) : EnqueueOutcome
        data object CancelledByCancelAll : EnqueueOutcome
    }

    override suspend fun uploadQueueGeneration(): Long =
        enqueueLock.withLock { uploadBatchGeneration.current() }

    override suspend fun enqueueUploadForGeneration(
        fileId: String,
        generation: Long
    ): AppResult<Boolean> = when (
        val result = enqueueInternal(
            fileId = fileId,
            type = TransferType.UPLOAD,
            priority = 0,
            sessionId = null,
            expectedUploadGeneration = generation
        )
    ) {
        is EnqueueOutcome.Enqueued -> AppResult.Success(true)
        is EnqueueOutcome.Failed -> AppResult.Failure(result.error)
        EnqueueOutcome.CancelledByCancelAll -> AppResult.Success(false)
    }


    override fun observeSection(
        section: TransferSection,
        limit: Int
    ): Flow<List<TransferTask>> = when (section) {
        TransferSection.ACTIVE -> transferDao.observeActive(limit)
        TransferSection.PAUSED -> transferDao.observePaused(limit)
        TransferSection.FAILED -> transferDao.observeFailed(limit)
        TransferSection.COMPLETED -> transferDao.observeCompleted(limit)
    }.map { list ->
        val tasks = list.map { it.toDomain() }
        if (section == TransferSection.COMPLETED) tasks.withFileLocalPaths() else tasks
    }

    /**
     * A transfer row's own localPath is never written when a download completes —
     * the saved path lives on the file's row (and split completion even clears the
     * transfer row's copy) — so a finished row would give the UI's open-with action
     * nothing to point at. Resolve it from the file row via fileId, the same source
     * "Open in Pranto Drive" already trusts.
     */
    private suspend fun List<TransferTask>.withFileLocalPaths(): List<TransferTask> {
        val fileIds = mapNotNull { task ->
            task.fileId?.takeIf { task.localPath == null }
        }.distinct()
        if (fileIds.isEmpty()) return this
        val pathsById = fileDao.byIds(fileIds)
            .mapNotNull { file -> file.localPath?.let { path -> file.id to path } }
            .toMap()
        if (pathsById.isEmpty()) return this
        return map { task ->
            val resolved = task.fileId?.let { pathsById[it] }
            if (task.localPath == null && resolved != null) {
                task.copy(localPath = resolved)
            } else {
                task
            }
        }
    }

    override fun observeSectionCount(section: TransferSection): Flow<Int> =
        (when (section) {
            TransferSection.ACTIVE -> transferDao.observeActiveCount()
            TransferSection.PAUSED -> transferDao.observeCountByState(TransferState.PAUSED)
            TransferSection.FAILED -> transferDao.observeCountByState(TransferState.FAILED)
            TransferSection.COMPLETED -> transferDao.observeCountByState(TransferState.COMPLETED)
        }).distinctUntilChanged()

    override fun observeActiveCount(): Flow<Int> =
        transferDao.observeActiveCount().distinctUntilChanged()

    override fun observeActiveForFile(fileId: String): Flow<TransferTask?> =
        transferDao.observeActiveForFile(fileId)
            .map { it?.toDomain() }
            .distinctUntilChanged()

    override fun observeActiveDownloads(): Flow<List<TransferTask>> =
        transferDao.observeActiveDownloads()
            .distinctUntilChanged()
            .map { list -> list.map { it.toDomain() } }

    override suspend fun enqueueUpload(fileId: String, priority: Int): AppResult<String> =
        enqueue(fileId, TransferType.UPLOAD, priority)

    /**
     * Orphans whose bytes exist are queued again; ones with nothing on disk or in Telegram are
     * dropped.
     */
    override suspend fun enqueuePendingUploads(): AppResult<Int> {
        val chatId = settingsRepository.preferences.first().storageChatId
        val pending = fileDao.localOnlyFileIds(chatId)
        val orphaned = fileDao.queuedWithoutTransferFileIds(chatId)
        val requeue = mutableListOf<String>()
        if (orphaned.isNotEmpty()) {
            val phantoms = mutableListOf<String>()
            fileDao.byIds(orphaned).forEach { entity ->
                val exists = entity.localPath?.let { File(it).exists() } == true
                when {
                    exists -> requeue.add(entity.id)
                    entity.remoteFileId == null -> phantoms.add(entity.id)
                    else -> fileDao.setBackupState(entity.id, BackupState.NONE)
                }
            }
            if (requeue.isNotEmpty()) {
                fileDao.setBackupStates(requeue, BackupState.NONE)
            }
            if (phantoms.isNotEmpty()) {
                SafeLog.w(TAG, "Dropping ${phantoms.size} queued records with no bytes left")
                fileDao.deleteByIds(phantoms)
            }
        }
        return AppResult.Success(
            enqueueBatch(pending + requeue, TransferType.UPLOAD, sessionId = null)
        )
    }

    override suspend fun enqueueDownload(fileId: String, priority: Int): AppResult<String> {
        val entity = fileDao.byId(fileId) ?: return AppResult.Failure(AppError.NotFound)
        if (entity.remoteFileId == null) {
            return AppResult.Failure(AppError.NoRemoteCopy)
        }
        val available = storageInspector.availableBytes()
        if (available < entity.sizeBytes + STORAGE_MARGIN) {
            return AppResult.Failure(
                AppError.InsufficientStorage(entity.sizeBytes + STORAGE_MARGIN, available)
            )
        }
        // vc184 A3: a dedupe hit must still leave a GENUINELY RUNNABLE
        // transfer behind. Before this, an existing unfinished row was
        // reported as "queued" success untouched — so a PAUSED row (paused
        // earlier, or preempted by a queue promotion) sat paused forever
        // while the caller believed a download was running, and a QUEUED row
        // whose worker had already exited was never kicked again. The row's
        // state decides the repair, applied after the lock (resume() takes
        // workerControlLock): PAUSED resumes, QUEUED re-kicks the worker,
        // RUNNING needs nothing.
        val outcome = enqueueLock.withLock {
            val existingId = transferDao.unfinishedIdsForFiles(listOf(fileId)).firstOrNull()
            if (existingId != null) {
                ExistingDownload(
                    id = existingId,
                    state = transferDao.byId(existingId)?.state
                )
            } else {
                when (
                    val inserted = insertTransfer(
                        entity.id,
                        entity.name,
                        entity.sizeBytes,
                        entity.chatId,
                        TransferType.DOWNLOAD,
                        priority,
                        null
                    )
                ) {
                    is AppResult.Success -> FreshDownload(inserted.value)
                    is AppResult.Failure -> FailedDownload(inserted.error)
                }
            }
        }
        return when (outcome) {
            is FreshDownload -> AppResult.Success(outcome.id)
            is FailedDownload -> AppResult.Failure(outcome.error)
            is ExistingDownload -> {
                when (existingDownloadAction(outcome.state)) {
                    ExistingDownloadAction.RESUME -> resumeExistingDownload(outcome.id)
                    ExistingDownloadAction.KICK -> kickWorker()
                    ExistingDownloadAction.NONE -> Unit
                }
                AppResult.Success(outcome.id)
            }
        }
    }

    /** Outcome of the locked section of [enqueueDownload]; see its vc184 note. */
    private sealed interface DownloadEnqueueOutcome

    private data class FreshDownload(val id: String) : DownloadEnqueueOutcome

    private data class FailedDownload(val error: AppError) : DownloadEnqueueOutcome

    private data class ExistingDownload(
        val id: String,
        val state: TransferState?
    ) : DownloadEnqueueOutcome

    suspend fun enqueueBackup(
        fileId: String,
        sessionId: String,
        priority: Int = 0
    ): AppResult<String> = enqueue(fileId, TransferType.BACKUP, priority, sessionId)

    override suspend fun enqueueBackupBatch(
        fileIds: List<String>,
        sessionId: String
    ): AppResult<Int> = AppResult.Success(
        enqueueBatch(fileIds, TransferType.BACKUP, sessionId)
    )

    private suspend fun enqueueBatch(
        fileIds: List<String>,
        type: TransferType,
        sessionId: String?
    ): Int {
        if (fileIds.isEmpty()) return 0

        val limits = currentLimits()
        val prefs = settingsRepository.preferences.first()
        val availableBytes = storageInspector.availableBytes()
        var queued = 0
        var waiting = 0

        for (chunk in fileIds.chunked(SQL_BATCH)) enqueueLock.withLock {
            val alreadyQueued = transferDao.unfinishedFileIds(chunk).toSet()
            waiting += alreadyQueued.size
            if (sessionId != null && alreadyQueued.isNotEmpty()) {
                transferDao.adoptIntoSession(alreadyQueued.toList(), sessionId)
            }
            val now = System.currentTimeMillis()
            val transfers = mutableListOf<TransferEntity>()
            for (fileId in chunk) {
                if (fileId in alreadyQueued) continue
                val entity = fileDao.byId(fileId) ?: continue
                if (entity.localPath == null) continue
                val scratch = if (prefs.encryptFiles) {
                    minOf(entity.sizeBytes, FileParts.PART_SIZE)
                } else {
                    0
                }
                val rejected = validateUpload(
                    fileSizeBytes = entity.sizeBytes,
                    limits = limits,
                    availableLocalBytes = availableBytes,
                    requiredScratchBytes = scratch,
                    splitsIfTooLarge = true
                )
                if (rejected != null) continue
                fileDao.setBackupStateIfLocalOnly(entity.id, BackupState.QUEUED)
                transfers += TransferEntity(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    fileId = entity.id,
                    displayName = entity.name,
                    localPath = null,
                    chatId = entity.chatId,
                    messageId = null,
                    remoteFileId = null,
                    sizeBytes = entity.sizeBytes,
                    state = TransferState.QUEUED,
                    priority = 0,
                    backupSessionId = sessionId,
                    createdAt = now,
                    updatedAt = now
                )
            }
            if (transfers.isNotEmpty()) {
                transferDao.upsertAll(transfers)
                queued += transfers.size
            }
        }
        if (queued > 0 || waiting > 0) kickWorker()
        return queued
    }

    private suspend fun enqueue(
        fileId: String,
        type: TransferType,
        priority: Int,
        sessionId: String? = null
    ): AppResult<String> = when (
        val result = enqueueInternal(
            fileId = fileId,
            type = type,
            priority = priority,
            sessionId = sessionId,
            expectedUploadGeneration = null
        )
    ) {
        is EnqueueOutcome.Enqueued -> AppResult.Success(result.id)
        is EnqueueOutcome.Failed -> AppResult.Failure(result.error)
        EnqueueOutcome.CancelledByCancelAll -> AppResult.Failure(
            AppError.TransferFailed("Upload batch was cancelled")
        )
    }

    private suspend fun enqueueInternal(
        fileId: String,
        type: TransferType,
        priority: Int,
        sessionId: String?,
        expectedUploadGeneration: Long?
    ): EnqueueOutcome {
        val entity = fileDao.byId(fileId)
            ?: return EnqueueOutcome.Failed(AppError.NotFound)
        if (entity.localPath == null) {
            return EnqueueOutcome.Failed(AppError.NoLocalCopy)
        }

        val limits = currentLimits()
        val prefs = settingsRepository.preferences.first()
        val scratch = if (prefs.encryptFiles) {
            minOf(entity.sizeBytes, FileParts.PART_SIZE)
        } else {
            0
        }
        validateUpload(
            fileSizeBytes = entity.sizeBytes,
            limits = limits,
            availableLocalBytes = storageInspector.availableBytes(),
            requiredScratchBytes = scratch,
            splitsIfTooLarge = true
        )?.let { return EnqueueOutcome.Failed(it) }

        return enqueueLock.withLock {
            if (
                expectedUploadGeneration != null &&
                !uploadBatchGeneration.isCurrent(expectedUploadGeneration)
            ) {
                return@withLock EnqueueOutcome.CancelledByCancelAll
            }
            transferDao.unfinishedIdsForFiles(listOf(fileId)).firstOrNull()?.let {
                return@withLock EnqueueOutcome.Enqueued(it)
            }
            fileDao.setBackupStateIfLocalOnly(entity.id, BackupState.QUEUED)
            when (
                val inserted = insertTransfer(
                    entity.id,
                    entity.name,
                    entity.sizeBytes,
                    entity.chatId,
                    type,
                    priority,
                    sessionId
                )
            ) {
                is AppResult.Success -> EnqueueOutcome.Enqueued(inserted.value)
                is AppResult.Failure -> EnqueueOutcome.Failed(inserted.error)
            }
        }
    }

    private suspend fun insertTransfer(
        fileId: String,
        name: String,
        sizeBytes: Long,
        chatId: Long?,
        type: TransferType,
        priority: Int,
        sessionId: String?
    ): AppResult<String> {
        val now = System.currentTimeMillis()
        val transfer = TransferEntity(
            id = UUID.randomUUID().toString(),
            type = type,
            fileId = fileId,
            displayName = name,
            localPath = null,
            chatId = chatId,
            messageId = null,
            remoteFileId = null,
            sizeBytes = sizeBytes,
            state = TransferState.QUEUED,
            priority = priority,
            backupSessionId = sessionId,
            createdAt = now,
            updatedAt = now
        )
        transferDao.upsert(transfer)
        SafeLog.d(
            TAG,
            "Transfer enqueued: id=${transfer.id} type=${transfer.type} " +
                "name=${transfer.displayName} size=${transfer.sizeBytes}"
        )
        kickWorker()
        return AppResult.Success(transfer.id)
    }

    /**
     * The enqueueDownload dedupe repair for a PAUSED row (vc184 MF-1).
     *
     * Outside Pause all this is exactly [resume] (the A3 fix: a PAUSED row
     * tapped again must genuinely run). But [resume] ends in
     * `transferScheduler.resume(...)`, which on BOTH platforms is the global
     * latch release — Android clears the drainer's pause hold, desktop
     * clears globallyPaused — so routing the repair through it while the
     * Pause-all hold is set would disarm Pause all for the whole queue: the
     * restarted drain would also claim every other QUEUED row, defeating
     * A2's guarantee that downloads enqueued during the hold must wait.
     *
     * While the hold is set, the repair therefore re-queues ONLY this row
     * and kicks: kicks are dropped while held on both platforms, so the row
     * waits QUEUED and drains when Resume all clears the hold (Resume all
     * also re-queues any PAUSED rows, so nothing is lost if this row is
     * somehow still PAUSED by then). The hold is re-checked under
     * [workerControlLock]; if it was released concurrently, the ordinary
     * [resume] path runs after the lock instead.
     */
    private suspend fun resumeExistingDownload(id: String) {
        val requeuedWhileHeld = workerControlLock.withLock {
            if (!transferScheduler.isPauseHeld()) return@withLock false
            val transfer = transferDao.byId(id) ?: return@withLock true
            if (transfer.type.isReportOnly) {
                // Mirror resume()'s report-only handling, minus the scheduler
                // resume: a paused report-only row still ends as FAILED.
                if (transfer.state == TransferState.PAUSED) {
                    transferDao.setFailed(
                        id,
                        TransferState.FAILED,
                        "A paused archive download can't be resumed. " +
                            "Download the file again from its ZIP.",
                        System.currentTimeMillis()
                    )
                }
                return@withLock true
            }
            if (transfer.state == TransferState.PAUSED ||
                transfer.state == TransferState.FAILED
            ) {
                transferDao.setState(id, TransferState.QUEUED, System.currentTimeMillis())
                kickWorker()
                backupSessionTracker.refresh(transfer.backupSessionId)
            }
            true
        }
        if (!requeuedWhileHeld) resume(id)
    }

    override suspend fun pause(id: String) = workerControlLock.withLock {
        val transfer = transferDao.byId(id) ?: return@withLock
        if (transfer.type.isReportOnly) {
            // Report-only rows own no native download, session or scheduler work: pausing
            // only flips the row and stops the in-flight extraction job, if any.
            if (transfer.state == TransferState.QUEUED || transfer.state == TransferState.RUNNING) {
                transferDao.setState(id, TransferState.PAUSED, System.currentTimeMillis())
                if (transfer.state == TransferState.RUNNING) {
                    archiveExtractionJobs.cancel(id)
                }
            }
            return@withLock
        }
        if (transfer.state == TransferState.QUEUED || transfer.state == TransferState.RUNNING) {
            transferDao.setState(id, TransferState.PAUSED, System.currentTimeMillis())
            pauseNativeDownloadIfPresent(transfer)
            backupSessionTracker.refresh(transfer.backupSessionId)
        }
    }

    override suspend fun resume(id: String) = workerControlLock.withLock {
        val transfer = transferDao.byId(id) ?: return@withLock
        if (transfer.type.isReportOnly) {
            // A report-only row can never be re-queued (the drainer would refuse it and
            // its extraction context is gone), so resuming a paused one ends it as FAILED
            // with the reason. FAILED archive rows are a no-op here.
            if (transfer.state == TransferState.PAUSED) {
                transferDao.setFailed(
                    id,
                    TransferState.FAILED,
                    "A paused archive download can't be resumed. " +
                        "Download the file again from its ZIP.",
                    System.currentTimeMillis()
                )
            }
            return@withLock
        }
        if (transfer.state == TransferState.PAUSED || transfer.state == TransferState.FAILED) {
            transferDao.setState(id, TransferState.QUEUED, System.currentTimeMillis())
            val prefs = settingsRepository.preferences.first()
            transferScheduler.resume(prefs.allowMeteredTransfers)
            backupSessionTracker.refresh(transfer.backupSessionId)
        }
    }

    override suspend fun cancel(id: String) = workerControlLock.withLock {
        // Serialized with pause/resume (vc183): repeated or concurrent Cancel
        // taps re-read the row under the lock, so a tap that lands after the
        // record already terminated takes the terminal path (history delete,
        // then absent no-op) instead of racing the first tap's state write
        // and job cancel.
        val transfer = transferDao.byId(id) ?: return@withLock
        if (transfer.type.isReportOnly) {
            // Terminal report-only rows are removed from history like any other record;
            // live ones are cancelled and their extraction job stopped. There is no
            // fileId/backup-state/session tail for these records.
            if (transfer.state.isTerminal) {
                transferDao.deleteByIds(listOf(id))
            } else {
                transferDao.setState(id, TransferState.CANCELLED, System.currentTimeMillis())
                archiveExtractionJobs.cancel(id)
            }
            return@withLock
        }
        if (transfer.state.isTerminal) {
            transferDao.deleteByIds(listOf(id))
        } else {
            transferDao.setState(id, TransferState.CANCELLED, System.currentTimeMillis())
            transfer.fileId?.let { fileDao.setBackupStateIfLocalOnly(it, BackupState.NONE) }
            // vc184 A2: cancel must reach the native queue too. A QUEUED row can
            // already sit in TDLib's persistent download list (attached by an
            // earlier execution or a priority refresh); flipping only the Room
            // row left TDLib downloading it in the background. The running
            // executor's own reconcile removes its row as well — removal is
            // idempotent, so doing both is harmless.
            removeNativeDownloadIfPresent(transfer)
        }
        syncFileStates()
        backupSessionTracker.refresh(transfer.backupSessionId)
    }

    override suspend fun cancelForFiles(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        fileIds.chunked(SQL_BATCH)
            .flatMap { transferDao.unfinishedIdsForFiles(it) }
            .forEach { cancel(it) }
    }

    override suspend fun pauseAll() = workerControlLock.withLock {
        // Capture active native downloads before changing the app rows to PAUSED.
        // The desktop scheduler cancels its drain job, so every native TDLib download must be
        // explicitly paused before that cancellation can detach the app's observer.
        val runningDownloads = transferDao.activeIncomingTransfersForPause()
        transferDao.pauseAll(System.currentTimeMillis())
        runningDownloads.forEach { pauseNativeDownloadIfPresent(it) }
        // Pause TDLib's persistent queue before joining the drain.
        transferScheduler.pause()
        backupSessionTracker.refreshActive()
        // Report-only archive rows are excluded from dao.pauseAll (they are not the
        // drainer's to pause): pause each live extraction's row, then stop its job.
        // The row is written first so the extraction's owner sees PAUSED, not RUNNING.
        val now = System.currentTimeMillis()
        archiveExtractionJobs.activeIds().forEach { archiveId ->
            when (transferDao.byId(archiveId)?.state) {
                TransferState.RUNNING -> {
                    transferDao.setState(archiveId, TransferState.PAUSED, now)
                    archiveExtractionJobs.cancel(archiveId)
                }
                TransferState.PAUSED -> archiveExtractionJobs.cancel(archiveId)
                else -> Unit // Terminal or absent: nothing to write or stop.
            }
        }
    }

    override suspend fun resumeAll() = workerControlLock.withLock {
        transferDao.resumeAll(System.currentTimeMillis())
        val prefs = settingsRepository.preferences.first()
        // Wake the worker pool immediately after the rows become QUEUED.
        transferScheduler.resume(prefs.allowMeteredTransfers)
        backupSessionTracker.refreshActive()
    }

    override suspend fun cancelAll() = workerControlLock.withLock {
        // Serialized with pause/resume like every other control op (vc184 A2):
        // previously cancelAll ran outside workerControlLock, so a concurrent
        // resume could re-queue a row after the sweep and leave it running
        // under a completed Cancel all.
        // Snapshot the live incoming rows BEFORE the sweep: after it they are
        // CANCELLED and indistinguishable from history, but each one can still
        // own an entry in TDLib's persistent download list (queued rows
        // included — an earlier execution or a priority refresh may have
        // attached them). Cancel all must remove those entries, not just flip
        // the Room rows; previously only a currently-running executor removed
        // its own row, so queued downloads kept moving inside TDLib.
        val activeIncoming = transferDao.incomingQueueOrder()
        val cancelled = enqueueLock.withLock {
            // Serialize cancellation against upload inserts. Any import batch holding an older
            // generation is rejected if it tries to enqueue after this block completes.
            uploadBatchGeneration.advance()
            val now = System.currentTimeMillis()
            // Report-only archive rows have their own bulk cancel (cancelAll excludes them).
            transferDao.cancelAll(now) + transferDao.cancelAllArchiveDownloads(now)
        }
        // The archive rows are already CANCELLED, so their owners see a terminal state
        // and will not overwrite it; just stop the extraction jobs.
        archiveExtractionJobs.activeIds().forEach { archiveExtractionJobs.cancel(it) }
        activeIncoming.forEach { removeNativeDownloadIfPresent(it) }
        SafeLog.d(TAG, "Cancel-all invoked: $cancelled transfers cancelled")
        syncFileStates()
        backupSessionTracker.refreshActive()
    }

    override suspend fun setPriority(id: String, priority: Int) = workerControlLock.withLock {
        val transfer = transferDao.byId(id) ?: return@withLock
        if (transfer.type.isReportOnly) return@withLock
        if (transfer.state.isTerminal) return@withLock

        if (transfer.type.isIncoming && priority - transfer.priority in -1..1 && priority != transfer.priority) {
            // The row menu sends +/-1. Treat that as a relative move in the saved queue rather
            // than relying on a small numeric range: drag ordering assigns larger rank values.
            val current = transferDao.incomingQueueOrder()
            val fromIndex = current.indexOfFirst { it.id == id }
            if (fromIndex >= 0) {
                val toIndex = (fromIndex - (priority - transfer.priority)).coerceIn(0, current.lastIndex)
                if (toIndex != fromIndex) {
                    val ordered = current.toMutableList()
                    val moved = ordered.removeAt(fromIndex)
                    ordered.add(toIndex, moved)
                    persistDownloadOrder(ordered)
                }
            }
        } else {
            transferDao.setPriority(id, priority, System.currentTimeMillis())
        }

        if (transfer.type.isIncoming) {
            val nextRunnable = transferDao.incomingQueueOrder()
                .firstOrNull { it.state != TransferState.PAUSED }
            if (nextRunnable != null && nextRunnable.state == TransferState.QUEUED) {
                freeWorkerSlotForPromotedDownload(nextRunnable)
            }
            // Room ordering alone is not enough for a download already registered in TDLib.
            // Re-apply native priorities so active downloads react immediately.
            applyNativeDownloadPriorities()
        }
        if (transfer.state == TransferState.QUEUED) kickWorker()
    }

    override suspend fun reorderDownloads(orderedIds: List<String>) = workerControlLock.withLock {
        val current = transferDao.incomingQueueOrder()
        if (current.isEmpty()) return@withLock
        val byId = current.associateBy { it.id }
        val requested = orderedIds.distinct().mapNotNull(byId::get)
        val requestedIds = requested.map { it.id }.toSet()
        val ordered = requested + current.filterNot { it.id in requestedIds }
        persistDownloadOrder(ordered)
        val first = ordered.firstOrNull()
        if (first != null) freeWorkerSlotForPromotedDownload(first)
        applyNativeDownloadPriorities()
        if (ordered.any { it.state == TransferState.QUEUED }) kickWorker()
    }

    override suspend fun moveDownloadToTop(id: String) = workerControlLock.withLock {
        val current = transferDao.incomingQueueOrder()
        val target = current.firstOrNull { it.id == id } ?: return@withLock
        val ordered = listOf(target) + current.filterNot { it.id == id }
        persistDownloadOrder(ordered)
        freeWorkerSlotForPromotedDownload(target)
        applyNativeDownloadPriorities()
        if (target.state == TransferState.QUEUED) kickWorker()
    }

    /**
     * If a queued file is promoted while every TeleDrive worker slot is occupied, pause the
     * lowest-priority active incoming job to release one slot. It remains QUEUED in Room and
     * PAUSED in TDLib, so its partial bytes are retained and it resumes after the promoted file.
     * Uploads/backups are never preempted by download reordering.
     */
    private suspend fun freeWorkerSlotForPromotedDownload(promoted: TransferEntity) {
        if (!promoted.type.isIncoming || promoted.state != TransferState.QUEUED) return
        val maxWorkers = settingsRepository.preferences.first().transferConcurrency.coerceIn(1, 6)
        val runningCount = transferDao.runningTransferCount()
        val slotsToFree = (runningCount - maxWorkers + 1).coerceAtLeast(0)
        if (slotsToFree == 0) return

        val victims = transferDao.runningIncomingTransfers()
            .asReversed()
            .filterNot { it.id == promoted.id }
            .take(slotsToFree)
        for (victim in victims) {
            transferDao.setState(victim.id, TransferState.QUEUED, System.currentTimeMillis())
            pauseNativeDownloadIfPresent(victim)
        }
    }

    private suspend fun persistDownloadOrder(ordered: List<TransferEntity>) {
        val now = System.currentTimeMillis()
        val firstPriority = ordered.size + 100
        ordered.forEachIndexed { index, transfer ->
            transferDao.setPriority(transfer.id, firstPriority - index, now)
        }
    }

    /**
     * Keep TDLib's persistent queue aligned with Room's priority/FIFO order.
     * AddFileToDownloads replaces an existing queue entry without deleting the partial cache.
     */
    private suspend fun applyNativeDownloadPriorities() {
        val active = transferDao.runningIncomingTransfers()
        active.forEachIndexed { index, transfer ->
            val fileId = transfer.fileId ?: return@forEachIndexed
            val file = fileDao.byId(fileId) ?: return@forEachIndexed
            val remoteFileId = file.remoteFileId ?: return@forEachIndexed
            val chatId = file.chatId ?: transfer.chatId ?: return@forEachIndexed
            val messageId = file.messageId ?: transfer.messageId ?: return@forEachIndexed
            val nativePriority = (32 - index).coerceAtLeast(1)
            try {
                telegramClient.updateManagedDownloadPriority(
                    remoteFileId = remoteFileId,
                    chatId = chatId,
                    messageId = messageId,
                    priority = nativePriority
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                SafeLog.w(
                    TAG,
                    "Could not refresh native download priority for ${transfer.id}: ${error.message}"
                )
            }
        }
    }

    override suspend fun retry(id: String) {
        workerControlLock.withLock { retryLocked(id) }
    }

    /**
     * The retry itself, for callers already holding [workerControlLock];
     * returns true only when the row was actually re-queued by this call, so
     * bulk counting can never claim a requeue that did not take effect.
     */
    private suspend fun retryLocked(id: String): Boolean {
        val transfer = transferDao.byId(id) ?: return false
        // Report-only rows can never be re-queued; retrying one is a no-op (the user
        // downloads the file again from its ZIP instead).
        if (transfer.type.isReportOnly) return false
        if (transfer.state == TransferState.FAILED || transfer.state == TransferState.CANCELLED) {
            transferDao.upsert(
                transfer.copy(
                    state = TransferState.QUEUED,
                    transferredBytes = 0,
                    speedBytesPerSecond = 0,
                    errorMessage = null,
                    updatedAt = System.currentTimeMillis()
                )
            )
            val prefs = settingsRepository.preferences.first()
            transferScheduler.resume(prefs.allowMeteredTransfers)
            return true
        }
        return false
    }

    override suspend fun deleteTransfers(ids: List<String>) {
        if (ids.isEmpty()) return
        // MF-3 (second pass): the read-filter-delete sequence runs under
        // workerControlLock, the same lock cancel()/retry() use. Bulk actions
        // are launched in separate coroutines and can overlap (two quick taps:
        // "Retry all" + "Remove all"), so a snapshot taken outside the lock
        // could name a row that a concurrent retry has since re-queued — and
        // the unconditional delete would remove a live record. Under the lock
        // no retry can interleave, and each row is re-read immediately before
        // it is deleted.
        workerControlLock.withLock {
            // Only terminal records are removed: a live row in the id list (a
            // selection made just before the row started running again) is left
            // for cancel(), never deleted out from under the engine.
            val terminalIds = ids.distinct().chunked(SQL_BATCH).flatMap { chunk ->
                chunk.mapNotNull { transferDao.byId(it) }
            }.filter { it.state.isTerminal }.map { it.id }
            if (terminalIds.isEmpty()) return@withLock
            terminalIds.chunked(SQL_BATCH).forEach { transferDao.deleteByIds(it) }
            syncFileStates()
        }
    }

    override suspend fun retryFailedTransfers(ids: List<String>): FailedRetryOutcome =
        workerControlLock.withLock { retryFailedLocked(ids) }

    /** [retryFailedTransfers] for callers already holding [workerControlLock]. */
    private suspend fun retryFailedLocked(ids: List<String>): FailedRetryOutcome {
        var requeued = 0
        var skippedArchiveDownloads = 0
        for (id in ids.distinct()) {
            // Re-read under the lock (MF-3): the row's state is confirmed at
            // the moment of acting, not from a snapshot a concurrent bulk
            // delete/retry could have invalidated.
            val row = transferDao.byId(id) ?: continue
            if (row.state != TransferState.FAILED) continue
            if (row.type.isReportOnly) {
                // A failed ARCHIVE_DOWNLOAD row retains only its display name
                // and size (fileId/chatId/messageId/remoteFileId are all null
                // by design, report 49): there is no archive or entry identity
                // an extraction could be re-run from, so the record is skipped
                // and reported, never re-queued or silently re-failed.
                skippedArchiveDownloads++
            } else if (retryLocked(id)) {
                // Counted only on confirmed effect: retryLocked re-read the
                // row under the same lock and actually re-queued it.
                requeued++
            }
        }
        return FailedRetryOutcome(
            requeued = requeued,
            skippedArchiveDownloads = skippedArchiveDownloads
        )
    }

    override suspend fun retryAllFailed(): FailedRetryOutcome =
        // The FAILED snapshot is taken INSIDE the lock (MF-3), not as an
        // argument evaluated before it.
        workerControlLock.withLock { retryFailedLocked(allFailedIds()) }

    override suspend fun removeAllFailed(): Int = workerControlLock.withLock {
        // MF-3: snapshot + re-check + delete are one locked sequence. A row
        // a concurrent retry re-queued is re-read as QUEUED here and left
        // alone — never deleted out from under the running engine — and the
        // returned count is the number of rows actually deleted.
        val failedIds = allFailedIds()
        if (failedIds.isEmpty()) return@withLock 0
        val stillFailedIds = failedIds.chunked(SQL_BATCH).flatMap { chunk ->
            chunk.mapNotNull { transferDao.byId(it) }
        }.filter { it.state == TransferState.FAILED }.map { it.id }
        if (stillFailedIds.isEmpty()) return@withLock 0
        stillFailedIds.chunked(SQL_BATCH).forEach { transferDao.deleteByIds(it) }
        syncFileStates()
        stillFailedIds.size
    }

    /** Every FAILED record's id, read through the section flow the UI already uses. */
    private suspend fun allFailedIds(): List<String> =
        transferDao.observeFailed(Int.MAX_VALUE).first().map { it.id }

    override suspend fun clearFinished() {
        transferDao.clearFinished()
        syncFileStates()
    }

    override suspend fun recoverOrphanedTransfers() {
        // Interrupted archive extractions fail (they can never be re-run by the queue);
        // this must happen before requeueRunning, which excludes them by type anyway.
        transferDao.failInterruptedArchiveDownloads(System.currentTimeMillis())
        transferDao.requeueRunning()
        syncFileStates()
        kickWorker()
    }

    override suspend fun wakeWorker() {
        val prefs = settingsRepository.preferences.first()
        transferScheduler.rekick(prefs.allowMeteredTransfers)
    }

    /**
     * The app's transfer row and TDLib's persistent download queue are separate state stores.
     * Pausing only the Room row is insufficient: TDLib can continue downloading after the
     * worker is cancelled or the app is closed. Explicitly pause the native queue entry too.
     */
    private suspend fun pauseNativeDownloadIfPresent(transfer: TransferEntity) {
        if (!transfer.type.isIncoming) return
        val remoteFileId = transfer.fileId
            ?.let { fileDao.byId(it)?.remoteFileId }
            ?.takeIf { it.isNotBlank() }
            ?: return
        try {
            telegramClient.pauseManagedDownload(remoteFileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A queued-but-not-yet-added or already-completed file has no native queue entry.
            // The Room row is still paused; record the mismatch for diagnostics.
            SafeLog.w(
                TAG,
                "Could not pause native download for transfer ${transfer.id}: ${error.message}"
            )
        }
    }

    /**
     * The cancel-side twin of [pauseNativeDownloadIfPresent]: TDLib's persistent
     * download list is a separate state store from the Room row, so cancelling
     * only the row leaves the native entry downloading. Best effort — a file
     * that was never attached has no entry to remove, and the row is already
     * cancelled either way.
     */
    private suspend fun removeNativeDownloadIfPresent(transfer: TransferEntity) {
        if (!transfer.type.isIncoming) return
        val remoteFileId = transfer.fileId
            ?.let { fileDao.byId(it)?.remoteFileId }
            ?.takeIf { it.isNotBlank() }
            ?: return
        try {
            telegramClient.removeManagedDownload(remoteFileId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            SafeLog.w(
                TAG,
                "Could not remove native download for transfer ${transfer.id}: ${error.message}"
            )
        }
    }

    /** A backup state only means something while a transfer backs the file up. */
    private suspend fun syncFileStates() {
        fileDao.repairBackedUpStates()
        fileDao.clearStaleQueuedStates()
        fileDao.clearStaleFailedStates()
        fileDao.deleteCancelledBackupEntries()
    }

    private suspend fun kickWorker() {
        val prefs = settingsRepository.preferences.first()
        transferScheduler.kick(prefs.allowMeteredTransfers)
    }

    private suspend fun currentLimits(): TelegramLimits = try {
        telegramClient.getLimits()
    } catch (_: TelegramException) {
        TelegramLimits.REGULAR
    }

    companion object {
        private const val TAG = "TransferRepository"
        private const val STORAGE_MARGIN = 100L * 1024 * 1024
        private const val SQL_BATCH = 500
    }
}

/** What a download request that dedupes onto an existing row must do to it (vc184 A3). */
enum class ExistingDownloadAction { RESUME, KICK, NONE }

/**
 * The repair for an existing unfinished download row a new download request
 * deduped onto, by the row's current state: a PAUSED row is resumed (nothing
 * else would ever start it — the requester was just told it is "queued"), a
 * QUEUED row re-kicks the worker (its original kick may have been consumed by
 * a worker run that exited before the row landed), and a RUNNING row is
 * already genuinely transferring. Anything else (terminal/absent) is left
 * alone. Pure, so the contract is host-testable.
 */
fun existingDownloadAction(state: TransferState?): ExistingDownloadAction = when (state) {
    TransferState.PAUSED -> ExistingDownloadAction.RESUME
    TransferState.QUEUED -> ExistingDownloadAction.KICK
    else -> ExistingDownloadAction.NONE
}
