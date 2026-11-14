package com.drdisagree.teledrive.presentation.preview

import android.content.Context
import android.graphics.Typeface
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import kotlin.math.roundToInt

internal const val DEFAULT_SUBTITLE_VERTICAL_POSITION = 0.88f

internal const val SUBTITLE_EDGE_NONE = 0
internal const val SUBTITLE_EDGE_OUTLINE = 1
internal const val SUBTITLE_EDGE_SHADOW = 2
internal const val SUBTITLE_EDGE_RAISED = 3
internal const val SUBTITLE_EDGE_DEPRESSED = 4

/**
 * Android-only subtitle preferences for TeleDrive's Media3 SubtitleView.
 * This model and its SharedPreferences store are intentionally independent of Windows/VLC.
 */
internal data class AndroidSubtitleStyle(
    val useSystemDefaults: Boolean = true,
    val fontFamily: String = "sans-serif",
    val textScale: Float = 1f,
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val textOpacity: Float = 1f,
    val backgroundColor: Int = 0xFF000000.toInt(),
    val backgroundOpacity: Float = 0.65f,
    val windowColor: Int = 0x00000000,
    val windowOpacity: Float = 0f,
    val edgeType: Int = SUBTITLE_EDGE_OUTLINE,
    val edgeColor: Int = 0xFF000000.toInt(),
    val edgeOpacity: Float = 1f,
    val verticalPosition: Float = DEFAULT_SUBTITLE_VERTICAL_POSITION
) {
    fun normalized(): AndroidSubtitleStyle = copy(
        fontFamily = fontFamily.takeIf(String::isNotBlank) ?: "sans-serif",
        textScale = textScale.coerceIn(0.5f, 2.5f),
        textOpacity = textOpacity.coerceIn(0f, 1f),
        backgroundOpacity = backgroundOpacity.coerceIn(0f, 1f),
        windowOpacity = windowOpacity.coerceIn(0f, 1f),
        edgeType = edgeType.coerceIn(SUBTITLE_EDGE_NONE, SUBTITLE_EDGE_DEPRESSED),
        edgeOpacity = edgeOpacity.coerceIn(0f, 1f),
        verticalPosition = verticalPosition.coerceIn(0.08f, 0.92f)
    )
}

internal object AndroidSubtitleStyleStore {
    private const val PREFERENCES = "android_subtitle_style"

    fun load(context: Context): AndroidSubtitleStyle = runCatching {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        AndroidSubtitleStyle(
            useSystemDefaults = preferences.getBoolean("use_player_defaults", true),
            fontFamily = preferences.getString("font_family", "sans-serif") ?: "sans-serif",
            textScale = preferences.getFloat("text_scale", 1f),
            textColor = preferences.getInt("text_color", 0xFFFFFFFF.toInt()),
            textOpacity = preferences.getFloat("text_opacity", 1f),
            backgroundColor = preferences.getInt("background_color", 0xFF000000.toInt()),
            backgroundOpacity = preferences.getFloat("background_opacity", 0.65f),
            windowColor = preferences.getInt("window_color", 0x00000000),
            windowOpacity = preferences.getFloat("window_opacity", 0f),
            edgeType = preferences.getInt("edge_type", SUBTITLE_EDGE_OUTLINE),
            edgeColor = preferences.getInt("edge_color", 0xFF000000.toInt()),
            edgeOpacity = preferences.getFloat("edge_opacity", 1f),
            verticalPosition = preferences.getFloat(
                "vertical_position",
                DEFAULT_SUBTITLE_VERTICAL_POSITION
            )
        ).normalized()
    }.getOrDefault(AndroidSubtitleStyle())

    fun save(context: Context, appearance: AndroidSubtitleStyle) {
        val value = appearance.normalized()
        runCatching {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
                .putBoolean("use_player_defaults", value.useSystemDefaults)
                .putString("font_family", value.fontFamily)
                .putFloat("text_scale", value.textScale)
                .putInt("text_color", value.textColor)
                .putFloat("text_opacity", value.textOpacity)
                .putInt("background_color", value.backgroundColor)
                .putFloat("background_opacity", value.backgroundOpacity)
                .putInt("window_color", value.windowColor)
                .putFloat("window_opacity", value.windowOpacity)
                .putInt("edge_type", value.edgeType)
                .putInt("edge_color", value.edgeColor)
                .putFloat("edge_opacity", value.edgeOpacity)
                .putFloat("vertical_position", value.verticalPosition)
                .apply()
        }
    }
}

@OptIn(UnstableApi::class)
internal fun applyAndroidSubtitleStyle(playerView: PlayerView, appearance: AndroidSubtitleStyle) {
    val subtitleView: SubtitleView = playerView.subtitleView ?: return
    val value = appearance.normalized()

    // Use Android Media3's own styling API so embedded track styles cannot override the
    // Android user's selected font, colours, size or position.
    subtitleView.setApplyEmbeddedStyles(false)
    subtitleView.setApplyEmbeddedFontSizes(false)
    subtitleView.setStyle(value.toCaptionStyle())
    subtitleView.setFractionalTextSize(
        (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * value.textScale).coerceIn(0.01f, 0.2f)
    )
    subtitleView.setBottomPaddingFraction((1f - value.verticalPosition).coerceIn(0.02f, 0.5f))
}

@OptIn(UnstableApi::class)
private fun AndroidSubtitleStyle.toCaptionStyle(): CaptionStyleCompat {
    if (useSystemDefaults) return CaptionStyleCompat.DEFAULT

    val value = normalized()
    return CaptionStyleCompat(
        withOpacity(value.textColor, value.textOpacity),
        withOpacity(value.backgroundColor, value.backgroundOpacity),
        withOpacity(value.windowColor, value.windowOpacity),
        when (value.edgeType) {
            SUBTITLE_EDGE_OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
            SUBTITLE_EDGE_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            SUBTITLE_EDGE_RAISED -> CaptionStyleCompat.EDGE_TYPE_RAISED
            SUBTITLE_EDGE_DEPRESSED -> CaptionStyleCompat.EDGE_TYPE_DEPRESSED
            else -> CaptionStyleCompat.EDGE_TYPE_NONE
        },
        withOpacity(value.edgeColor, value.edgeOpacity),
        Typeface.create(value.fontFamily, Typeface.NORMAL)
    )
}

private fun withOpacity(color: Int, opacity: Float): Int {
    val existingAlpha = (color ushr 24) and 0xFF
    val alpha = (existingAlpha * opacity.coerceIn(0f, 1f)).roundToInt().coerceIn(0, 255)
    return (color and 0x00FFFFFF) or (alpha shl 24)
}
