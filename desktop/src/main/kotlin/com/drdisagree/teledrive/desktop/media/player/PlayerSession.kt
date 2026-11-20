package com.drdisagree.teledrive.desktop.media.player

import com.drdisagree.teledrive.core.common.SafeLog
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer

/**
 * The stable player facade the media viewer binds to (PC-19, report 20).
 *
 * Subtitle appearance is LibVLC **instance** configuration: the freetype
 * renderer reads it when the instance's modules are created, libVLC's
 * public API offers no way to set the underlying variables on a running
 * player (the public subtitle API is track selection + delay only — see
 * report 20's research), and the per-media-option route was proven inert
 * on the user's PC in 1.6.3. The one channel that demonstrably reaches
 * the renderer is an instance built with the appearance's factory
 * arguments (proven in 1.6.1). But recreating the player in the open —
 * the 1.6.1/1.6.2 design — wedged the transport controls, because every
 * control, listener and surface was bound to the destroyed instance.
 *
 * This session combines the two proven halves:
 *
 * - **The facade never changes.** The viewer creates ONE session; every
 *   control lambda, the event listener, the render surface and the state
 *   channels are bound to the session for the viewer's whole life.
 *   Commands submitted to the session are resolved against the CURRENT
 *   native player at execution time.
 * - **Appearance swaps are atomic and rollback-safe.** An Apply builds a
 *   candidate instance + player on the swap executor, starts the SAME
 *   media on it (position inside the start, muted until proven), and
 *   flips the facade only after the candidate reports Playing/Paused —
 *   the [AppearanceSwapMachine] decides. On any failure (factory
 *   creation, start error, hard timeout) the candidate is discarded and
 *   the live player keeps the session: it was never stopped, paused or
 *   reconfigured. All events and frames are generation/identity-gated
 *   through the viewer's [PlayerEventGate], which is attached to the
 *   candidate only at the flip — so a retired or not-yet-live player's
 *   events can never touch the viewer's state.
 *
 * The initial player is built directly on the saved appearance's
 * instance when one is saved, so the first playback is already styled;
 * a plain-default appearance uses the shared singleton instance.
 */
