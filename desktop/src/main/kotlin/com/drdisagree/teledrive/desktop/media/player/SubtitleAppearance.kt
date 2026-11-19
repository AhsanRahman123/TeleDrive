package com.drdisagree.teledrive.desktop.media.player

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

    fun load(): SubtitleAppearance = load(preferences)

    internal fun load(source: Preferences): SubtitleAppearance = runCatching {
        SubtitleAppearance(
            useSystemDefaults = source.getBoolean("use_system_defaults", true),
            fontFamily = source.get("font_family", "Segoe UI"),
            textScale = source.getFloat("text_scale", 1f),
            textColor = source.getInt("text_color", 0xFFFFFFFF.toInt()),
            textOpacity = source.getFloat("text_opacity", 1f),
            backgroundColor = source.getInt("background_color", 0xFF000000.toInt()),
            backgroundOpacity = source.getFloat("background_opacity", 0.65f),
            windowColor = source.getInt("window_color", 0x00000000),
            windowOpacity = source.getFloat("window_opacity", 0f),
            edgeType = source.getInt("edge_type", EDGE_OUTLINE),
            edgeColor = source.getInt("edge_color", 0xFF000000.toInt()),
            edgeOpacity = source.getFloat("edge_opacity", 1f),
            verticalPosition = source.getFloat(
                "vertical_position",
                DEFAULT_SUBTITLE_VERTICAL_POSITION
            )
        ).normalized()
    }.getOrDefault(SubtitleAppearance())

    fun save(appearance: SubtitleAppearance) = save(preferences, appearance)

    internal fun save(target: Preferences, appearance: SubtitleAppearance) {
        val value = appearance.normalized()
        runCatching {
            target.putBoolean("use_system_defaults", value.useSystemDefaults)
            target.put("font_family", value.fontFamily)
            target.putFloat("text_scale", value.textScale)
            target.putInt("text_color", value.textColor)
            target.putFloat("text_opacity", value.textOpacity)
            target.putInt("background_color", value.backgroundColor)
            target.putFloat("background_opacity", value.backgroundOpacity)
            target.putInt("window_color", value.windowColor)
            target.putFloat("window_opacity", value.windowOpacity)
            target.putInt("edge_type", value.edgeType)
            target.putInt("edge_color", value.edgeColor)
            target.putFloat("edge_opacity", value.edgeOpacity)
            target.putFloat("vertical_position", value.verticalPosition)
            target.flush()
        }
    }
}

/**
 * VLC 3's Freetype text-renderer settings are LibVLC instance options, not reliable
 * per-media options. Build these arguments before creating the MediaPlayerFactory so
 * the text renderer receives them when its module is instantiated.
 */
