package com.drdisagree.teledrive.desktop.media.player

import uk.co.caprica.vlcj.media.AudioTrackInfo
import uk.co.caprica.vlcj.media.TextTrackInfo
import uk.co.caprica.vlcj.media.TrackInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.util.Locale

internal const val VLC_TRACK_DISABLED = -1

internal fun MediaPlayer.audioTracks(fallbackPrefix: String): List<VlcTrack> {
    val info = runCatching { media().info()?.audioTracks().orEmpty() }
        .getOrDefault(emptyList<AudioTrackInfo>())
    val current = runCatching { audio().track() }.getOrDefault(VLC_TRACK_DISABLED)
    val descriptions = runCatching { audio().trackDescriptions() }.getOrDefault(emptyList())
    return descriptions.filter { it.id() != VLC_TRACK_DISABLED }
        .mapIndexed { position, description ->
            val track = info.firstOrNull { it.id() == description.id() }
            VlcTrack(
                id = description.id(),
                label = trackLabel(
                    description = description.description(),
                    track = track,
                    channels = track?.channels() ?: 0,
                    fallback = "$fallbackPrefix ${position + 1}"
                ),
                language = normalizeLanguage(track?.language()),
                selected = description.id() == current
            )
        }
}

internal fun MediaPlayer.textTracks(fallbackPrefix: String): List<VlcTrack> {
    val info = runCatching { media().info()?.textTracks().orEmpty() }
        .getOrDefault(emptyList<TextTrackInfo>())
    // Codec visibility for diagnosing doubled/unstyled subtitles: an ASS or
    // bitmap (PGS/DVB) track renders very differently from a plain text track.
    if (info.isNotEmpty()) {
        com.drdisagree.teledrive.core.common.SafeLog.d(
            "VlcTracks",
            "SPU tracks: " + info.joinToString { track ->
                "id=${track.id()} codec=${track.codecDescription()} lang=${track.language()}"
            }
        )
    }
    val current = runCatching { subpictures().track() }.getOrDefault(VLC_TRACK_DISABLED)
    val descriptions = runCatching { subpictures().trackDescriptions() }.getOrDefault(emptyList())
    return descriptions.filter { it.id() != VLC_TRACK_DISABLED }
        .mapIndexed { position, description ->
            val track = info.firstOrNull { it.id() == description.id() }
            VlcTrack(
                id = description.id(),
                label = trackLabel(
                    description = description.description(),
                    track = track,
                    channels = 0,
                    fallback = "$fallbackPrefix ${position + 1}"
                ),
                language = normalizeLanguage(track?.language()),
                selected = description.id() == current
            )
        }
}

private fun trackLabel(
    description: String?,
    track: TrackInfo?,
    channels: Int,
    fallback: String
): String {
    val language = normalizeLanguage(track?.language())
        .takeIf { it.isNotEmpty() }
        ?.let { Locale.forLanguageTag(it).displayLanguage }
        ?.takeIf { it.isNotBlank() }
    val parts = buildList {
        // Track descriptions cross the same vlcj/JNA native-string seam as
        // media meta, so embedded stream titles get the same guarded
        // mojibake repair (no-op for the usual ASCII language names).
        add(language ?: description?.takeIf { it.isNotBlank() }?.let(::repairMetaText) ?: fallback)
        track?.description()?.takeIf { it.isNotBlank() }?.let { add(repairMetaText(it)) }
        channelLabel(channels)?.let { add(it) }
        track?.codecDescription()?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return parts.distinct().joinToString(" · ")
}

private fun channelLabel(channels: Int): String? = when {
    channels <= 0 -> null
    channels >= 6 -> "${channels - 1}.1"
    else -> "$channels.0"
}

internal fun normalizeLanguage(raw: String?): String {
    val value = raw?.trim()?.lowercase(Locale.ROOT).orEmpty()
    return when {
        value.isEmpty() || value == "und" || value == "undefined" -> ""
        value.length == 2 -> value
        value.length == 3 -> Locale.getISOLanguages()
            .firstOrNull { Locale.forLanguageTag(it).isO3Language == value } ?: value

        else -> Locale.getISOLanguages().firstOrNull {
            Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).equals(value, true)
        }.orEmpty()
    }
}
