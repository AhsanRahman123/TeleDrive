package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.KeyUnavailableException
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.security.DigestOutputStream
import java.security.MessageDigest
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import kotlinx.coroutines.withContext

/**
 * The partly built file is kept between attempts, so a download resumes from the part it reached.
 */
class PartDownloader(
    private val telegramClient: TelegramClient,
    private val filePartDao: FilePartDao,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val dispatchers: DispatcherProvider
) {

    fun download(
        fileId: String,
        encrypted: Boolean,
        target: PartialDownload
    ): Flow<PartDownloadEvent> = flow {
        val parts = filePartDao.partsOf(fileId).sortedBy { it.partIndex }
        if (parts.isEmpty()) error("File has no parts on record")

        val resumeFrom = withContext(dispatchers.io) { resumePoint(target, parts) }
        var written = resumeFrom.second
        emit(PartDownloadEvent.Progress(written))

        for (part in parts.drop(resumeFrom.first)) {
            val remoteFileId = part.remoteFileId ?: error("Part ${part.partIndex + 1} is missing")
            var downloaded: String? = null
            telegramClient.downloadDocument(remoteFileId).collect { event ->
                when (event) {
                    is TelegramDownloadEvent.Progress ->
                        emit(PartDownloadEvent.Progress(written + event.transferredBytes.coerceAtMost(part.plainSize)))

                    is TelegramDownloadEvent.Completed -> downloaded = event.localPath
                }
            }

            val source = downloaded?.let(::File)?.takeIf { it.exists() }
                ?: error("Part ${part.partIndex + 1} did not download")

            emit(PartDownloadEvent.Joining(part.partIndex))
            val actualHash = try {
                withContext(dispatchers.io) {
                    appendPart(source, target, encrypted).also {
                        if (target.length() != written + part.plainSize) {
                            target.truncate(written)
                            error("Part ${part.partIndex + 1} was not written completely")
                        }
                    }
                }
            } finally {
                runCatching { telegramClient.deleteLocalCopy(remoteFileId) }
                    .onFailure {
                        SafeLog.w(TAG, "Could not free part ${part.partIndex + 1}", it)
                    }
            }

            if (part.plainHash != null && actualHash != part.plainHash) {
                withContext(dispatchers.io) { target.truncate(written) }
                emit(PartDownloadEvent.IntegrityFailure(part.partIndex))
                return@flow
            }

            written += part.plainSize
            emit(PartDownloadEvent.Progress(written))
        }

        emit(PartDownloadEvent.Completed(target.token))
    }

    private fun appendPart(source: File, target: PartialDownload, encrypted: Boolean): String {
        val digest = MessageDigest.getInstance("SHA-256")
        target.append { output ->
            val buffered = DigestOutputStream(output, digest).buffered()
            if (encrypted) {
                val key = wrappedKeyRepository.get(CryptoKeys.CONTENT)
                    ?: throw KeyUnavailableException(CryptoKeys.CONTENT)
                source.inputStream().buffered().use { input ->
                    streamCrypto.decryptStream(key, input, buffered)
                }
            } else {
                source.inputStream().buffered().use { input -> input.copyTo(buffered) }
            }
            buffered.flush()
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * A resumed prefix is trusted only after each stored part hash matches. Legacy parts without
     * hashes retain the older whole-file verification at the end of the download.
     */
    private fun resumePoint(target: PartialDownload, parts: List<FilePartEntity>): Pair<Int, Long> {
        val length = target.length()
        if (length == 0L) return 0 to 0L

        var covered = 0L
        var index = 0
        target.read().use { input ->
            val buffer = ByteArray(HASH_BUFFER_SIZE)
            for (part in parts) {
                if (covered + part.plainSize > length) break
                val digest = if (part.plainHash != null) {
                    MessageDigest.getInstance("SHA-256")
                } else {
                    null
                }
                var remaining = part.plainSize
                var complete = true
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read <= 0) {
                        complete = false
                        break
                    }
                    digest?.update(buffer, 0, read)
                    remaining -= read
                }
                if (!complete) break
                val actualHash = digest?.digest()?.joinToString("") { "%02x".format(it) }
                if (part.plainHash != null && actualHash != part.plainHash) break
                covered += part.plainSize
                index++
            }
        }

        if (covered != length) target.truncate(covered)
        return index to covered
    }

    private companion object {
        const val TAG = "PartDownloader"
        const val HASH_BUFFER_SIZE = 64 * 1024
    }
}
