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

/**
 * A fake [TelegramClient] (via [Proxy], the pattern the existing archive tests use) that
 * models the DownloadFile / ReadFilePart semantics established from the TDLight / upstream
 * TDLib 1.8.x source (report 52, `52-tdlight-source-findings.md`). This is also the
 * documented-official behaviour of Android's `org.drinkless.tdlib`: the findings show the
 * ReadFilePart gate is the same upstream code in both libraries, so this one fake encodes
 * the Android regression argument as well — there is no separate "Android variant".
 *
 * Modelled semantics (all source-derived; the invented 1.6.22 variants V2/V3/R2/C2 were
 * removed because they contradict that source):
 * - Bytes live in ONE partial file as a persistent part bitmask. Parts persist across
 *   download windows and across CancelDownloadFile (cancel only stops the window).
 * - DownloadFile keeps ONE window per file. A new request re-targets it: in-flight parts
 *   outside the new window are dropped, and the reported downloaded_prefix_size is
 *   instantly re-based to the new offset (it describes ONLY the current window, as the
 *   contiguous ready parts starting at the part containing that offset).
 * - The fetched range is the part-aligned expansion of [offset, offset+limit): every part
 *   intersecting the range. offset+limit past EOF wraps and also fetches the file start.
 * - A limit>0 window NEVER sets isDownloadingCompleted: it terminates with
 *   isDownloadingActive=false and downloaded_prefix_size >= limit (part overshoot
 *   possible). limit=0 downloads the whole file and only then completes it.
 * - ReadFilePart is all-or-nothing: it returns exactly count bytes iff the parts are
 *   contiguous from the read offset (regardless of the current window), otherwise it
 *   throws TelegramException(400, "There is not enough downloaded bytes in the file to
 *   read"). count=0 returns the contiguous prefix from the offset (possibly empty) and
 *   never fails on coverage.
 * - 1.6.24 modes: [evictLocalCache] models the node losing its readable local path
 *   (ReadFilePart then throws 400 "File is not inside the cache", findings §2(a).6,
 *   until a DownloadFile re-establishes a partial); [simulateCompetingDownload]
 *   models another issuer holding the single window at a foreign offset;
 *   `growthStepIntervalMs` makes DELAYED growth slow in wall time but progressing.
 */
