package com.drdisagree.teledrive.desktop.media.player

import java.util.Locale
import java.util.prefs.Preferences
import kotlin.math.roundToInt

internal const val DEFAULT_SUBTITLE_VERTICAL_POSITION = 0.88f

internal data class SubtitleAppearance(
    val useSystemDefaults: Boolean = true,
    val fontFamily: String = "Segoe UI",
    val textScale: Float = 1f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textOpacity: Float = 1f,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.65f,
    val windowColor: Int = 0x00000000,
    val windowOpacity: Float = 0f,
    val edgeType: Int = EDGE_OUTLINE,
    val edgeColor: Int = 0xFF000000.toInt(),
    val edgeOpacity: Float = 1f,
    val verticalPosition: Float = DEFAULT_SUBTITLE_VERTICAL_POSITION
) {
    fun normalized(): SubtitleAppearance = copy(
        fontFamily = fontFamily.takeIf(String::isNotBlank) ?: "Segoe UI",
        textScale = textScale.coerceIn(0.5f, 2.5f),
        textOpacity = textOpacity.coerceIn(0f, 1f),
        backgroundOpacity = backgroundOpacity.coerceIn(0f, 1f),
        windowOpacity = windowOpacity.coerceIn(0f, 1f),
        edgeType = edgeType.coerceIn(EDGE_NONE, EDGE_DEPRESSED),
        edgeOpacity = edgeOpacity.coerceIn(0f, 1f),
        verticalPosition = verticalPosition.coerceIn(0.08f, 0.92f)
    )
}

internal object SubtitleAppearanceStore {
    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/subtitle-appearance")
    }

    fun load(): SubtitleAppearance = runCatching {
        SubtitleAppearance(
            useSystemDefaults = preferences.getBoolean("use_system_defaults", true),
            fontFamily = preferences.get("font_family", "Segoe UI"),
            textScale = preferences.getFloat("text_scale", 1f),
            textColor = preferences.getInt("text_color", 0xFFFFFFFF.toInt()),
            textOpacity = preferences.getFloat("text_opacity", 1f),
            backgroundColor = preferences.getInt("background_color", 0xFF000000.toInt()),
            backgroundOpacity = preferences.getFloat("background_opacity", 0.65f),
            windowColor = preferences.getInt("window_color", 0x00000000),
            windowOpacity = preferences.getFloat("window_opacity", 0f),
            edgeType = preferences.getInt("edge_type", EDGE_OUTLINE),
            edgeColor = preferences.getInt("edge_color", 0xFF000000.toInt()),
            edgeOpacity = preferences.getFloat("edge_opacity", 1f),
            verticalPosition = preferences.getFloat(
                "vertical_position",
                DEFAULT_SUBTITLE_VERTICAL_POSITION
            )
        ).normalized()
    }.getOrDefault(SubtitleAppearance())

    fun save(appearance: SubtitleAppearance) {
        val value = appearance.normalized()
        runCatching {
            preferences.putBoolean("use_system_defaults", value.useSystemDefaults)
            preferences.put("font_family", value.fontFamily)
            preferences.putFloat("text_scale", value.textScale)
            preferences.putInt("text_color", value.textColor)
            preferences.putFloat("text_opacity", value.textOpacity)
            preferences.putInt("background_color", value.backgroundColor)
            preferences.putFloat("background_opacity", value.backgroundOpacity)
            preferences.putInt("window_color", value.windowColor)
            preferences.putFloat("window_opacity", value.windowOpacity)
            preferences.putInt("edge_type", value.edgeType)
            preferences.putInt("edge_color", value.edgeColor)
            preferences.putFloat("edge_opacity", value.edgeOpacity)
            preferences.putFloat("vertical_position", value.verticalPosition)
            preferences.flush()
        }
    }
}

/**
 * VLC 3 applies Freetype subtitle appearance as per-media options. Changes therefore take
 * effect when the current media is safely recreated; the caller preserves its playback time.
 *
 * VLC has one text-background layer, so the Android text-background and window layers are
 * alpha-composited before being passed to Freetype. Raised/depressed edges are approximated
 * with the available outline/shadow renderer.
 */
