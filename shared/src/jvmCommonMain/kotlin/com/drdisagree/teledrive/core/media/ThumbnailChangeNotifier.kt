package com.drdisagree.teledrive.core.media

import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.BufferOverflow
import java.util.concurrent.ConcurrentHashMap

data class ThumbnailChanged(
    val fileId: String,
    val sizeBytes: Long,
    val generatedAt: Long,
    val encrypted: Boolean
)

/**
 * SQLite remains the source of truth. This keyed event stream only wakes visible tiles whose
 * thumbnail changed, instead of broadcasting every event to every visible tile.
 */
object ThumbnailChangeNotifier {

    // Kept for manager-level screens that need to observe all thumbnail changes.
    // Individual tiles use eventsFor(fileId) and are not woken by unrelated changes.
    private val _events = MutableSharedFlow<ThumbnailChanged>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<ThumbnailChanged> = _events.asSharedFlow()

    private val listeners =
        ConcurrentHashMap<String, MutableSet<SendChannel<ThumbnailChanged>>>()

    fun eventsFor(fileId: String): Flow<ThumbnailChanged> = callbackFlow {
        listeners.compute(fileId) { _, current ->
            val subscribers = current
                ?: ConcurrentHashMap.newKeySet<SendChannel<ThumbnailChanged>>()
            subscribers.add(channel)
            subscribers
        }
        awaitClose {
            listeners.computeIfPresent(fileId) { _, subscribers ->
                subscribers.remove(channel)
                subscribers.takeIf { it.isNotEmpty() }
            }
        }
    }

    fun notifyChanged(
        fileId: String,
        sizeBytes: Long,
        generatedAt: Long,
        encrypted: Boolean
    ) {
        val event = ThumbnailChanged(fileId, sizeBytes, generatedAt, encrypted)
        _events.tryEmit(event)
        listeners[fileId]?.forEach { it.trySend(event) }
    }
}
