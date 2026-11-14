package com.drdisagree.teledrive.desktop.media.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps the last selected volume available immediately across media composable replacement,
 * while disk persistence happens off the UI thread and is debounced.
 */
internal object RememberedPlayerVolume {
    private val current = AtomicInteger(PlayerControlPreferencesStore.load().volume)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writer = DebouncedIntWriter(
        scope = ioScope,
        debounceMillis = 300L,
        save = PlayerControlPreferencesStore::saveVolume
    )

    fun currentVolume(): Int = current.get().coerceIn(0, MAX_VOLUME)

    fun update(value: Int, persist: Boolean) {
        val normalized = value.coerceIn(0, MAX_VOLUME)
        current.set(normalized)
        if (persist) writer.schedule(normalized)
    }

    private const val MAX_VOLUME = 200
}

/**
 * Conflates rapid values and persists only the latest value after input has settled.
 * The writer's process-wide IO scope survives media switches and composable disposal.
 */
internal class DebouncedIntWriter(
    scope: CoroutineScope,
    private val debounceMillis: Long,
    private val save: (Int) -> Unit
) {
    private val pending = Channel<Int>(Channel.CONFLATED)

    init {
        require(debounceMillis >= 0L)
        scope.launch(Dispatchers.IO) {
            for (firstValue in pending) {
                var latest = firstValue
                while (true) {
                    delay(debounceMillis)
                    val newer = pending.tryReceive().getOrNull() ?: break
                    latest = newer
                }
                runCatching { save(latest) }
            }
        }
    }

    fun schedule(value: Int) {
        pending.trySend(value)
    }
}

/**
 * Sends only the latest requested audio state to VLCJ. Slider drags and repeated keyboard
 * adjustments therefore cannot leave a long queue of obsolete volume commands behind.
 */
internal class LatestAudioStateDispatcher(
    private val enqueue: (() -> Unit) -> Unit,
    private val apply: (volume: Int, muted: Boolean) -> Unit
) {
    private data class AudioState(val volume: Int, val muted: Boolean)

    private val pending = AtomicReference<AudioState?>(null)
    private val drainScheduled = AtomicBoolean(false)

    fun submit(volume: Int, muted: Boolean) {
        pending.set(AudioState(volume.coerceIn(0, 200), muted || volume <= 0))
        scheduleDrain()
    }

    private fun scheduleDrain() {
        if (!drainScheduled.compareAndSet(false, true)) return
        runCatching {
            enqueue(::drain)
        }.onFailure {
            drainScheduled.set(false)
        }
    }

    private fun drain() {
        try {
            while (true) {
                val next = pending.getAndSet(null) ?: break
                runCatching { apply(next.volume, next.muted) }
            }
        } finally {
            drainScheduled.set(false)
            if (pending.get() != null) scheduleDrain()
        }
    }
}
