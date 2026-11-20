package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.presentation.platform.TransferBubblePreference
import java.util.prefs.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop store for the floating transfer bubble: the master switch from
 * Settings and the bubble's dragged position, in the same java.util.prefs
 * store family as the other desktop-only preferences (picker positions,
 * Transfers sort, player controls). The switch is also exposed as a
 * [StateFlow] so the overlay appears/disappears the moment Settings flips
 * it, without a restart.
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

    override fun isEnabled(): Boolean = _enabled.value

    override fun setEnabled(value: Boolean) {
        saveEnabled(preferences, value)
        _enabled.value = value
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
        if (x.isNaN() || y.isNaN()) null else x to y
    }.getOrNull()

    internal fun savePosition(target: Preferences, xDp: Float, yDp: Float) {
        runCatching {
            target.putFloat(KEY_POSITION_X, xDp)
            target.putFloat(KEY_POSITION_Y, yDp)
            target.flush()
        }
    }

    private const val KEY_ENABLED = "enabled"
    private const val KEY_POSITION_X = "position_x_dp"
    private const val KEY_POSITION_Y = "position_y_dp"
    private const val DEFAULT_ENABLED = true
}
