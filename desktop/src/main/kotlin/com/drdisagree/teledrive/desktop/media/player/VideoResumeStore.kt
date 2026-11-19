package com.drdisagree.teledrive.desktop.media.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.prefs.Preferences
import kotlin.math.abs

/**
 * Per-video resume positions for the desktop player. Reads are tiny local preference lookups;
 * writes are throttled and always executed off the player/Compose threads.
 */
internal object VideoResumeStore {
    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/video-resume")
    }
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastPersisted = ConcurrentHashMap<String, Long>()

    fun loadPosition(mediaId: String): Long = loadPosition(preferences, lastPersisted, mediaId)

    internal fun loadPosition(
        source: Preferences,
        cache: ConcurrentHashMap<String, Long>,
        mediaId: String
    ): Long = runCatching {
        val key = keyFor(mediaId)
        source.getLong(key, 0L).coerceAtLeast(0L).also { saved ->
            if (saved > 0L) cache[key] = saved
        }
    }.getOrDefault(0L)

    fun savePosition(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false
    ) = savePosition(preferences, lastPersisted, mediaId, positionMs, durationMs, force)

    internal fun savePosition(
        target: Preferences,
        cache: ConcurrentHashMap<String, Long>,
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false
    ) {
        if (positionMs <= 0L) return
        if (durationMs > 0L && durationMs - positionMs <= COMPLETION_CLEAR_MARGIN_MS) {
            clear(target, cache, mediaId)
            return
        }

        val key = keyFor(mediaId)
        val previous = cache[key]
        if (!force && previous != null && abs(positionMs - previous) < MIN_PERSIST_DELTA_MS) return
        cache[key] = positionMs

        ioScope.launch {
            runCatching {
                synchronized(target) {
                    // An older write can be scheduled before a newer time event but run later
                    // on the IO pool. Skip it if the in-memory latest value has moved on.
                    if (cache[key] == positionMs) {
                        target.putLong(key, positionMs)
                        target.flush()
                    }
                }
            }.onFailure {
                cache.remove(key, positionMs)
            }
        }
    }

    fun clear(mediaId: String) = clear(preferences, lastPersisted, mediaId)

    internal fun clear(
        target: Preferences,
        cache: ConcurrentHashMap<String, Long>,
        mediaId: String
    ) {
        val key = keyFor(mediaId)
        // Snapshot what is stored right now: the delayed removal below may only
        // delete that value. The previous guard ("remove only while the cache
        // has no entry") could never fire once anything re-read the position —
        // loadPosition re-seeds the cache from the stored value, so a reopened
        // video (or any read between scheduling and execution) resurrected the
        // cleared resume point permanently.
        val storedValue = runCatching { target.getLong(key, 0L) }.getOrDefault(0L)
        cache.remove(key)
        ioScope.launch {
            runCatching {
                synchronized(target) {
                    // Do not let a delayed clear delete a newer position from a
                    // replayed video: a save after this clear supersedes it,
                    // whether its write has landed yet (cache holds the newer
                    // value) or already replaced the stored one.
                    val superseded = cache[key]?.let { it != storedValue } == true
                    if (!superseded && target.getLong(key, 0L) == storedValue) {
                        target.remove(key)
                        target.flush()
                    }
                }
            }
        }
    }

    internal fun keyFor(mediaId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(mediaId.toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return "video_$hex"
    }

    private const val MIN_PERSIST_DELTA_MS = 5_000L
    private const val COMPLETION_CLEAR_MARGIN_MS = 1_500L
}

/** Do not resume near the end; completed videos should start from the beginning next time. */
internal fun videoResumeTarget(savedPositionMs: Long, durationMs: Long): Long? {
    if (savedPositionMs < MIN_RESUME_POSITION_MS || durationMs <= 0L) return null
    if (durationMs - savedPositionMs <= VIDEO_RESUME_END_MARGIN_MS) return null
    return savedPositionMs.coerceAtMost(durationMs - VIDEO_RESUME_END_MARGIN_MS)
}

internal fun isVideoResumePositionConfirmed(actualPositionMs: Long, expectedPositionMs: Long): Boolean =
    expectedPositionMs > 0L &&
        // The exact boundary is rejected so an opening timestamp cannot masquerade as a seek.
        abs(actualPositionMs - expectedPositionMs) < RESUME_POSITION_CONFIRMATION_MS

private const val MIN_RESUME_POSITION_MS = 2_000L
private const val VIDEO_RESUME_END_MARGIN_MS = 1_500L
private const val RESUME_POSITION_CONFIRMATION_MS = 1_500L
