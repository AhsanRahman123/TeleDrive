package com.drdisagree.teledrive.core.media

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * In-process signal for visible Compose tiles. The SQLite cache is the source of truth; this
 * stream only tells current viewers that a file's cached preview has changed and should reload.
 */
object ThumbnailChangeNotifier {

    private val mutableEvents = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<String> = mutableEvents

    fun notifyChanged(fileId: String) {
        mutableEvents.tryEmit(fileId)
    }
}
