package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DiscardingOutputStream
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
        val partCount = FileParts.countFor(totalSize)
        val done = filePartDao.partsOf(entity.id)
            .filter { it.messageId != null }
            .associateBy { it.partIndex }
            .toMutableMap()

        var uploadedBefore = done.values.sumOf { it.plainSize }
        emit(PartUploadEvent.Progress(uploadedBefore))

        val hasher = if (done.isEmpty()) HashAccumulator() else null

        for (index in 0 until partCount) {
            if (done.containsKey(index)) continue

            val plainOffset = FileParts.offsetOf(index)
            val plainSize = FileParts.sizeOf(index, totalSize)
            val scratch = File(scratchDir(), "${entity.id}.${index}.part")

            try {
                if (encrypt) emit(PartUploadEvent.Sealing(index))
                val partHasher = HashAccumulator()
                withContext(dispatchers.io) {
                    writePart(source, plainOffset, plainSize, scratch, encrypt, hasher, partHasher)
                }
                val partHash = partHasher.result() ?: error("Could not calculate part hash")
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
                    iconFileId = if (index == 0) iconFileId ?: manifest.iconFileId else null,
                    partHash = partHash
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

        if (source.length() != totalSize) throw PartSourceChangedException()
        val completedParts = done.values.sortedBy { it.partIndex }
        val contentHash = hasher?.result() ?: withContext(dispatchers.io) {
            hashResumedSource(source, completedParts, partCount, manifest.contentHash)
        }
        emit(PartUploadEvent.Completed(completedParts, contentHash))
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
        hasher: HashAccumulator?,
        partHasher: HashAccumulator
    ) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            skipExactly(input, plainOffset)
            val ranged = RangeInputStream(input, plainSize)
            val partHashed = partHasher.wrap(ranged)
            val hashed = hasher?.wrap(partHashed) ?: partHashed
            target.outputStream().buffered().use { output ->
                if (encrypt) {
                    val key = wrappedKeyRepository.getOrCreate(CryptoKeys.CONTENT)
                    streamCrypto.encryptStream(key, hashed, output)
                } else {
                    hashed.copyTo(output)
                }
            }
            if (ranged.bytesRead != plainSize) throw PartSourceChangedException()
        }
    }

    private fun skipExactly(input: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) throw PartSourceChangedException()
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun hashResumedSource(
        source: File,
        parts: List<FilePartEntity>,
        expectedPartCount: Int,
        expectedContentHash: String?
    ): String? {
        if (parts.size != expectedPartCount) throw PartSourceChangedException()
        val checkWholeHash = expectedContentHash != null || parts.all { it.plainHash != null }
        if (!checkWholeHash && parts.none { it.plainHash != null }) return null

        val wholeHasher = if (checkWholeHash) HashAccumulator() else null
        val totalSize = source.length()
        var offset = 0L
        source.inputStream().use { input ->
            parts.sortedBy { it.partIndex }.forEachIndexed { index, part ->
                val expectedSize = FileParts.sizeOf(index, totalSize)
                if (part.partIndex != index || part.plainOffset != offset ||
                    part.plainSize != expectedSize
                ) throw PartSourceChangedException()

                val range = RangeInputStream(input, expectedSize)
                val wholeInput = wholeHasher?.wrap(range) ?: range
                val partHasher = part.plainHash?.let { HashAccumulator() }
                val hashedInput = partHasher?.wrap(wholeInput) ?: wholeInput
                val copied = hashedInput.copyTo(DiscardingOutputStream)
                if (copied != expectedSize ||
                    (part.plainHash != null && partHasher?.result() != part.plainHash)
                ) throw PartSourceChangedException()
                offset += copied
            }
        }
        if (offset != totalSize) throw PartSourceChangedException()
        val actualWholeHash = wholeHasher?.result()
        if (expectedContentHash != null && actualWholeHash != expectedContentHash) {
            throw PartSourceChangedException()
        }
        return actualWholeHash
    }

    private fun scratchDir(): File = File(storagePaths.cacheDir, SCRATCH_DIR).apply { mkdirs() }

    private companion object {
        const val TAG = "PartUploader"
        const val SCRATCH_DIR = "parts"
        const val OCTET_STREAM = "application/octet-stream"
    }
}

internal class PartSourceChangedException : IllegalStateException("Source file does not match the previously uploaded parts")
