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
import org.jetbrains.annotations.VisibleForTesting
import uk.co.caprica.vlcj.player.base.MediaPlayer
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates the lifetime of the embedded VLC players used by the desktop preview.
 *
 * Each MediaPlayer has its own asynchronous submit queue. Disposing one player and starting
 * another without waiting allows the outgoing audio output/decoder to remain alive while the
 * incoming player starts. This coordinator enforces a single active player and waits for the
 * outgoing player's queued commands, synchronous stop, and release before starting the next.
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
                active = null
                registered.remove(previous)
                stopAndRelease(previous)
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
            if (!registered.remove(player)) return
            if (active === player) active = null
            stopAndRelease(player)
        }
    }
}

internal object DesktopVlcPlaybackLifecycle {
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val coordinator = SingleActivePlaybackCoordinator<MediaPlayer> { player ->
        player.submitAndAwait {
            try {
                // ControlsApi.stop() waits for the native stopped event. Do not start another
                // media item until this has completed and the old player has been released.
                runCatching { player.controls().stop() }
            } finally {
                player.release()
            }
        }
    }

    fun register(player: MediaPlayer) {
        coordinator.register(player)
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
