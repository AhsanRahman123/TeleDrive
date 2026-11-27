package com.drdisagree.teledrive.desktop.media.player

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.delay

/**
 * One read of the native player's CURRENT media: the mrl of the media the
 * player actually holds at read time, plus its raw TITLE / ARTIST / ALBUM
 * meta (not yet mojibake-repaired).
 */
internal data class MediaMetaSnapshot(
    val currentMrl: String?,
    val title: String,
    val artist: String,
    val album: String
)

/** Display-ready audio meta for one loaded entry, mojibake-repaired. */
internal data class AudioMeta(
    val title: String,
    val artist: String,
    val album: String
) {
    val hasDisplayableTags: Boolean
        get() = title.isNotBlank() || artist.isNotBlank()
}

/**
 * Loads the display meta for the entry identified by [expectedMrl] from a
 * native player whose current media is only observable indirectly.
 *
 * PC 1.6.26 P1 root cause: `AudioStage` polled `player.media().meta()` —
 * the meta of WHATEVER media the native player held at that instant. On a
 * switch, the poll started immediately while the player's own start
 * command was still queued behind stop+start on the session's serial
 * executor, so the first poll read the PREVIOUS track's (non-blank) tags,
 * satisfied the old exit condition, and never re-read: the display lagged
 * exactly one entry behind playback on every switch.
 *
 * This loader only accepts a snapshot whose current-media mrl
 * (`media().info()?.mrl()`) matches [expectedMrl] after [normalizeMediaMrl].
 * Snapshots of any other media (above all the outgoing track) are ignored
 * and polling continues. A matching snapshot with no tags yet keeps the
 * poll going (tags land shortly after start); the last matching snapshot
 * is returned when attempts run out, and null when no snapshot ever
 * matched — the caller then keeps the entry-name fallback, exactly as the
 * pre-fix code did when no meta arrived at all. Wrong tags are therefore
 * impossible; the worst case is the fallback name.
 *
 * Accepted fields pass through [repairMetaText] — the vlcj/JNA mojibake
 * seam is preserved unchanged (see MetaTextRepair).
 */
internal class AudioMetaLoader(
    private val expectedMrl: String,
    private val snapshot: () -> MediaMetaSnapshot?,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val pollDelayMs: Long = DEFAULT_POLL_DELAY_MS
) {
    suspend fun load(): AudioMeta? {
        var lastMatching: AudioMeta? = null
        repeat(maxAttempts) { attempt ->
            val snap = runCatching { snapshot() }.getOrNull()
            if (snap != null && mediaMrlMatches(snap.currentMrl, expectedMrl)) {
                val meta = AudioMeta(
                    title = repairMetaText(snap.title),
                    artist = repairMetaText(snap.artist),
                    album = repairMetaText(snap.album)
                )
                if (meta.hasDisplayableTags) return meta
                lastMatching = meta
            }
            if (attempt < maxAttempts - 1 && pollDelayMs > 0) delay(pollDelayMs)
        }
        return lastMatching
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 12
        const val DEFAULT_POLL_DELAY_MS = 500L
    }
}

/** True when [currentMrl] identifies the same media as [expectedMrl]. */
internal fun mediaMrlMatches(currentMrl: String?, expectedMrl: String): Boolean {
    if (currentMrl.isNullOrBlank()) return false
    if (currentMrl == expectedMrl) return true
    val current = normalizeMediaMrl(currentMrl)
    val expected = normalizeMediaMrl(expectedMrl)
    return current.isNotEmpty() && current == expected
}

/**
 * Canonicalises an mrl for equality comparison between the form this app
 * passes to the player (a plain filesystem path, or a loopback stream URL)
 * and the form libVLC reports back for its current media (commonly a
 * `file://` URI with forward slashes and percent-escapes). Applied to BOTH
 * sides, so any consistent representation converges: scheme stripped,
 * leading slashes stripped, percent-escapes decoded as UTF-8, backslashes
 * unified to slashes, case folded (the desktop target's filesystem is
 * case-insensitive, and both sides are folded identically).
 */
internal fun normalizeMediaMrl(mrl: String?): String {
    var value = mrl?.trim().orEmpty()
    if (value.isEmpty()) return ""
    val schemeEnd = value.indexOf("://")
    if (schemeEnd >= 0) {
        value = value.substring(schemeEnd + 3)
    } else {
        // A bare scheme ("file:C:\..."): a colon NOT at drive-letter
        // position (index 1) introduces a scheme, not a path.
        val colon = value.indexOf(':')
        if (colon > 1) value = value.substring(colon + 1)
    }
    value = value.trimStart('/')
    value = percentDecodeUtf8(value)
    value = value.replace('\\', '/')
    return value.lowercase()
}

/** Decodes %XX escapes as UTF-8 bytes; a malformed escape is left as-is. */
private fun percentDecodeUtf8(value: String): String {
    if (!value.contains('%')) return value
    val bytes = ByteArrayOutputStream(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char == '%' && index + 2 <= value.lastIndex) {
            val hex = value.substring(index + 1, index + 3)
            val decoded = hex.toIntOrNull(16)
            if (decoded != null) {
                bytes.write(decoded)
                index += 3
                continue
            }
        }
        bytes.write(char.toString().toByteArray(Charsets.UTF_8))
        index++
    }
    return String(bytes.toByteArray(), Charsets.UTF_8)
}
