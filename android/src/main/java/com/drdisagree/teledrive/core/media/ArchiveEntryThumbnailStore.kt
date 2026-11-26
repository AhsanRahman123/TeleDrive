package com.drdisagree.teledrive.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.util.Size
import com.drdisagree.teledrive.core.archive.ArchiveEntryThumbnailPolicy
import com.drdisagree.teledrive.core.archive.ArchiveThumbnailSession
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.domain.model.DriveFile
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
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
 * On-demand thumbnails for entries of REMOTE ZIP/CBZ archives (vc182 item 6).
 *
 * Deliberately unlike [AndroidThumbnailStore]: no Room rows, no durable queue, no
 * warm-up or enumeration pass of any kind. A thumbnail is fetched only when a row
 * for that exact entry asks via [request], and all state is per cache key in
 * memory — nothing here ever flows through the preview screen's UI state, so
 * scrolling, searching and selecting in the archive list are untouched.
 *
 * Fetches run on Dispatchers.IO under a Semaphore(3), deduped per key, and are
 * cancelled when their last waiter releases the key (the row left composition).
 * Extractions for one archive share a single [ArchiveThumbnailSession], so the
 * central directory is parsed once per archive, not once per row. Only the most
 * recent archive's session is kept: opening another archive's session closes the
 * previous one (bounded memory — one open byte source — at the cost of a rare
 * re-parse if the user swipes back and forth between two archives).
 *
 * Generated JPEGs live in cacheDir/archive-thumbs/<key>.jpg under a hard 32 MiB
 * LRU cap. Failures are remembered for the process session only and never
 * retried until the app restarts; a failure leaves the row on its generic icon.
 * Two failures are NOT remembered, because they say nothing about the entry:
 * a fetch whose session was superseded mid-flight when another archive was
 * opened (the closed-session race), and a Ready key whose cache file was
 * evicted or externally cleared — both stay retryable and refetch on the next
 * request.
 */
