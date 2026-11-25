package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

class TelegramMediaByteSource(
    private val telegramClient: TelegramClient,
    private val remoteFileId: String,
    private val chatId: Long? = null,
    private val messageId: Long? = null,
    /**
     * When true (the media-streaming default), resolving an undownloaded file primes a
     * download from byte 0. Range-only consumers (the zip archive reader) pass false: their
     * first ranged [read] reaches `awaitAvailable()`, which requests exactly the window it
     * needs, so no whole-file download from offset 0 is ever started on their behalf.
     */
    private val primeFromStart: Boolean = true
) : MediaByteSource {

    private var fileId: Int = 0
    private var totalSize: Long = -1

    override suspend fun size(): Long {
        resolve()
        return totalSize
    }

    /**
     * Returns EXACTLY the requested bytes (clamped at end-of-file), or throws.
     *
     * 1.6.22 hardening (report 51): the previous implementation accepted ANY non-empty
     * `readFilePart` result as the requested range, without ever checking the client's
     * own coverage report. Documented TDLib errors on an uncovered range, so that was
     * harmless there — but a client whose ReadFilePart instead returns whatever part of
     * the download window it happens to hold (short, or bytes belonging to a different
     * offset) silently poisoned every archive extraction while listings, whose reads
     * happen to be the window itself, kept working. So now:
     * - a fast-path read is attempted ONLY when [covers] proves the full range is local;
     * - every accepted read is length-verified against the request;
     * - a short/empty verified read re-awaits coverage and re-reads, bounded by
     *   [MAX_READ_ATTEMPTS], then fails with a typed [TelegramException] — never with
     *   silently wrong or short bytes, and never with a bare timeout cancellation.
     */
    override suspend fun read(position: Long, count: Int): ByteArray {
        resolve()
        val toRead = minOf(count.toLong(), totalSize - position)
        if (toRead <= 0) return ByteArray(0)
        try {
            var attempt = 0
            while (true) {
                attempt++
                val info = telegramClient.getFileInfo(fileId)
                if (covers(info, position, toRead)) {
                    val part = telegramClient.readFilePart(fileId, position, toRead)
                    if (part.size.toLong() == toRead) return part
                    runCatching {
                        SafeLog.w(
                            TAG,
                            "readFilePart returned ${part.size} of $toRead bytes at $position " +
                                "despite reported coverage (attempt $attempt)"
                        )
                    }
                }
                if (attempt >= MAX_READ_ATTEMPTS) {
                    throw TelegramException(
                        INCOMPLETE_READ_CODE,
                        "Telegram returned incomplete data at offset $position " +
                            "($toRead bytes requested)"
                    )
                }
                awaitAvailable(position, toRead, forceRequest = attempt > 1)
            }
        } catch (failure: TimeoutCancellationException) {
            // Our own download-window deadline expired. That is a FAILURE, not a user
            // cancellation: rethrowing the TimeoutCancellationException would make every
            // caller treat it as a stop (archive rows stuck/misreported, previews
            // silently swallowed). A genuine external cancellation arrives as a plain
            // CancellationException from a cancelled job and is not caught here.
            if (!currentCoroutineContext().isActive) throw failure
            runCatching {
                SafeLog.w(TAG, "Timed out waiting for Telegram data at $position ($toRead bytes)")
            }
            throw TelegramException(
                BUFFER_TIMEOUT_CODE,
                "Telegram did not deliver the requested data in time (offset $position)"
            )
        }
    }

    private suspend fun resolve() {
        if (totalSize >= 0) return
        val info = telegramClient.resolveFile(remoteFileId, chatId, messageId)
        fileId = info.fileId
        totalSize = info.sizeBytes
        SafeLog.d(TAG, "resolve fileId=$fileId size=$totalSize completed=${info.isDownloadingCompleted}")
        if (primeFromStart && !info.isDownloadingCompleted) {
            telegramClient.requestFileRange(fileId, 0, 0)
        }
    }

    /**
     * Waits until [covers] reports the full range local, re-requesting the window
     * whenever waiting on the current one cannot reach the range:
     * - no download is active (it finished, was cancelled, or never started), or
     * - the range starts BEFORE the active window (that window grows away from it), or
     * - [forceRequest] after a verified read came back short/empty anyway.
     *
     * The re-request matters for clients that ignore a DownloadFile re-target while a
     * window is active: once that window finishes, the next loop pass requests again
     * and is heard. Throws [TimeoutCancellationException] at the overall deadline;
     * [read] converts that into a typed failure.
     */
    private suspend fun awaitAvailable(readPosition: Long, count: Long, forceRequest: Boolean = false) {
        var forced = forceRequest
        withTimeout(BUFFER_TIMEOUT_MS.milliseconds) {
            while (true) {
                val info = telegramClient.getFileInfo(fileId)
                if (covers(info, readPosition, count)) return@withTimeout
                val windowCannotReach = !info.isDownloadingActive && !info.isDownloadingCompleted ||
                    readPosition < info.downloadOffset
                if (forced || windowCannotReach) {
                    SafeLog.d(
                        TAG,
                        "requestFileRange offset=$readPosition " +
                            "(window=${info.downloadOffset}+${info.downloadedPrefixSize} active=${info.isDownloadingActive})"
                    )
                    telegramClient.requestFileRange(fileId, readPosition, 0)
                    forced = false
                }
                try {
                    withTimeoutOrNull(RECHECK_INTERVAL_MS.milliseconds) {
                        telegramClient.fileUpdates(fileId).first { covers(it, readPosition, count) }
                    }
                } catch (ignored: NoSuchElementException) {
                    // The update flow completed without a covering update (some
                    // clients expose a finite flow): fall through to the next
                    // getFileInfo recheck instead of failing the read.
                }
            }
        }
    }

    private fun covers(info: TelegramFileInfo, readPosition: Long, count: Long): Boolean {
        if (info.isDownloadingCompleted) return true
        val start = info.downloadOffset
        val end = start + info.downloadedPrefixSize
        return readPosition >= start && readPosition + count <= end
    }

    override fun close() {
        if (fileId != 0) {
            runCatching { runBlocking { telegramClient.cancelFileDownload(fileId) } }
        }
    }

    private companion object {
        const val TAG = "TelegramMediaByteSource"
        const val BUFFER_TIMEOUT_MS = 30_000L
        const val RECHECK_INTERVAL_MS = 500L
        const val MAX_READ_ATTEMPTS = 3
        const val BUFFER_TIMEOUT_CODE = 408
        const val INCOMPLETE_READ_CODE = 400
    }
}
