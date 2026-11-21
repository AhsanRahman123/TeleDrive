package com.drdisagree.teledrive.desktop.startup

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Round 11: a startup action that must NOT run during process/window
 * bring-up, only once [trigger] is called (from the post-first-frame
 * effect in Main). Triggering is idempotent — the action runs exactly
 * once, on [scope], however many times trigger is called — and nothing
 * runs at construction time, so merely wiring the action into `main()`
 * costs nothing on the launch path.
 *
 * Used for the two pieces of work that used to compete with the window
 * and TDLib bring-up: the libVLC factory warm-up and the repository
 * startup block (transfer recovery, orphan sweeps, trash repair).
 */
class DeferredStartupAction(
    private val scope: CoroutineScope,
    private val action: suspend () -> Unit
) {
    private val started = AtomicBoolean(false)

    /** True once [trigger] has fired (the action may still be running). */
    val isTriggered: Boolean get() = started.get()

    fun trigger() {
        if (!started.compareAndSet(false, true)) return
        scope.launch { action() }
    }
}
