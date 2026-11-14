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

    fun loadPosition(mediaId: String): Long = runCatching {
        val key = keyFor(mediaId)
        preferences.getLong(key, 0L).coerceAtLeast(0L).also { saved ->
            if (saved > 0L) lastPersisted[key] = saved
        }
    }.getOrDefault(0L)

    fun savePosition(
        mediaId: String,
        positionMs: Long,
        durationMs: Long,
        force: Boolean = false
    ) {
        if (positionMs <= 0L) return
        if (durationMs > 0L && durationMs - positionMs <= COMPLETION_CLEAR_MARGIN_MS) {
            clear(mediaId)
            return
        }

        val key = keyFor(mediaId)
        val previous = lastPersisted[key]
        if (!force && previous != null && abs(positionMs - previous) < MIN_PERSIST_DELTA_MS) return
        lastPersisted[key] = positionMs

        ioScope.launch {
            runCatching {
                synchronized(preferences) {
                    // An older write can be scheduled before a newer time event but run later
                    // on the IO pool. Skip it if the in-memory latest value has moved on.
                    if (lastPersisted[key] == positionMs) {
                        preferences.putLong(key, positionMs)
                        preferences.flush()
                    }
                }
            }.onFailure {
                lastPersisted.remove(key, positionMs)
            }
        }
    }

    fun clear(mediaId: String) {
        val key = keyFor(mediaId)
        lastPersisted.remove(key)
        ioScope.launch {
            runCatching {
                synchronized(preferences) {
                    // Do not let a delayed clear delete a newer position from a replayed video.
                    if (!lastPersisted.containsKey(key)) {
                        preferences.remove(key)
                        preferences.flush()
                    }
                }
            }
        }
    }

    private fun keyFor(mediaId: String): String {
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

private const val MIN_RESUME_POSITION_MS = 2_000L
private const val VIDEO_RESUME_END_MARGIN_MS = 1_500L
