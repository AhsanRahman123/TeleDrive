package com.drdisagree.teledrive.desktop.media.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer

/**
 * Orders LibVLC factory retirement after the release of the factory's players.
 *
 * A per-appearance [MediaPlayerFactory] must outlive every player created from
 * it: releasing the factory while one of its players still exists is a native
 * use-after-release. The previous code released a retired factory on a fixed
 * 1-second timer, but the retired player's own teardown is an asynchronous
 * `submit { stop; release }` on vlcj's serial executor with no time bound, so
 * the timer could win the race.
 *
 * Instead, players are [register]ed against their factory at creation time,
 * and each player's teardown reports [onPlayerReleased] from *inside* its
 * submitted task, after `player.release()` has returned. [retire] only marks
 * the factory; the actual `factory.release()` runs once the last live player
 * has reported (or immediately, when none are live). The decision logic is
 * independent of the order in which the two disposals happen.
 *
 * Failure mode by design: if a player's executor ever wedges so its release
 * task never runs, the factory is leaked rather than freed under a live
 * player — a bounded memory cost instead of a native crash.
 *
 * The shared singleton factory is never registered or retired here; only
 * per-appearance factories participate.
 */
internal object VlcFactoryLifecycle {

    private val lock = Any()
    private val livePlayers = mutableMapOf<MediaPlayerFactory, MutableSet<MediaPlayer>>()
    private val retiredFactories = mutableSetOf<MediaPlayerFactory>()
    private val releaseClaimed = mutableSetOf<MediaPlayerFactory>()
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(factory: MediaPlayerFactory, player: MediaPlayer) {
        synchronized(lock) {
            livePlayers.getOrPut(factory) { mutableSetOf() }.add(player)
        }
    }

    /** Mark [factory] retired; it is released once its last live player reports released. */
    fun retire(factory: MediaPlayerFactory) {
        val releaseNow = synchronized(lock) {
            retiredFactories += factory
            livePlayers[factory].isNullOrEmpty() && releaseClaimed.add(factory)
        }
        if (releaseNow) release(factory)
    }

    /**
     * Called after [player]'s native release has completed (from the player's
     * own submitted teardown task, so the ordering is structural, not timed).
     */
    fun onPlayerReleased(factory: MediaPlayerFactory, player: MediaPlayer) {
        val releaseNow = synchronized(lock) {
            val players = livePlayers[factory]
            players?.remove(player)
            if (players != null && players.isEmpty()) livePlayers.remove(factory)
            factory in retiredFactories &&
                !livePlayers.containsKey(factory) &&
                releaseClaimed.add(factory)
        }
        if (releaseNow) release(factory)
    }

    private fun release(factory: MediaPlayerFactory) {
        // Initiated only after the last player's release() returned, so the
        // happens-before edge holds even though the call itself is async.
        releaseScope.launch { runCatching { factory.release() } }
    }
}