internal class PlayerSession(
    private val eventGate: PlayerEventGate<MediaPlayer>,
    private val configurePlayer: (EmbeddedMediaPlayer) -> Unit,
    private val onSwapSettled: () -> Unit
) {

    /** What an Apply captured from the viewer at the moment it was pressed. */
    data class Capture(
        val mrl: String,
        val positionMs: Long,
        val wasPlaying: Boolean,
        val wasFinished: Boolean,
        val rate: Float,
        val volume: Int,
        val muted: Boolean,
        val repeatOne: Boolean
    )

    // Facade commands serialize here, exactly as they serialized on the
    // single player's vlcj executor before: a command that blocks (a
    // media start waiting for the playing report) blocks later commands
    // the same way it always did, and commands never run concurrently
    // against the native player.
    private val commands = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlayerSession-commands").apply { isDaemon = true }
    }
    private val swapExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "PlayerSession-swap").apply { isDaemon = true }
    }
    private val machine = AppearanceSwapMachine()

    @Volatile
    private var current: MediaPlayer? = null

    /** The dedicated appearance instance owning [current], or null when
     *  the current player lives on the shared singleton instance. */
    @Volatile
    private var currentDedicatedFactory: MediaPlayerFactory? = null

    @Volatile
    private var released = false

    private var parkedPlayer: MediaPlayer? = null
    private var candidate: MediaPlayer? = null
    private var candidateDedicatedFactory: MediaPlayerFactory? = null

    /**
     * Builds the first player, on the saved appearance's own instance
     * when the appearance is non-default. If that instance cannot be
     * created, the player falls back to the shared instance (default
     * rendering, working player) and the machine's settled key stays the
     * default — so a later Apply retries the swap.
     */
    fun start(initialAppearance: SubtitleAppearance) {
        // A new preview replaces any background-playback session parked by
        // a previous preview (see BackgroundPlayback).
        BackgroundPlayback.stopParked()
        val targetArgs = initialAppearance.toVlcFactoryArguments()
        var dedicated: MediaPlayerFactory? = null
        var settledArgs = emptyList<String>()
        if (targetArgs.isNotEmpty()) {
            dedicated = VlcPlayback.createAppearanceFactory(targetArgs)
            if (dedicated != null) settledArgs = targetArgs
        }
        val factory = dedicated ?: VlcPlayback.factory
        if (factory == null) {
            SafeLog.w(TAG, "No LibVLC instance available for this viewer")
            return
        }
        val player = createPlayer(factory, dedicated)
        current = player
        currentDedicatedFactory = dedicated
        machine.seed(settledArgs)
        eventGate.attach(player)
    }

    // -- Facade surface: everything the viewer calls on "the player". --

    /** Serializes [task] with this session's other commands. */
    fun submit(task: Runnable) {
        if (released) return
        runCatching {
            commands.execute {
                // A command that fails must not kill the executor thread;
                // the call sites treat native failures as best-effort.
                runCatching { task.run() }
            }
        }
    }

    fun controls() = live().controls()

    fun audio() = live().audio()

    fun subpictures() = live().subpictures()

    fun media() = live().media()

    fun status() = live().status()

    fun snapshots() = live().snapshots()

    fun audioTracks(fallbackPrefix: String): List<VlcTrack> =
        current?.audioTracks(fallbackPrefix) ?: emptyList()

    fun textTracks(fallbackPrefix: String): List<VlcTrack> =
        current?.textTracks(fallbackPrefix) ?: emptyList()

    /** The native player commands should target right now, if any. */
    fun currentPlayerOrNull(): MediaPlayer? = current

    private fun live(): MediaPlayer =
        checkNotNull(current) { "This player session has no live player" }

    // -- Appearance swaps. --

    /**
     * Applies [appearance] by swapping the native instance behind the
     * facade. Runs entirely on the swap executor; the viewer hears about
     * the outcome only through [onSwapSettled]. An appearance whose
     * factory arguments equal the live instance's is a no-op (settled
     * immediately), as is one equal to a swap already in flight.
     */
    fun requestAppearance(appearance: SubtitleAppearance, capture: Capture) {
        if (released) return
        val targetArgs = appearance.toVlcFactoryArguments()
        runCatching { swapExecutor.execute { runSwap(targetArgs, capture) } }
    }

    private fun runSwap(targetArgs: List<String>, capture: Capture) {
        if (released) return
        val pending = machine.pendingKey()
        if (pending != null) {
            if (pending == targetArgs) return // this exact swap is already in flight
            // else fall through: begin() supersedes the in-flight swap.
        } else if (targetArgs == machine.settledKey()) {
            onSwapSettled()
            return
        }
        val generation = machine.begin(targetArgs)
        val old = current ?: run {
            machine.settle(generation, candidateReady = false)
            return
        }

        // Snapshot the live player's state (native reads on this swap
        // thread, never on the UI thread). The old player keeps playing
        // untouched until the flip.
        val positionMs = runCatching { old.status().time() }.getOrNull()
            ?.takeIf { it >= 0L } ?: capture.positionMs
        val spuTrack = runCatching { old.subpictures().track() }.getOrNull()
        val audioTrack = runCatching { old.audio().track() }.getOrNull()

        // Build the candidate instance. Nothing about the live player has
        // been touched yet, so a failure here is a free rollback.
        val dedicated = if (targetArgs.isEmpty()) {
            null
        } else {
            VlcPlayback.createAppearanceFactory(targetArgs)
        }
        if (targetArgs.isNotEmpty() && dedicated == null) {
            machine.settle(generation, candidateReady = false)
            onSwapSettled()
            return
        }
        val candidateFactory = dedicated ?: VlcPlayback.factory ?: run {
            machine.settle(generation, candidateReady = false)
            onSwapSettled()
            return
        }
        val candidatePlayer = runCatching {
            createPlayer(candidateFactory, dedicated)
        }.getOrNull() ?: run {
            machine.settle(generation, candidateReady = false)
            onSwapSettled()
            return
        }
        candidate = candidatePlayer
        candidateDedicatedFactory = dedicated

        // The candidate stays silent until it owns the session.
        runCatching { candidatePlayer.audio().setVolume(0) }
        runCatching { candidatePlayer.audio().isMute = true }

        val ready = if (capture.wasFinished) {
            // Nothing to restart: the new instance simply owns the next
            // play. (Starting a finished media at its end position would
            // just finish again.)
            true
        } else {
            val latch = StartLatch()
            candidatePlayer.events().addMediaPlayerEventListener(latch)
            val options = swapStartOptions(positionMs, capture.wasPlaying)
            val playCalled = runCatching {
                candidatePlayer.media().play(capture.mrl, *options.toTypedArray())
            }.getOrDefault(false)
            val proven = playCalled && latch.await(CANDIDATE_START_TIMEOUT_MS)
            candidatePlayer.events().removeMediaPlayerEventListener(latch)
            proven
        }

        when (machine.settle(generation, candidateReady = ready)) {
            AppearanceSwapMachine.Decision.FLIP ->
                flip(candidatePlayer, dedicated, capture, spuTrack, audioTrack)

            AppearanceSwapMachine.Decision.ROLLBACK -> {
                releaseCandidate()
                onSwapSettled()
            }

            // A newer Apply superseded this one; the newer swap owns the
            // session and will settle it.
            AppearanceSwapMachine.Decision.DISCARD -> releaseCandidate()
        }
    }

    /**
     * The candidate has proven itself live: restore the session state on
     * it, move the facade and the event gate to it, and only then retire
     * the old player. From the flip on, the old player's events and
     * frames are rejected by identity — it can be torn down whenever its
     * executor gets to it.
     */
    private fun flip(
        candidatePlayer: MediaPlayer,
        dedicated: MediaPlayerFactory?,
        capture: Capture,
        spuTrack: Int?,
        audioTrack: Int?
    ) {
        if (!capture.wasFinished) {
            runCatching { candidatePlayer.controls().setRate(capture.rate) }
            runCatching { candidatePlayer.controls().repeat = capture.repeatOne }
            // Restore the exact track selections captured from the old
            // player (including "subtitles off", id -1) — the candidate
            // would otherwise auto-select. The viewer's preferred-language
            // re-apply remains as a backstop for ids that do not carry
            // over.
            if (spuTrack != null) {
                runCatching { candidatePlayer.subpictures().setTrack(spuTrack) }
            }
            if (audioTrack != null) {
                runCatching { candidatePlayer.audio().setTrack(audioTrack) }
            }
        }
        runCatching { candidatePlayer.audio().setVolume(capture.volume) }
        runCatching { candidatePlayer.audio().isMute = capture.muted }

        val oldPlayer = current
        val oldDedicatedFactory = currentDedicatedFactory
        current = candidatePlayer
        currentDedicatedFactory = dedicated
        candidate = null
        candidateDedicatedFactory = null

        // The latch already proved this player reached Playing/Paused, so
        // its events may flow immediately: open the gate here instead of
        // waiting for a playing() callback that already fired (that wait
        // was the PC-11 dead-controls trap).
        eventGate.attach(candidatePlayer)
        val mediaGeneration = eventGate.beginMedia()
        eventGate.expectGeneration(mediaGeneration)
        eventGate.setAcceptsFrames(true)
        eventGate.openEvents()

        if (oldPlayer != null && oldPlayer !== candidatePlayer) {
            // detach() clears the gates only if the old player is still
            // current; it no longer is, so this is a no-op safeguard.
            eventGate.detach(oldPlayer)
            retirePlayer(oldPlayer, oldDedicatedFactory)
        }
        onSwapSettled()
    }

    private fun releaseCandidate() {
        val player = candidate
        val dedicated = candidateDedicatedFactory
        candidate = null
        candidateDedicatedFactory = null
        if (player != null) retirePlayer(player, dedicated)
    }

    // -- Lifecycle: parking and disposal. --

    /**
     * Hands the current native player to [BackgroundPlayback] (the viewer
     * is leaving with background playback on). The session is empty
     * afterwards; [disposeViewer] will not release the parked player.
     */
    fun parkCurrent() {
        val player = current ?: return
        val dedicated = currentDedicatedFactory
        current = null
        currentDedicatedFactory = null
        parkedPlayer = player
        BackgroundPlayback.park(player, dedicated)
    }

    /**
     * The viewer is gone. Releases the current player (unless parked),
     * any in-flight candidate, and the dedicated instances behind them —
     * each factory through [VlcFactoryLifecycle], so an instance is only
     * released after its last player's release has completed.
     */
    fun disposeViewer() {
        released = true
        releaseCandidate()
        parkedPlayer?.let { parked ->
            // BackgroundPlayback owns the parked player's release; just
            // shut this viewer's gates against its late callbacks.
            eventGate.detach(parked)
            parkedPlayer = null
        }
        val player = current
        val dedicated = currentDedicatedFactory
        current = null
        currentDedicatedFactory = null
        if (player != null) {
            eventGate.detach(player)
            retirePlayer(player, dedicated)
        }
        runCatching { swapExecutor.shutdown() }
        runCatching { commands.shutdown() }
    }

    private fun createPlayer(
        factory: MediaPlayerFactory,
        dedicatedFactory: MediaPlayerFactory?
    ): EmbeddedMediaPlayer {
        val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        if (dedicatedFactory != null) {
            VlcFactoryLifecycle.register(dedicatedFactory, player)
        }
        configurePlayer(player)
        return player
    }

    /**
     * Serialized native teardown on the player's own vlcj executor — the
     * same pattern the viewer and [BackgroundPlayback] already use — with
     * the factory's release reported from inside the task, after the
     * player's release has returned.
     */
    private fun retirePlayer(player: MediaPlayer, dedicatedFactory: MediaPlayerFactory?) {
        runCatching {
            player.submit {
                runCatching { player.controls().stop() }
                runCatching { player.release() }
                if (dedicatedFactory != null) {
                    VlcFactoryLifecycle.onPlayerReleased(dedicatedFactory, player)
                }
            }
        }
        if (dedicatedFactory != null) {
            VlcFactoryLifecycle.retire(dedicatedFactory)
        }
    }

    /**
     * One-shot proof that a swap candidate actually started: completes
     * true on the first Playing/Paused report (a `:start-paused` start
     * may only ever report Paused), false on Error/Stopped. A candidate
     * that finishes immediately did start, so Finished counts as proof.
     */
    private class StartLatch : MediaPlayerEventAdapter() {
        private val result = CompletableFuture<Boolean>()

        override fun playing(mediaPlayer: MediaPlayer) {
            result.complete(true)
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            result.complete(true)
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            result.complete(true)
        }

        override fun error(mediaPlayer: MediaPlayer) {
            result.complete(false)
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            result.complete(false)
        }

        fun await(timeoutMs: Long): Boolean =
            runCatching { result.get(timeoutMs, TimeUnit.MILLISECONDS) }
                .getOrDefault(false)
    }

    private companion object {
        /**
         * Hard bound on proving a swap candidate live. A local or streamed
         * start that has not reported Playing/Paused by then is treated
         * as failed and rolled back — the live player was never touched,
         * so the user simply keeps watching with the old appearance.
         */
        const val CANDIDATE_START_TIMEOUT_MS = 6_000L
        const val TAG = "PlayerSession"
    }
}
