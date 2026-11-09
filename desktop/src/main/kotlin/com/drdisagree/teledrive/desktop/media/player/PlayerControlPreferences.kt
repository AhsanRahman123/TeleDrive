package com.drdisagree.teledrive.desktop.media.player

import java.util.prefs.Preferences

internal data class PlayerControlPreferences(
    val autoHideVolumeSlider: Boolean = true,
    val autoHideBrightnessSlider: Boolean = true,
    val autoHidePlayerControls: Boolean = true,
    val showPlayerControls: Boolean = true,
    val hideDelayMillis: Long = 3_000L
)

internal object PlayerControlPreferencesStore {

    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/player-controls")
    }

    fun load(): PlayerControlPreferences = runCatching {
        PlayerControlPreferences(
            autoHideVolumeSlider = preferences.getBoolean(KEY_AUTO_HIDE_VOLUME, true),
            autoHideBrightnessSlider = preferences.getBoolean(KEY_AUTO_HIDE_BRIGHTNESS, true),
            autoHidePlayerControls = preferences.getBoolean(KEY_AUTO_HIDE_PLAYER_CONTROLS, true),
            showPlayerControls = preferences.getBoolean(KEY_SHOW_PLAYER_CONTROLS, true),
            hideDelayMillis = preferences.getLong(KEY_HIDE_DELAY, 3_000L)
                .coerceIn(500L, 10_000L)
        )
    }.getOrDefault(PlayerControlPreferences())

    fun save(value: PlayerControlPreferences) {
        runCatching {
            preferences.putBoolean(KEY_AUTO_HIDE_VOLUME, value.autoHideVolumeSlider)
            preferences.putBoolean(KEY_AUTO_HIDE_BRIGHTNESS, value.autoHideBrightnessSlider)
            preferences.putBoolean(KEY_AUTO_HIDE_PLAYER_CONTROLS, value.autoHidePlayerControls)
            preferences.putBoolean(KEY_SHOW_PLAYER_CONTROLS, value.showPlayerControls)
            preferences.putLong(KEY_HIDE_DELAY, value.hideDelayMillis.coerceIn(500L, 10_000L))
            preferences.flush()
        }
    }

    private const val KEY_AUTO_HIDE_VOLUME = "auto_hide_volume_slider"
    private const val KEY_AUTO_HIDE_BRIGHTNESS = "auto_hide_brightness_slider"
    private const val KEY_AUTO_HIDE_PLAYER_CONTROLS = "auto_hide_player_controls"
    private const val KEY_SHOW_PLAYER_CONTROLS = "show_player_controls"
    private const val KEY_HIDE_DELAY = "slider_hide_delay_ms"
}
