package com.drdisagree.teledrive.presentation.transfers

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.drdisagree.teledrive.presentation.platform.TransferBubblePreference
import com.drdisagree.teledrive.presentation.platform.TransferWidgetColor
import com.drdisagree.teledrive.presentation.platform.TransferWidgetStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android store for the floating transfer widget: the master switch from Settings, the
 * shell style (bubble / bottom strip) and colour preset, and the bubble's dragged
 * position — the same shape as the Windows store, backed by the app's DataStore settings
 * store with its own keys (the pattern the app's other DataStore-backed stores use).
 * Every value is also exposed as a [StateFlow] so the overlay reacts the moment Settings
 * changes it — style and colour switch live, without a restart.
 *
 * Positions are stored as dp offsets of the bubble's top-left corner from the window's
 * top-left, so they survive resolution/DPI changes sanely; the layout layer clamps them
 * into the current window on every frame.
 */
class AndroidTransferBubbleStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : TransferBubblePreference {

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _style = MutableStateFlow(TransferWidgetStyle.BUBBLE)
    val style: StateFlow<TransferWidgetStyle> = _style.asStateFlow()

    private val _color = MutableStateFlow(TransferWidgetColor.APP_ACCENT)
    val color: StateFlow<TransferWidgetColor> = _color.asStateFlow()

    private val _position = MutableStateFlow<BubbleOffset?>(null)
    val position: StateFlow<BubbleOffset?> = _position.asStateFlow()

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                _enabled.value = prefs[KEY_ENABLED] ?: DEFAULT_ENABLED
                _style.value = prefs[KEY_STYLE]?.let { name ->
                    runCatching { TransferWidgetStyle.valueOf(name) }.getOrNull()
                } ?: TransferWidgetStyle.BUBBLE
                _color.value = prefs[KEY_COLOR]?.let { name ->
                    runCatching { TransferWidgetColor.valueOf(name) }.getOrNull()
                } ?: TransferWidgetColor.APP_ACCENT
                val x = prefs[KEY_POSITION_X]
                val y = prefs[KEY_POSITION_Y]
                // A non-finite stored value is corruption, never a position.
                _position.value = if (x != null && y != null && x.isFinite() && y.isFinite()) {
                    BubbleOffset(x, y)
                } else {
                    null
                }
            }
        }
    }

    override fun isEnabled(): Boolean = _enabled.value

    override fun setEnabled(value: Boolean) {
        _enabled.value = value
        scope.launch { dataStore.edit { it[KEY_ENABLED] = value } }
    }

    override fun widgetStyle(): TransferWidgetStyle = _style.value

    override fun setWidgetStyle(style: TransferWidgetStyle) {
        _style.value = style
        scope.launch { dataStore.edit { it[KEY_STYLE] = style.name } }
    }

    override fun widgetColor(): TransferWidgetColor = _color.value

    override fun setWidgetColor(color: TransferWidgetColor) {
        _color.value = color
        scope.launch { dataStore.edit { it[KEY_COLOR] = color.name } }
    }

    /** The remembered bubble position, or null when it was never dragged. */
    fun loadPosition(): BubbleOffset? = _position.value

    fun savePosition(xDp: Float, yDp: Float) {
        if (!xDp.isFinite() || !yDp.isFinite()) return
        _position.value = BubbleOffset(xDp, yDp)
        scope.launch {
            dataStore.edit {
                it[KEY_POSITION_X] = xDp
                it[KEY_POSITION_Y] = yDp
            }
        }
    }

    companion object {
        internal val KEY_ENABLED = booleanPreferencesKey("transfer_bubble_enabled")
        internal val KEY_STYLE = stringPreferencesKey("transfer_bubble_style")
        internal val KEY_COLOR = stringPreferencesKey("transfer_bubble_color")
        internal val KEY_POSITION_X = floatPreferencesKey("transfer_bubble_position_x_dp")
        internal val KEY_POSITION_Y = floatPreferencesKey("transfer_bubble_position_y_dp")
        internal const val DEFAULT_ENABLED = true
    }
}