class FakeTdLibTelegramClient(
    private val bytes: ByteArray,
    private val growth: FakeGrowth = FakeGrowth.INSTANT,
    private val growthStepBytes: Long = 128 * 1024,
    private val partSizeBytes: Long = DEFAULT_PART_SIZE,
    /**
     * When true, `fileUpdates` behaves like the real replay-0 SharedFlow: it emits
     * NOTHING while the observed state is unchanged and emits only on an actual state
     * change. The default (false) keeps the original unconditional 20 ms emission
     * the other tests were written against (second pass, report 52 — the unconditional
     * mode masked the stale-snapshot re-issue defect in `readRangeOnly`).
     */
    private val emitUpdatesOnlyOnChange: Boolean = false,
    /**
     * When > 0, a requested window stays active but lands NO parts until this many
     * milliseconds have elapsed since the (latest) request, then lands whole. Models
     * a slow-starting window whose first part arrives after more than one 500 ms
     * wait slice in `readRangeOnly`. Re-issuing the request restarts the delay,
     * like a re-target dropping in-flight parts.
     */
    private val slowStartDelayMs: Long = 0L,
    /**
     * When > 0 (DELAYED growth only), at most one growth step lands per this many
     * milliseconds of wall time, however often the fake is polled. Models a window
     * that is genuinely slow but still progressing (1.6.24, Failure B).
     */
    private val growthStepIntervalMs: Long = 0L
) {
    private val partSize = partSizeBytes.coerceAtLeast(1)
    private val numParts = if (bytes.isEmpty()) 0 else ((bytes.size + partSize - 1) / partSize).toInt()
    private val ready = BooleanArray(numParts)

    private var windowOffset = 0L
    private var windowLimit = 0L
    private var active = false
    private var windowRequestedAtMs = 0L
    private var lastGrowthStepMs = 0L

    /**
     * 1.6.24 Failure A: the file node has NO readable local path (the partial was
     * evicted/deleted, or the node was re-resolved fresh), so ReadFilePart fails with
     * 400 "File is not inside the cache" (findings §2(a).6) until a DownloadFile
     * re-establishes a partial file. Set via [evictLocalCache].
     */
    private var cacheMissing = false

    /**
     * 1.6.24 Failure B: a competing issuer (e.g. a Transfers full-file download of the
     * same fileId) holds the single window at ITS offset, active, landing none of our
     * parts. Set via [simulateCompetingDownload]; cleared when our side re-issues a
     * DownloadFile (the designed re-target path).
     */
    private var competitorHeld = false

    var competingRequests = 0
        private set

    var rangeRequests = 0
        private set
    val rangeRequestList = mutableListOf<Pair<Long, Long>>()
    var cancelCalls = 0
        private set
    var readCalls = 0
        private set

    private val completed: Boolean get() = numParts > 0 && ready.all { it }

    /** Contiguous ready bytes starting at [offset] (the ReadFilePart gate / prefix rule). */
    private fun contiguousFrom(offset: Long): Long {
        if (offset < 0 || offset >= bytes.size || numParts == 0) return 0L
        var part = (offset / partSize).toInt()
        if (!ready[part]) return 0L
        var ones = 0
        while (part + ones < numParts && ready[part + ones]) ones++
        val end = minOf(bytes.size.toLong(), (part + ones) * partSize)
        return end - offset
    }

    @Synchronized
    private fun info(): TelegramFileInfo = TelegramFileInfo(
        fileId = FILE_ID,
        sizeBytes = bytes.size.toLong(),
        localPath = null,
        isDownloadingCompleted = completed,
        isDownloadingActive = active,
        downloadOffset = windowOffset,
        downloadedPrefixSize = if (completed) bytes.size.toLong() else contiguousFrom(windowOffset)
    )

    /** Parts intersecting the current window, in streaming order from the window offset. */
    private fun windowPartsInOrder(): List<Int> {
        if (numParts == 0) return emptyList()
        val size = bytes.size.toLong()
        val inWindow = BooleanArray(numParts)
        if (windowLimit == 0L) {
            inWindow.fill(true)
        } else {
            val begin = windowOffset
            val end = windowOffset + windowLimit
            for (p in 0 until numParts) {
                val pStart = p * partSize
                val pEnd = minOf(size, pStart + partSize)
                if (pStart < end && begin < pEnd) inWindow[p] = true
                // Wrap-around: the part of the window past EOF fetches the file start.
                if (end > size && pStart < end - size) inWindow[p] = true
            }
        }
        val startPart = if (size == 0L) 0 else (windowOffset / partSize).toInt().coerceIn(0, numParts - 1)
        val ordered = mutableListOf<Int>()
        for (i in 0 until numParts) {
            val p = (startPart + i) % numParts
            if (inWindow[p]) ordered += p
        }
        return ordered
    }

    private fun windowSatisfied(): Boolean = windowPartsInOrder().all { ready[it] }

    /** The node's local copy vanishes between two operations (Failure A setup). */
    @Synchronized
    fun evictLocalCache() {
        ready.fill(false)
        active = false
        competitorHeld = false
        cacheMissing = true
    }

    /** Another issuer re-targets the single window to its own range and holds it. */
    @Synchronized
    fun simulateCompetingDownload(offset: Long, limit: Long) {
        competingRequests++
        windowOffset = offset
        windowLimit = limit
        active = true
        competitorHeld = true
        windowRequestedAtMs = System.currentTimeMillis()
    }

    @Synchronized
    private fun advance() {
        if (!active) return
        if (competitorHeld) return
        if (slowStartDelayMs > 0L) {
            if (System.currentTimeMillis() - windowRequestedAtMs < slowStartDelayMs) return
            for (p in windowPartsInOrder()) ready[p] = true
            if (windowSatisfied()) active = false
            return
        }
        if (growth != FakeGrowth.DELAYED) return
        if (growthStepIntervalMs > 0L) {
            val now = System.currentTimeMillis()
            if (now - lastGrowthStepMs < growthStepIntervalMs) return
            lastGrowthStepMs = now
        }
        var budget = growthStepBytes
        for (p in windowPartsInOrder()) {
            if (budget <= 0) break
            if (!ready[p]) {
                ready[p] = true
                budget -= partSize
            }
        }
        if (windowSatisfied()) active = false
    }

    @Synchronized
    private fun requestRange(offset: Long, limit: Long) {
        rangeRequests++
        rangeRequestList += offset to limit
        // Re-target: the single window moves; already-downloaded parts are kept, the
        // reported prefix re-bases to the new offset automatically via [info].
        // A DownloadFile also re-establishes a readable partial file (clears the
        // Failure-A cache-missing state) and takes the window back from a competitor.
        cacheMissing = false
        competitorHeld = false
        windowOffset = offset
        windowLimit = limit
        active = true
        windowRequestedAtMs = System.currentTimeMillis()
        if (growth == FakeGrowth.INSTANT && slowStartDelayMs == 0L) {
            for (p in windowPartsInOrder()) ready[p] = true
            active = false
        }
    }

    @Synchronized
    private fun readPart(offset: Long, count: Long): ByteArray {
        readCalls++
        // Failure A (findings §2(a).6): no readable local path at all. Modelled ahead
        // of the coverage gate — the field-observed behaviour is this exact 400
        // surfacing from the read; the node's bitmask state in that instant is not
        // observable from the app.
        if (cacheMissing) throw TelegramException(400, "File is not inside the cache")
        if (offset < 0) throw TelegramException(400, "Parameter offset must be non-negative")
        if (count < 0) throw TelegramException(400, "Parameter count must be non-negative")
        if (count == 0L) {
            val available = contiguousFrom(offset)
            if (available == 0L) return ByteArray(0)
            return bytes.copyOfRange(offset.toInt(), (offset + available).toInt())
        }
        if (contiguousFrom(offset) < count) {
            throw TelegramException(400, "There is not enough downloaded bytes in the file to read")
        }
        val end = minOf(offset + count, bytes.size.toLong())
        return bytes.copyOfRange(offset.toInt(), end.toInt())
    }

    @Synchronized
    private fun cancel() {
        cancelCalls++
        // The partial file and its parts persist; only the active window stops.
        active = false
    }

    val client: TelegramClient = Proxy.newProxyInstance(
        TelegramClient::class.java.classLoader,
        arrayOf(TelegramClient::class.java),
        InvocationHandler { _, method, args ->
            try {
                dispatch(method.name, args)
            } catch (failure: TelegramException) {
                // java.lang.reflect.Proxy wraps checked exceptions thrown by a handler in
                // UndeclaredThrowableException; the real clients are plain Kotlin classes
                // and deliver TelegramException directly. Resume the caller's continuation
                // with the failure instead, so callers see the faithful exception.
                val continuation = args?.lastOrNull() as? kotlin.coroutines.Continuation<*>
                    ?: throw failure
                @Suppress("UNCHECKED_CAST")
                (continuation as kotlin.coroutines.Continuation<Any?>)
                    .resumeWith(Result.failure(failure))
                kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
            }
        }
    ) as TelegramClient

    private fun dispatch(methodName: String, args: Array<out Any?>?): Any? =
            when (methodName) {
                "resolveFile" -> info()
                "getFileInfo" -> {
                    advance()
                    info()
                }
                "requestFileRange" -> {
                    requestRange((args!![1] as Number).toLong(), (args[2] as Number).toLong())
                    Unit
                }
                "readFilePart" -> readPart(
                    (args!![1] as Number).toLong(),
                    (args[2] as Number).toLong()
                )
                "cancelFileDownload" -> {
                    cancel()
                    Unit
                }
                "fileUpdates" -> if (emitUpdatesOnlyOnChange) {
                    flow {
                        var last = info()
                        while (true) {
                            delay(20)
                            advance()
                            val current = info()
                            if (current != last) {
                                last = current
                                emit(current)
                            }
                        }
                    }
                } else {
                    flow {
                        while (true) {
                            advance()
                            emit(info())
                            delay(20)
                        }
                    }
                }
                else -> throw UnsupportedOperationException(methodName)
            }

    companion object {
        const val FILE_ID = 7
        const val DEFAULT_PART_SIZE = 64L * 1024
    }
}
