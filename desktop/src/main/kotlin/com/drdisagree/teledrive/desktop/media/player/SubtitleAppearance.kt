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
        options += "--freetype-rel-fontsize=${freetypeRelFontSize(value.textScale)}"
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
        options += "--sub-margin=${subtitleSubMargin(value.verticalPosition, videoHeight)}"
    }

    return options
}

/**
 * The same appearance as **per-media options** (`:name=value`), for passing
 * to a media start on an already-running player (PC-13 rescue, report 18).
 *
 * This is the route the VLC ecosystem itself uses (LibVLCSharp's official
 * Q&A and VLC-Android's VLCOptions both set `freetype-*` through media
 * options): options attached to an input item are inherited by the module
 * tree the input spawns (vout -> spu -> text renderer), so the renderer
 * created for that media picks them up — with the player instance, its
 * event listener and its render surface left completely untouched.
 *
 * It is also strictly safer than instance arguments: an unrecognised
 * *instance* argument makes libvlc_new return NULL (the report-16
 * `--no-libass` failure), while an unrecognised *media* option is simply
 * ignored. The option names and values are exactly the verified factory
 * set, re-prefixed.
 */
internal fun SubtitleAppearance.toVlcMediaOptions(videoHeight: Int = 1080): List<String> =
    toVlcFactoryArguments(videoHeight).map { factoryArgument ->
        ":" + factoryArgument.removePrefix("--")
    }

/**
 * The full media-option list for one media start on the lifelong player
 * (PC-13): the appearance's media options, plus — for an appearance-Apply
 * reload — the resume position carried by `:start-time` (part of the
 * start itself, not a post-start seek) and `:start-paused` when the video
 * was paused. For an ordinary first start [restartPositionMs] is null and
 * only the appearance options are emitted. Pure and pinned by tests so the
 * Apply sequence (switch track -> Apply -> same position, same play state,
 * controls untouched) cannot silently regress.
 */
internal fun mediaStartOptions(
    appearance: SubtitleAppearance,
    restartPositionMs: Long?,
    restartWasPlaying: Boolean
): List<String> = buildList {
    addAll(appearance.toVlcMediaOptions())
    if (restartPositionMs != null) {
        if (restartPositionMs >= 1_000L) {
            add(":start-time=${restartPositionMs / 1000.0}")
        }
        if (!restartWasPlaying) add(":start-paused")
    }
}

/**
 * The `freetype-rel-fontsize` value for a text scale (1.0 = 100%).
 *
 * VLC 3.0's freetype renderer treats this option as a DIVISOR, not a size:
 * `GetFontSize()` in modules/text_renderer/freetype/freetype.c computes
 * `i_size = fmt_out.video.i_height / i_ratio` when no absolute
 * `freetype-fontsize` is set. The mapping must therefore INVERT the scale —
 * a larger requested scale needs a smaller divisor. The previous direct
 * mapping (16 × scale) did the opposite: raising Text size in the dialog
 * made the rendered subtitles smaller (user test #2, PC-9). At scale 1.0
 * the value stays at VLC's own default of 16, so 100% renders exactly as
 * VLC renders it.
 *
 * The coerce range keeps the effective scale honest across the dialog's
 * 0.5–2.5 slider range: 16/0.5 = 32 and 16/2.5 ≈ 6 both stay reachable, so
 * the effective glyph size is strictly increasing over the whole range.
 * (The option's suggested value list in the module descriptor — 20/18/16/12/6 —
 * is a UI hint for VLC's own preferences dialog; instance-option values are
 * not clamped to it, as the previous build's 8–40 range already proved by
 * rendering at visibly different, if inverted, sizes.)
 */
internal fun freetypeRelFontSize(textScale: Float): Int =
    (16f / textScale.coerceIn(0.5f, 2.5f)).roundToInt().coerceIn(5, 48)

/**
 * The effective glyph height (px) VLC's freetype renderer produces for a
 * video of [videoHeight] px with the given relative font size, under the
 * documented formula `height / relFontSize`. Exposed so tests can pin the
 * direction of the size mapping against VLC's own arithmetic.
 */
internal fun freetypeEffectiveGlyphHeight(videoHeight: Int, relFontSize: Int): Float =
    videoHeight.toFloat() / relFontSize

/**
 * The `sub-margin` value (px) for a vertical position fraction.
 *
 * VLC's own subtitle-position hotkeys define the sign: SUBPOS_UP ("move
 * subtitles higher") INCREMENTS sub-margin and SUBPOS_DOWN decrements it
 * (modules/control/hotkeys.c), so a positive margin moves subtitles UP.
 * The app's position fraction grows DOWNWARD (0.88 = near the bottom), so
 * raising the position above the default (a smaller fraction, higher on
 * screen) must produce a POSITIVE margin: margin = (default − position) ×
 * height. The previous (position − default) mapping inverted the slider
 * the same way the font size was inverted (PC-9).
 */
internal fun subtitleSubMargin(verticalPosition: Float, videoHeight: Int): Int =
    ((DEFAULT_SUBTITLE_VERTICAL_POSITION - verticalPosition) * videoHeight)
        .roundToInt()
        .coerceIn(-videoHeight / 2, videoHeight * 4 / 5)

/**
 * The subtitle font library offered by the picker (PC-10).
 *
 * The picker used to enumerate every font family installed on the machine,
 * which on the user's PC means a long list of Bengali shareware fonts he
 * never picks for subtitles. The library is now a curated list of widely
 * used families, in two tiers:
 *
 * - [SUBTITLE_FONT_CORE]: families that ship with Windows 10/11 by default,
 *   so they resolve through freetype on any typical Windows machine. Always
 *   offered, even if font enumeration fails.
 * - [SUBTITLE_FONT_EXTENDED]: popular open-licensed families that do NOT
 *   ship with Windows. Offered only when actually installed (detected
 *   against the system font list), so the picker never names a font
 *   freetype cannot resolve.
 */
internal val SUBTITLE_FONT_CORE: List<String> = listOf(
    "Arial",
    "Arial Narrow",
    "Calibri",
    "Cambria",
    "Comic Sans MS",
    "Consolas",
    "Courier New",
    "Georgia",
    "Segoe UI",
    "Tahoma",
    "Times New Roman",
    "Trebuchet MS",
    "Verdana"
)

internal val SUBTITLE_FONT_EXTENDED: List<String> = listOf(
    "Helvetica Neue",
    "Lato",
    "Montserrat",
    "Noto Sans",
    "Noto Sans Bengali",
    "Open Sans",
    "Roboto"
)

/**
 * Resolves the library against the fonts actually installed: the core list
 * verbatim, then each extended family that is installed — spelled exactly
 * as the system reports it, so the name handed to `--freetype-font` is the
 * name freetype will look up. A saved [currentSelection] that is not in the
 * library (e.g. chosen before this list existed) is appended so an existing
 * choice is never silently dropped from the picker.
 */
internal fun resolveSubtitleFontLibrary(
    installedFamilyNames: Collection<String>,
    currentSelection: String
): List<String> {
    val installedByLowercase = installedFamilyNames.associateBy { it.lowercase() }
    val resolved = SUBTITLE_FONT_CORE.toMutableList()
    SUBTITLE_FONT_EXTENDED.forEach { family ->
        installedByLowercase[family.lowercase()]?.let { installedName ->
            if (installedName !in resolved) resolved += installedName
        }
    }
    if (currentSelection.isNotBlank() &&
        resolved.none { it.equals(currentSelection, ignoreCase = true) }
    ) {
        resolved += currentSelection
    }
    return resolved
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
