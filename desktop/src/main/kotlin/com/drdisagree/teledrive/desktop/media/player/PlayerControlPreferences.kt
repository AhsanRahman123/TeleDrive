package com.drdisagree.teledrive.desktop.media.player

import java.util.prefs.Preferences

internal data class PlayerControlPreferences(
    val autoHideVolumeSlider: Boolean = false,
    val autoHideBrightnessSlider: Boolean = false,
    val autoHidePlayerControls: Boolean = false,
    val showPlayerControls: Boolean = true,
    val hideDelayMillis: Long = 3_000L,
    val rememberVolume: Boolean = true,
    val volume: Int = 200
)

internal object PlayerControlPreferencesStore {

    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/player-controls")
    }

    fun load(): PlayerControlPreferences = load(preferences)

    internal fun load(source: Preferences): PlayerControlPreferences = runCatching {
        PlayerControlPreferences(
            autoHideVolumeSlider = source.getBoolean(KEY_AUTO_HIDE_VOLUME, false),
            autoHideBrightnessSlider = source.getBoolean(KEY_AUTO_HIDE_BRIGHTNESS, false),
            autoHidePlayerControls = source.getBoolean(KEY_AUTO_HIDE_PLAYER_CONTROLS, false),
            showPlayerControls = source.getBoolean(KEY_SHOW_PLAYER_CONTROLS, true),
            hideDelayMillis = source.getLong(KEY_HIDE_DELAY, 3_000L)
                .coerceIn(500L, 10_000L),
            rememberVolume = source.getBoolean(KEY_REMEMBER_VOLUME, true),
            volume = source.getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, MAX_VOLUME)
        )
    }.getOrDefault(PlayerControlPreferences())

    fun save(value: PlayerControlPreferences) = save(preferences, value)

    /**
     * Persist only the last volume value. Called by the debounced IO writer, never by
     * the slider callback or keyboard handler.
     */
    fun saveVolume(value: Int) {
        runCatching {
            preferences.putInt(KEY_VOLUME, value.coerceIn(0, MAX_VOLUME))
            preferences.flush()
        }
    }

    internal fun save(target: Preferences, value: PlayerControlPreferences) {
        runCatching {
            target.putBoolean(KEY_AUTO_HIDE_VOLUME, value.autoHideVolumeSlider)
            target.putBoolean(KEY_AUTO_HIDE_BRIGHTNESS, value.autoHideBrightnessSlider)
            target.putBoolean(KEY_AUTO_HIDE_PLAYER_CONTROLS, value.autoHidePlayerControls)
            target.putBoolean(KEY_SHOW_PLAYER_CONTROLS, value.showPlayerControls)
            target.putLong(KEY_HIDE_DELAY, value.hideDelayMillis.coerceIn(500L, 10_000L))
            target.putBoolean(KEY_REMEMBER_VOLUME, value.rememberVolume)
            target.putInt(KEY_VOLUME, value.volume.coerceIn(0, MAX_VOLUME))
            target.flush()
        }
    }

    private const val KEY_AUTO_HIDE_VOLUME = "auto_hide_volume_slider"
    private const val KEY_AUTO_HIDE_BRIGHTNESS = "auto_hide_brightness_slider_v2"
    private const val KEY_AUTO_HIDE_PLAYER_CONTROLS = "auto_hide_player_controls_v2"
    private const val KEY_SHOW_PLAYER_CONTROLS = "show_player_controls"
    private const val KEY_HIDE_DELAY = "slider_hide_delay_ms"
    private const val KEY_REMEMBER_VOLUME = "remember_volume_across_media"
    private const val KEY_VOLUME = "last_player_volume"
    private const val DEFAULT_VOLUME = 200
    private const val MAX_VOLUME = 200
}
