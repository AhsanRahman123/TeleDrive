package com.drdisagree.teledrive.desktop.media.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer

/**
 * The parked native player behind the Background playback setting.
 *
 * When the user leaves a preview with the setting on and media playing,
 * DesktopMediaPlayer hands its VLC player here instead of releasing it, so
 * the audio keeps playing with no preview on screen. The parked player's
 * event/render callbacks still point at the disposed preview's channels and
 * are dropped there (its frame gate is shut before parking).
 *
 * Replacement semantics: creating the next preview player stops and
 * releases the parked one first. Closing the app ends the process, which
 * stops the parked player with it. At most one player is ever parked.
 */
internal object BackgroundPlayback {

    private var parkedPlayer: MediaPlayer? = null
    private var parkedFactory: MediaPlayerFactory? = null

    @Synchronized
    fun park(player: MediaPlayer, factory: MediaPlayerFactory?) {
        stopParkedLocked()
        parkedPlayer = player
        parkedFactory = factory
    }

    /** True while [factory] belongs to the parked player and must not be released yet. */
    @Synchronized
    fun ownsFactory(factory: MediaPlayerFactory?): Boolean =
        factory != null && parkedFactory === factory

    /** True while [player] is the parked one (its preview teardown must not release it). */
    @Synchronized
    fun isParked(player: MediaPlayer): Boolean = parkedPlayer === player

    @Synchronized
    fun stopParked() = stopParkedLocked()

    private fun stopParkedLocked() {
        val player = parkedPlayer ?: return
        val factory = parkedFactory
        parkedPlayer = null
        parkedFactory = null
        // Same serialized teardown the preview itself uses.
        runCatching {
            player.submit {
                runCatching { player.controls().stop() }
                runCatching { player.release() }
            }
        }
        if (factory != null) {
            // Mirror the preview's factory retirement: release the LibVLC
            // instance shortly after its player, off the UI thread.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                delay(1_000)
                runCatching { factory.release() }
            }
        }
    }
}
