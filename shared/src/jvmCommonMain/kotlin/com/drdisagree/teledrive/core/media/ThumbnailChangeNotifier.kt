package com.drdisagree.teledrive.core.media

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

data class ThumbnailChanged(
    val fileId: String,
    val sizeBytes: Long,
    val generatedAt: Long,
    val encrypted: Boolean
)

/**
 * In-process signal for visible Compose tiles. SQLite remains the source of truth; this stream
 * tells open screens that one file's preview has been created and provides its accounting data.
 */
object ThumbnailChangeNotifier {

    private val mutableEvents = MutableSharedFlow<ThumbnailChanged>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<ThumbnailChanged> = mutableEvents

    fun notifyChanged(
        fileId: String,
        sizeBytes: Long,
        generatedAt: Long,
        encrypted: Boolean
    ) {
        mutableEvents.tryEmit(
            ThumbnailChanged(fileId, sizeBytes, generatedAt, encrypted)
        )
    }
}
