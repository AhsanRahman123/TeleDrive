package com.drdisagree.teledrive.core.media

import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/** How the fake's download window grows after a DownloadFile request. */
enum class FakeGrowth { INSTANT, DELAYED, STALLED }

/** What ReadFilePart does for a range that is not fully covered by the download window. */
enum class FakeReadVariant {
    /** Documented TDLib behaviour: an error. */
    V1_THROWS,

    /** Returns only the covered sub-range (short, and misaligned when the request starts before the window). */
    V2_COVERED_SUBRANGE,

    /** Returns an empty array. */
    V3_EMPTY
}

/** What CancelDownloadFile does to the already-downloaded prefix. */
enum class FakeCancelVariant { C1_PREFIX_KEPT, C2_PREFIX_DISCARDED }

/** What a DownloadFile request at a new offset does while another window is active. */
enum class FakeRangeVariant { R1_RESTARTS, R2_IGNORED_WHILE_ACTIVE }

/**
 * A fake [TelegramClient] (via [Proxy], the pattern the existing archive tests use) that
 * faithfully emulates TDLib's download-window semantics over one in-memory file, instead of
 * the trivial "always fully downloaded" fake the older tests used:
 *
 * - `resolveFile` reports the file id/size with no local window yet.
 * - `requestFileRange` (DownloadFile) starts/restarts a window at the offset; the prefix
 *   grows from there toward end-of-file (growth mode is controllable).
 * - `getFileInfo` reports downloadOffset/downloadedPrefixSize/isDownloadingActive/
 *   isDownloadingCompleted driven by the window; polling it advances a DELAYED window,
 *   which is how the passage of download time shows up to the byte source's poll loop.
 * - `readFilePart` serves exact bytes only for a fully covered range; uncovered ranges
 *   follow the selected read variant.
 * - `fileUpdates` re-emits the current info periodically (an update stream).
 * - `cancelFileDownload` stops the window, keeping or discarding the prefix per variant.
 */
class FakeTdLibTelegramClient(
    private val bytes: ByteArray,
    private val readVariant: FakeReadVariant = FakeReadVariant.V1_THROWS,
    private val cancelVariant: FakeCancelVariant = FakeCancelVariant.C1_PREFIX_KEPT,
    private val rangeVariant: FakeRangeVariant = FakeRangeVariant.R1_RESTARTS,
    private val growth: FakeGrowth = FakeGrowth.INSTANT,
    private val growthStepBytes: Long = 128 * 1024
) {
    private var windowOffset = 0L
    private var prefix = 0L
    private var active = false
    private var completed = false

    var rangeRequests = 0
        private set
    var ignoredRangeRequests = 0
        private set
    var cancelCalls = 0
        private set
    var readCalls = 0
        private set

    @Synchronized
    private fun info(): TelegramFileInfo = TelegramFileInfo(
        fileId = FILE_ID,
        sizeBytes = bytes.size.toLong(),
        localPath = null,
        isDownloadingCompleted = completed,
        isDownloadingActive = active,
        downloadOffset = windowOffset,
        downloadedPrefixSize = prefix
    )

    @Synchronized
    private fun advance() {
        if (!active || growth != FakeGrowth.DELAYED) return
        prefix = minOf(bytes.size - windowOffset, prefix + growthStepBytes)
        if (prefix >= bytes.size - windowOffset) {
            active = false
            if (windowOffset == 0L) completed = true
        }
    }

    @Synchronized
    private fun requestRange(offset: Long) {
        rangeRequests++
        if (active && rangeVariant == FakeRangeVariant.R2_IGNORED_WHILE_ACTIVE) {
            ignoredRangeRequests++
            return
        }
        windowOffset = offset
        prefix = 0L
        completed = false
        active = true
        if (growth == FakeGrowth.INSTANT) {
            prefix = bytes.size - offset
            active = false
            if (offset == 0L) completed = true
        }
    }

    @Synchronized
    private fun readPart(offset: Long, count: Long): ByteArray {
        readCalls++
        val size = bytes.size.toLong()
        if (offset >= size) return ByteArray(0)
        val wantEnd = minOf(offset + count, size)
        val coveredStart = if (completed) 0L else windowOffset
        val coveredEnd = if (completed) size else windowOffset + prefix
        if (offset >= coveredStart && wantEnd <= coveredEnd) {
            return bytes.copyOfRange(offset.toInt(), wantEnd.toInt())
        }
        return when (readVariant) {
            FakeReadVariant.V1_THROWS -> throw TelegramException(
                400, "File part is not downloaded yet"
            )
            FakeReadVariant.V3_EMPTY -> ByteArray(0)
            FakeReadVariant.V2_COVERED_SUBRANGE -> {
                val interStart = maxOf(offset, coveredStart)
                val interEnd = minOf(wantEnd, coveredEnd)
                when {
                    interEnd > interStart ->
                        bytes.copyOfRange(interStart.toInt(), interEnd.toInt())
                    coveredEnd > coveredStart -> {
                        // No overlap: the covered sub-range is all the client has, and it
                        // hands those bytes back for a request that asked elsewhere.
                        val end = minOf(coveredEnd, coveredStart + count)
                        bytes.copyOfRange(coveredStart.toInt(), end.toInt())
                    }
                    else -> ByteArray(0)
                }
            }
        }
    }

    @Synchronized
    private fun cancel() {
        cancelCalls++
        active = false
        if (cancelVariant == FakeCancelVariant.C2_PREFIX_DISCARDED) {
            prefix = 0L
            windowOffset = 0L
            completed = false
        }
    }

    val client: TelegramClient = Proxy.newProxyInstance(
        TelegramClient::class.java.classLoader,
        arrayOf(TelegramClient::class.java),
        InvocationHandler { _, method, args ->
            when (method.name) {
                "resolveFile" -> info()
                "getFileInfo" -> {
                    advance()
                    info()
                }
                "requestFileRange" -> {
                    requestRange((args[1] as Number).toLong())
                    Unit
                }
                "readFilePart" -> readPart(
                    (args[1] as Number).toLong(),
                    (args[2] as Number).toLong()
                )
                "cancelFileDownload" -> {
                    cancel()
                    Unit
                }
                "fileUpdates" -> flow {
                    while (true) {
                        advance()
                        emit(info())
                        delay(20)
                    }
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        }
    ) as TelegramClient

    companion object {
        const val FILE_ID = 7
    }
}
