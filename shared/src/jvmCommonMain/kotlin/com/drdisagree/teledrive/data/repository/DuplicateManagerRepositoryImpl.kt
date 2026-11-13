package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.Hashing
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.repository.DuplicateFileGroup
import com.drdisagree.teledrive.domain.repository.DuplicateManagerRepository
import com.drdisagree.teledrive.domain.repository.DuplicateManagerState
import com.drdisagree.teledrive.domain.repository.DuplicateScanFile
import com.drdisagree.teledrive.domain.repository.DuplicateScanQueueItem
import com.drdisagree.teledrive.domain.repository.DuplicateScanStatus
import com.drdisagree.teledrive.domain.repository.FileRepository
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.join
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Scans only the requested folder tree. The durable file metadata remains in Room; this
 * controller owns the current scan session and its actionable work queue.
 *
 * Hashing is streamed from disk/TDLib downloads. Large files are never loaded into memory.
 */
class DuplicateManagerRepositoryImpl(
    private val fileRepository: FileRepository,
    private val fileDao: FileDao,
    private val filePartDao: FilePartDao,
    private val telegramClient: TelegramClient,
    private val folderPathResolver: FolderPathResolver,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val streamCrypto: StreamCrypto
) : DuplicateManagerRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startMutex = Mutex()
    private val pausedFileIds = mutableSetOf<String>()
    private val cancelledFileIds = mutableSetOf<String>()

    private val _state = MutableStateFlow(DuplicateManagerState())
    override val state: StateFlow<DuplicateManagerState> = _state.asStateFlow()

    @Volatile private var workerJob: Job? = null
    @Volatile private var activeTask: Job? = null
    @Volatile private var scanGeneration = 0L
    @Volatile private var activeFileId: String? = null
    @Volatile private var cancelAllRequested = false

    override suspend fun startScan(folderId: String) {
        startMutex.withLock {
            scanGeneration++
            val generation = scanGeneration
            activeTask?.cancel()
            workerJob?.cancelAndJoin()
            activeTask = null
            activeFileId = null
            pausedFileIds.clear()
            cancelledFileIds.clear()
            cancelAllRequested = false

            val ids = fileRepository.fileIdsInTree(folderId).distinct()
            val entities = ids.chunked(SQL_BATCH_SIZE).flatMap { fileDao.byIds(it) }
                .filter { it.trashedAt == null }

            val queue = entities.map { entity ->
                DuplicateScanQueueItem(
                    file = entity.toScanFile(folderPathResolver.pathOf(entity.folderId ?: entity.preTrashFolderId)),
                    status = if (entity.contentHash.isNullOrBlank()) {
                        DuplicateScanStatus.WAITING
                    } else {
                        DuplicateScanStatus.SCANNED
                    }
                )
            }

            _state.value = DuplicateManagerState(
                folderId = folderId,
                queue = queue,
                groups = buildGroups(queue),
                isRunning = queue.any { it.status == DuplicateScanStatus.WAITING },
                scannedFiles = queue.count { it.status == DuplicateScanStatus.SCANNED },
                totalFiles = queue.size
            )

            if (generation == scanGeneration && queue.any { it.status == DuplicateScanStatus.WAITING }) {
                launchWorker(generation)
            }
        }
    }

    override fun pauseAll() {
        _state.update { current ->
            current.copy(
                isPaused = true,
                queue = current.queue.map { item ->
                    if (item.status == DuplicateScanStatus.WAITING ||
                        item.status == DuplicateScanStatus.GENERATING
                    ) {
                        item.copy(status = DuplicateScanStatus.PAUSED)
                    } else item
                }
            )
        }
        activeTask?.cancel()
    }

    override fun resumeAll() {
        _state.update { current ->
            current.copy(
                isPaused = false,
                queue = current.queue.map { item ->
                    if (item.status == DuplicateScanStatus.PAUSED) {
                        item.copy(status = DuplicateScanStatus.WAITING, error = null)
                    } else item
                }
            )
        }
        launchWorkerIfNeeded()
    }

    override fun cancelAll() {
        cancelAllRequested = true
        scanGeneration++
        activeTask?.cancel()
        workerJob?.cancel()
        workerJob = null
        activeTask = null
        activeFileId = null
        _state.update { current ->
            current.copy(
                isRunning = false,
                isPaused = false,
                activeFileId = null,
                queue = current.queue.map { item ->
                    if (
                        item.status == DuplicateScanStatus.WAITING ||
                        item.status == DuplicateScanStatus.PAUSED ||
                        item.status == DuplicateScanStatus.GENERATING
                    ) {
                        item.copy(status = DuplicateScanStatus.CANCELLED)
                    } else item
                }
            )
        }
    }

    override fun pauseFiles(fileIds: Set<String>) {
        if (fileIds.isEmpty()) return
        synchronized(pausedFileIds) { pausedFileIds.addAll(fileIds) }
        _state.update { current ->
            current.copy(queue = current.queue.map { item ->
                if (
                    item.file.id in fileIds &&
                    (item.status == DuplicateScanStatus.WAITING ||
                        item.status == DuplicateScanStatus.GENERATING)
                ) {
                    item.copy(status = DuplicateScanStatus.PAUSED)
                } else item
            })
        }
        if (activeFileId?.let { it in fileIds } == true) activeTask?.cancel()
    }

    override fun resumeFiles(fileIds: Set<String>) {
        if (fileIds.isEmpty()) return
        synchronized(pausedFileIds) { pausedFileIds.removeAll(fileIds) }
        _state.update { current ->
            current.copy(queue = current.queue.map { item ->
                if (item.file.id in fileIds && item.status == DuplicateScanStatus.PAUSED) {
                    item.copy(status = DuplicateScanStatus.WAITING, error = null)
                } else item
            })
        }
        launchWorkerIfNeeded()
    }

    override fun cancelFiles(fileIds: Set<String>) {
        if (fileIds.isEmpty()) return
        synchronized(cancelledFileIds) { cancelledFileIds.addAll(fileIds) }
        synchronized(pausedFileIds) { pausedFileIds.removeAll(fileIds) }
        _state.update { current ->
            current.copy(queue = current.queue.map { item ->
                if (
                    item.file.id in fileIds &&
                    item.status != DuplicateScanStatus.SCANNED
                ) {
                    item.copy(status = DuplicateScanStatus.CANCELLED, error = null)
                } else item
            })
        }
        if (activeFileId?.let { it in fileIds } == true) activeTask?.cancel()
    }

    override fun retryFiles(fileIds: Set<String>) {
        if (fileIds.isEmpty()) return
        synchronized(cancelledFileIds) { cancelledFileIds.removeAll(fileIds) }
        synchronized(pausedFileIds) { pausedFileIds.removeAll(fileIds) }
        cancelAllRequested = false
        _state.update { current ->
            current.copy(
                isPaused = false,
                queue = current.queue.map { item ->
                    if (
                        item.file.id in fileIds &&
                        (item.status == DuplicateScanStatus.FAILED ||
                            item.status == DuplicateScanStatus.CANCELLED ||
                            item.status == DuplicateScanStatus.PAUSED)
                    ) {
                        item.copy(status = DuplicateScanStatus.WAITING, error = null)
                    } else item
                }
            )
        }
        launchWorkerIfNeeded()
    }

    override fun retryFailed() {
        retryFiles(
            _state.value.queue.filter {
                it.status == DuplicateScanStatus.FAILED ||
                    it.status == DuplicateScanStatus.CANCELLED
            }.map { it.file.id }.toSet()
        )
    }

    private fun launchWorkerIfNeeded() {
        if (cancelAllRequested || _state.value.isPaused) return
        if (_state.value.queue.none { it.status == DuplicateScanStatus.WAITING }) return
        val currentWorker = workerJob
        if (currentWorker?.isActive == true) {
            // A retry can arrive while the previous worker is exiting its final iteration.
            // Join it first, then launch a fresh worker if the new waiting rows remain.
            scope.launch {
                currentWorker.join()
                if (workerJob === currentWorker) {
                    workerJob = null
                    launchWorkerIfNeeded()
                }
            }
            return
        }
        launchWorker(scanGeneration)
    }

    private fun launchWorker(generation: Long) {
        if (workerJob?.isActive == true) return
        workerJob = scope.launch {
            runQueue(generation)
        }
    }

    private suspend fun runQueue(generation: Long) {
        _state.update { it.copy(isRunning = true, error = null) }
        try {
            while (generation == scanGeneration) {
                currentCoroutineContext().ensureActive()
                if (cancelAllRequested) break

                val next = _state.value.queue.firstOrNull { it.status == DuplicateScanStatus.WAITING }
                    ?: break

                if (!waitUntilRunnable(next.file.id, generation)) {
                    markStatus(next.file.id, DuplicateScanStatus.CANCELLED)
                    continue
                }

                activeFileId = next.file.id
                _state.update { it.copy(activeFileId = next.file.id) }
                markStatus(next.file.id, DuplicateScanStatus.GENERATING)
                try {
                    val hash = coroutineScope {
                        val task = async(Dispatchers.IO) { calculateHash(next.file.id) }
                        activeTask = task
                        task.await()
                    }
                    activeTask = null
                    if (generation != scanGeneration) break

                    if (hash.isNullOrBlank()) {
                        markStatus(
                            next.file.id,
                            DuplicateScanStatus.FAILED,
                            "No readable local or remote copy was available to hash."
                        )
                    } else {
                        fileDao.setContentHash(next.file.id, hash)
                        updateScannedHash(next.file.id, hash)
                    }
                } catch (cancelled: CancellationException) {
                    activeTask = null
                    currentCoroutineContext().ensureActive()
                    when {
                        generation != scanGeneration -> throw cancelled
                        cancelAllRequested || isFileCancelled(next.file.id) -> {
                            markStatus(next.file.id, DuplicateScanStatus.CANCELLED)
                        }
                        _state.value.isPaused || isFilePaused(next.file.id) -> {
                            markStatus(next.file.id, DuplicateScanStatus.PAUSED)
                        }
                        else -> throw cancelled
                    }
                } catch (error: Exception) {
                    activeTask = null
                    markStatus(
                        next.file.id,
                        DuplicateScanStatus.FAILED,
                        error.message ?: error::class.simpleName ?: "Hashing failed"
                    )
                } finally {
                    activeTask = null
                    activeFileId = null
                    _state.update { current ->
                        if (generation == scanGeneration) current.copy(activeFileId = null)
                        else current
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (generation == scanGeneration) {
                _state.update { it.copy(error = error.message ?: "Duplicate scan stopped.") }
            }
        } finally {
            if (generation == scanGeneration) {
                _state.update { it.copy(isRunning = false, activeFileId = null) }
            }
        }
    }

    private suspend fun waitUntilRunnable(fileId: String, generation: Long): Boolean {
        while (true) {
            currentCoroutineContext().ensureActive()
            if (generation != scanGeneration || cancelAllRequested || isFileCancelled(fileId)) {
                return false
            }

            val paused = _state.value.isPaused || isFilePaused(fileId)
            if (!paused) return true
            markStatus(fileId, DuplicateScanStatus.PAUSED)
            delay(PAUSE_POLL_MS)
        }
    }

    private suspend fun calculateHash(fileId: String): String? {
        val entity = fileDao.byId(fileId) ?: return null
        entity.contentHash?.takeIf { it.isNotBlank() }?.let { return it }

        val local = entity.localPath?.let(::File)?.takeIf { it.isFile && it.length() > 0L }
        if (local != null) {
            return Hashing.sha256(local)
        }

        val source = if (entity.partCount > 1) {
            assembleSplitFile(entity) ?: return null
        } else {
            val remoteFileId = entity.remoteFileId ?: return null
            val localPath = downloadPath(remoteFileId, entity.chatId, entity.messageId) ?: return null
            File(localPath)
        }

        return if (entity.isEncrypted) {
            decryptAndHash(source, entity.id)
        } else {
            try {
                Hashing.sha256(source)
            } finally {
                if (source.name.endsWith(".assembled")) source.delete()
            }
        }
    }

    private suspend fun assembleSplitFile(entity: FileEntity): File? {
        val parts = filePartDao.partsOf(entity.id).sortedBy { it.partIndex }
        if (parts.size < entity.partCount || parts.isEmpty()) return null

        val target = File.createTempFile("td-duplicate-\${entity.id.take(8)}-", ".assembled")
        var completed = false
        try {
            target.outputStream().buffered().use { output ->
                for (part in parts) {
                    val remoteFileId = part.remoteFileId
                        ?: throw IllegalStateException("A split-file part has no Telegram file ID.")
                    val path = downloadPath(remoteFileId, part.chatId, part.messageId)
                        ?: throw IllegalStateException("A split-file part could not be downloaded.")
                    val source = File(path)
                    check(source.isFile) { "A split-file part download is missing." }
                    source.inputStream().buffered().use { input -> input.copyTo(output) }
                }
            }
            completed = true
            return target
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } finally {
            if (!completed) target.delete()
        }
    }

    private suspend fun downloadPath(
        remoteFileId: String,
        chatId: Long?,
        messageId: Long?
    ): String? {
        var completedPath: String? = null
        telegramClient.downloadDocument(
            remoteFileId = remoteFileId,
            chatId = chatId,
            messageId = messageId
        ).collect { event ->
            if (event is TelegramDownloadEvent.Completed) {
                completedPath = event.localPath
            }
        }
        return completedPath?.takeIf { File(it).isFile }
    }

    private suspend fun decryptAndHash(encryptedFile: File, fileId: String): String? {
        val key = wrappedKeyRepository.get(CryptoKeys.CONTENT) ?: return null
        val plaintext = File.createTempFile("td-duplicate-\${fileId.take(8)}-", ".plain")
        return try {
            encryptedFile.inputStream().buffered().use { input ->
                plaintext.outputStream().buffered().use { output ->
                    streamCrypto.decryptStream(key, input, output)
                }
            }
            Hashing.sha256(plaintext)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            plaintext.delete()
            if (encryptedFile.name.endsWith(".assembled")) encryptedFile.delete()
        }
    }

    private fun updateScannedHash(fileId: String, hash: String) {
        _state.update { current ->
            val updatedQueue = current.queue.map { item ->
                if (item.file.id == fileId) {
                    item.copy(
                        file = item.file.copy(contentHash = hash),
                        status = DuplicateScanStatus.SCANNED,
                        error = null
                    )
                } else item
            }
            val scanned = updatedQueue.count { it.status == DuplicateScanStatus.SCANNED }
            current.copy(
                queue = updatedQueue,
                groups = buildGroups(updatedQueue),
                scannedFiles = scanned
            )
        }
    }

    private fun markStatus(
        fileId: String,
        status: DuplicateScanStatus,
        error: String? = null
    ) {
        _state.update { current ->
            current.copy(queue = current.queue.map { item ->
                if (item.file.id == fileId) item.copy(status = status, error = error)
                else item
            })
        }
    }

    private fun isFilePaused(fileId: String): Boolean =
        synchronized(pausedFileIds) { fileId in pausedFileIds }

    private fun isFileCancelled(fileId: String): Boolean =
        synchronized(cancelledFileIds) { fileId in cancelledFileIds }

    private fun buildGroups(queue: List<DuplicateScanQueueItem>): List<DuplicateFileGroup> =
        queue.asSequence()
            .filter { it.status == DuplicateScanStatus.SCANNED && !it.file.contentHash.isNullOrBlank() }
            .groupBy { it.file.contentHash!! }
            .filterValues { it.size > 1 }
            .map { (hash, matches) ->
                DuplicateFileGroup(hash, matches.map { it.file }.sortedBy { it.name.lowercase() })
            }
            .sortedWith(
                compareByDescending<DuplicateFileGroup> { it.duplicateCount }
                    .thenByDescending { it.wastedBytes }
                    .thenBy { it.files.firstOrNull()?.name.orEmpty().lowercase() }
            )

    private fun FileEntity.toScanFile(folderPath: String) = DuplicateScanFile(
        id = id,
        name = name,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        folderPath = folderPath.ifBlank { "Drive root" },
        modifiedAt = modifiedAt,
        addedAt = addedAt,
        contentHash = contentHash,
        isEncrypted = isEncrypted
    )

    private companion object {
        const val SQL_BATCH_SIZE = 400
        const val PAUSE_POLL_MS = 150L
    }
}
