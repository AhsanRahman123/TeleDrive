package com.drdisagree.teledrive.presentation.preview

import android.content.Context
import android.graphics.Color as AndroidColor
import android.graphics.Typeface
import android.view.accessibility.CaptioningManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView

data class SubtitleAppearance(
    val useSystemDefaults: Boolean = true,
    val fontFamily: String = "sans-serif",
    val textScale: Float = 1f,
    val textColor: Int = AndroidColor.WHITE,
    val textOpacity: Float = 1f,
    val backgroundColor: Int = AndroidColor.BLACK,
    val backgroundOpacity: Float = 0.65f,
    val windowColor: Int = AndroidColor.TRANSPARENT,
    val windowOpacity: Float = 0f,
    val edgeType: Int = CaptionStyleCompat.EDGE_TYPE_OUTLINE,
    val edgeColor: Int = AndroidColor.BLACK,
    val verticalPosition: Float = DEFAULT_SUBTITLE_VERTICAL_POSITION
) {
    fun normalized() = copy(
        textScale = textScale.coerceIn(0.5f, 2.5f),
        textOpacity = textOpacity.coerceIn(0f, 1f),
        backgroundOpacity = backgroundOpacity.coerceIn(0f, 1f),
        windowOpacity = windowOpacity.coerceIn(0f, 1f),
        verticalPosition = verticalPosition.coerceIn(0.08f, 0.92f)
    )
}

object SubtitleAppearanceStore {
    private const val PREFS = "player_subtitle_appearance"

    fun load(context: Context): SubtitleAppearance {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SubtitleAppearance(
            useSystemDefaults = prefs.getBoolean("use_system_defaults", true),
            fontFamily = prefs.getString("font_family", "sans-serif") ?: "sans-serif",
            textScale = prefs.getFloat("text_scale", 1f),
            textColor = prefs.getInt("text_color", AndroidColor.WHITE),
            textOpacity = prefs.getFloat("text_opacity", 1f),
            backgroundColor = prefs.getInt("background_color", AndroidColor.BLACK),
            backgroundOpacity = prefs.getFloat("background_opacity", 0.65f),
            windowColor = prefs.getInt("window_color", AndroidColor.TRANSPARENT),
            windowOpacity = prefs.getFloat("window_opacity", 0f),
            edgeType = prefs.getInt("edge_type", CaptionStyleCompat.EDGE_TYPE_OUTLINE),
            edgeColor = prefs.getInt("edge_color", AndroidColor.BLACK),
            verticalPosition = prefs.getFloat("vertical_position", DEFAULT_SUBTITLE_VERTICAL_POSITION)
        ).normalized()
    }

    fun save(context: Context, appearance: SubtitleAppearance) {
        val value = appearance.normalized()
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("use_system_defaults", value.useSystemDefaults)
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
            .putFloat("vertical_position", value.verticalPosition)
            .apply()
    }
}

@OptIn(UnstableApi::class)
fun applySubtitleAppearance(
    subtitleView: SubtitleView,
    context: Context,
    appearance: SubtitleAppearance
) {
    val style = appearance.normalized()
    subtitleView.setApplyEmbeddedStyles(false)
    subtitleView.setApplyEmbeddedFontSizes(false)

    if (style.useSystemDefaults) {
        subtitleView.setUserDefaultStyle()
        val captioning = context.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
        val systemScale = captioning?.fontScale?.takeIf { it > 0f } ?: 1f
        subtitleView.setFractionalTextSize(
            (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * systemScale * style.textScale)
                .coerceIn(0.015f, 0.2f),
            true
        )
        return
    }

    val captionStyle = CaptionStyleCompat(
        foregroundWithOpacity(style.textColor, style.textOpacity),
        foregroundWithOpacity(style.backgroundColor, style.backgroundOpacity),
        foregroundWithOpacity(style.windowColor, style.windowOpacity),
        style.edgeType,
        style.edgeColor,
        Typeface.create(style.fontFamily, Typeface.NORMAL)
    )
    subtitleView.setStyle(captionStyle)
    subtitleView.setFractionalTextSize(
        (SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * style.textScale).coerceIn(0.015f, 0.2f),
        true
    )
}

private fun foregroundWithOpacity(color: Int, opacity: Float): Int =
    AndroidColor.argb(
        (opacity.coerceIn(0f, 1f) * 255f).toInt(),
        AndroidColor.red(color),
        AndroidColor.green(color),
        AndroidColor.blue(color)
    )
