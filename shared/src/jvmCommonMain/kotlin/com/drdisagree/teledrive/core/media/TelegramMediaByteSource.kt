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
    private val primeFromStart: Boolean = true,
    /**
     * The range-only read deadline in milliseconds (production default
     * [BUFFER_TIMEOUT_MS]). A test seam so the harness can run the deadline paths in
     * seconds instead of 30 s; the value carries no behaviour of its own.
     */
    private val bufferTimeoutMs: Long = BUFFER_TIMEOUT_MS
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
     * window. So the range-only loop tries the read first, and only a coverage-class
     * 400 (see [readRangeOnly]'s isCoverageFailure: "not enough downloaded bytes", or
     * "File is not inside the cache" since 1.6.24) triggers a DownloadFile for exactly
     * this range.
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
     * Range-only read loop (1.6.23, deadline/window rules revised 1.6.24): driven by
     * ReadFilePart's own all-or-nothing gate.
     *
     * 1. Try `readFilePart(position, toRead)` directly — bytes already in the partial file
     *    from ANY earlier window (e.g. the listing's tail window) are readable with no new
     *    DownloadFile, even after the window was re-targeted elsewhere.
     * 2. On a coverage-class failure (see [isCoverageFailure]), ensure a DownloadFile for
     *    exactly [position, position+toRead) is outstanding (clamped at EOF by the
     *    caller's `toRead`), then poll `getFileInfo` / `fileUpdates` at a non-spinning
     *    interval and retry the read when the info suggests the gate could pass, when
     *    the window has finished, or periodically regardless (getFileInfo can lag the
     *    bitmask).
     * 3. If the window finished and the read still fails, re-issue the DownloadFile —
     *    bounded by [MAX_DOWNLOAD_ATTEMPTS] — then fail with full state detail.
     *
     * Other TelegramExceptions are real failures and propagate untouched.
     *
     * 1.6.24 changes, from Pranto's 1.6.23 field retest (report 54):
     * - The deadline measures NO-PROGRESS time, not total time. The field failure was a
     *   window active at the requested offset with 458,470 of 524,288 bytes landed when
     *   the absolute 30 s deadline killed the read; a window that keeps landing parts
     *   now keeps the read alive, and only [bufferTimeoutMs] without any advance of the
     *   contiguous coverage toward this range produces the typed 408 (still carrying
     *   the last observed state and the last ReadFilePart error).
     * - An ACTIVE window whose offset is not this read's position is a FOREIGN window
     *   (another issuer — e.g. a Transfers download of the same file — re-targeted the
     *   single per-file window, findings §1b). It can never satisfy this read's gate,
     *   so the read re-issues its own DownloadFile instead of waiting on it. An active
     *   window at exactly this read's position is ours and is never re-issued while it
     *   runs (the report-52 second-pass rule, now keyed on the offset as well).
     *
     * 1.6.25 change (English.zip, report 55): a node can report FULLY downloaded
     * (isDownloadingCompleted, prefix = size) while every ReadFilePart still fails
     * with the §2(a).6 "File is not inside the cache" 400 — the Full local path
     * fails TDLib's cache-dir check, or no readable file exists at it. DownloadFile
     * cannot fix that state (on a completed file it is a no-op), so when the fresh
     * poll shows completed + this exact 400, the loop recovers once per read from
     * the node's own local path: read the range directly from it when the file
     * there is verified (regular file, length exactly the resolved size), or
     * DeleteFile the stale node when no file exists there and let the normal
     * DownloadFile path genuinely re-download. An existing file of a different
     * length is neither read nor deleted. This branch lives in the shared byte
     * source, so Android takes it too: official TDLib implements the identical
     * read_file_part gate (report 52 §4/§6), and both remedies are equally
     * correct under those semantics — a verified-length local read of a
     * completed file, and a cache reset only when the file is demonstrably gone.
     */
    private suspend fun readRangeOnly(position: Long, toRead: Long): ByteArray {
        var lastInfo: TelegramFileInfo? = null
        var lastReadError: TelegramException? = null
        var downloadAttempts = 0
        var needRequest = true
        var shortReads = 0
        // 1.6.25: the completed-outside-cache recovery (see the block after the
        // getFileInfo poll below) runs at most once per read.
        var localRecoveryDone = false
        // No-progress clock: [bestCoverageEnd] is the furthest contiguous coverage
        // toward [position, position+toRead) observed so far; the 408 fires only when
        // it has not advanced for [bufferTimeoutMs].
        var bestCoverageEnd = position
        var lastProgressAtMs = System.currentTimeMillis()

        fun noteProgress(info: TelegramFileInfo) {
            val end = when {
                info.isDownloadingCompleted -> position + toRead
                info.downloadOffset <= position ->
                    info.downloadOffset + info.downloadedPrefixSize
                else -> position
            }
            if (end > bestCoverageEnd) {
                bestCoverageEnd = end
                lastProgressAtMs = System.currentTimeMillis()
            }
        }

        while (true) {
            if (System.currentTimeMillis() - lastProgressAtMs > bufferTimeoutMs) {
                runCatching {
                    SafeLog.w(TAG, "No progress waiting for Telegram data at $position ($toRead bytes)")
                }
                throw TelegramException(
                    BUFFER_TIMEOUT_CODE,
                    "Telegram did not deliver the requested data in time " +
                        "(offset $position, $toRead bytes requested): " +
                        stateText(lastInfo, lastReadError)
                )
            }
            try {
                val part = telegramClient.readFilePart(fileId, position, toRead)
                if (part.size.toLong() == toRead) return part
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
            noteProgress(info)
            // 1.6.25 recovery — the completed-but-not-inside-the-cache node
            // (English.zip, report 55). The fresh poll says the file is FULLY
            // downloaded (isDownloadingCompleted, prefix = size), yet
            // ReadFilePart rejects every range with the §2(a).6 400: the Full
            // local path fails TDLib's cache-dir check, or no readable file
            // exists at it. A DownloadFile on a completed file is a no-op — it
            // neither relocates nor re-fetches it — so the ordinary
            // coverage recovery below would burn all attempts and give up
            // exactly as the field showed. Instead, once per read:
            // - If the reported local path holds a file whose length is
            //   EXACTLY the resolved size, it demonstrably IS this file: read
            //   the range from it directly (the same verification
            //   DesktopTelegramClient.readyPath applies to completed paths;
            //   the zip layer's own length/CRC checks still run on top).
            // - If the path names no file at all (stale Full node — TDLib
            //   keeps reporting completed after the cache file is removed),
            //   or no path is reported, DeleteFile drops the stale local
            //   location and the loop's normal DownloadFile then genuinely
            //   re-downloads the range into the cache.
            // - If a file exists at the path but its length differs, it is
            //   NOT trusted and NOT deleted (it may be the user's own file,
            //   not a cache copy): the read fails through the normal path,
            //   and stateText now carries the local-path state that says why.
            if (!localRecoveryDone && info.isDownloadingCompleted &&
                isNotInsideCacheFailure(lastReadError)
            ) {
                localRecoveryDone = true
                val localFile = info.localPath?.let { java.io.File(it) }
                when {
                    localFile != null && localFile.isFile &&
                        totalSize > 0 && localFile.length() == totalSize -> {
                        val direct = runCatching {
                            readLocalRange(localFile, position, toRead)
                        }.getOrNull()
                        if (direct != null && direct.size.toLong() == toRead) {
                            runCatching {
                                SafeLog.d(
                                    TAG,
                                    "readFilePart rejected a completed file as not inside " +
                                        "the cache; read $toRead bytes at $position from " +
                                        "the verified local path ${info.localPath}"
                                )
                            }
                            return direct
                        }
                    }
                    localFile == null || !localFile.exists() -> {
                        runCatching {
                            SafeLog.w(
                                TAG,
                                "Completed file has no readable local file " +
                                    "(path=${info.localPath}); deleting the stale cache " +
                                    "entry so the range can be re-downloaded"
                            )
                        }
                        // Not runCatching: a genuine cancellation must propagate,
                        // not be swallowed as a failed cache reset.
                        try {
                            telegramClient.deleteLocalCopy(remoteFileId)
                        } catch (ignored: TelegramException) {
                            // The reset is best-effort: if DeleteFile itself is
                            // refused, the loop continues and the give-up error
                            // below still carries the local-path state.
                        }
                        needRequest = true
                    }
                    else -> runCatching {
                        SafeLog.w(
                            TAG,
                            "Completed file's local path ${info.localPath} holds a file " +
                                "of length ${localFile?.length()} != $totalSize; " +
                                "not reading or deleting it"
                        )
                    }
                }
            }
            // Second pass (report 52): the catch above may have set needRequest from
            // the STALE pre-request snapshot in lastInfo (active=false was the reason
            // the request was issued). The freshly polled info is authoritative: OUR
            // window — active at exactly this read's offset — is already outstanding,
            // so no re-issue is needed. 1.6.24: an active window at any OTHER offset
            // is foreign (see the KDoc above) and must not clear the request.
            val gateHint = gateCouldPass(info, position, toRead)
            val windowIsOurs = info.downloadOffset == position
            if (info.isDownloadingActive && windowIsOurs) needRequest = false
            val foreignWindow = info.isDownloadingActive && !windowIsOurs && !gateHint
            if (needRequest || foreignWindow || (!info.isDownloadingActive && !gateHint)) {
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
                        noteProgress(update)
                        gateCouldPass(update, position, toRead) ||
                            !update.isDownloadingActive
                    }
                }
            } catch (ignored: NoSuchElementException) {
                delay(RECHECK_INTERVAL_MS.milliseconds)
            }
        }
    }

    /**
     * The ReadFilePart coverage-class failures from the TDLib source (code 400): the
     * requested bytes are not readable YET and a DownloadFile for the range is the
     * remedy — as opposed to a real failure, which propagates.
     * - "not enough downloaded bytes": the contiguous-parts gate (findings §2b).
     * - "File is not inside the cache" (1.6.24, Failure A): the file node has NO
     *   readable local path at all (findings §2(a).6) — e.g. the partial was evicted
     *   between the archive listing and the click, or the node was re-resolved fresh.
     *   Pre-fix this propagated fatally from the extract's first read, before any
     *   DownloadFile was ever issued; a DownloadFile re-establishes a partial file,
     *   after which the read is retried like any other coverage miss.
     */
    private fun isCoverageFailure(failure: TelegramException): Boolean =
        failure.code == 400 &&
            (failure.message.contains("not enough downloaded bytes", ignoreCase = true) ||
                failure.message.contains("File is not inside the cache", ignoreCase = true))

    /** The §2(a).6 sibling 400 alone: no readable local path for the node's location. */
    private fun isNotInsideCacheFailure(failure: TelegramException?): Boolean =
        failure != null && failure.code == 400 &&
            failure.message.contains("File is not inside the cache", ignoreCase = true)

    /**
     * Positional read of [count] bytes at [position] from a local file already verified
     * (by the caller) to be a regular file of exactly the resolved size. Used only for
     * the 1.6.25 completed-outside-cache recovery; the returned array is length-checked
     * by the caller before it is handed back.
     */
    private fun readLocalRange(localFile: java.io.File, position: Long, count: Long): ByteArray {
        java.io.RandomAccessFile(localFile, "r").use { raf ->
            raf.seek(position)
            val buffer = ByteArray(count.toInt())
            raf.readFully(buffer)
            return buffer
        }
    }

    /** Hint only: whether [info] suggests ReadFilePart's gate could pass for the range. */
    private fun gateCouldPass(info: TelegramFileInfo, readPosition: Long, count: Long): Boolean {
        if (info.isDownloadingCompleted) return true
        return info.downloadOffset <= readPosition &&
            info.downloadOffset + info.downloadedPrefixSize >= readPosition + count
    }

    private fun stateText(info: TelegramFileInfo?, lastError: TelegramException?): String {
        // 1.6.25: the local-path state is part of the failure contract — for the
        // not-inside-the-cache class it is the detail that explains the failure
        // (a path outside the cache dir, a vanished file, or no path at all),
        // and the 1.6.24 field message could not distinguish those without it.
        val localPath = info?.localPath
        val localFileState = localPath?.let { path ->
            runCatching {
                val file = java.io.File(path)
                "exists=${file.isFile} length=${if (file.isFile) file.length() else -1}"
            }.getOrDefault("unknown")
        }
        return "downloadOffset=${info?.downloadOffset} " +
            "downloadedPrefixSize=${info?.downloadedPrefixSize} " +
            "isDownloadingActive=${info?.isDownloadingActive} " +
            "isDownloadingCompleted=${info?.isDownloadingCompleted} " +
            "localPath=$localPath localFile=$localFileState " +
            "lastReadError=${lastError?.let { "${it.code}: ${it.message}" }}"
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
