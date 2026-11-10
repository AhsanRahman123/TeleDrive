package com.drdisagree.teledrive.data.repository

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
    private val backupSessionTracker: BackupSessionTracker
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
    }.map { list -> list.map { it.toDomain() } }

    override fun observeSectionCount(section: TransferSection): Flow<Int> = when (section) {
        TransferSection.ACTIVE -> transferDao.observeActiveCount()
        TransferSection.PAUSED -> transferDao.observeCountByState(TransferState.PAUSED)
        TransferSection.FAILED -> transferDao.observeCountByState(TransferState.FAILED)
        TransferSection.COMPLETED -> transferDao.observeCountByState(TransferState.COMPLETED)
    }

    override fun observeActiveCount(): Flow<Int> = transferDao.observeActiveCount()

    override fun observeActiveForFile(fileId: String): Flow<TransferTask?> =
        transferDao.observeActiveForFile(fileId).map { it?.toDomain() }

    override fun observeActiveDownloads(): Flow<List<TransferTask>> =
        transferDao.observeActiveDownloads().map { list -> list.map { it.toDomain() } }

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
        return enqueueLock.withLock {
            transferDao.unfinishedIdsForFiles(listOf(fileId)).firstOrNull()
                ?.let { return@withLock AppResult.Success(it) }
            insertTransfer(
                entity.id,
                entity.name,
                entity.sizeBytes,
                entity.chatId,
                TransferType.DOWNLOAD,
                priority,
                null
            )
        }
    }

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
        kickWorker()
        return AppResult.Success(transfer.id)
    }

    override suspend fun pause(id: String) = workerControlLock.withLock {
        val transfer = transferDao.byId(id) ?: return@withLock
        if (transfer.state == TransferState.QUEUED || transfer.state == TransferState.RUNNING) {
            transferDao.setState(id, TransferState.PAUSED, System.currentTimeMillis())
            pauseNativeDownloadIfPresent(transfer)
            backupSessionTracker.refresh(transfer.backupSessionId)
        }
    }

    override suspend fun resume(id: String) = workerControlLock.withLock {
        val transfer = transferDao.byId(id) ?: return@withLock
        if (transfer.state == TransferState.PAUSED || transfer.state == TransferState.FAILED) {
            transferDao.setState(id, TransferState.QUEUED, System.currentTimeMillis())
            val prefs = settingsRepository.preferences.first()
            transferScheduler.resume(prefs.allowMeteredTransfers)
            backupSessionTracker.refresh(transfer.backupSessionId)
        }
    }

    override suspend fun cancel(id: String) {
        val transfer = transferDao.byId(id) ?: return
        if (transfer.state.isTerminal) {
            transferDao.deleteByIds(listOf(id))
        } else {
            transferDao.setState(id, TransferState.CANCELLED, System.currentTimeMillis())
            transfer.fileId?.let { fileDao.setBackupStateIfLocalOnly(it, BackupState.NONE) }
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
    }

    override suspend fun resumeAll() = workerControlLock.withLock {
        transferDao.resumeAll(System.currentTimeMillis())
        val prefs = settingsRepository.preferences.first()
        // Wake the worker pool immediately after the rows become QUEUED.
        transferScheduler.resume(prefs.allowMeteredTransfers)
        backupSessionTracker.refreshActive()
    }

    override suspend fun cancelAll() {
        enqueueLock.withLock {
            // Serialize cancellation against upload inserts. Any import batch holding an older
            // generation is rejected if it tries to enqueue after this block completes.
            uploadBatchGeneration.advance()
            transferDao.cancelAll(System.currentTimeMillis())
        }
        syncFileStates()
        backupSessionTracker.refreshActive()
    }

    override suspend fun setPriority(id: String, priority: Int) = workerControlLock.withLock {
        val transfer = transferDao.byId(id) ?: return@withLock
        if (transfer.state.isTerminal) return@withLock
        transferDao.setPriority(id, priority, System.currentTimeMillis())
        // Room ordering alone is not enough for a download already registered in TDLib.
        // Re-apply native priorities so active downloads react immediately.
        applyNativeDownloadPriorities()
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
        applyNativeDownloadPriorities()
        if (ordered.any { it.state == TransferState.QUEUED }) kickWorker()
    }

    override suspend fun moveDownloadToTop(id: String) = workerControlLock.withLock {
        val current = transferDao.incomingQueueOrder()
        val target = current.firstOrNull { it.id == id } ?: return@withLock
        val ordered = listOf(target) + current.filterNot { it.id == id }
        persistDownloadOrder(ordered)
        applyNativeDownloadPriorities()
        if (target.state == TransferState.QUEUED) kickWorker()
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

    override suspend fun retry(id: String) = workerControlLock.withLock {
        val transfer = transferDao.byId(id) ?: return@withLock
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
        }
    }

    override suspend fun clearFinished() {
        transferDao.clearFinished()
        syncFileStates()
    }

    override suspend fun recoverOrphanedTransfers() {
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
