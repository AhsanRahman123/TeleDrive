package com.drdisagree.teledrive.desktop.media.player

import com.drdisagree.teledrive.core.common.SafeLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the lifetime of the embedded VLC players used by the desktop preview.
 *
 * Each MediaPlayer has its own asynchronous submit queue. Disposing one player and starting
 * another without waiting allows the outgoing audio output/decoder to remain alive while the
 * incoming player starts. This coordinator enforces a single active player and waits for the
 * outgoing player's queued stop command and native release to finish before starting the next.
 */
internal class SingleActivePlaybackCoordinator<T : Any>(
    private val stopAndRelease: suspend (T) -> Unit
) {
    private val mutex = Mutex()
    private val registered = ConcurrentHashMap.newKeySet<T>()
    private var active: T? = null

    fun register(player: T) {
        registered.add(player)
    }

    suspend fun activate(player: T, start: suspend () -> Unit) {
        mutex.withLock {
            if (!registered.contains(player)) return

            val previous = active
            if (previous != null && previous !== player) {
                // Do not clear the active reference until stop/release has actually completed.
                // If native teardown fails, later activations must retry it rather than allowing
                // another player to start alongside a possibly-live outgoing player.
                stopAndRelease(previous)
                registered.remove(previous)
                if (active === previous) active = null
            }

            if (!registered.contains(player)) return
            active = player
            // Keep the player marked active if start throws: the next activation or dispose
            // must still stop and release it before another native player can start.
            start()
        }
    }

    suspend fun dispose(player: T) {
        mutex.withLock {
            if (!registered.contains(player)) return
            stopAndRelease(player)
            registered.remove(player)
            if (active === player) active = null
        }
    }
}

internal object DesktopVlcPlaybackLifecycle {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val factoryLock = Any()
    private val playerFactories = mutableMapOf<MediaPlayer, MediaPlayerFactory>()
    private val retiredFactories = mutableSetOf<MediaPlayerFactory>()

    private val coordinator = SingleActivePlaybackCoordinator<MediaPlayer> { player ->
        stopThenReleaseOutsidePlayerExecutor(
            stopOnPlayerExecutor = {
                // VLCJ requires calls into LibVLC to be serialized on the player's own
                // executor. Await the stop command, but let that executor task return before
                // releasing the player from another thread.
                player.submitAndAwait {
                    runCatching { player.controls().stop() }
                }.onFailure {
                    SafeLog.w(TAG, "Could not stop outgoing VLC player cleanly", it)
                }
            },
            releasePlayer = {
                try {
                    player.release()
                } finally {
                    releaseFactoryFor(player)
                }
            }
        )
    }

    fun register(player: MediaPlayer, factory: MediaPlayerFactory) {
        synchronized(factoryLock) {
            playerFactories[player] = factory
        }
        coordinator.register(player)
    }

    /**
     * A factory is shared by successive media items while one preview surface remains mounted.
     * Retire it only when that surface changes subtitle appearance or is disposed, then release
     * it after every player created from it has completed native teardown.
     */
    fun retireFactory(factory: MediaPlayerFactory) {
        val releaseNow = synchronized(factoryLock) {
            retiredFactories.add(factory)
            if (playerFactories.values.none { it === factory }) {
                retiredFactories.remove(factory)
                factory
            } else {
                null
            }
        }
        releaseNow?.let(::releaseFactory)
    }

    private fun releaseFactoryFor(player: MediaPlayer) {
        val releaseAfterPlayer = synchronized(factoryLock) {
            val factory = playerFactories.remove(player)
            if (factory != null &&
                retiredFactories.contains(factory) &&
                playerFactories.values.none { it === factory }
            ) {
                retiredFactories.remove(factory)
                factory
            } else {
                null
            }
        }
        releaseAfterPlayer?.let(::releaseFactory)
    }

    private fun releaseFactory(factory: MediaPlayerFactory) {
        runCatching { factory.release() }
            .onFailure { SafeLog.w(TAG, "Could not release retired VLC factory cleanly", it) }
    }

    suspend fun activate(player: MediaPlayer, start: suspend () -> Unit) {
        coordinator.activate(player, start)
    }

    fun dispose(player: MediaPlayer) {
        cleanupScope.launch {
            runCatching { coordinator.dispose(player) }
                .onFailure { SafeLog.w(TAG, "Could not fully release outgoing VLC player", it) }
        }
    }

    private const val TAG = "DesktopVlcLifecycle"
}

/**
 * Run native stop on the player's serialized executor, then release the player from IO.
 *
 * MediaPlayer.release() shuts down its own TaskExecutor and waits for that executor to
 * terminate. Calling release from inside a submitted task makes the worker wait for itself,
 * delaying teardown until the executor's shutdown timeout. Keep release outside that worker.
 */
internal suspend fun stopThenReleaseOutsidePlayerExecutor(
    stopOnPlayerExecutor: suspend () -> Unit,
    releasePlayer: () -> Unit
) {
    try {
        stopOnPlayerExecutor()
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            releasePlayer()
        }
    }
}

internal suspend fun <T> MediaPlayer.submitAndAwait(block: () -> T): T {
    val result = CompletableDeferred<T>()
    submit {
        try {
            result.complete(block())
        } catch (error: Throwable) {
            result.completeExceptionally(error)
        }
    }
    // The native command must finish even if the Compose effect that requested it is cancelled.
    // The lifecycle mutex remains held until the queued stop/release/start command is complete.
    return withContext(NonCancellable) { result.await() }
}
