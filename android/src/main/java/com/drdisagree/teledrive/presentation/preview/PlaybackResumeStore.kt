package com.drdisagree.teledrive.presentation.preview

import android.content.Context
import java.security.MessageDigest

/**
 * Per-file playback checkpoints. Writes use apply() so periodic checkpoints never block playback.
 * The key is derived from the stable database file ID, not a temporary player/page instance.
 */
internal object PlaybackResumeStore {
    private const val PREFS = "video_playback_resume"
    private const val PREFIX = "position_"
    private const val COMPLETION_MARGIN_MS = 2_500L

    fun load(context: Context, fileId: String): Long =
        preferences(context).getLong(key(fileId), 0L).coerceAtLeast(0L)

    fun save(context: Context, fileId: String, positionMs: Long, durationMs: Long) {
        val prefs = preferences(context)
        val key = key(fileId)
        val safePosition = positionMs.coerceAtLeast(0L)
        if (durationMs > 0L && safePosition >= (durationMs - COMPLETION_MARGIN_MS).coerceAtLeast(0L)) {
            prefs.edit().remove(key).apply()
            return
        }
        if (prefs.getLong(key, Long.MIN_VALUE) == safePosition) return
        prefs.edit().putLong(key, safePosition).apply()
    }

    fun clear(context: Context, fileId: String) {
        preferences(context).edit().remove(key(fileId)).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(fileId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(fileId.toByteArray(Charsets.UTF_8))
        val encoded = buildString(digest.size * 2) {
            digest.forEach { byte -> append("%02x".format(byte)) }
        }
        return PREFIX + encoded
    }
}
