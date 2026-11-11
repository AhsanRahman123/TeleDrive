package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.HashAccumulator
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext

/**
 * Each part is sent and deleted before the next is built, so encryption never needs room for a
 * second copy, and recorded parts are skipped, so an interrupted upload resumes.
 */
class PartUploader(
    private val storagePaths: AppStoragePaths,
    private val telegramClient: TelegramClient,
    private val filePartDao: FilePartDao,
    private val manifestCodec: ManifestCodec,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val thumbnailStore: ThumbnailStore,
    private val dispatchers: DispatcherProvider,
    private val apkIconUploader: ApkIconUploader
) {

    fun upload(
        entity: FileEntity,
        source: File,
        chatId: Long,
        manifest: RemoteFileManifest,
        encrypt: Boolean,
        thumbnailPath: String? = null
    ): Flow<PartUploadEvent> = flow {
        val totalSize = source.length()
        val sourceModifiedAt = source.lastModified()
        val partCount = FileParts.countFor(totalSize)
        val done = filePartDao.partsOf(entity.id)
            .filter { it.messageId != null }
            .associateBy { it.partIndex }
            .toMutableMap()

        var uploadedBefore = done.values.sumOf { it.plainSize }
        emit(PartUploadEvent.Progress(uploadedBefore))

        val hasher = HashAccumulator()
        if (done.keys.any { it !in 0 until partCount }) {
            emit(PartUploadEvent.SourceChanged(null))
            return@flow
        }

        for (index in 0 until partCount) {
            val plainOffset = FileParts.offsetOf(index)
            val plainSize = FileParts.sizeOf(index, totalSize)
            val existingPart = done[index]
            if (existingPart != null) {
                if (existingPart.plainOffset != plainOffset || existingPart.plainSize != plainSize) {
                    emit(PartUploadEvent.SourceChanged(index))
                    return@flow
                }
                val currentPartHash = withContext(dispatchers.io) {
                    hashRange(source, plainOffset, plainSize, hasher)
                }
                if (existingPart.plainHash != null && existingPart.plainHash != currentPartHash) {
                    emit(PartUploadEvent.SourceChanged(index))
                    return@flow
                }
                continue
            }

            val scratch = File(scratchDir(), "${entity.id}.${index}.part")

            try {
                if (encrypt) emit(PartUploadEvent.Sealing(index))
                val partHash = withContext(dispatchers.io) {
                    writePart(source, plainOffset, plainSize, scratch, encrypt, hasher)
                }
                if (encrypt) emit(PartUploadEvent.PartDone(index, partCount))

                val iconFileId = if (index == 0) {
                    apkIconUploader.uploadIconIfApk(entity, chatId, encrypt)
                } else null

                val partManifest = manifest.copy(
                    version = RemoteFileManifest.PART_VERSION,
                    partCount = partCount,
                    partIndex = index,
                    partOffset = plainOffset,
                    partSize = plainSize,
                    partHash = partHash,
                    iconFileId = if (index == 0) iconFileId ?: manifest.iconFileId else null
                )
                val partName = if (encrypt) {
                    FileParts.nameFor(entity.id, index)
                } else {
                    FileParts.nameFor(entity.name, index)
                }

                var stored: FilePartEntity? = null
                val alreadySent = uploadedBefore
                telegramClient.uploadDocument(
                    chatId = chatId,
                    localPath = scratch.absolutePath,
                    fileName = partName,
                    mimeType = if (encrypt) OCTET_STREAM else entity.mimeType,
                    caption = manifestCodec.encode(partManifest, encrypt),
                    thumbnailPath = if (index == 0) thumbnailPath else null
                ).collect { event ->
                    when (event) {
                        is TelegramUploadEvent.Started -> Unit
                        is TelegramUploadEvent.Progress -> {
                            val within = event.transferredBytes
                                .coerceAtMost(plainSize)
                            emit(PartUploadEvent.Progress(alreadySent + within))
                        }

                        is TelegramUploadEvent.Completed -> {
                            val document = event.document
                            stored = FilePartEntity(
                                fileId = entity.id,
                                partIndex = index,
                                chatId = document.chatId,
                                messageId = document.messageId,
                                remoteFileId = document.remoteFileId,
                                remoteUniqueId = document.uniqueFileId,
                                plainOffset = plainOffset,
                                plainSize = plainSize,
                                storedSize = scratch.length(),
                                uploadedAt = System.currentTimeMillis(),
                                plainHash = partHash
                            )
                        }
                    }
                }

                val part = stored ?: error("Part ${index + 1} did not finish")
                filePartDao.upsert(part)
                done[index] = part
                uploadedBefore += plainSize
                emit(PartUploadEvent.Progress(uploadedBefore))
                emit(PartUploadEvent.PartDone(index, partCount))
            } finally {
                withContext(NonCancellable + dispatchers.io) { scratch.delete() }
            }
        }

        val contentHash = hasher.result()
        if (
            source.length() != totalSize ||
            source.lastModified() != sourceModifiedAt ||
            (manifest.contentHash != null && contentHash != manifest.contentHash)
        ) {
            emit(PartUploadEvent.SourceChanged(null))
            return@flow
        }
        val safeContentHash = if (
            manifest.contentHash == null && done.values.any { it.plainHash == null }
        ) {
            // Older remote parts have no checksum to compare against; do not publish a hash
            // calculated only from the current local source as if it verified those messages.
            null
        } else {
            contentHash
        }
        emit(PartUploadEvent.Completed(done.values.sortedBy { it.partIndex }, safeContentHash))
    }

    suspend fun discardParts(fileId: String) {
        val parts = filePartDao.partsOf(fileId)
        for ((chatId, group) in parts.groupBy { it.chatId }) {
            if (chatId == null) continue
            val messageIds = group.mapNotNull { it.messageId }
            if (messageIds.isEmpty()) continue
            runCatching { telegramClient.deleteMessages(chatId, messageIds) }
                .onFailure { SafeLog.w(TAG, "Could not drop ${messageIds.size} orphan parts", it) }
        }
        filePartDao.deleteFor(listOf(fileId))
    }

    private fun writePart(
        source: File,
        plainOffset: Long,
        plainSize: Long,
        target: File,
        encrypt: Boolean,
        wholeHasher: HashAccumulator
    ): String {
        val partHasher = HashAccumulator()
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            skipExactly(input, plainOffset)
            val ranged = RangeInputStream(input, plainSize)
            val wholeHashed = wholeHasher.wrap(ranged)
            val hashed = partHasher.wrap(wholeHashed)
            target.outputStream().buffered().use { output ->
                if (encrypt) {
                    val key = wrappedKeyRepository.getOrCreate(CryptoKeys.CONTENT)
                    streamCrypto.encryptStream(key, hashed, output)
                } else {
                    hashed.copyTo(output)
                }
            }
        }
        return partHasher.result() ?: error("Could not calculate part checksum")
    }

    private fun hashRange(
        source: File,
        plainOffset: Long,
        plainSize: Long,
        wholeHasher: HashAccumulator
    ): String {
        val partHasher = HashAccumulator()
        source.inputStream().use { input ->
            skipExactly(input, plainOffset)
            val ranged = RangeInputStream(input, plainSize)
            val wholeHashed = wholeHasher.wrap(ranged)
            partHasher.wrap(wholeHashed).use { hashed ->
                hashed.copyTo(OutputStream.nullOutputStream())
            }
        }
        return partHasher.result() ?: error("Could not calculate completed part checksum")
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) error("Source file ended before the part range")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun scratchDir(): File = File(storagePaths.cacheDir, SCRATCH_DIR).apply { mkdirs() }

    private companion object {
        const val TAG = "PartUploader"
        const val SCRATCH_DIR = "parts"
        const val OCTET_STREAM = "application/octet-stream"
    }
}
