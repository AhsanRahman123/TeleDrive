package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
     * Streaming sources ([primeFromStart] = true) keep the 1.6.22 behaviour: a fast-path
     * read only when [covers] proves the full range local, length-verified, with
     * [awaitAvailable] driving the unbounded window.
     *
     * Range-only sources ([primeFromStart] = false, archives) use [readRangeOnly], which
     * is driven by ReadFilePart's own gate instead of the window proxy (1.6.23, report 52,
     * from the TDLight / upstream TDLib source findings): ReadFilePart succeeds iff the
     * parts are contiguous from the READ offset — bytes downloaded under any earlier
     * window count, even though `downloaded_prefix_size` only ever describes the current
     * window. So the range-only loop tries the read first, and only the specific
     * 400 "not enough downloaded bytes" coverage failure triggers a DownloadFile for
     * exactly this range.
     */
    override suspend fun read(position: Long, count: Int): ByteArray {
        resolve()
        val toRead = minOf(count.toLong(), totalSize - position)
        if (toRead <= 0) return ByteArray(0)
        return if (primeFromStart) readStreaming(position, toRead) else readRangeOnly(position, toRead)
    }

    /** The 1.6.22 streaming/range-shared read loop, kept behaviourally untouched for streaming. */
    private suspend fun readStreaming(position: Long, toRead: Long): ByteArray {
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

    /**
     * Range-only read loop (1.6.23): driven by ReadFilePart's own all-or-nothing gate.
     *
     * 1. Try `readFilePart(position, toRead)` directly — bytes already in the partial file
     *    from ANY earlier window (e.g. the listing's tail window) are readable with no new
     *    DownloadFile, even after the window was re-targeted elsewhere.
     * 2. On the specific coverage failure (400 + "not enough downloaded bytes"), ensure a
     *    DownloadFile for exactly [position, position+toRead) is outstanding (clamped at
     *    EOF by the caller's `toRead`), then poll `getFileInfo` / `fileUpdates` at a
     *    non-spinning interval and retry the read when the info suggests the gate could
     *    pass, when the window has finished, or periodically regardless (getFileInfo can
     *    lag the bitmask).
     * 3. If the window finished and the read still fails, re-issue the DownloadFile —
     *    bounded by [MAX_DOWNLOAD_ATTEMPTS] — then fail with full state detail.
     *
     * Other TelegramExceptions are real failures and propagate untouched. The overall
     * per-read deadline is [BUFFER_TIMEOUT_MS]; expiry becomes a typed
     * TelegramException(408) whose message carries the last observed state and the last
     * ReadFilePart error, so a field recurrence is diagnosable from the message alone.
     */
    private suspend fun readRangeOnly(position: Long, toRead: Long): ByteArray {
        var lastInfo: TelegramFileInfo? = null
        var lastReadError: TelegramException? = null
        try {
            return withTimeout(BUFFER_TIMEOUT_MS.milliseconds) {
                var downloadAttempts = 0
                var needRequest = true
                var shortReads = 0
                var result: ByteArray? = null
                while (result == null) {
                    try {
                        val part = telegramClient.readFilePart(fileId, position, toRead)
                        if (part.size.toLong() == toRead) {
                            result = part
                            break
                        }
                        // ReadFilePart is all-or-nothing per the source, so a short reply
                        // despite the gate is an anomaly: keep the length check as a guard,
                        // treat it like a coverage failure, and bound the retries.
                        shortReads++
                        lastReadError = TelegramException(
                            INCOMPLETE_READ_CODE,
                            "Telegram returned ${part.size} of $toRead bytes at offset $position"
                        )
                        if (shortReads >= MAX_READ_ATTEMPTS) {
                            throw TelegramException(
                                INCOMPLETE_READ_CODE,
                                "Telegram returned incomplete data at offset $position " +
                                    "($toRead bytes requested): ${stateText(lastInfo, lastReadError)}"
                            )
                        }
                        needRequest = true
                    } catch (failure: TelegramException) {
                        if (!isCoverageFailure(failure)) throw failure
                        lastReadError = failure
                        // If the previous poll already showed a finished window, the read
                        // failing again means that window did not deliver: re-issue.
                        if (lastInfo != null && !lastInfo!!.isDownloadingActive) needRequest = true
                    }
                    val info = telegramClient.getFileInfo(fileId)
                    lastInfo = info
                    // Second pass (report 52): the catch above may have set
                    // needRequest from the STALE pre-request snapshot in lastInfo
                    // (active=false was the reason the request was issued). The
                    // freshly polled info is authoritative: a window that is now
                    // active is already outstanding, so no re-issue is needed.
                    if (info.isDownloadingActive) needRequest = false
                    val gateHint = gateCouldPass(info, position, toRead)
                    if (needRequest || (!info.isDownloadingActive && !gateHint)) {
                        if (downloadAttempts >= MAX_DOWNLOAD_ATTEMPTS) {
                            throw TelegramException(
                                INCOMPLETE_READ_CODE,
                                "Telegram could not deliver the requested data at offset $position " +
                                    "($toRead bytes requested) after $downloadAttempts download " +
                                    "requests: ${stateText(lastInfo, lastReadError)}"
                            )
                        }
                        runCatching {
                            SafeLog.d(
                                TAG,
                                "requestFileRange offset=$position limit=$toRead " +
                                    "(window=${info.downloadOffset}+${info.downloadedPrefixSize} " +
                                    "active=${info.isDownloadingActive})"
                            )
                        }
                        telegramClient.requestFileRange(fileId, position, toRead)
                        downloadAttempts++
                        needRequest = false
                    }
                    // Wait, but never spin: retry the read when an update suggests the gate
                    // could pass or the window finished, and in any case after the slice.
                    try {
                        withTimeoutOrNull(RECHECK_INTERVAL_MS.milliseconds) {
                            telegramClient.fileUpdates(fileId).first { update ->
                                lastInfo = update
                                gateCouldPass(update, position, toRead) ||
                                    !update.isDownloadingActive
                            }
                        }
                    } catch (ignored: NoSuchElementException) {
                        delay(RECHECK_INTERVAL_MS.milliseconds)
                    }
                }
                result!!
            }
        } catch (failure: TimeoutCancellationException) {
            if (!currentCoroutineContext().isActive) throw failure
            runCatching {
                SafeLog.w(TAG, "Timed out waiting for Telegram data at $position ($toRead bytes)")
            }
            throw TelegramException(
                BUFFER_TIMEOUT_CODE,
                "Telegram did not deliver the requested data in time " +
                    "(offset $position, $toRead bytes requested): " +
                    stateText(lastInfo, lastReadError)
            )
        }
    }

    /** The exact ReadFilePart coverage failure from the TDLib source (code + message). */
    private fun isCoverageFailure(failure: TelegramException): Boolean =
        failure.code == 400 &&
            failure.message.contains("not enough downloaded bytes", ignoreCase = true)

    /** Hint only: whether [info] suggests ReadFilePart's gate could pass for the range. */
    private fun gateCouldPass(info: TelegramFileInfo, readPosition: Long, count: Long): Boolean {
        if (info.isDownloadingCompleted) return true
        return info.downloadOffset <= readPosition &&
            info.downloadOffset + info.downloadedPrefixSize >= readPosition + count
    }

    private fun stateText(info: TelegramFileInfo?, lastError: TelegramException?): String =
        "downloadOffset=${info?.downloadOffset} " +
            "downloadedPrefixSize=${info?.downloadedPrefixSize} " +
            "isDownloadingActive=${info?.isDownloadingActive} " +
            "isDownloadingCompleted=${info?.isDownloadingCompleted} " +
            "lastReadError=${lastError?.let { "${it.code}: ${it.message}" }}"

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
                    // Range-only consumers (archives) request exactly the window
                    // they need; streaming consumers keep the unbounded (to-EOF)
                    // window that gives them their prefetch behaviour.
                    telegramClient.requestFileRange(
                        fileId,
                        readPosition,
                        if (primeFromStart) 0 else count
                    )
                    forced = false
                }
                try {
                    withTimeoutOrNull(RECHECK_INTERVAL_MS.milliseconds) {
                        telegramClient.fileUpdates(fileId).first { covers(it, readPosition, count) }
                    }
                } catch (ignored: NoSuchElementException) {
                    // The update flow completed without a covering update (some
                    // clients expose a finite flow): fall through to the next
                    // getFileInfo recheck instead of failing the read. The delay
                    // keeps a finite flow from turning the wait into a busy spin.
                    delay(RECHECK_INTERVAL_MS.milliseconds)
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
        if (fileId == 0) return
        // Range-only sources do NOT cancel on close (1.6.23, report 52): re-targeting the
        // single download window is TDLib's designed path, bytes already in the partial
        // file persist across windows, and each requested window is bounded and
        // self-terminates at its limit. A leftover bounded window may therefore finish
        // downloading after the operation ends — that is intended, and the next operation
        // on this file can reuse those bytes via ReadFilePart's gate. Streaming sources
        // keep the existing cancel-on-close.
        if (!primeFromStart) return
        runCatching { runBlocking { telegramClient.cancelFileDownload(fileId) } }
    }

    private companion object {
        const val TAG = "TelegramMediaByteSource"
        const val BUFFER_TIMEOUT_MS = 30_000L
        const val RECHECK_INTERVAL_MS = 500L
        const val MAX_READ_ATTEMPTS = 3
        const val MAX_DOWNLOAD_ATTEMPTS = 3
        const val BUFFER_TIMEOUT_CODE = 408
        const val INCOMPLETE_READ_CODE = 400
    }
}
