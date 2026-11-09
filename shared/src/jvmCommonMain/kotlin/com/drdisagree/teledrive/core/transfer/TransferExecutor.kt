package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.KeyUnavailableException
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.DiscardingOutputStream
import com.drdisagree.teledrive.core.files.HashAccumulator
import com.drdisagree.teledrive.core.files.Hashing
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramLimits
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.data.local.dao.BackupDao
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.BackupRecordEntity
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import com.drdisagree.teledrive.data.repository.FileBase
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferStage
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

/**
 * Pause and cancel are cooperative: the row is checked on each progress event and TDLib is stopped
 * by aborting collection.
 */
class TransferExecutor(
    private val messages: TransferErrorMessages,
    private val storagePaths: AppStoragePaths,
    private val telegramClient: TelegramClient,
    private val transferDao: TransferDao,
    private val fileDao: FileDao,
    private val backupDao: BackupDao,
    private val manifestCodec: ManifestCodec,
    private val folderPathResolver: FolderPathResolver,
    private val thumbnailStore: ThumbnailStore,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val downloadWriter: DownloadWriter,
    private val fileImporter: FileImporter,
    private val settingsRepository: SettingsRepository,
    private val filePartDao: FilePartDao,
    private val partUploader: PartUploader,
    private val partDownloader: PartDownloader,
    private val apkIconUploader: ApkIconUploader,
    private val localCopyDeleter: LocalCopyDeleter
) {

    /**
     * A dead connection stops emitting without an error, which would leave the row RUNNING forever.
     */
    private fun <T> Flow<T>.failWhenIdle(transferId: String, message: String): Flow<T> = channelFlow {
        val relay = Channel<T>(Channel.BUFFERED)
        val producer = launch {
            try {
                collect { relay.send(it) }
                relay.close()
            } catch (e: Throwable) {
                relay.close(e)
            }
        }
        var lastEventAt = System.currentTimeMillis()
        try {
            while (true) {
                // Pause/cancel/resume must release a worker even when TDLib has stopped
                // emitting progress. Poll independently of network progress instead of
                // waiting for the long network-idle timeout.
                checkControl(transferId)
                val received = withTimeoutOrNull(CONTROL_POLL_MS.milliseconds) {
                    relay.receiveCatching()
                }
                if (received == null) {
                    if (System.currentTimeMillis() - lastEventAt >= STALL_TIMEOUT_MS) {
                        throw TelegramException(STALL_CODE, message)
                    }
                    continue
                }
                received.exceptionOrNull()?.let { throw it }
                if (received.isClosed) break
                lastEventAt = System.currentTimeMillis()
                send(received.getOrThrow())
            }
        } finally {
            // The parent drain can itself be cancelled by Pause All. Keep cleanup non-cancellable
            // so the TDLib flow's awaitClose handler finishes before this worker slot is released.
            withContext(NonCancellable) {
                producer.cancelAndJoin()
            }
        }
    }

    suspend fun execute(transfer: TransferEntity): TransferOutcome = try {
        when (transfer.type) {
            TransferType.UPLOAD, TransferType.BACKUP -> executeUpload(transfer)
            TransferType.DOWNLOAD, TransferType.RESTORE -> executeDownload(transfer)
        }
    } catch (_: KeyUnavailableException) {
        TransferOutcome.Failed(messages.keyMissing)
    }

    private suspend fun cacheOrQueueUploadedThumbnail(
        file: FileEntity,
        temporaryPreviewPath: String?
    ) {
        if (!supportsThumbnail(file)) return
        val cached = try {
            temporaryPreviewPath?.let {
                thumbnailStore.cacheUploadedThumbnail(file.id, it)
            } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        if (!cached) {
            try {
                thumbnailStore.enqueueAfterUpload(file.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A cache/queue failure must not turn an already completed Telegram upload into
                // a failed transfer. The file remains available and can be prepared manually.
            }
        }
    }

    private fun supportsThumbnail(file: FileEntity): Boolean =
        file.category == FileCategory.IMAGE ||
            file.category == FileCategory.VIDEO ||
            file.mimeType.startsWith("text/", ignoreCase = true) ||
            file.mimeType.equals("application/vnd.android.package-archive", ignoreCase = true)

    private suspend fun executeUpload(transfer: TransferEntity): TransferOutcome {
        val fileId = transfer.fileId
            ?: return TransferOutcome.Failed(messages.noFileReference)
        val entity = fileDao.byId(fileId)
            ?: return TransferOutcome.Failed(messages.fileRecordMissing)
        val localPath = entity.localPath
            ?: return TransferOutcome.Failed(messages.noLocalCopy)
        val sourceFile = File(localPath)
        if (!sourceFile.exists()) return TransferOutcome.Failed(messages.localFileGone)

        val prefs = settingsRepository.preferences.first()
        val chatId = transfer.chatId
            ?: entity.chatId
            ?: telegramClient.ensureStorageChat(prefs.storageChatId).also { resolved ->
                if (resolved != prefs.storageChatId) {
                    settingsRepository.update { it.copy(storageChatId = resolved) }
                }
            }

        val encrypt = prefs.encryptFiles && prefs.keyBackupCreated
        val uploadSize = sourceFile.length()
        val sizeChanged = entity.sizeBytes != uploadSize
        val hashUpFront = uploadSize <= EAGER_HASH_LIMIT ||
                !splitsIntoParts(entity, sourceFile, encrypt)
        val contentHash = when {
            !hashUpFront -> entity.contentHash
            sizeChanged -> Hashing.sha256(sourceFile)
            else -> entity.contentHash ?: Hashing.sha256(sourceFile)
        }
        if (sizeChanged || (contentHash != null && contentHash != entity.contentHash)) {
            fileDao.upsert(entity.copy(sizeBytes = uploadSize, contentHash = contentHash))
        }
        val supersededMessageId = entity.messageId
            ?.takeIf { entity.chatId == chatId && filePartDao.countOf(entity.id) == 0 }

        val rawIconPath = thumbnailStore.uploadThumbnailFile(entity.id)?.absolutePath
        val iconFileId = apkIconUploader.uploadIconIfApk(entity, chatId, encrypt)
        val previewPath = if (encrypt) null else rawIconPath

        val manifest = RemoteFileManifest(
            fileId = entity.id,
            name = entity.name,
            folderPath = folderPathResolver.pathOf(entity.folderId),
            folderId = entity.folderId,
            mimeType = entity.mimeType,
            sizeBytes = uploadSize,
            contentHash = contentHash,
            hidden = entity.isHidden,
            archived = entity.isArchived,
            encrypted = encrypt,
            createdAt = entity.createdAt,
            modifiedAt = entity.modifiedAt,
            width = entity.width,
            height = entity.height,
            durationMs = entity.durationMs,
            iconFileId = iconFileId
        )
        val caption = manifestCodec.encode(manifest, encrypt)

        fileDao.setBackupState(entity.id, BackupState.UPLOADING)

        if (splitsIntoParts(entity, sourceFile, encrypt)) {
            try {
                val result = uploadInParts(
                    transfer = transfer,
                    entity = entity,
                    sourceFile = sourceFile,
                    localPath = localPath,
                    chatId = chatId,
                    manifest = manifest,
                    encrypt = encrypt,
                    contentHash = contentHash,
                    supersededMessageId = supersededMessageId,
                    previewPath = previewPath
                )
                if (result == TransferOutcome.Completed) {
                    cacheOrQueueUploadedThumbnail(entity, rawIconPath)
                }
                return result
            } finally {
                rawIconPath?.let { File(it).delete() }
            }
        }

        var stagingFile: File? = null
        val (uploadPath, uploadName) = if (encrypt) {
            val staging = File(stagingDir(), "${entity.id}.tde")
            val key = wrappedKeyRepository.getOrCreate(CryptoKeys.CONTENT)
            sourceFile.inputStream().use { input ->
                staging.outputStream().buffered().use { output ->
                    streamCrypto.encryptStream(key, input, output)
                }
            }
            stagingFile = staging
            staging.absolutePath to "${entity.id}.tde"
        } else {
            sourceFile.absolutePath to entity.name
        }

        val ticker = ProgressTicker()
        var tickerStarted = false

        return try {
            var outcome: TransferOutcome =
                TransferOutcome.Failed(messages.uploadEnded)
            telegramClient.uploadDocument(
                chatId = chatId,
                localPath = uploadPath,
                fileName = uploadName,
                mimeType = if (encrypt) "application/octet-stream" else entity.mimeType,
                caption = caption,
                thumbnailPath = previewPath
            ).failWhenIdle(transfer.id, messages.uploadStalled).collect { event ->
                currentCoroutineContext().ensureActive()
                when (event) {
                    is TelegramUploadEvent.Started -> Unit
                    is TelegramUploadEvent.Progress -> {
                        val now = System.currentTimeMillis()
                        if (!tickerStarted) {
                            ticker.start(event.transferredBytes, now)
                            tickerStarted = true
                            transferDao.updateProgress(
                                transfer.id, event.transferredBytes, 0L, now
                            )
                        } else {
                            ticker.tick(event.transferredBytes, now)?.let { speed ->
                                transferDao.updateProgress(
                                    transfer.id, event.transferredBytes, speed, now
                                )
                            }
                        }
                        checkControl(transfer.id)
                    }

                    is TelegramUploadEvent.Completed -> {
                        val document = event.document
                        fileDao.setRemoteMapping(
                            id = entity.id,
                            chatId = document.chatId,
                            messageId = document.messageId,
                            remoteFileId = document.remoteFileId,
                            remoteUniqueId = document.uniqueFileId,
                            state = BackupState.BACKED_UP,
                            iconFileId = iconFileId
                        )
                        fileDao.setRemoteBase(entity.id, FileBase.of(manifest).toText())
                        if (transfer.type == TransferType.BACKUP) {
                            backupDao.upsertRecord(
                                BackupRecordEntity(
                                    id = UUID.randomUUID().toString(),
                                    sourcePath = localPath,
                                    fileId = entity.id,
                                    sizeBytes = uploadSize,
                                    modifiedAt = sourceFile.lastModified(),
                                    contentHash = contentHash,
                                    backedUpAt = System.currentTimeMillis(),
                                    chatId = document.chatId
                                )
                            )
                        }
                        supersededMessageId
                            ?.takeIf { it != document.messageId }
                            ?.let { stale ->
                                runCatching {
                                    telegramClient.deleteMessages(chatId, listOf(stale))
                                }
                            }
                        releaseLocalCopy(transfer.type, entity, localPath, contentHash)
                        transferDao.setCompleted(transfer.id, System.currentTimeMillis())
                        outcome = TransferOutcome.Completed
                    }
                }
            }
            if (outcome == TransferOutcome.Completed) {
                cacheOrQueueUploadedThumbnail(entity, rawIconPath)
            }
            outcome
        } catch (e: TransferControlException) {
            fileDao.setBackupStateIfLocalOnly(
                entity.id,
                if (e.paused) BackupState.QUEUED else BackupState.NONE
            )
            if (e.paused) TransferOutcome.Paused else TransferOutcome.Canceled
        } catch (e: TelegramException) {
            fileDao.setBackupStateIfLocalOnly(entity.id, BackupState.FAILED)
            TransferOutcome.Failed(e.message, e.retryAfterSeconds)
        } finally {
            stagingFile?.delete()
            rawIconPath?.let { File(it).delete() }
        }
    }

    /**
     * A file already split stays split; the limit is checked against the sealed size, which grows
     * by a frame header per megabyte.
     */
    private suspend fun splitsIntoParts(
        entity: FileEntity,
        source: File,
        encrypt: Boolean
    ): Boolean {
        if (filePartDao.countOf(entity.id) > 0) return true
        val limit = runCatching { telegramClient.getLimits() }
            .getOrDefault(TelegramLimits.REGULAR)
            .maxFileBytes
        val plainSize = source.length()
        val uploadSize = if (encrypt) streamCrypto.storedSize(plainSize) else plainSize
        return FileParts.splits(uploadSize, limit)
    }

    private suspend fun uploadInParts(
        transfer: TransferEntity,
        entity: FileEntity,
        sourceFile: File,
        localPath: String,
        chatId: Long,
        manifest: RemoteFileManifest,
        encrypt: Boolean,
        contentHash: String?,
        supersededMessageId: Long?,
        previewPath: String?
    ): TransferOutcome {
        val ticker = ProgressTicker()
        var tickerStarted = false
        var outcome: TransferOutcome =
            TransferOutcome.Failed(messages.uploadEnded)

        return try {
            partUploader.upload(entity, sourceFile, chatId, manifest, encrypt, previewPath)
                .collect { event ->
                    currentCoroutineContext().ensureActive()
                    when (event) {
                        is PartUploadEvent.Progress -> {
                            val now = System.currentTimeMillis()
                            if (!tickerStarted) {
                                ticker.start(event.transferredBytes, now)
                                tickerStarted = true
                                transferDao.updateProgress(
                                    transfer.id, event.transferredBytes, 0L, now
                                )
                            } else {
                                ticker.tick(event.transferredBytes, now)?.let { speed ->
                                    transferDao.updateProgress(
                                        transfer.id, event.transferredBytes, speed, now
                                    )
                                }
                            }
                            checkControl(transfer.id)
                        }

                        is PartUploadEvent.Sealing -> {
                            transferDao.setStage(
                                transfer.id,
                                TransferStage.SEALING,
                                System.currentTimeMillis()
                            )
                            checkControl(transfer.id)
                        }

                        is PartUploadEvent.PartDone -> {
                            transferDao.setStage(transfer.id, null, System.currentTimeMillis())
                            checkControl(transfer.id)
                        }

                        is PartUploadEvent.Completed -> {
                            val first = event.parts.firstOrNull()
                                ?: error("Upload finished with no parts")
                            val finalHash = event.contentHash ?: contentHash
                            if (finalHash != null || first.plainHash != null) {
                                val firstChatId = first.chatId ?: chatId
                                val firstMessageId = first.messageId
                                    ?: error("First part has no Telegram message")
                                val remoteCaption = telegramClient.getDocument(firstChatId, firstMessageId)
                                    ?.let { manifestCodec.decode(it.caption) } ?: manifest
                                val durableCaption = remoteCaption.copy(
                                    version = RemoteFileManifest.PART_VERSION,
                                    partCount = event.parts.size,
                                    partIndex = first.partIndex,
                                    partOffset = first.plainOffset,
                                    partSize = first.plainSize,
                                    contentHash = finalHash ?: remoteCaption.contentHash,
                                    partHash = first.plainHash ?: remoteCaption.partHash
                                )
                                if (durableCaption.contentHash != remoteCaption.contentHash ||
                                    durableCaption.partHash != remoteCaption.partHash
                                ) {
                                    telegramClient.editCaption(
                                        firstChatId,
                                        firstMessageId,
                                        manifestCodec.encode(durableCaption, encrypt)
                                    )
                                }
                            }
                            if (finalHash != null && finalHash != entity.contentHash) {
                                fileDao.setContentHash(entity.id, finalHash)
                            }
                            fileDao.setPartCount(entity.id, event.parts.size)
                            fileDao.setRemoteMapping(
                                id = entity.id,
                                chatId = first.chatId,
                                messageId = first.messageId,
                                remoteFileId = first.remoteFileId,
                                remoteUniqueId = first.remoteUniqueId,
                                state = BackupState.BACKED_UP,
                                iconFileId = manifest.iconFileId
                            )
                            fileDao.setRemoteBase(entity.id, FileBase.of(manifest).toText())
                            if (transfer.type == TransferType.BACKUP) {
                                backupDao.upsertRecord(
                                    BackupRecordEntity(
                                        id = UUID.randomUUID().toString(),
                                        sourcePath = localPath,
                                        fileId = entity.id,
                                        sizeBytes = manifest.sizeBytes,
                                        modifiedAt = sourceFile.lastModified(),
                                        contentHash = finalHash,
                                        backedUpAt = System.currentTimeMillis(),
                                        chatId = first.chatId
                                    )
                                )
                            }
                            supersededMessageId?.let { stale ->
                                runCatching {
                                    telegramClient.deleteMessages(chatId, listOf(stale))
                                }
                            }
                            releaseLocalCopy(transfer.type, entity, localPath, finalHash)
                            transferDao.setCompleted(transfer.id, System.currentTimeMillis())
                            outcome = TransferOutcome.Completed
                        }
                    }
                }
            outcome
        } catch (e: PartSourceChangedException) {
            fileDao.setBackupStateIfLocalOnly(entity.id, BackupState.FAILED)
            TransferOutcome.Failed(messages.verifyFailed)
        } catch (e: TransferControlException) {
            fileDao.setBackupStateIfLocalOnly(
                entity.id,
                if (e.paused) BackupState.QUEUED else BackupState.NONE
            )
            if (!e.paused && fileDao.byId(entity.id)?.messageId == null) {
                partUploader.discardParts(entity.id)
            }
            if (e.paused) TransferOutcome.Paused else TransferOutcome.Canceled
        } catch (e: TelegramException) {
            fileDao.setBackupStateIfLocalOnly(entity.id, BackupState.FAILED)
            TransferOutcome.Failed(e.message, e.retryAfterSeconds)
        }
    }

    private suspend fun executeDownload(transfer: TransferEntity): TransferOutcome {
        val fileId = transfer.fileId
            ?: return TransferOutcome.Failed(messages.noFileReference)
        val entity = fileDao.byId(fileId)
            ?: return TransferOutcome.Failed(messages.fileRecordMissing)

        if (filePartDao.countOf(entity.id) > 1) return downloadInParts(transfer, entity)

        val remoteFileId = entity.remoteFileId
            ?: return TransferOutcome.Failed(messages.noRemoteCopy)

        val ticker = ProgressTicker()
        var tickerStarted = false

        return try {
            var outcome: TransferOutcome =
                TransferOutcome.Failed(messages.downloadEnded)
            telegramClient.downloadDocument(remoteFileId)
                .failWhenIdle(transfer.id, messages.downloadStalled)
                .collect { event ->
                    currentCoroutineContext().ensureActive()
                    when (event) {
                        is TelegramDownloadEvent.Progress -> {
                            val now = System.currentTimeMillis()
                            if (!tickerStarted) {
                                // TDLib can resume a partially downloaded local file. Seed the
                                // meter from its existing byte count instead of counting those
                                // old bytes as fresh network traffic.
                                ticker.start(event.transferredBytes, now)
                                tickerStarted = true
                                transferDao.updateProgress(
                                    transfer.id, event.transferredBytes, 0L, now
                                )
                            } else {
                                ticker.tick(event.transferredBytes, now)?.let { speed ->
                                    transferDao.updateProgress(
                                        transfer.id, event.transferredBytes, speed, now
                                    )
                                }
                            }
                            checkControl(transfer.id)
                        }

                        is TelegramDownloadEvent.Completed -> {
                            outcome = finalizeDownload(transfer, entity.id, event.localPath)
                        }
                    }
                }
            outcome
        } catch (e: TransferControlException) {
            if (e.paused) TransferOutcome.Paused else TransferOutcome.Canceled
        } catch (e: TelegramException) {
            TransferOutcome.Failed(e.message, e.retryAfterSeconds)
        }
    }

    private suspend fun downloadInParts(
        transfer: TransferEntity,
        entity: FileEntity
    ): TransferOutcome {
        val folderPath = folderPathResolver.pathOf(entity.folderId)
        val target = downloadWriter.openPartial(
            fileName = entity.name,
            mimeType = entity.mimeType,
            folderPath = folderPath,
            token = transfer.localPath
        ) ?: return TransferOutcome.Failed(messages.saveFailed)
        transferDao.setLocalPath(transfer.id, target.token)

        val needed = entity.sizeBytes - target.length()
        val free = downloadWriter.freeBytes(folderPath)
        if (free != null && free < needed + SPACE_MARGIN_BYTES) {
            return TransferOutcome.Failed(messages.destinationFull(needed))
        }
        val cacheNeeded = FileParts.PART_SIZE + SPACE_MARGIN_BYTES
        if (storagePaths.filesDir.usableSpace in 1 until cacheNeeded) {
            return TransferOutcome.Failed(messages.cacheFull(cacheNeeded))
        }

        val ticker = ProgressTicker()
        var tickerStarted = false
        var outcome: TransferOutcome =
            TransferOutcome.Failed(messages.downloadEnded)

        return try {
            partDownloader.download(entity.id, entity.isEncrypted, target)
                .failWhenIdle(transfer.id, messages.downloadStalled)
                .collect { event ->
                    currentCoroutineContext().ensureActive()
                    when (event) {
                        is PartDownloadEvent.Progress -> {
                            val now = System.currentTimeMillis()
                            transferDao.setStage(transfer.id, null, now)
                            if (!tickerStarted) {
                                // The destination may contain a retained partial file; begin
                                // measuring only bytes received after this transfer resumes.
                                ticker.start(event.transferredBytes, now)
                                tickerStarted = true
                                transferDao.updateProgress(
                                    transfer.id, event.transferredBytes, 0L, now
                                )
                            } else {
                                ticker.tick(event.transferredBytes, now)?.let { speed ->
                                    transferDao.updateProgress(
                                        transfer.id, event.transferredBytes, speed, now
                                    )
                                }
                            }
                            checkControl(transfer.id)
                        }

                        is PartDownloadEvent.Joining -> {
                            transferDao.setStage(
                                transfer.id,
                                TransferStage.JOINING,
                                System.currentTimeMillis()
                            )
                            checkControl(transfer.id)
                        }

                        is PartDownloadEvent.Completed -> {
                            outcome = finishParts(transfer, entity, target)
                        }
                    }
                }
            outcome
        } catch (e: TransferControlException) {
            if (!e.paused) {
                target.discard()
                transferDao.setLocalPath(transfer.id, null)
            }
            if (e.paused) TransferOutcome.Paused else TransferOutcome.Canceled
        } catch (e: PartIntegrityException) {
            transferDao.setStage(transfer.id, null, System.currentTimeMillis())
            TransferOutcome.Failed(messages.verifyFailed)
        } catch (e: TelegramException) {
            TransferOutcome.Failed(e.message, e.retryAfterSeconds)
        }
    }

    private suspend fun finishParts(
        transfer: TransferEntity,
        entity: FileEntity,
        target: PartialDownload
    ): TransferOutcome {
        transferDao.setStage(transfer.id, TransferStage.VERIFYING, System.currentTimeMillis())
        val intact = withContext(Dispatchers.IO) {
            target.length() == entity.sizeBytes && (
                    entity.contentHash == null ||
                            Hashing.sha256(target.read()) == entity.contentHash
                    )
        }
        transferDao.setStage(transfer.id, null, System.currentTimeMillis())
        if (!intact) {
            target.discard()
            transferDao.setLocalPath(transfer.id, null)
            return TransferOutcome.Failed(messages.verifyFailed)
        }
        val savedPath = target.finish() ?: return TransferOutcome.Failed(messages.saveFailed)
        transferDao.setLocalPath(transfer.id, null)
        fileDao.setLocalPath(entity.id, savedPath)
        transferDao.setCompleted(transfer.id, System.currentTimeMillis())
        return TransferOutcome.Completed
    }

    private suspend fun finalizeDownload(
        transfer: TransferEntity,
        fileId: String,
        tdlibPath: String,
        alreadyPlain: Boolean = false
    ): TransferOutcome {
        val entity = fileDao.byId(fileId)
            ?: return TransferOutcome.Failed(messages.fileRecordMissing)
        val source = File(tdlibPath)
        if (!source.exists()) return TransferOutcome.Failed(messages.downloadedMissing)

        val key = if (entity.isEncrypted && !alreadyPlain) {
            wrappedKeyRepository.get(CryptoKeys.CONTENT)
                ?: return TransferOutcome.Failed(messages.keyMissing)
        } else {
            null
        }

        if (entity.contentHash != null) {
            val actualHash = try {
                withContext(Dispatchers.IO) {
                    val accumulator = HashAccumulator()
                    val hashingOutput = accumulator.wrap(DiscardingOutputStream)
                    source.inputStream().buffered().use { input ->
                        if (key != null) {
                            streamCrypto.decryptStream(key, input, hashingOutput)
                        } else {
                            input.copyTo(hashingOutput)
                        }
                    }
                    hashingOutput.flush()
                    accumulator.result()
                }
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                return TransferOutcome.Failed(
                    if (key != null) messages.decryptionFailed else messages.verifyFailed
                )
            }
            if (actualHash != entity.contentHash) {
                return TransferOutcome.Failed(messages.verifyFailed)
            }
        }

        val folderPath = folderPathResolver.pathOf(entity.folderId)
        val savedPath = downloadWriter.write(
            fileName = entity.name,
            mimeType = entity.mimeType,
            folderPath = folderPath
        ) { output ->
            source.inputStream().buffered().use { input ->
                if (key != null) {
                    streamCrypto.decryptStream(key, input, output)
                } else {
                    input.copyTo(output)
                }
            }
        } ?: return TransferOutcome.Failed(
            if (entity.isEncrypted) {
                messages.decryptionFailed
            } else {
                messages.saveFailed
            }
        )

        fileDao.setLocalPath(fileId, savedPath)
        transferDao.setCompleted(transfer.id, System.currentTimeMillis())
        return TransferOutcome.Completed
    }

    /**
     * The user's own file only goes when the setting asks, it is not kept offline, and it still
     * hashes to what was uploaded.
     */
    private suspend fun releaseLocalCopy(
        type: TransferType,
        entity: FileEntity,
        localPath: String,
        contentHash: String?
    ) {
        if (fileImporter.isStaged(localPath)) {
            File(localPath).delete()
            fileDao.setLocalPath(entity.id, null)
            return
        }
        if (type != TransferType.BACKUP) return
        if (!settingsRepository.preferences.first().deleteAfterUpload) return
        if (fileDao.isKeptOffline(entity.id)) return

        val source = File(localPath)
        if (!source.isFile || source.length() != entity.sizeBytes) return
        if (source.length() <= EAGER_HASH_LIMIT &&
            (contentHash == null || Hashing.sha256(source) != contentHash)
        ) {
            return
        }
        val cleanup = localCopyDeleter.delete(listOf(localPath))
        if (cleanup.deletedCount > 0) fileDao.setLocalPath(entity.id, null)
    }

    private suspend fun checkControl(transferId: String) {
        when (transferDao.byId(transferId)?.state) {
            TransferState.RUNNING -> Unit
            TransferState.PAUSED,
            TransferState.QUEUED -> throw TransferControlException(paused = true)
            TransferState.CANCELLED,
            TransferState.COMPLETED,
            TransferState.FAILED,
            null -> throw TransferControlException(paused = false)
        }
    }

    private fun stagingDir(): File =
        File(storagePaths.cacheDir, "staging").apply { mkdirs() }

    companion object {
        private const val EAGER_HASH_LIMIT = 512L * 1024 * 1024
        private const val SPACE_MARGIN_BYTES = 256L * 1024 * 1024
        private const val CONTROL_POLL_MS = 250L
        private const val STALL_TIMEOUT_MS = 180_000L
        private const val STALL_CODE = 408
    }
}
