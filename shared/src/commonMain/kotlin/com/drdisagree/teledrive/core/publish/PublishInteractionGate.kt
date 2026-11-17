package com.drdisagree.teledrive.core.publish

import kotlin.concurrent.Volatile

/**
 * Coordinates the publish outbox with collection browsing.
 *
 * Every manifest the outbox publishes ends in a write to the `files` table
 * (see PublishOutboxDrainer.clearIfUnchanged), and every such write
 * invalidates the Room paging source behind the Files/Gallery lists, forcing
 * a reload and recomposition of the visible list. While the user is
 * scrolling, or a folder/tab change is still settling, that reload lands in
 * the middle of the gesture and shows up as a long frame stall.
 *
 * Collection screens therefore mark this gate busy while they are being
 * interacted with, and the drainer waits for a quiet window before
 * publishing the next manifest. The gate is purely a timing hint: it never
 * changes what gets published or when a publish is recorded, it only defers
 * the drainer's next step. It fails open — the busy window expires on its
 * own, and the drainer additionally caps how long it will wait — so a
 * screen that disappears without clearing its state can never starve the
 * outbox.
 */
object PublishInteractionGate {

    @Volatile
    private var busyUntilMs: Long = 0L

    /**
     * Called repeatedly (about once a second) for as long as a collection
     * list is scrolling. Each call extends the busy window a little past
     * "now", so the window closes shortly after scrolling actually stops,
     * even if nothing ever reports the stop.
     */
    fun keepBusy() {
        markBusy(SCROLL_WINDOW_MS)
    }

    fun markBusy(durationMs: Long) {
        val until = System.currentTimeMillis() + durationMs
        if (until > busyUntilMs) busyUntilMs = until
    }

    fun isBusy(): Boolean = System.currentTimeMillis() < busyUntilMs

    private const val SCROLL_WINDOW_MS = 3_000L
}