internal fun SubtitleAppearance.toVlcMediaOptions(videoHeight: Int): List<String> {
    val value = normalized()
    val options = mutableListOf<String>()

    if (!value.useSystemDefaults) {
        val (backgroundRgb, backgroundAlpha) = compositeSubtitleBackground(
            foregroundColor = value.backgroundColor,
            foregroundOpacity = value.backgroundOpacity,
            backgroundColor = value.windowColor,
            backgroundOpacity = value.windowOpacity
        )
        options += ":freetype-font=${value.fontFamily}"
        options += ":freetype-rel-fontsize=${(16f * value.textScale).roundToInt().coerceIn(8, 40)}"
        options += ":freetype-color=${vlcRgb(value.textColor)}"
        options += ":freetype-opacity=${(value.textOpacity * 255f).roundToInt()}"
        options += ":freetype-background-color=${vlcRgb(backgroundRgb)}"
        options += ":freetype-background-opacity=${backgroundAlpha}"

        val hasOutline = value.edgeType == EDGE_OUTLINE ||
            value.edgeType == EDGE_RAISED || value.edgeType == EDGE_DEPRESSED
        val hasShadow = value.edgeType == EDGE_SHADOW ||
            value.edgeType == EDGE_RAISED || value.edgeType == EDGE_DEPRESSED
        options += ":freetype-outline-thickness=${if (hasOutline) 4 else 0}"
        options += ":freetype-outline-color=${vlcRgb(value.edgeColor)}"
        options += ":freetype-outline-opacity=${if (hasOutline) (value.edgeOpacity * 255f).roundToInt() else 0}"
        options += ":freetype-shadow-color=${vlcRgb(value.edgeColor)}"
        options += ":freetype-shadow-opacity=${if (hasShadow) (value.edgeOpacity * 160f).roundToInt() else 0}"
    }

    if (videoHeight > 0 &&
        kotlin.math.abs(value.verticalPosition - DEFAULT_SUBTITLE_VERTICAL_POSITION) > 0.001f
    ) {
        // VLC's primary sub-margin is an absolute pixel offset from its default subtitle
        // placement. Negative values move the text lower; positive values move it higher.
        val margin = (
            (DEFAULT_SUBTITLE_VERTICAL_POSITION - value.verticalPosition) * videoHeight
        ).roundToInt().coerceIn(-videoHeight / 2, videoHeight / 2)
        options += ":sub-margin=$margin"
    }

    return options
}

internal fun compositeSubtitleBackground(
    foregroundColor: Int,
    foregroundOpacity: Float,
    backgroundColor: Int,
    backgroundOpacity: Float
): Pair<Int, Int> {
    val foregroundAlpha = foregroundOpacity.coerceIn(0f, 1f)
    val backgroundAlpha = backgroundOpacity.coerceIn(0f, 1f)
    val outputAlpha = foregroundAlpha + backgroundAlpha * (1f - foregroundAlpha)
    if (outputAlpha <= 0f) return 0x000000 to 0

    fun channel(shift: Int): Int {
        val foreground = (foregroundColor ushr shift) and 0xFF
        val background = (backgroundColor ushr shift) and 0xFF
        val value = foreground * foregroundAlpha +
            background * backgroundAlpha * (1f - foregroundAlpha)
        return (value / outputAlpha).roundToInt().coerceIn(0, 255)
    }

    val rgb = (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
    return rgb to (outputAlpha * 255f).roundToInt().coerceIn(0, 255)
}

private fun vlcRgb(color: Int): String =
    String.format(Locale.ROOT, "0x%06X", color and 0xFFFFFF)

internal const val EDGE_NONE = 0
internal const val EDGE_OUTLINE = 1
internal const val EDGE_SHADOW = 2
internal const val EDGE_RAISED = 3
internal const val EDGE_DEPRESSED = 4
