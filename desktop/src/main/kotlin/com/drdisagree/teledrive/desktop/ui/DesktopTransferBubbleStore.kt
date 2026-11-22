package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.presentation.platform.TransferBubblePreference
import com.drdisagree.teledrive.presentation.platform.TransferWidgetColor
import com.drdisagree.teledrive.presentation.platform.TransferWidgetStyle
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop store for the floating transfer widget: the master switch from
 * Settings, the shell style (bubble / bottom strip) and colour preset
 * (round 6), and the bubble's dragged position, in the same
 * java.util.prefs store family as the other desktop-only preferences
 * (picker positions, Transfers sort, player controls). Every value is
 * also exposed as a [StateFlow] so the overlay reacts the moment
 * Settings changes it — style and colour switch live, without a restart.
 *
 * Positions are stored as dp offsets of the bubble's top-left corner from
 * the window's top-left, so they survive resolution/DPI changes sanely;
 * the layout layer clamps them into the current window on every frame.
 */
internal object DesktopTransferBubbleStore : TransferBubblePreference {

    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/transfer-bubble")
    }

    private val _enabled = MutableStateFlow(loadEnabled(preferences))
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _style = MutableStateFlow(loadStyle(preferences))
    val style: StateFlow<TransferWidgetStyle> = _style.asStateFlow()

    private val _color = MutableStateFlow(loadColor(preferences))
    val color: StateFlow<TransferWidgetColor> = _color.asStateFlow()

    override fun isEnabled(): Boolean = _enabled.value

    override fun setEnabled(value: Boolean) {
        saveEnabled(preferences, value)
        _enabled.value = value
    }

    override fun widgetStyle(): TransferWidgetStyle = _style.value

    override fun setWidgetStyle(style: TransferWidgetStyle) {
        saveStyle(preferences, style)
        _style.value = style
    }

    override fun widgetColor(): TransferWidgetColor = _color.value

    override fun setWidgetColor(color: TransferWidgetColor) {
        saveColor(preferences, color)
        _color.value = color
    }

    /** The remembered bubble position, or null when it was never dragged. */
    fun loadPosition(): Pair<Float, Float>? = loadPosition(preferences)

    fun savePosition(xDp: Float, yDp: Float) = savePosition(preferences, xDp, yDp)

    internal fun loadEnabled(source: Preferences): Boolean =
        runCatching { source.getBoolean(KEY_ENABLED, DEFAULT_ENABLED) }
            .getOrDefault(DEFAULT_ENABLED)

    internal fun saveEnabled(target: Preferences, value: Boolean) {
        runCatching {
            target.putBoolean(KEY_ENABLED, value)
            target.flush()
        }
    }

    internal fun loadPosition(source: Preferences): Pair<Float, Float>? = runCatching {
        val x = source.getFloat(KEY_POSITION_X, Float.NaN)
        val y = source.getFloat(KEY_POSITION_Y, Float.NaN)
        // Report 40, fix 1: a non-finite stored value is corruption,
        // never a position — reject it here so no caller can trust it.
        if (!x.isFinite() || !y.isFinite()) null else x to y
    }.getOrNull()

    internal fun savePosition(target: Preferences, xDp: Float, yDp: Float) {
        runCatching {
            target.putFloat(KEY_POSITION_X, xDp)
            target.putFloat(KEY_POSITION_Y, yDp)
            target.flush()
        }
    }

    internal fun loadStyle(source: Preferences): TransferWidgetStyle = runCatching {
        TransferWidgetStyle.valueOf(
            source.get(KEY_STYLE, TransferWidgetStyle.BUBBLE.name)
        )
    }.getOrDefault(TransferWidgetStyle.BUBBLE)

    internal fun saveStyle(target: Preferences, value: TransferWidgetStyle) {
        runCatching {
            target.put(KEY_STYLE, value.name)
            target.flush()
        }
    }

    internal fun loadColor(source: Preferences): TransferWidgetColor = runCatching {
        TransferWidgetColor.valueOf(
            source.get(KEY_COLOR, TransferWidgetColor.APP_ACCENT.name)
        )
    }.getOrDefault(TransferWidgetColor.APP_ACCENT)

    internal fun saveColor(target: Preferences, value: TransferWidgetColor) {
        runCatching {
            target.put(KEY_COLOR, value.name)
            target.flush()
        }
    }

    private const val KEY_ENABLED = "enabled"
    private const val KEY_POSITION_X = "position_x_dp"
    private const val KEY_POSITION_Y = "position_y_dp"
    private const val KEY_STYLE = "widget_style"
    private const val KEY_COLOR = "widget_color"
    private const val DEFAULT_ENABLED = true
}
