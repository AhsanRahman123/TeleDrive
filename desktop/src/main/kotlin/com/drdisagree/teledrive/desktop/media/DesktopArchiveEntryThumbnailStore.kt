package com.drdisagree.teledrive.desktop.media

import com.drdisagree.teledrive.core.archive.ArchiveEntryThumbnailPolicy
import com.drdisagree.teledrive.core.archive.ArchiveThumbnailSession
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.domain.model.DriveFile
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Per-key thumbnail state for one archive entry; the generic row icon covers Placeholder and Failed. */
sealed interface ArchiveThumbState {
    data object Placeholder : ArchiveThumbState
    data class Ready(val file: File) : ArchiveThumbState
    data object Failed : ArchiveThumbState
}

/**
 * Desktop port of Android's vc182 item-6 store (PC 1.6.25 item 6): on-demand
 * thumbnails for entries of REMOTE ZIP/CBZ archives, fetched only when a visible
 * row asks via [request]. Identical discipline to the Android store: per-key
 * StateFlow, listing-level eligibility pre-check before any session is opened
 * (ineligible rows perform ZERO byte-source reads), one
 * [ArchiveThumbnailSession] for the most recent archive only (one central-
 * directory parse per archive), fetches on Dispatchers.IO under Semaphore(3)
 * with in-flight dedupe and waiter-counted cancellation, MF-1/MF-2 lifecycle
 * rules (superseded-session failures and evicted Ready files stay retryable).
 * Differences are platform-only: the cache lives under the desktop app's
 * existing cache dir (`AppStoragePaths.cacheDir/archive-thumbs/`, the same root
 * DesktopThumbnailStore uses), and decode/write uses ImageIO instead of
 * Android Bitmap APIs. Decode target follows the caller's thumbnail step
 * (PC 1.6.27 item N6): the default is
 * [ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX] (256 px), and the ZIP
 * list's Big step requests 512 px; each decode size is cached under its
 * own key suffix so a 256 px decode is never served for the Big tile.
 * Video poster frames stay HELD (report 55 §6.1): not eligible here.
 */
