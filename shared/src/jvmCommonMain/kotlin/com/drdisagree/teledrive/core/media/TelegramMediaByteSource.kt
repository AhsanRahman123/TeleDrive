package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class TelegramMediaByteSource(
    private val telegramClient: TelegramClient,
    private val remoteFileId: String,
    private val chatId: Long? = null,
    private val messageId: Long? = null,
    /**
     * Streaming callers prime an unbounded download from byte 0 on first use. Ranged readers
     * (the ZIP archive service) must NOT: they pass false and let [awaitAvailable] request the
     * exact offset each read needs, so no whole-file download is ever started for them.
     */
    private val primeFromStart: Boolean = true
) : MediaByteSource {

    private var fileId: Int = 0
    private var totalSize: Long = -1

    /**
     * Set once TDLib's "downloading completed" claim for this file has proven false
     * (vc181 A3): a just-uploaded file keeps a Full local reference to the in-place
     * original the app deleted after the upload, so the claim — and the prefix fields
     * reported with it — can never serve a read. From then on only a verified local
     * copy or a real partial-download state counts as coverage.
     */
    private var completedClaimBroken: Boolean = false

    /** The stale-state eviction (DeleteFile via the client) is a one-time repair. */
    private var evictionAttempted: Boolean = false

    override suspend fun size(): Long {
        resolve()
        return totalSize
    }

    override suspend fun read(position: Long, count: Int): ByteArray {
        resolve()
        val toRead = minOf(count.toLong(), totalSize - position)
        if (toRead <= 0) return ByteArray(0)
        readDownloaded(fileId, position, toRead)?.let { return it }
        awaitAvailable(position, toRead)
        return try {
            telegramClient.readFilePart(fileId, position, toRead)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            // The state TDLib reported did not yield bytes (stale completed claim, or
            // coverage reported over an unreadable local reference): evict the stale
            // download state like downloadDocument() does, force the ranged download
            // for exactly this range through awaitAvailable, and read once more. A
            // second failure is real and propagates unchanged.
            evictStaleCompleted()
            awaitAvailable(position, toRead)
            telegramClient.readFilePart(fileId, position, toRead)
        }
    }

    private suspend fun readDownloaded(
        fileId: Int,
        position: Long,
        count: Long
    ): ByteArray? = runCatching {
        telegramClient.readFilePart(fileId, position, count).takeIf { it.isNotEmpty() }
    }.getOrNull()

    private suspend fun resolve() {
        if (totalSize >= 0) return
        val info = telegramClient.resolveFile(remoteFileId, chatId, messageId)
        fileId = info.fileId
        totalSize = info.sizeBytes
        if (!info.isDownloadingCompleted && primeFromStart) {
            telegramClient.requestFileRange(fileId, 0, 0)
        }
    }

    private suspend fun awaitAvailable(readPosition: Long, count: Long) {
        var info = telegramClient.getFileInfo(fileId)
        if (info.isDownloadingCompleted && !completedClaimBroken && !localCopyVerified(info)) {
            // Claimed completed, but the claimed local copy is not on disk at the
            // claimed size — the readyPath() check every other consumer applies.
            // The claim is the stale upload-origin reference: evict it and fall
            // through to a real ranged download instead of trusting covers().
            evictStaleCompleted()
            info = telegramClient.getFileInfo(fileId)
        }
        if (covers(info, readPosition, count)) return

        val downloading = info.isDownloadingCompleted || info.isDownloadingActive
        if (completedClaimBroken ||
            !downloading ||
            readPosition < info.downloadOffset ||
            readPosition > info.downloadOffset + info.downloadedPrefixSize
        ) {
            telegramClient.requestFileRange(fileId, readPosition, 0)
        }
        withTimeout(BUFFER_TIMEOUT_MS.milliseconds) {
            while (!covers(telegramClient.getFileInfo(fileId), readPosition, count)) {
                withTimeoutOrNull(RECHECK_INTERVAL_MS.milliseconds) {
                    telegramClient.fileUpdates(fileId).first { covers(it, readPosition, count) }
                }
            }
        }
    }

    private fun covers(info: TelegramFileInfo, readPosition: Long, count: Long): Boolean {
        if (info.isDownloadingCompleted) {
            // Once the completed claim has proven false for this file, the prefix
            // fields reported alongside it are equally untrustworthy: only a local
            // copy verified on disk counts, never the claim itself.
            return !completedClaimBroken || localCopyVerified(info)
        }
        val start = info.downloadOffset
        val end = start + info.downloadedPrefixSize
        return readPosition >= start && readPosition + count <= end
    }

    /**
     * readyPath()-style verification of a claimed local copy: the path must exist as
     * a file at exactly the claimed size. TDLib keeps reporting a completed copy
     * after its backing file is removed, so the bytes must still be on disk.
     */
    private fun localCopyVerified(info: TelegramFileInfo): Boolean {
        val path = info.localPath?.takeIf { it.isNotBlank() } ?: return false
        val candidate = File(path)
        if (!candidate.isFile) return false
        return info.sizeBytes <= 0 || candidate.length() == info.sizeBytes
    }

    /**
     * Evicts the stale download state (the client's DeleteFile, the same eviction
     * downloadDocument() applies to a claimed-completed file before downloading) and
     * marks the completed claim broken for this source. Best-effort and one-time:
     * the ranged request in awaitAvailable is the actual repair, so an eviction
     * failure must not mask it.
     */
    private suspend fun evictStaleCompleted() {
        completedClaimBroken = true
        if (evictionAttempted) return
        evictionAttempted = true
        runCatching { telegramClient.deleteLocalCopy(remoteFileId) }
    }

    override fun close() {
        if (fileId != 0) {
            runCatching { runBlocking { telegramClient.cancelFileDownload(fileId) } }
        }
    }

    private companion object {
        const val BUFFER_TIMEOUT_MS = 30_000L
        const val RECHECK_INTERVAL_MS = 500L
    }
}