internal fun SubtitleAppearance.toVlcFactoryArguments(videoHeight: Int = 1080): List<String> {
    val value = normalized()
    val options = mutableListOf<String>()

    if (!value.useSystemDefaults) {
        // Select VLC's freetype renderer for text subtitles so the style
        // below actually applies to the tracks freetype renders (SRT and
        // other plain-text formats). VLC-defaults mode emits no renderer
        // argument and keeps VLC's own choice.
        //
        // There is deliberately NO attempt to disable libass here. VLC
        // 3.0.21 has no such switch: no config item named "libass" exists in
        // libvlccore or in the libass plugin (its only option is
        // "ssa-fontsdir"), and libvlc treats an unknown instance option as
        // fatal — libvlc_new returns NULL and factory creation throws.
        // Emitting "--no-libass" therefore made EVERY custom appearance fail
        // on real hardware (proven against the 3.0.21 binaries: the exact
        // argument list below creates an instance; adding "--no-libass"
        // makes libvlc_new return NULL). ASS/SSA tracks keep their script
        // styling via libass — a VLC platform limit the settings dialog
        // discloses — they are not broken, just not restylable.
        options += "--text-renderer=freetype"
        val (backgroundRgb, backgroundAlpha) = compositeSubtitleBackground(
            foregroundColor = value.backgroundColor,
            foregroundOpacity = value.backgroundOpacity,
            backgroundColor = value.windowColor,
            backgroundOpacity = value.windowOpacity
        )
        options += "--freetype-font=${value.fontFamily}"
        options += "--freetype-rel-fontsize=${(16f * value.textScale).roundToInt().coerceIn(8, 40)}"
        options += "--freetype-color=${value.textColor and 0xFFFFFF}"
        options += "--freetype-opacity=${(value.textOpacity * 255f).roundToInt()}"
        options += "--freetype-background-color=$backgroundRgb"
        options += "--freetype-background-opacity=$backgroundAlpha"

        val hasOutline = value.edgeType == EDGE_OUTLINE ||
            value.edgeType == EDGE_RAISED || value.edgeType == EDGE_DEPRESSED
        val hasShadow = value.edgeType == EDGE_SHADOW ||
            value.edgeType == EDGE_RAISED || value.edgeType == EDGE_DEPRESSED
        options += "--freetype-outline-thickness=${if (hasOutline) 4 else 0}"
        options += "--freetype-outline-color=${value.edgeColor and 0xFFFFFF}"
        options += "--freetype-outline-opacity=${if (hasOutline) (value.edgeOpacity * 255f).roundToInt() else 0}"
        options += "--freetype-shadow-color=${value.edgeColor and 0xFFFFFF}"
        options += "--freetype-shadow-opacity=${if (hasShadow) (value.edgeOpacity * 160f).roundToInt() else 0}"
    }

    if (videoHeight > 0 &&
        kotlin.math.abs(value.verticalPosition - DEFAULT_SUBTITLE_VERTICAL_POSITION) > 0.001f
    ) {
        // sub-margin is a LibVLC instance option. Keep the default height as a
        // reference scale; VLC applies the margin to the active video's subtitle area.
        val margin = (
            (value.verticalPosition - DEFAULT_SUBTITLE_VERTICAL_POSITION) * videoHeight
        ).roundToInt().coerceIn(-videoHeight / 2, videoHeight * 4 / 5)
        options += "--sub-margin=$margin"
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

/**
 * Every LibVLC instance option this app may emit, verified against the VLC
 * 3.0.21 binaries the desktop build bundles: the core options live in
 * libvlccore ("text-renderer", "sub-margin", "no-video-title-show", "quiet")
 * and the rest are the config items of the shipped freetype text-renderer
 * plugin (plugins/text_renderer/libfreetype_plugin.dll). This list exists
 * because libvlc_new fails outright on an unknown option — an earlier build
 * emitted the non-existent "--no-libass" and every custom subtitle
 * appearance silently fell back to default rendering. The unit tests assert
 * nothing outside this set is ever emitted; extend it only with options
 * verified in the bundled VLC's binaries.
 */
internal val VERIFIED_VLC_FACTORY_OPTIONS: Set<String> = setOf(
    "no-video-title-show",
    "quiet",
    "text-renderer",
    "sub-margin",
    "freetype-font",
    "freetype-monofont",
    "freetype-fontsize",
    "freetype-rel-fontsize",
    "freetype-color",
    "freetype-opacity",
    "freetype-background-color",
    "freetype-background-opacity",
    "freetype-outline-thickness",
    "freetype-outline-color",
    "freetype-outline-opacity",
    "freetype-shadow-color",
    "freetype-shadow-opacity",
    "freetype-shadow-angle",
    "freetype-shadow-distance",
    "freetype-bold",
    "freetype-effect",
    "freetype-text-direction",
    "freetype-yuvp"
)

/** The bare option name of one "--name=value" / "--name" factory argument. */
internal fun vlcOptionName(argument: String): String =
    argument.removePrefix("--").substringBefore('=')

internal const val EDGE_NONE = 0
internal const val EDGE_OUTLINE = 1
internal const val EDGE_SHADOW = 2
internal const val EDGE_RAISED = 3
internal const val EDGE_DEPRESSED = 4
