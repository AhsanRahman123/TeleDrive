package com.drdisagree.teledrive.desktop.media.player

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

/**
 * Hard bound on proving one media start. A local file or loopback stream
 * that has not reported Playing/Paused (or Error) by then is treated as a
 * failed start: the caller surfaces the failure and the user can retry
 * with the Play control — instead of the session executor staying blocked
 * forever (see [startMediaBounded]).
 */
internal const val MEDIA_START_TIMEOUT_MS = 10_000L

/**
 * Bounded replacement for vlcj `MediaApi.start(mrl)` on the viewer's
 * switch path (PC 1.6.26 P4).
 *
 * `MediaApi.start()` plays the media and then waits — with NO timeout —
 * until the source reports playing or an error. The viewer's start
 * command runs on [PlayerSession]'s single-thread command executor, so
 * one start whose source never reports (a wedged input) blocks that
 * thread forever: every later command — the next switch's stop+start,
 * but also pause, seek and volume — queues behind it and never runs.
 * The ViewModel has already published the new preview by then, so the UI
 * moves while playback and the controls are dead: the field shape of
 * "in-zip video switching stops after 2 videos" (the ViewModel layer was
 * exonerated by the 4-video stepping test passing on the 1.6.25 baseline;
 * this serial executor is the only seam where one stuck native call
 * blocks all later switches).
 *
 * This helper plays the media and waits on [MediaStartLatch] for at most
 * [timeoutMs]. The wait is the only thing that changed: playback itself
 * starts with the same `play()` call, immediately — landing on a video
 * still ends in playback with no extra action (A5). On timeout the start
 * reports failure, the caller's existing failure branch surfaces it
 * (finished state, Play control retries), and the executor thread is
 * free for the next command.
 */
internal fun startMediaBounded(
    player: MediaPlayer,
    mrl: String,
    timeoutMs: Long = MEDIA_START_TIMEOUT_MS,
    beforePlay: (MediaPlayer) -> Unit = {}
): Boolean {
    val latch = MediaStartLatch()
    player.events().addMediaPlayerEventListener(latch)
    return try {
        boundedStartSequence(
            beforePlay = { runCatching { beforePlay(player) } },
            play = { runCatching { player.media().play(mrl) }.getOrDefault(false) },
            awaitStarted = { latch.await(it) },
            timeoutMs = timeoutMs
        )
    } finally {
        runCatching { player.events().removeMediaPlayerEventListener(latch) }
    }
}

/**
 * The ordering core of [startMediaBounded], separated so it is unit-testable
 * without a native player (PC 1.6.27 item P2).
 *
 * [beforePlay] runs FIRST, on the caller's thread (the session's serial
 * command executor), immediately before [play] is invoked. That ordering is
 * the volume-ramp fix: state applied in [beforePlay] — the viewer applies
 * the user's current volume/mute there — is already set on the native
 * player when its audio output is created for the new media, so the first
 * audible moment plays at the user's volume. Before this hook existed, the
 * ONLY application was a Compose effect that ran after the playing event
 * (plus a 250 ms delay per attempt, plus a submit queued behind this very
 * start command on the same executor), so every start opened at libVLC's
 * own volume and jumped when the effect landed — the field shape "starts
 * low, plays a bit, then full volume suddenly arrives", on first play and
 * on every file switch alike (each switch runs this same start).
 *
 * A failing [beforePlay] never blocks the start: the bounded await still
 * decides the outcome exactly as the pre-P2 code did.
 */
internal fun boundedStartSequence(
    beforePlay: () -> Unit,
    play: () -> Boolean,
    awaitStarted: (Long) -> Boolean,
    timeoutMs: Long
): Boolean {
    runCatching { beforePlay() }
    if (!play()) return false
    return awaitStarted(timeoutMs)
}

/**
 * One-shot proof that a started media actually started: completes true
 * on the first Playing/Paused report (a start that reaches Paused did
 * start), true on Finished (a very short media that finishes immediately
 * did start), false on Error.
 *
 * `stopped` is deliberately NOT terminal: this latch is registered right
 * after the outgoing media's `stop()` on the same player, and that stop's
 * asynchronous Stopped report can land inside this start's window — it
 * says nothing about the incoming media. A start that truly dies reports
 * Error; one that wedges hits the [await] timeout. Same semantics as the
 * appearance-swap StartLatch in PlayerSession, minus the stopped case
 * that is only safe there because a swap candidate has no outgoing media.
 */
internal class MediaStartLatch : MediaPlayerEventAdapter() {
    private val result = CompletableFuture<Boolean>()

    override fun playing(mediaPlayer: MediaPlayer) = onPlaying()

    override fun paused(mediaPlayer: MediaPlayer) = onPaused()

    override fun finished(mediaPlayer: MediaPlayer) = onFinished()

    override fun error(mediaPlayer: MediaPlayer) = onError()

    override fun stopped(mediaPlayer: MediaPlayer) = onStopped()

    // The decision core, separated from the vlcj callbacks so it is
    // unit-testable without a native player (MediaPlayer is a class, not
    // an interface, so it cannot be faked with a proxy).
    internal fun onPlaying() {
        result.complete(true)
    }

    internal fun onPaused() {
        result.complete(true)
    }

    internal fun onFinished() {
        result.complete(true)
    }

    internal fun onError() {
        result.complete(false)
    }

    internal fun onStopped() {
        // Deliberately not terminal — see the class doc.
    }

    fun await(timeoutMs: Long): Boolean =
        runCatching { result.get(timeoutMs, TimeUnit.MILLISECONDS) }
            .getOrDefault(false)
}
