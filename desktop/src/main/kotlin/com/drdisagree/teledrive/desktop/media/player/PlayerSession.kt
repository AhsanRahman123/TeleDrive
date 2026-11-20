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
 *   candidate instance on the swap executor and proves it on a tiny
 *   local proof media ([SwapProofMedia]) — the [AppearanceSwapMachine]
 *   decides. The proof runs on a DISPOSABLE player from the candidate
 *   instance; the player that flips is a second, virgin player from the
 *   same instance whose first-ever playback is the real media (PC-28:
 *   proving and flipping on one player let the proof's stop() event
 *   poison the real start's verdict and silently rolled every swap
 *   back). Only after the proof does the facade flip: the old player is
 *   paused and silenced, and the flip player starts the real media at
 *   the captured position as the stream's only reader. (PC-27: proving
 *   on the real media while the live player played it made streamed
 *   swaps impossible — both players share one TDLib download window
 *   through the stream's byte source, so the candidate could never
 *   reach Playing and every Apply rolled back silently.) If the flip
 *   player's real start fails, the flip is undone and the old player
 *   resumes — it was paused, never stopped. All events and frames are
 *   generation/identity-gated through the viewer's [PlayerEventGate],
 *   which is attached to the flip player only at the flip — so a
 *   retired or not-yet-live player's events can never touch the
 *   viewer's state.
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

    /** The in-flight swap's players (proof + flip), retired together on rollback. */
    private var candidatePlayers: List<MediaPlayer> = emptyList()
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
        val candidateFactory = dedicated ?: VlcPlayback.factory ?: run {
            machine.settle(generation, candidateReady = false)
            onSwapSettled()
            return
        }
        // PC-28: the swap gets TWO players from the candidate instance
        // (see SwapPlayers): a disposable proof player and the virgin
        // flip player. Creating either failing is a free rollback —
        // nothing about the live player has been touched yet — and any
        // player created before the failure is retired, not leaked.
        val created = mutableListOf<MediaPlayer>()
        val players = runCatching {
            assignSwapPlayers(needsProof = !capture.wasFinished) {
                createPlayer(candidateFactory, dedicated).also { created += it }
            }
        }.getOrNull() ?: run {
            created.forEach { retirePlayer(it, dedicated) }
            machine.settle(generation, candidateReady = false)
            onSwapSettled()
            return
        }
        val flipPlayer = players.flipPlayer
        val proofPlayer = players.proofPlayer
        candidatePlayers = listOfNotNull(proofPlayer, flipPlayer)
        candidateDedicatedFactory = dedicated

        // The candidates stay silent until one of them owns the session.
        candidatePlayers.forEach { player ->
            runCatching { player.audio().setVolume(0) }
            runCatching { player.audio().isMute = true }
        }

        if (capture.wasFinished) {
            // Nothing to restart: the new instance simply owns the next
            // play. (Starting a finished media at its end position would
            // just finish again.)
            when (machine.settle(generation, candidateReady = true)) {
                AppearanceSwapMachine.Decision.FLIP ->
                    flip(flipPlayer, dedicated, capture, spuTrack, audioTrack)

                AppearanceSwapMachine.Decision.ROLLBACK -> {
                    releaseCandidate()
                    onSwapSettled()
                }

                // A newer Apply superseded this one; the newer swap owns
                // the session and will settle it.
                AppearanceSwapMachine.Decision.DISCARD -> releaseCandidate()
            }
            return
        }

        // PC-27: the candidate instance proves itself on a tiny LOCAL
        // proof media (see SwapProofMedia), never on the session's own
        // MRL. The old proof — start the real media on the candidate
        // while the live player plays it — is impossible for streamed
        // media: both players share one byte source over TDLib's single
        // ranged-download window, every uncovered candidate read
        // re-seeks that window and blocks behind the live player's
        // reads, so the candidate could not reach Playing inside the
        // timeout and every Apply silently rolled back (customization
        // inert for Gallery/streamed playback; local files, read
        // directly by both players, flipped fine).
        //
        // PC-28: the proof runs on the DISPOSABLE proof player, and the
        // flip player is never played or stopped before the flip — the
        // 1.6.8 code proved and flipped on ONE player, so the proof's
        // stop() queued a Stopped event that the real start's latch
        // (Stopped = failure) could consume as the start's own verdict,
        // silently rolling the swap back. On separate players that
        // event lives on a different event manager and cannot reach
        // the flip player's latch at all.
        val proofLatch = StartLatch()
        proofPlayer!!.events().addMediaPlayerEventListener(proofLatch)
        val proofPlayCalled = runCatching {
            proofPlayer.media().play(SwapProofMedia.mrl())
        }.getOrDefault(false)
        val proven = proofPlayCalled && proofLatch.await(PROOF_TIMEOUT_MS)
        proofPlayer.events().removeMediaPlayerEventListener(proofLatch)

        if (!proven) {
            // The proof player has done its job either way; retire it
            // before the fallback so its events can never linger.
            retireCandidatePlayer(proofPlayer, dedicated)
            // The proof file itself could not be played (an environment
            // problem, not an appearance problem): fall back to the
            // pre-PC-27 proof on the real media — on the still-virgin
            // flip player — which remains correct for local files,
            // rather than failing every Apply.
            val latch = StartLatch()
            flipPlayer.events().addMediaPlayerEventListener(latch)
            val options = swapStartOptions(positionMs, capture.wasPlaying)
            val playCalled = runCatching {
                flipPlayer.media().play(capture.mrl, *options.toTypedArray())
            }.getOrDefault(false)
            val legacyProven = playCalled && latch.await(CANDIDATE_START_TIMEOUT_MS)
            flipPlayer.events().removeMediaPlayerEventListener(latch)
            when (machine.settle(generation, candidateReady = legacyProven)) {
                AppearanceSwapMachine.Decision.FLIP ->
                    flip(flipPlayer, dedicated, capture, spuTrack, audioTrack)

                AppearanceSwapMachine.Decision.ROLLBACK -> {
                    releaseCandidate()
                    onSwapSettled()
                }

                AppearanceSwapMachine.Decision.DISCARD -> releaseCandidate()
            }
            return
        }

        // Proof passed: retire the proof player (its stop and release
        // happen on its own executor; the flip player is untouched by
        // all of it) and flip to the virgin player, which starts the
        // real media as its first-ever playback.
        retireCandidatePlayer(proofPlayer, dedicated)

        val previousKey = machine.settledKey()
        when (machine.settle(generation, candidateReady = true)) {
            AppearanceSwapMachine.Decision.FLIP -> flipThenStart(
                candidatePlayer = flipPlayer,
                dedicated = dedicated,
                oldPlayer = old,
                capture = capture,
                positionMs = positionMs,
                spuTrack = spuTrack,
                audioTrack = audioTrack,
                previousKey = previousKey
            )

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
     * Retires one of the swap's candidate players mid-flow (the proof
     * player, once its verdict is in) and drops it from the rollback
     * set. The candidate factory is only MARKED retired by this (see
     * [VlcFactoryLifecycle]): it is released once its last registered
     * player — the flip player — is also released, so retiring the
     * proof player early can never free the instance under the flip
     * player.
     */
    private fun retireCandidatePlayer(player: MediaPlayer, dedicated: MediaPlayerFactory?) {
        candidatePlayers = candidatePlayers - player
        retirePlayer(player, dedicated)
    }

    /**
     * PC-27 flip: the candidate is proven (on the proof media) but has
     * not started the real media yet. The facade and the event gate move
     * to it FIRST, the old player is paused and silenced (its events are
     * identity-gated off from this moment, and pausing it makes the
     * candidate the stream's only reader — the contention that killed
     * the old proof cannot recur), and only then does the candidate
     * start the real media at the captured position. If that start
     * fails, the flip is undone: the old player — which never stopped
     * its media, only paused — resumes and owns the session again, and
     * the machine's settled key reverts with it.
     */
    private fun flipThenStart(
        candidatePlayer: MediaPlayer,
        dedicated: MediaPlayerFactory?,
        oldPlayer: MediaPlayer,
        capture: Capture,
        positionMs: Long,
        spuTrack: Int?,
        audioTrack: Int?,
        previousKey: List<String>
    ) {
        val oldDedicatedFactory = currentDedicatedFactory

        current = candidatePlayer
        currentDedicatedFactory = dedicated
        candidatePlayers = emptyList()
        candidateDedicatedFactory = null
        eventGate.attach(candidatePlayer)
        val mediaGeneration = eventGate.beginMedia()
        eventGate.expectGeneration(mediaGeneration)
        eventGate.setAcceptsFrames(true)
        eventGate.openEvents()

        // The freshest position is the old player's own clock, read
        // before pausing it: the proof took a moment, and the snapshot
        // from the swap's start would rewind the video by that much.
        val startPositionMs = runCatching { oldPlayer.status().time() }.getOrNull()
            ?.takeIf { it >= 0L } ?: positionMs
        runCatching { oldPlayer.audio().setVolume(0) }
        runCatching { oldPlayer.audio().isMute = true }
        if (capture.wasPlaying) {
            runCatching { oldPlayer.controls().pause() }
        }

        val latch = StartLatch()
        candidatePlayer.events().addMediaPlayerEventListener(latch)
        val options = swapStartOptions(startPositionMs, capture.wasPlaying)
        val playCalled = runCatching {
            candidatePlayer.media().play(capture.mrl, *options.toTypedArray())
        }.getOrDefault(false)
        val started = playCalled && latch.await(REAL_START_TIMEOUT_MS)
        candidatePlayer.events().removeMediaPlayerEventListener(latch)

        if (!started) {
            flipBack(
                failedCandidate = candidatePlayer,
                failedDedicated = dedicated,
                oldPlayer = oldPlayer,
                oldDedicatedFactory = oldDedicatedFactory,
                capture = capture,
                previousKey = previousKey
            )
            return
        }

        // The same session-state restoration the classic flip performs.
        runCatching { candidatePlayer.controls().setRate(capture.rate) }
        runCatching { candidatePlayer.controls().repeat = capture.repeatOne }
        if (spuTrack != null) {
            runCatching { candidatePlayer.subpictures().setTrack(spuTrack) }
        }
        if (audioTrack != null) {
            runCatching { candidatePlayer.audio().setTrack(audioTrack) }
        }
        runCatching { candidatePlayer.audio().setVolume(capture.volume) }
        runCatching { candidatePlayer.audio().isMute = capture.muted }

        eventGate.detach(oldPlayer)
        retirePlayer(oldPlayer, oldDedicatedFactory)
        onSwapSettled()
    }

    /** Undoes [flipThenStart]: the old player owns the session again. */
    private fun flipBack(
        failedCandidate: MediaPlayer,
        failedDedicated: MediaPlayerFactory?,
        oldPlayer: MediaPlayer,
        oldDedicatedFactory: MediaPlayerFactory?,
        capture: Capture,
        previousKey: List<String>
    ) {
        machine.revertSettled(previousKey)
        current = oldPlayer
        currentDedicatedFactory = oldDedicatedFactory
        eventGate.attach(oldPlayer)
        val mediaGeneration = eventGate.beginMedia()
        eventGate.expectGeneration(mediaGeneration)
        eventGate.setAcceptsFrames(true)
        eventGate.openEvents()

        runCatching { oldPlayer.audio().setVolume(capture.volume) }
        runCatching { oldPlayer.audio().isMute = capture.muted }
        if (capture.wasPlaying) {
            runCatching { oldPlayer.controls().play() }
        }
        retirePlayer(failedCandidate, failedDedicated)
        onSwapSettled()
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
        candidatePlayers = emptyList()
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
        val players = candidatePlayers
        val dedicated = candidateDedicatedFactory
        candidatePlayers = emptyList()
        candidateDedicatedFactory = null
        players.forEach { retirePlayer(it, dedicated) }
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
         * Hard bound on the fallback proof (candidate starts the real
         * media, pre-PC-27 style). A start that has not reported
         * Playing/Paused by then is treated as failed and rolled back —
         * the live player was never touched, so the user simply keeps
         * watching with the old appearance.
         */
        const val CANDIDATE_START_TIMEOUT_MS = 6_000L

        /**
         * Hard bound on the PC-27 proof: the candidate starts a tiny
         * local file, which reaches Playing in well under a second on
         * any working instance; the bound only fires when the instance
         * itself is broken.
         */
        const val PROOF_TIMEOUT_MS = 6_000L

        /**
         * Hard bound on the candidate's post-flip start of the real
         * media (PC-27). The candidate is the stream's only reader by
         * then, but a streamed start still needs its range fetches
         * (header, then the captured position), so the bound is
         * generous; on failure the flip is undone and the old player
         * resumes.
         */
        const val REAL_START_TIMEOUT_MS = 20_000L
        const val TAG = "PlayerSession"
    }
}