class DesktopArchiveEntryThumbnailStore private constructor(
    private val thumbsDir: File,
    private val remoteArchiveService: RemoteArchiveService
) {

    constructor(
        storagePaths: AppStoragePaths,
        remoteArchiveService: RemoteArchiveService
    ) : this(
        File(storagePaths.cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME),
        remoteArchiveService
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    private val states = ConcurrentHashMap<String, MutableStateFlow<ArchiveThumbState>>()
    private val failedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val requestLock = Any()
    private val inFlight = mutableMapOf<String, Deferred<Unit>>()
    private val waiters = mutableMapOf<String, Int>()

    private val sessionLock = Any()
    private var session: ArchiveThumbnailSession? = null
    private var sessionArchiveId: String? = null

    private val writesSinceEvictionCheck = AtomicInteger(0)

    init {
        scope.launch {
            runCatching {
                val cutoff = System.currentTimeMillis() - TEMP_ORPHAN_MIN_AGE_MS
                thumbsDir.listFiles()
                    ?.filter {
                        it.isFile && it.lastModified() < cutoff &&
                            (it.name.startsWith(TEMP_PREFIX) || it.name.endsWith(PART_SUFFIX))
                    }
                    ?.forEach { it.delete() }
            }
        }
    }

    fun state(
        cacheKey: String,
        decodePx: Int = ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX
    ): StateFlow<ArchiveThumbState> = stateFlow(keyFor(cacheKey, decodePx)).asStateFlow()

    /**
     * Settled-page priority (PC 1.6.27 N4): while a preview extraction for
     * this archive is running, thumbnail fetches must not compete with it.
     * Thumbnail sessions and preview extractions open separate byte sources
     * but the SAME TDLib file, and TDLib keeps exactly one download window
     * per file (report 52 §1b) — a thumbnail's ranged request re-targets
     * that window and can starve the page the user is waiting on.
     *
     * Activating priority cancels in-flight fetches (a cancelled fetch
     * leaves its row Placeholder, never Failed) and queues new requests;
     * deactivating replays the queued requests. Requests for rows already
     * Ready are unaffected.
     */
    @Volatile
    private var previewPriorityActive = false

    private data class PendingThumbRequest(
        val cacheKey: String,
        val archiveFile: DriveFile,
        val entryIndex: Int,
        val entryName: String,
        val sizeBytes: Long,
        val decodePx: Int
    )

    /** Guarded by [requestLock]. */
    private val pendingRequests = mutableMapOf<String, PendingThumbRequest>()

    fun setPreviewPriorityActive(active: Boolean) {
        val toReplay: List<PendingThumbRequest>
        synchronized(requestLock) {
            previewPriorityActive = active
            if (active) {
                val running = inFlight.values.toList()
                inFlight.clear()
                running.forEach { it.cancel() }
                toReplay = emptyList()
            } else {
                toReplay = pendingRequests.values.toList()
                pendingRequests.clear()
            }
        }
        // Replay outside the lock: dispatchRequest re-enters it.
        toReplay.forEach { pending ->
            dispatchRequest(
                pending.cacheKey, pending.archiveFile, pending.entryIndex,
                pending.entryName, pending.sizeBytes, pending.decodePx
            )
        }
    }

    /** Test seam: whether settled-page priority is currently holding fetches. */
    internal fun isPreviewPriorityActive(): Boolean = previewPriorityActive

    /** Test seam: how many thumbnail requests are queued behind priority. */
    internal fun pendingRequestCount(): Int = synchronized(requestLock) { pendingRequests.size }

    fun request(
        cacheKey: String,
        archiveFile: DriveFile,
        entryIndex: Int,
        entryName: String,
        sizeBytes: Long,
        decodePx: Int = ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX
    ) {
        val key = keyFor(cacheKey, decodePx)
        val flow = stateFlow(key)
        synchronized(requestLock) {
            waiters[key] = (waiters[key] ?: 0) + 1
        }
        dispatchRequest(cacheKey, archiveFile, entryIndex, entryName, sizeBytes, decodePx)
    }

    private fun dispatchRequest(
        cacheKey: String,
        archiveFile: DriveFile,
        entryIndex: Int,
        entryName: String,
        sizeBytes: Long,
        decodePx: Int
    ) {
        val key = keyFor(cacheKey, decodePx)
        val flow = stateFlow(key)
        if (flow.value is ArchiveThumbState.Ready) {
            val cached = cacheFile(key)
            if (cached.isFile && cached.length() > 0L) {
                cached.setLastModified(System.currentTimeMillis())
                return
            }
        }
        if (key in failedKeys) {
            flow.value = ArchiveThumbState.Failed
            return
        }
        if (!ArchiveEntryThumbnailPolicy.isEligibleByListing(entryName, sizeBytes, isDirectory = false)) {
            failedKeys += key
            flow.value = ArchiveThumbState.Failed
            return
        }
        synchronized(requestLock) {
            if (previewPriorityActive) {
                // Queued behind the running preview extraction; replayed
                // by setPreviewPriorityActive(false). The row stays
                // Placeholder meanwhile — never Failed.
                pendingRequests[key] = PendingThumbRequest(
                    cacheKey, archiveFile, entryIndex, entryName, sizeBytes, decodePx
                )
                return
            }
            val existing = inFlight[key]
            if (existing == null || existing.isCompleted) {
                inFlight[key] = scope.async {
                    fetch(key, archiveFile, entryIndex, decodePx)
                }
            }
        }
    }

    fun release(
        cacheKey: String,
        decodePx: Int = ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX
    ) {
        val key = keyFor(cacheKey, decodePx)
        synchronized(requestLock) {
            val remaining = (waiters[key] ?: 0) - 1
            if (remaining > 0) {
                waiters[key] = remaining
                return
            }
            waiters.remove(key)
            pendingRequests.remove(key)
            inFlight.remove(key)?.cancel()
        }
    }

    /**
     * The state/cache identity for one entry at one decode size (N6): the
     * listing-derived [cacheKey] stays the base, and the decode cap is
     * suffixed so steps never share a decode. The default 256 px keeps the
     * historical unsuffixed name, so existing on-disk caches stay valid.
     */
    private fun keyFor(cacheKey: String, decodePx: Int): String =
        if (decodePx == ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX) {
            cacheKey
        } else {
            "$cacheKey@$decodePx"
        }

    private suspend fun fetch(
        cacheKey: String,
        archiveFile: DriveFile,
        entryIndex: Int,
        decodePx: Int
    ) {
        val flow = stateFlow(cacheKey)
        val target = cacheFile(cacheKey)
        if (target.isFile && target.length() > 0L) {
            flow.value = ArchiveThumbState.Ready(target)
            return
        }
        var usedSession: ArchiveThumbnailSession? = null
        try {
            fetchSemaphore.withPermit {
                val session = sessionFor(archiveFile)
                usedSession = session
                val info = session.entries().getOrNull(entryIndex)
                if (info == null || !ArchiveEntryThumbnailPolicy.isEligible(info)) {
                    failedKeys += cacheKey
                    flow.value = ArchiveThumbState.Failed
                    return@withPermit
                }
                thumbsDir.mkdirs()
                val temp = File(thumbsDir, "$TEMP_PREFIX$cacheKey.tmp")
                try {
                    session.extractEntry(info, temp)
                    val image = decodeThumbnail(temp, decodePx)
                    if (image == null || !writeJpeg(image, target)) {
                        failedKeys += cacheKey
                        flow.value = ArchiveThumbState.Failed
                        return@withPermit
                    }
                } finally {
                    temp.delete()
                }
                flow.value = ArchiveThumbState.Ready(target)
                evictIfNeededThrottled()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (isSupersededSessionFailure(usedSession, failure)) {
                flow.value = ArchiveThumbState.Placeholder
            } else {
                failedKeys += cacheKey
                flow.value = ArchiveThumbState.Failed
            }
        }
    }

    private fun isSupersededSessionFailure(
        candidate: ArchiveThumbnailSession?,
        failure: Exception
    ): Boolean {
        if (candidate != null && synchronized(sessionLock) { session !== candidate }) {
            return true
        }
        return failure is IllegalStateException &&
            failure.message?.contains("closed", ignoreCase = true) == true
    }

    private suspend fun sessionFor(archiveFile: DriveFile): ArchiveThumbnailSession {
        synchronized(sessionLock) {
            session?.takeIf { sessionArchiveId == archiveFile.id }?.let { return it }
        }
        val opened = remoteArchiveService.openThumbnailSession(archiveFile)
        synchronized(sessionLock) {
            val current = session
            if (current != null && sessionArchiveId == archiveFile.id) {
                opened.close()
                return current
            }
            current?.close()
            session = opened
            sessionArchiveId = archiveFile.id
            return opened
        }
    }

    /** Decodes [file] and scales it to at most [decodePx] per side. */
    private fun decodeThumbnail(file: File, decodePx: Int): BufferedImage? = runCatching {
        val source = ImageIO.read(file) ?: return null
        val maxSide = maxOf(source.width, source.height)
        val target = decodePx.coerceAtLeast(1)
        if (maxSide <= target) return source
        val scale = target.toDouble() / maxSide
        val w = (source.width * scale).toInt().coerceAtLeast(1)
        val h = (source.height * scale).toInt().coerceAtLeast(1)
        BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also { copy ->
            copy.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                drawImage(source, 0, 0, w, h, null)
                dispose()
            }
        }
    }.getOrNull()

    private fun writeJpeg(image: BufferedImage, target: File): Boolean {
        val part = File(thumbsDir, target.name + PART_SUFFIX)
        return try {
            val written = part.outputStream().buffered().use { output ->
                ImageIO.write(image, "jpg", output)
            }
            if (!written) {
                false
            } else {
                if (target.exists()) target.delete()
                part.renameTo(target)
            }
        } catch (failure: Exception) {
            false
        } finally {
            part.delete()
        }
    }

    private fun evictIfNeededThrottled() {
        if (writesSinceEvictionCheck.incrementAndGet() % EVICTION_CHECK_EVERY_WRITES != 0) return
        runCatching {
            val files = thumbsDir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(JPEG_SUFFIX) }
                ?: return
            var total = files.sumOf { it.length() }
            if (total <= MAX_CACHE_BYTES) return
            for (victim in files.sortedBy { it.lastModified() }) {
                if (total <= MAX_CACHE_BYTES) break
                val length = victim.length()
                if (victim.delete()) {
                    total -= length
                    val victimKey = victim.name.removeSuffix(JPEG_SUFFIX)
                    states[victimKey]?.let { flow ->
                        if (flow.value is ArchiveThumbState.Ready) {
                            flow.value = ArchiveThumbState.Placeholder
                        }
                    }
                }
            }
        }
    }

    private fun stateFlow(cacheKey: String): MutableStateFlow<ArchiveThumbState> =
        states.computeIfAbsent(cacheKey) {
            val file = cacheFile(cacheKey)
            MutableStateFlow(
                when {
                    cacheKey in failedKeys -> ArchiveThumbState.Failed
                    file.isFile && file.length() > 0L -> ArchiveThumbState.Ready(file)
                    else -> ArchiveThumbState.Placeholder
                }
            )
        }

    private fun cacheFile(cacheKey: String): File = File(thumbsDir, "$cacheKey$JPEG_SUFFIX")

    companion object {
        private const val MAX_CONCURRENT_FETCHES = 3
        private const val TEMP_PREFIX = "extract-"
        private const val TEMP_ORPHAN_MIN_AGE_MS = 60_000L
        private const val PART_SUFFIX = ".part"
        private const val JPEG_SUFFIX = ".jpg"
        private const val EVICTION_CHECK_EVERY_WRITES = 8
        private const val MAX_CACHE_BYTES = 32L * 1024 * 1024

        /** Test seam: build a store over an explicit cache dir instead of AppStoragePaths. */
        internal fun forCacheDir(
            cacheDir: File,
            remoteArchiveService: RemoteArchiveService
        ): DesktopArchiveEntryThumbnailStore = DesktopArchiveEntryThumbnailStore(
            File(cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME),
            remoteArchiveService
        )
    }
}
