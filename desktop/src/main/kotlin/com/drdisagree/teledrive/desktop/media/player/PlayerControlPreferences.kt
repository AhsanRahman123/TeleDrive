package com.drdisagree.teledrive.desktop.media.player

import java.util.prefs.Preferences

internal data class PlayerControlPreferences(
    val autoHideSeekSlider: Boolean = true,
    val autoHideBrightnessSlider: Boolean = true,
    val hideDelayMillis: Long = 3_000L
)

internal object PlayerControlPreferencesStore {

    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/player-controls")
    }

    fun load(): PlayerControlPreferences = runCatching {
        PlayerControlPreferences(
            autoHideSeekSlider = preferences.getBoolean(KEY_AUTO_HIDE_SEEK, true),
            autoHideBrightnessSlider = preferences.getBoolean(KEY_AUTO_HIDE_BRIGHTNESS, true),
            hideDelayMillis = preferences.getLong(KEY_HIDE_DELAY, 3_000L)
                .coerceIn(500L, 10_000L)
        )
    }.getOrDefault(PlayerControlPreferences())

    fun save(value: PlayerControlPreferences) {
        runCatching {
            preferences.putBoolean(KEY_AUTO_HIDE_SEEK, value.autoHideSeekSlider)
            preferences.putBoolean(KEY_AUTO_HIDE_BRIGHTNESS, value.autoHideBrightnessSlider)
            preferences.putLong(KEY_HIDE_DELAY, value.hideDelayMillis.coerceIn(500L, 10_000L))
            preferences.flush()
        }
    }

    private const val KEY_AUTO_HIDE_SEEK = "auto_hide_seek_slider"
    private const val KEY_AUTO_HIDE_BRIGHTNESS = "auto_hide_brightness_slider"
    private const val KEY_HIDE_DELAY = "slider_hide_delay_ms"
}