class ArchiveEntryThumbnailStore private constructor(
    private val thumbsDir: File,
    private val remoteArchiveService: RemoteArchiveService
) {

    constructor(context: Context, remoteArchiveService: RemoteArchiveService) : this(
        File(context.cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME),
        remoteArchiveService
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    private val states = ConcurrentHashMap<String, MutableStateFlow<ArchiveThumbState>>()
    private val failedKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()

    // In-flight fetches and their waiter counts, guarded by requestLock. A fetch
    // is cancelled when its waiter count drops back to zero.
    private val requestLock = Any()
    private val inFlight = mutableMapOf<String, Deferred<Unit>>()
    private val waiters = mutableMapOf<String, Int>()

    // The single live session (most recent archive only), guarded by sessionLock.
    private val sessionLock = Any()
    private var session: ArchiveThumbnailSession? = null
    private var sessionArchiveId: String? = null

    private val writesSinceEvictionCheck = AtomicInteger(0)

    init {
        // Extraction temps live inside thumbsDir and are deleted in a finally on
        // every fetch; only a process death mid-fetch can orphan one. Sweep those
        // here, once, at store creation (first archive open) instead of extending
        // the application startup sweep, which only covers cacheDir root files.
        scope.launch {
            runCatching {
                // The age threshold keeps this sweep from ever eating a temp that a
                // fetch started moments ago (the sweep and the first fetches race on
                // the IO dispatcher): a live temp is written continuously, so only a
                // temp untouched for the whole threshold can be an orphan.
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

    /** The observable state for [cacheKey]; the initial value already reflects the disk cache. */
    fun state(cacheKey: String): StateFlow<ArchiveThumbState> = stateFlow(cacheKey).asStateFlow()

    /**
     * Asks for the thumbnail of the entry at [entryIndex] in [archiveFile]'s listing.
     * [entryName] and [sizeBytes] are the listing's own values for that entry: the
     * listing-level eligibility pre-check runs on them synchronously, before any
     * session is opened, so an ineligible row (not an image, over the size cap)
     * causes zero byte-source reads. Pairs with [release].
     */
    fun request(
        cacheKey: String,
        archiveFile: DriveFile,
        entryIndex: Int,
        entryName: String,
        sizeBytes: Long
    ) {
        val flow = stateFlow(cacheKey)
        synchronized(requestLock) {
            waiters[cacheKey] = (waiters[cacheKey] ?: 0) + 1
        }
        if (flow.value is ArchiveThumbState.Ready) {
            val cached = cacheFile(cacheKey)
            if (cached.isFile && cached.length() > 0L) {
                // Cache hit: refresh the LRU timestamp, no fetch, no reads.
                cached.setLastModified(System.currentTimeMillis())
                return
            }
            // Ready in memory but the file is gone (LRU-evicted, or the cache
            // was cleared externally): fall through to the fetch path instead
            // of stranding the row on the generic icon for this process.
        }
        if (cacheKey in failedKeys) {
            flow.value = ArchiveThumbState.Failed
            return
        }
        if (!ArchiveEntryThumbnailPolicy.isEligibleByListing(entryName, sizeBytes, isDirectory = false)) {
            failedKeys += cacheKey
            flow.value = ArchiveThumbState.Failed
            return
        }
        synchronized(requestLock) {
            val existing = inFlight[cacheKey]
            if (existing == null || existing.isCompleted) {
                inFlight[cacheKey] = scope.async {
                    fetch(cacheKey, archiveFile, entryIndex)
                }
            }
        }
    }

    /**
     * Drops one waiter for [cacheKey]; when the last waiter goes away the fetch
     * (if still running) is cancelled, so scrolling past a row costs no download.
     */
    fun release(cacheKey: String) {
        synchronized(requestLock) {
            val remaining = (waiters[cacheKey] ?: 0) - 1
            if (remaining > 0) {
                waiters[cacheKey] = remaining
                return
            }
            waiters.remove(cacheKey)
            inFlight.remove(cacheKey)?.cancel()
        }
    }

    private suspend fun fetch(cacheKey: String, archiveFile: DriveFile, entryIndex: Int) {
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
                    val bitmap = decodeThumbnail(temp)
                    if (bitmap == null || !writeJpeg(bitmap, target)) {
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
            // Not a failure: the row scrolled away. The key stays retryable.
            throw cancelled
        } catch (failure: Exception) {
            if (isSupersededSessionFailure(usedSession, failure)) {
                // Not a failure: opening another archive closed this fetch's
                // session underneath it. The key stays retryable — the next
                // request reopens this archive's session and fetches afresh —
                // instead of being marked Failed for the whole process.
                flow.value = ArchiveThumbState.Placeholder
            } else {
                failedKeys += cacheKey
                flow.value = ArchiveThumbState.Failed
            }
        }
    }

    /**
     * Whether [failure] came from [candidate] having been closed because a
     * different archive's session replaced it. The session-identity check is
     * the primary signal: it catches every superseded failure regardless of
     * the exception type it surfaced as (the session's own `check(!closed)`
     * IllegalStateException when the fetch had not started extracting, or an
     * I/O failure from reading the closed byte source mid-extraction). The
     * closed-session message check is defence in depth for a session closed
     * without a replacement ever being installed.
     */
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

    /**
     * The session for [archiveFile], opened on first use. Switching archives
     * closes the previous session (see class doc); racing opens for the same
     * archive converge on one session.
     */
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

    private fun decodeThumbnail(file: File): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ThumbnailUtils.createImageThumbnail(
                file,
                Size(
                    ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX,
                    ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX
                ),
                null
            )
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val sample = maxOf(
                1,
                maxOf(options.outWidth, options.outHeight) /
                    ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX
            )
            BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            )
        }
    }.getOrNull()

    /** Writes [bitmap] as JPEG q82 to [target] via a temp file + rename, so readers never see a partial file. */
    private fun writeJpeg(bitmap: Bitmap, target: File): Boolean {
        val part = File(thumbsDir, target.name + PART_SUFFIX)
        return try {
            val written = part.outputStream().buffered().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
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
            bitmap.recycle()
            part.delete()
        }
    }

    /**
     * Enforces the disk cap, checked only every few successful writes (never per
     * row): evicts least-recently-modified thumbnails until the dir is back
     * under [MAX_CACHE_BYTES].
     */
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
                    // Keep memory and disk in agreement: a Ready key whose
                    // file was just evicted goes back to Placeholder, so the
                    // next request for a visible row refetches it instead of
                    // returning early on a Ready that points at nothing.
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
        private const val JPEG_QUALITY = 82
        private const val TEMP_PREFIX = "extract-"
        private const val TEMP_ORPHAN_MIN_AGE_MS = 60_000L
        private const val PART_SUFFIX = ".part"
        private const val JPEG_SUFFIX = ".jpg"
        private const val EVICTION_CHECK_EVERY_WRITES = 8
        private const val MAX_CACHE_BYTES = 32L * 1024 * 1024

        /** Test seam: build a store over an explicit cache dir instead of a Context. */
        internal fun forCacheDir(
            cacheDir: File,
            remoteArchiveService: RemoteArchiveService
        ): ArchiveEntryThumbnailStore = ArchiveEntryThumbnailStore(
            File(cacheDir, ArchiveEntryThumbnailPolicy.CACHE_DIR_NAME),
            remoteArchiveService
        )
    }
}
