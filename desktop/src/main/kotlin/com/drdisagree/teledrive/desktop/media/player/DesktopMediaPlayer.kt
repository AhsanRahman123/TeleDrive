package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.drdisagree.teledrive.presentation.components.TooltipIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.desktop.ui.LocalFullscreenController
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.player_audio_track
import com.drdisagree.teledrive.resources.player_more_actions
import com.drdisagree.teledrive.resources.player_queue
import com.drdisagree.teledrive.resources.player_resize_fill
import com.drdisagree.teledrive.resources.player_resize_fit
import com.drdisagree.teledrive.resources.player_resize_mode
import com.drdisagree.teledrive.resources.player_resize_zoom
import com.drdisagree.teledrive.resources.player_subtitle_appearance
import com.drdisagree.teledrive.resources.player_video_scale
import com.drdisagree.teledrive.resources.player_exit_full_screen
import com.drdisagree.teledrive.resources.player_full_screen
import com.drdisagree.teledrive.resources.player_hide_subtitles
import com.drdisagree.teledrive.resources.player_mute
import com.drdisagree.teledrive.resources.player_pause
import com.drdisagree.teledrive.resources.player_play
import com.drdisagree.teledrive.resources.player_repeat_off
import com.drdisagree.teledrive.resources.player_repeat_one
import com.drdisagree.teledrive.resources.player_replay
import com.drdisagree.teledrive.resources.player_scaling
import com.drdisagree.teledrive.resources.player_show_subtitles
import com.drdisagree.teledrive.resources.player_skip_back
import com.drdisagree.teledrive.resources.player_skip_forward
import com.drdisagree.teledrive.resources.player_subtitles_off
import com.drdisagree.teledrive.resources.player_track
import com.drdisagree.teledrive.resources.preview_next
import com.drdisagree.teledrive.resources.preview_previous
import com.drdisagree.teledrive.resources.player_unmute
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DesktopMediaPlayer(
    mrl: String,
    isAudio: Boolean,
    mediaId: String = mrl,
    modifier: Modifier = Modifier,
    preferredAudioLanguage: String = "",
    preferredSubtitleLanguage: String = "",
    onPreferredAudioLanguage: (String) -> Unit = {},
    onPreferredSubtitleLanguage: (String) -> Unit = {},
    onPrevious: () -> Unit = {},
    onNext: () -> Unit = {},
    hasPrevious: Boolean = false,
    hasNext: Boolean = false,
    repeatAll: Boolean = false,
    shuffle: Boolean = false,
    queueItems: List<PlayerQueueItem> = emptyList(),
    currentQueueItemId: String? = null,
    sleepTimerLabel: String = "Sleep timer",
    sleepStopRequest: Int = 0,
    sleepAtTrackEnd: Boolean = false,
    onToggleRepeatAll: () -> Unit = {},
    onToggleShuffle: () -> Unit = {},
    onSelectQueueItem: (String) -> Unit = {},
    onSetSleepTimer: (Long) -> Unit = {},
    onSleepAtTrackEnd: () -> Unit = {},
    onClearSleepTimer: () -> Unit = {},
    onPlaybackFinished: () -> Unit = {},
    onControlsVisibilityChange: (Boolean) -> Unit = {},
    onDelete: () -> Unit = {},
    backgroundPlayback: Boolean = false,
    onBackgroundPlaybackChange: (Boolean) -> Unit = {},
    mediaTitle: String = ""
) {
    // Subtitle appearance (PC-13 rescue, report 18): ONE factory and ONE
    // player for the viewer's whole life. The appearance is NOT a LibVLC
    // instance configuration any more — it is passed as per-media options
    // on every media start (see SubtitleAppearance.toVlcMediaOptions), the
    // route LibVLCSharp and VLC-Android use, so applying a new appearance
    // never recreates the factory, the player, its event listener or its
    // render surface. The previous design (a factory per appearance, the
    // player keyed on it) destroyed and recreated the native player on
    // every Apply and wedged the transport controls in the process.
    var subtitleAppearance by remember { mutableStateOf(SubtitleAppearanceStore.load()) }
    // Bumped by applySubtitleAppearance: the media start effect below is
    // keyed on it, so an Apply re-starts the CURRENT media on the SAME
    // player with the new appearance's media options and a :start-time
    // resume — the player object the controls are bound to never changes.
    var appearanceReloadTick by remember { mutableIntStateOf(0) }
    val factory = VlcPlayback.factory ?: return
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var hasCurrentMediaFrame by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var controlPreferences by remember { mutableStateOf(PlayerControlPreferencesStore.load()) }
    var volume by remember(mrl) {
        mutableIntStateOf(
            if (controlPreferences.rememberVolume) {
                RememberedPlayerVolume.currentVolume()
            } else {
                100
            }
        )
    }
    var brightness by remember { mutableIntStateOf(100) }
    var rate by remember(mrl) { mutableStateOf(1.0f) }
    var repeatOne by remember { mutableStateOf(false) }
    var trackTick by remember { mutableIntStateOf(0) }
    // Per-video resume: the saved position is loaded when a media starts, applied
    // once its duration is known, and only trusted for further saving once playback
    // is observed near it (a seek that lands elsewhere is a manual seek and ends
    // the restoration). See VideoResumeStore for the position rules.
    var showSubtitleSettings by remember { mutableStateOf(false) }
    var subtitleTrackActive by remember { mutableStateOf(false) }
    // Set only by applySubtitleAppearance, in the instant before the factory
    // swap recreates the player: the captured position/play state the restarted
    // player must come back to.
    var styleRestart by remember { mutableStateOf<StyleRestart?>(null) }
    var scalePreset by remember(mrl) { mutableStateOf(VideoScalePreset.BEST_FIT) }
    var stageSize by remember { mutableStateOf(IntSize.Zero) }
    val startPausedArmed = remember { AtomicBoolean(false) }
    var resumeSavedPosition by remember(mrl) { mutableStateOf<Long?>(null) }
    var resumeTarget by remember(mrl) { mutableStateOf<Long?>(null) }
    var resumeSettled by remember(mrl) { mutableStateOf(false) }
    var resumeSeekRetried by remember(mrl) { mutableStateOf(false) }
    var lastObservedTimeMs by remember(mrl) { mutableLongStateOf(0L) }
    var scaling by remember { mutableStateOf(VideoScaling.FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var controlsPinned by remember { mutableStateOf(false) }
    var seekFeedbackVisible by remember { mutableStateOf(false) }
    var seekFeedbackTick by remember { mutableIntStateOf(0) }
    var hKeyPressed by remember { mutableStateOf(false) }
    var lastVisibilityInteractionTick by remember { mutableIntStateOf(0) }
    // MediaPlayerEventAdapter outlives individual recompositions; use updated state so
    // a play/resume event cannot reapply the initial 100% / unmuted values.
    val latestVolume = rememberUpdatedState(volume)
    val latestMuted = rememberUpdatedState(muted)
    val latestRepeatOne = rememberUpdatedState(repeatOne)
    val latestSleepAtTrackEnd = rememberUpdatedState(sleepAtTrackEnd)
    val latestOnPlaybackFinished = rememberUpdatedState(onPlaybackFinished)
    val latestOnControlsVisibilityChange = rememberUpdatedState(onControlsVisibilityChange)
    val latestBackgroundPlayback = rememberUpdatedState(backgroundPlayback)
    val latestPlaying = rememberUpdatedState(playing)
    val latestFinished = rememberUpdatedState(finished)
    val playerScope = rememberCoroutineScope()
    // Which player's events and frames may touch this viewer's state. The gate
    // outlives individual players on purpose: an appearance Apply recreates
    // the player, and the retired player's late events must be rejected by
    // identity, not by guard flags the retired teardown itself maintained
    // (see PlayerEventGate). Render callbacks can also finish copying after
    // a media switch, so frames are tagged with the gate's generation.
    val eventGate = remember(isAudio) { PlayerEventGate<MediaPlayer>() }

    // VLC calls its render callback from a native thread. Conflate pending frames before
    // publishing them to Compose so a slow UI frame cannot build an unbounded render backlog.
    // Each ImageBitmap already owns an independent pixel buffer (see ComposeVideoSurface).
    val frameUpdates = remember(isAudio) {
        Channel<Pair<Long, VideoFramePixels>>(capacity = Channel.CONFLATED)
    }
    LaunchedEffect(frameUpdates) {
        for ((generation, nextFrame) in frameUpdates) {
            // Bitmap installation can be expensive for full-resolution frames. Keep that work
            // off the UI dispatcher; only publish a frame if it still belongs to this MRL.
            val image = withContext(Dispatchers.Default) {
                nextFrame.toImageBitmap()
            } ?: continue
            if (generation == eventGate.generation()) {
                frame = image
                hasCurrentMediaFrame = true
                buffering = false
            }
        }
    }
    DisposableEffect(frameUpdates) {
        onDispose { frameUpdates.close() }
    }

    // Keep high-frequency playback position notifications bounded as well.
    val timeUpdates = remember(isAudio) {
        Channel<Long>(capacity = Channel.CONFLATED)
    }
    LaunchedEffect(timeUpdates) {
        for (position in timeUpdates) timeMs = position
    }
    DisposableEffect(timeUpdates) {
        onDispose { timeUpdates.close() }
    }

    // One native VLC instance is retained for the lifetime of this media viewer. The
    // active MRL is replaced in-place below, avoiding a destroy/recreate flash on W/E.
    val player = remember(factory, isAudio) {
        // A new preview replaces any background-playback session parked by a
        // previous preview (see BackgroundPlayback).
        BackgroundPlayback.stopParked()
        factory.mediaPlayers().newEmbeddedMediaPlayer().apply {
            // From this moment only this player's events and frames are live;
            // a retired player's listener can still fire during its async
            // teardown and must be rejected by identity (see PlayerEventGate).
            eventGate.attach(this)
            if (!isAudio) {
                videoSurface().set(composeVideoSurface { image ->
                    val generation = eventGate.generation()
                    if (eventGate.acceptsFrames()) {
                        frameUpdates.trySend(generation to image)
                    }
                })
            }
            events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                // vlcj event callbacks run on native/event threads. Compose state is only
                // mutated from the player scope, never directly from those callback threads.
                override fun playing(mediaPlayer: MediaPlayer) {
                    if (!eventGate.isCurrent(mediaPlayer)) return
                    if (!eventGate.isExpectedGeneration()) return
                    eventGate.openEvents()
                    playerScope.launch {
                        // A style restart of a paused video starts via
                        // :start-paused; VLC still emits a playing event for
                        // it, so consume the one-shot flag and stay paused.
                        playing = !startPausedArmed.getAndSet(false)
                        finished = false
                        // VLC's playing event can precede its first video frame. Keep the old
                        // frame covered until the new source has actually rendered a frame.
                        buffering = !isAudio && !hasCurrentMediaFrame
                        // VLC can report the media length before the first playing event. Read
                        // the status here as a fallback so the remaining-time label and seekbar
                        // are initialized even when lengthChanged arrived during opening.
                        val reportedLength = runCatching { mediaPlayer.status().length() }
                            .getOrNull()
                        if (reportedLength != null && reportedLength > 0L) {
                            durationMs = reportedLength
                        }
                        val reportedTime = runCatching { mediaPlayer.status().time() }
                            .getOrNull()
                        if (reportedTime != null && reportedTime >= 0L) {
                            timeMs = reportedTime
                        }
                        trackTick++
                    }
                }

                override fun audioDeviceChanged(mediaPlayer: MediaPlayer, audioDevice: String) {
                    if (!eventGate.isCurrent(mediaPlayer)) return
                    // Windows can replace the default output when USB/Bluetooth earphones are
                    // attached. Re-apply audio state from the Compose scope.
                    playerScope.launch { trackTick++ }
                }

                override fun elementaryStreamAdded(
                    mediaPlayer: MediaPlayer,
                    type: TrackType,
                    id: Int
                ) {
                    if (!eventGate.isCurrent(mediaPlayer)) return
                    playerScope.launch { trackTick++ }
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    if (!eventGate.isCurrent(mediaPlayer)) return
                    if (!eventGate.isExpectedGeneration()) return
                    if (latestFinished.value) return
                    // A :start-paused load (subtitle Apply while paused) may
                    // never emit a playing event in libVLC, so this paused
                    // report is what opens the event gate for it — under the
                    // same identity + generation checks as playing(). Without
                    // it, the live player's time/length reports were dropped
                    // forever and the transport controls desynced (PC-11).
                    eventGate.openEvents()
                    playerScope.launch {
                        playing = false
                        // Mirror the playing() fallbacks so a start-paused
                        // load still gets its duration (the seek bar and the
                        // style-restart seek both key off it) and position.
                        val reportedLength = runCatching { mediaPlayer.status().length() }
                            .getOrNull()
                        if (reportedLength != null && reportedLength > 0L) {
                            durationMs = reportedLength
                        }
                        val reportedTime = runCatching { mediaPlayer.status().time() }
                            .getOrNull()
                        if (reportedTime != null && reportedTime >= 0L) {
                            timeMs = reportedTime
                        }
                        if (isAudio) buffering = false
                    }
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    if (!eventGate.isCurrent(mediaPlayer)) return
                    if (!eventGate.isExpectedGeneration()) return
                    if (!eventGate.closeEvents()) return
                    playerScope.launch {
                        playing = false
                        finished = true
                        if (!latestRepeatOne.value || latestSleepAtTrackEnd.value) {
                            latestOnPlaybackFinished.value()
                        }
                    }
                }

                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                    if (
                        eventGate.isCurrent(mediaPlayer) &&
                        eventGate.isExpectedGeneration() &&
                        eventGate.eventsOpen()
                    ) {
                        playerScope.launch {
                            // Once a frame of the current media has been
                            // accepted, the video is by definition rendering:
                            // a streamed source reports cache < 100 for its
                            // whole session, and letting those events
                            // re-raise the spinner left it spinning over a
                            // playing video (PC-16). Cache events may only
                            // drive the spinner before the first frame.
                            if (!hasCurrentMediaFrame) {
                                buffering = newCache < 100f || !isAudio
                            }
                        }
                    }
                }

                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                    if (eventGate.isCurrent(mediaPlayer) && eventGate.eventsOpen()) {
                        timeUpdates.trySend(newTime)
                    }
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    if (eventGate.isCurrent(mediaPlayer) && eventGate.eventsOpen()) {
                        playerScope.launch { durationMs = newLength }
                    }
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    // Errors before the incoming item reaches playing are returned by
                    // MediaApi.start(); ignore late errors from the outgoing source.
                    if (!eventGate.isCurrent(mediaPlayer)) return
                    if (!eventGate.isExpectedGeneration()) return
                    if (!eventGate.closeEvents()) return
                    eventGate.setAcceptsFrames(false)
                    playerScope.launch {
                        playing = false
                        finished = true
                        buffering = false
                    }
                }
            })
        }
    }

    // Render the physical brightness control immediately for video. A successful DDC/CI
    // read will synchronize its value, but an initial transient read failure must not hide it.
    var brightnessAvailable by remember(mrl, isAudio) { mutableStateOf(!isAudio) }
    var brightnessMonitor by remember { mutableStateOf("") }
    var brightnessDragging by remember(mrl) { mutableStateOf(false) }
    var brightnessLastInputAt by remember(mrl) { mutableLongStateOf(0L) }
    val brightnessUpdates = remember(mrl) { Channel<Float>(Channel.CONFLATED) }

    // Mirror Monitorian: read physical monitor brightness, but never let a polling
    // result overwrite an in-progress drag or a value just sent to the monitor.
    LaunchedEffect(mrl, isAudio) {
        if (isAudio) return@LaunchedEffect
        while (isActive) {
            val shouldRead = !brightnessDragging &&
                System.currentTimeMillis() - brightnessLastInputAt >= BRIGHTNESS_READ_GUARD_MS
            if (shouldRead) {
                val reading = withContext(Dispatchers.IO) {
                    WindowsMonitorBrightness.read()
                }
                val stillIdle = !brightnessDragging &&
                    System.currentTimeMillis() - brightnessLastInputAt >= BRIGHTNESS_READ_GUARD_MS
                if (stillIdle && reading != null) {
                    // Once DDC/CI has been detected for this video, keep the slider visible
                    // through temporary monitor-read failures (sleep/wake, focus, hot-plug).
                    brightnessAvailable = true
                    brightness = reading.percent.roundToInt().coerceIn(0, MAX_BRIGHTNESS)
                    brightnessMonitor = reading.monitorDescription
                }
            }
            delay(BRIGHTNESS_POLL_INTERVAL_MS)
        }
    }

    // Latest-value-wins DDC/CI writes avoid flooding the monitor while dragging.
    // The slider itself updates immediately; polling is paused until the write settles.
    LaunchedEffect(brightnessUpdates) {
        for (requested in brightnessUpdates) {
            val applied = withContext(Dispatchers.IO) {
                WindowsMonitorBrightness.setPercent(requested)
            }
            if (applied) brightnessAvailable = true
        }
    }
    DisposableEffect(brightnessUpdates) {
        onDispose { brightnessUpdates.cancel() }
    }

    DisposableEffect(playing, isAudio) {
        ScreenAwake.keepAwake(playing && !isAudio)
        onDispose { ScreenAwake.keepAwake(false) }
    }

    val fullscreenController = LocalFullscreenController.current
    LaunchedEffect(player, mrl, appearanceReloadTick) {
        // Reuse one VLC player for the lifetime of this viewer. Recreating native VLC and
        // its render/event channels for every MRL caused the previously-fixed flash/black frame
        // when moving to the next video. Close the event gate while swapping sources so late
        // callbacks from the outgoing item cannot update the incoming item's state.
        val mediaGeneration = eventGate.beginMedia()
        hasCurrentMediaFrame = false
        while (frameUpdates.tryReceive().isSuccess) Unit
        while (timeUpdates.tryReceive().isSuccess) Unit
        // Keep the last completed frame visible under the buffering overlay until the
        // incoming source produces its first accepted frame; clearing it here caused a flash.
        playing = false
        finished = false
        buffering = true
        timeMs = 0L
        durationMs = 0L
        // A pending subtitle style restart belongs to the file it was captured on. If the
        // user moved to another file before the restarted player came up, drop it here:
        // the new file starts from its own resume position, never the old file's.
        val pendingRestart = styleRestart
        if (pendingRestart != null && pendingRestart.mediaId != mediaId) {
            styleRestart = null
        }
        val activeRestart = styleRestart
        val savedResume = if (activeRestart == null) {
            VideoResumeStore.loadPosition(mediaId)
        } else {
            // A subtitle style restart resumes at the position captured when
            // the change was applied (already force-saved to the store), not
            // at a fresh resume lookup.
            0L
        }
        resumeSavedPosition = if (savedResume > 0L) savedResume else null
        resumeTarget = null
        resumeSettled = savedResume <= 0L
        resumeSeekRetried = false
        lastObservedTimeMs = 0L
        trackTick++
        player.submit {
            // A newer selection can arrive while this command is waiting in VLC's serial
            // executor. Never start an obsolete MRL after a newer generation was requested.
            if (eventGate.generation() == mediaGeneration) {
                // Stop the outgoing source before attaching another item. MediaApi.start()
                // waits until the incoming source reports playing or an error.
                runCatching { player.controls().stop() }
                if (eventGate.generation() == mediaGeneration) {
                    eventGate.expectGeneration(mediaGeneration)
                    // stop() has completed the outgoing native transition; only now allow
                    // this source's render callbacks into the generation-tagged frame channel.
                    eventGate.setAcceptsFrames(true)
                    val restart = activeRestart
                    // Appearance rides on EVERY start as per-media options
                    // (report 18): the first playback already honours the
                    // saved appearance, and an Apply reload picks up the new
                    // one — on this same player, with the position carried
                    // by :start-time as part of the start itself instead of
                    // a post-start seek racing the event stream.
                    val startOptions = mediaStartOptions(
                        appearance = subtitleAppearance,
                        restartPositionMs = restart?.positionMs,
                        restartWasPlaying = restart?.wasPlaying ?: true
                    )
                    val started = runCatching {
                        player.media().start(mrl, *startOptions.toTypedArray())
                    }.getOrDefault(false)
                    val stillCurrent = eventGate.generation() == mediaGeneration
                    if (!started || !stillCurrent) {
                        eventGate.closeEvents()
                        if (!stillCurrent) {
                            eventGate.setAcceptsFrames(false)
                            eventGate.clearExpected(mediaGeneration)
                            // A newer MRL was requested while start() was waiting. Stop this
                            // stale source before the next queued command attaches its media.
                            runCatching { player.controls().stop() }
                        } else {
                            // Keep this generation associated with the selected MRL so the
                            // user can retry playback with the normal Play control. Its render
                            // callbacks must remain accepted during that retry.
                            eventGate.setAcceptsFrames(true)
                            playerScope.launch {
                                playing = false
                                finished = true
                                buffering = false
                            }
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            eventGate.detach(player)
            // The viewer-level effect below may have parked this player for
            // background playback; a parked player must not be released here.
            if (BackgroundPlayback.isParked(player)) return@onDispose
            // Keep teardown serialized with pending play/seek/track commands. Disposing the
            // composable must not race a queued media replacement against native player release.
            // The player was created from the shared singleton factory, which is never
            // released — there is no per-player factory bookkeeping (report 18).
            runCatching {
                player.submit {
                    runCatching { player.controls().stop() }
                    runCatching { player.release() }
                }
            }
        }
    }

    // Background playback: declared after the per-player effect so it is
    // disposed first when the whole viewer leaves (navigation). It parks the
    // current player instead of letting the teardown above release it. The
    // parked player's factory is the shared singleton (null here): it is
    // never retired, so parking needs no lifecycle bookkeeping.
    val latestPlayer = rememberUpdatedState(player)
    DisposableEffect(Unit) {
        onDispose {
            if (latestBackgroundPlayback.value && latestPlaying.value && !latestFinished.value) {
                BackgroundPlayback.park(latestPlayer.value, null)
            }
        }
    }

    LaunchedEffect(player, durationMs) {
        val restart = styleRestart ?: return@LaunchedEffect
        if (restart.mediaId != mediaId) {
            // The pending restart was captured for a file that is no longer current;
            // never seek this file to another file's position.
            styleRestart = null
            return@LaunchedEffect
        }
        if (durationMs <= 0L) return@LaunchedEffect
        styleRestart = null
        val target = restart.positionMs
        if (target >= 2_000L && durationMs - target > 1_500L && timeMs < target - 1_500L) {
            player.submit { runCatching { player.controls().setTime(target) } }
            delay(1_500)
            if (timeMs < target - 1_500L && playing) {
                player.submit { runCatching { player.controls().setTime(target) } }
            }
        }
        player.submit {
            runCatching { player.controls().setRate(rate) }
            runCatching { player.controls().repeat = repeatOne }
        }
    }

    LaunchedEffect(player, durationMs) {
        if (durationMs <= 0L) return@LaunchedEffect
        val saved = resumeSavedPosition ?: return@LaunchedEffect
        resumeSavedPosition = null
        val target = videoResumeTarget(saved, durationMs)
        if (target == null) {
            resumeSettled = true
        } else {
            resumeTarget = target
            player.submit { runCatching { player.controls().setTime(target) } }
        }
    }

    LaunchedEffect(timeMs) {
        val target = resumeTarget
        if (target != null) {
            when {
                isVideoResumePositionConfirmed(timeMs, target) -> {
                    resumeTarget = null
                    resumeSettled = true
                }

                timeMs > lastObservedTimeMs + 2_000L -> {
                    // A jump that did not land on the target is a manual seek:
                    // restoration is over and normal saving resumes from here.
                    resumeTarget = null
                    resumeSettled = true
                }

                timeMs < target - 1_500L && playing && !resumeSeekRetried -> {
                    resumeSeekRetried = true
                    player.submit { runCatching { player.controls().setTime(target) } }
                }
            }
        }
        lastObservedTimeMs = timeMs
        if (resumeSettled && styleRestart == null && timeMs > 0L) {
            VideoResumeStore.savePosition(mediaId, timeMs, durationMs)
        }
    }

    LaunchedEffect(playing) {
        if (!playing && resumeSettled && styleRestart == null && timeMs > 0L) {
            VideoResumeStore.savePosition(mediaId, timeMs, durationMs, force = true)
        }
    }

    val latestTimeMs = rememberUpdatedState(timeMs)
    val latestDurationMs = rememberUpdatedState(durationMs)
    DisposableEffect(mediaId) {
        onDispose {
            if (latestTimeMs.value > 0L) {
                VideoResumeStore.savePosition(
                    mediaId,
                    latestTimeMs.value,
                    latestDurationMs.value,
                    force = true
                )
            }
        }
    }

    var lastSleepStopRequest by remember(mrl) {
        mutableIntStateOf(sleepStopRequest)
    }
    LaunchedEffect(player, sleepStopRequest) {
        if (sleepStopRequest > lastSleepStopRequest) {
            lastSleepStopRequest = sleepStopRequest
            player.submit { runCatching { player.controls().pause() } }
            playing = false
        }
    }

    LaunchedEffect(player, repeatAll, sleepAtTrackEnd) {
        // Repeat-current and repeat-all are mutually exclusive. End-of-track sleep
        // must override VLC's native repeat flag so the timer can stop playback.
        if (repeatAll || sleepAtTrackEnd) {
            repeatOne = false
            player.submit { runCatching { player.controls().repeat = false } }
        }
    }

    // Volume/mute restoration is submitted to vlcj's serialized player executor.
    // Never call AudioApi directly from an event callback or the Compose UI thread: audio
    // device hot-plug on Windows can temporarily hold native LibVLC audio locks.
    LaunchedEffect(player, mrl, playing, trackTick) {
        if (playing) {
            repeat(AUDIO_OUTPUT_REAPPLY_ATTEMPTS) {
                delay(AUDIO_OUTPUT_REAPPLY_INTERVAL_MS)
                if (!playing) return@LaunchedEffect
                player.submitAudioState(
                    latestVolume.value.coerceIn(0, MAX_VOLUME),
                    latestMuted.value
                )
            }
        }
    }

    val trackWord = stringResource(Res.string.player_track)
    val playerFocusRequester = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(mrl) { playerFocusRequester.requestFocus() }
    LaunchedEffect(windowInfo.isWindowFocused) {
        if (!windowInfo.isWindowFocused) {
            // A key release may be delivered to another application during Alt+Tab.
            hKeyPressed = false
        } else {
            playerFocusRequester.requestFocus()
        }
    }

    val togglePlayback: () -> Unit = {
        when {
            finished -> {
                finished = false
                player.submit { runCatching { player.controls().play() } }
            }
            playing -> player.submit { runCatching { player.controls().pause() } }
            else -> player.submit { runCatching { player.controls().play() } }
        }
    }
    val toggleMute: () -> Unit = {
        muted = !muted
        player.submitAudioState(volume, muted)
    }
    // A full save rewrites every preference key, including the volume. Always
    // carry the live volume into it: the snapshot in controlPreferences can be
    // older than the value the debounced volume writer already persisted (or
    // is about to), and an unrelated toggle must never revert it.
    fun persistControlPreferences(updated: PlayerControlPreferences) {
        val synced = updated.copy(volume = volume)
        controlPreferences = synced
        PlayerControlPreferencesStore.save(synced)
    }
    val toggleControlsVisibility: () -> Unit = {
        val showControls = !(controlsVisible || seekFeedbackVisible)
        persistControlPreferences(controlPreferences.copy(showPlayerControls = showControls))
        controlsPinned = false
        seekFeedbackVisible = false
        controlsVisible = showControls
        latestOnControlsVisibilityChange.value(showControls)
        interactionTick++
        lastVisibilityInteractionTick = interactionTick
        // The surface tap is also a keyboard interaction point. Keep the stable player
        // target focused after the chrome visibility state changes.
        playerFocusRequester.requestFocus()
    }
    val revealControlsForSeek: () -> Unit = {
        seekFeedbackVisible = true
        controlsVisible = true
        latestOnControlsVisibilityChange.value(true)
        interactionTick++
        seekFeedbackTick++
    }
    fun setPlayerVolume(value: Int, unmute: Boolean = true) {
        val nextVolume = value.coerceIn(0, MAX_VOLUME)
        volume = nextVolume
        if (nextVolume == 0) {
            muted = true
        } else if (unmute) {
            muted = false
        }
        // Update the shared in-memory volume immediately so a fast media switch uses
        // the latest value; disk persistence is debounced on an IO thread.
        RememberedPlayerVolume.update(
            nextVolume,
            persist = controlPreferences.rememberVolume
        )
        player.submitAudioState(nextVolume, muted)
    }
    fun adjustPlayerVolume(delta: Int) {
        setPlayerVolume(volume + delta, unmute = delta > 0)
    }

    fun applySubtitleAppearance(next: SubtitleAppearance) {
        val normalized = next.normalized()
        if (normalized == subtitleAppearance) {
            showSubtitleSettings = false
            return
        }
        // Capture position and play state BEFORE the factory swap recreates
        // the player; afterwards they would read the new player's zeroed state.
        // The retained frame bitmap is deliberately left alone so the restart
        // does not flash black.
        styleRestart = StyleRestart(mediaId = mediaId, positionMs = timeMs, wasPlaying = playing)
        if (!playing) startPausedArmed.set(true)
        VideoResumeStore.savePosition(mediaId, timeMs, durationMs, force = true)
        SubtitleAppearanceStore.save(normalized)
        subtitleAppearance = normalized
        // Same player, same factory: the start effect re-runs and re-starts
        // the current media with the new appearance's media options and a
        // :start-time resume. Nothing the controls reference is recreated,
        // so seek/play/pause cannot desync (PC-13).
        appearanceReloadTick++
        showSubtitleSettings = false
        interactionTick++
    }
    fun saveVideoSnapshot() {
        if (isAudio) return
        val snapshotDirectory = File(
            File(System.getProperty("user.home"), "Pictures"),
            "TeleDrive"
        )
        if (!snapshotDirectory.exists() && !snapshotDirectory.mkdirs()) return
        player.submit {
            runCatching {
                player.snapshots().setSnapshotDirectory(snapshotDirectory.absolutePath)
                player.snapshots().save()
            }
        }
    }
    fun setPlaybackRate(value: Float) {
        val requestedRate = normalizePlaybackRate(value)
        rate = requestedRate
        player.submit { runCatching { player.controls().setRate(requestedRate) } }
        interactionTick++
    }
    fun cycleAudioTrack() {
        player.submit {
            val tracks = runCatching { player.audioTracks(trackWord) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) {
                val selected = tracks.indexOfFirst { it.selected }
                val next = tracks[(selected + 1).coerceAtLeast(0) % tracks.size]
                runCatching { player.audio().setTrack(next.id) }
                playerScope.launch {
                    onPreferredAudioLanguage(next.language)
                    trackTick++
                }
            }
        }
    }
    fun cycleSubtitleTrack(reverse: Boolean = false) {
        player.submit {
            val tracks = runCatching { player.textTracks(trackWord) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) {
                val selected = tracks.indexOfFirst { it.selected }
                val nextIndex = if (reverse) {
                    if (selected <= 0) tracks.lastIndex else selected - 1
                } else {
                    (selected + 1) % tracks.size
                }
                val next = tracks[nextIndex]
                runCatching { player.subpictures().setTrack(next.id) }
                playerScope.launch {
                    onPreferredSubtitleLanguage(next.language)
                    trackTick++
                }
            }
        }
    }
    fun toggleSubtitles() {
        player.submit {
            val tracks = runCatching { player.textTracks(trackWord) }.getOrDefault(emptyList())
            if (tracks.isNotEmpty()) {
                val selected = tracks.firstOrNull { it.selected }
                if (selected != null) {
                    runCatching { player.subpictures().setTrack(VLC_TRACK_DISABLED) }
                } else {
                    val next = tracks.firstOrNull { it.language == preferredSubtitleLanguage }
                        ?: tracks.first()
                    runCatching { player.subpictures().setTrack(next.id) }
                    playerScope.launch { onPreferredSubtitleLanguage(next.language) }
                }
                playerScope.launch { trackTick++ }
            }
        }
    }

    var languagesApplied by remember(mrl, player, appearanceReloadTick) { mutableStateOf(false) }
    LaunchedEffect(mrl, trackTick, preferredAudioLanguage, preferredSubtitleLanguage) {
        if (languagesApplied || trackTick == 0) return@LaunchedEffect
        val applied = CompletableDeferred<Boolean>()
        runCatching {
            player.submit {
                val audio = runCatching { player.audioTracks(trackWord) }.getOrDefault(emptyList())
                val text = runCatching { player.textTracks(trackWord) }.getOrDefault(emptyList())
                if (audio.isEmpty() && text.isEmpty()) {
                    applied.complete(false)
                } else {
                    if (preferredAudioLanguage.isNotEmpty()) {
                        audio.firstOrNull { it.language == preferredAudioLanguage }
                            ?.let { runCatching { player.audio().setTrack(it.id) } }
                    }
                    if (preferredSubtitleLanguage.isNotEmpty()) {
                        text.firstOrNull { it.language == preferredSubtitleLanguage }
                            ?.let { chosen ->
                                // Clear VLC's auto-selected SPU first, or the
                                // auto track and the chosen track can render
                                // together (doubled subtitles).
                                runCatching {
                                    player.subpictures().setTrack(VLC_TRACK_DISABLED)
                                }
                                runCatching { player.subpictures().setTrack(chosen.id) }
                            }
                    }
                    applied.complete(true)
                }
            }
        }.onFailure { applied.complete(false) }
        if (withTimeoutOrNull(PLAYER_NATIVE_CALL_TIMEOUT_MS) { applied.await() } != true) {
            return@LaunchedEffect
        }
        languagesApplied = true
        trackTick++
    }

    LaunchedEffect(
        mrl,
        playing,
        interactionTick,
        controlsPinned,
        controlPreferences.autoHidePlayerControls,
        controlPreferences.showPlayerControls,
        controlPreferences.hideDelayMillis,
        seekFeedbackVisible
    ) {
        if (!controlPreferences.showPlayerControls) {
            if (controlsVisible) controlsVisible = false
            latestOnControlsVisibilityChange.value(false)
            return@LaunchedEffect
        }
        // Media changes restart this timer but do not reveal controls hidden by the user.
        // Only a real keyboard/pointer interaction may reveal an auto-hidden control bar.
        if (interactionTick != lastVisibilityInteractionTick) {
            lastVisibilityInteractionTick = interactionTick
            if (!controlsVisible) controlsVisible = true
        }
        if (!controlsVisible) {
            latestOnControlsVisibilityChange.value(false)
            return@LaunchedEffect
        }
        latestOnControlsVisibilityChange.value(true)
        if (!controlPreferences.autoHidePlayerControls || controlsPinned) {
            return@LaunchedEffect
        }
        val hideDelay = if (seekFeedbackVisible) {
            SEEK_FEEDBACK_DURATION_MS.milliseconds
        } else {
            controlPreferences.hideDelayMillis.milliseconds
        }
        delay(hideDelay)
        if (!controlsPinned && interactionTick == lastVisibilityInteractionTick) {
            controlsVisible = false
            latestOnControlsVisibilityChange.value(false)
        }
    }

    LaunchedEffect(seekFeedbackTick) {
        if (seekFeedbackTick == 0) return@LaunchedEffect
        seekFeedbackVisible = true
        delay(SEEK_FEEDBACK_DURATION_MS)
        seekFeedbackVisible = false
        if (!controlPreferences.showPlayerControls && !controlsVisible) {
            latestOnControlsVisibilityChange.value(false)
        }
    }

    val blankCursor = remember {
        PointerIcon(
            Toolkit.getDefaultToolkit().createCustomCursor(
                BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB),
                Point(0, 0),
                "blank"
            )
        )
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            // Oversized pictures (Fit screen / Center presets) crop at the
            // stage edge, never past it onto the surrounding chrome (PC-8).
            .clipToBounds()
            .onSizeChanged { stageSize = it }
            .focusRequester(playerFocusRequester)
            .onPreviewKeyEvent { event ->
                if (showSubtitleSettings) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    if (event.key == Key.H) {
                        hKeyPressed = false
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.Delete) return@onPreviewKeyEvent true
                    return@onPreviewKeyEvent false
                }
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = event.key
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed
                val shift = event.isShiftPressed

                when {
                    key == Key.Delete && !ctrl && !alt && !shift -> {
                        onDelete()
                        interactionTick++
                        true
                    }
                    key == Key.Spacebar && !ctrl && !alt && !shift -> {
                        togglePlayback()
                        interactionTick++
                        true
                    }
                    key == Key.MediaPlayPause -> {
                        togglePlayback()
                        interactionTick++
                        true
                    }
                    key == Key.MediaPlay -> {
                        player.submit { runCatching { player.controls().play() } }
                        interactionTick++
                        true
                    }
                    key == Key.MediaPause -> {
                        player.submit { runCatching { player.controls().pause() } }
                        interactionTick++
                        true
                    }
                    key == Key.S && ctrl && shift && !alt -> {
                        showSubtitleSettings = true
                        interactionTick++
                        true
                    }
                    key == Key.S && shift && !ctrl && !alt -> {
                        saveVideoSnapshot()
                        interactionTick++
                        true
                    }
                    key == Key.MediaStop || (key == Key.S && !shift && !ctrl && !alt) -> {
                        eventGate.closeEvents()
                        eventGate.setAcceptsFrames(false)
                        player.submit { runCatching { player.controls().stop() } }
                        playing = false
                        finished = true
                        timeMs = 0L
                        interactionTick++
                        true
                    }
                    key == Key.C && !ctrl && !alt && !shift -> {
                        val updated = controlPreferences.copy(
                            showPlayerControls = !controlPreferences.showPlayerControls
                        )
                        persistControlPreferences(updated)
                        controlsPinned = updated.showPlayerControls
                        interactionTick++
                        true
                    }
                    key == Key.R && !ctrl && !alt && !shift -> {
                        onToggleRepeatAll()
                        interactionTick++
                        true
                    }
                    key == Key.U && !ctrl && !alt && !shift -> {
                        onToggleShuffle()
                        interactionTick++
                        true
                    }
                    key == Key.F && !ctrl && !alt && !shift -> {
                        fullscreenController?.toggle()
                        interactionTick++
                        fullscreenController != null
                    }
                    key == Key.Escape && !ctrl && !alt && !shift && fullscreenController?.isFullscreen() == true -> {
                        fullscreenController.toggle()
                        true
                    }
                    key == Key.MediaNext -> {
                        onNext()
                        interactionTick++
                        true
                    }
                    key == Key.MediaPrevious -> {
                        onPrevious()
                        interactionTick++
                        true
                    }
                    // W/E are handled by the preview screen so mixed image/video folders
                    // are navigated as one sequence. Player transport buttons stay queue-scoped.
                    !alt && !shift && key == Key.DirectionUp -> {
                        adjustPlayerVolume(VOLUME_STEP)
                        interactionTick++
                        true
                    }
                    !alt && !shift && key == Key.DirectionDown -> {
                        adjustPlayerVolume(-VOLUME_STEP)
                        interactionTick++
                        true
                    }
                    key == Key.M && !ctrl && !alt && !shift -> {
                        toggleMute()
                        interactionTick++
                        true
                    }
                    (key == Key.Plus && !ctrl && !alt) || (key == Key.Equals && shift && !ctrl && !alt) -> {
                        setPlaybackRate(rate * 1.5f)
                        true
                    }
                    key == Key.RightBracket && !ctrl && !alt && !shift -> {
                        setPlaybackRate(rate + FINE_RATE_STEP)
                        true
                    }
                    key == Key.Minus && !ctrl && !alt && !shift -> {
                        setPlaybackRate(rate / 1.5f)
                        true
                    }
                    key == Key.LeftBracket && !ctrl && !alt && !shift -> {
                        setPlaybackRate(rate - FINE_RATE_STEP)
                        true
                    }
                    key == Key.Equals && !shift && !ctrl && !alt -> {
                        setPlaybackRate(1f)
                        true
                    }
                    // Short, dedicated seek shortcuts requested for keyboard users.
                    key == Key.P && !ctrl && !alt && !shift -> {
                        player.submit { runCatching { player.controls().skipTime(-SHORT_KEY_SEEK_STEP_MS) } }
                        revealControlsForSeek()
                        true
                    }
                    key == Key.N && !ctrl && !alt && !shift -> {
                        player.submit { runCatching { player.controls().skipTime(SHORT_KEY_SEEK_STEP_MS) } }
                        revealControlsForSeek()
                        true
                    }
                    key == Key.DirectionLeft && !ctrl && !alt && !shift -> {
                        player.submit { runCatching { player.controls().skipTime(-KEY_SEEK_STEP_MS) } }
                        revealControlsForSeek()
                        true
                    }
                    key == Key.DirectionRight && !ctrl && !alt && !shift -> {
                        player.submit { runCatching { player.controls().skipTime(KEY_SEEK_STEP_MS) } }
                        revealControlsForSeek()
                        true
                    }
                    key == Key.DirectionLeft || key == Key.DirectionRight -> {
                        val jumpMs = when {
                            ctrl && alt && !shift -> 300_000L
                            ctrl && !alt && !shift -> 60_000L
                            alt && !ctrl && !shift -> 10_000L
                            shift && !ctrl && !alt -> 3_000L
                            else -> 0L
                        }
                        if (jumpMs == 0L) {
                            false
                        } else {
                            val direction = if (key == Key.DirectionRight) 1L else -1L
                            player.submit {
                                runCatching { player.controls().skipTime(direction * jumpMs) }
                            }
                            revealControlsForSeek()
                            true
                        }
                    }
                    key == Key.L && !ctrl && !alt && !shift -> {
                        repeatOne = !repeatOne
                        if (repeatOne && repeatAll) onToggleRepeatAll()
                        player.submit { runCatching { player.controls().repeat = repeatOne } }
                        interactionTick++
                        true
                    }
                    key == Key.B && !ctrl && !alt && !shift -> {
                        cycleAudioTrack()
                        interactionTick++
                        true
                    }
                    key == Key.V && shift && !ctrl && !alt -> {
                        toggleSubtitles()
                        interactionTick++
                        true
                    }
                    key == Key.V && alt && !ctrl && !shift -> {
                        cycleSubtitleTrack(reverse = true)
                        interactionTick++
                        true
                    }
                    key == Key.V && !ctrl && !alt && !shift -> {
                        cycleSubtitleTrack()
                        interactionTick++
                        true
                    }
                    key == Key.J && !ctrl && !alt && !shift -> {
                        player.submit {
                            runCatching { player.audio().setDelay(player.audio().delay() - SYNC_STEP_US) }
                        }
                        interactionTick++
                        true
                    }
                    key == Key.K && !ctrl && !alt && !shift -> {
                        player.submit {
                            runCatching { player.audio().setDelay(player.audio().delay() + SYNC_STEP_US) }
                        }
                        interactionTick++
                        true
                    }
                    key == Key.G && !ctrl && !alt && !shift -> {
                        player.submit {
                            runCatching {
                                player.subpictures().setDelay(player.subpictures().delay() - SYNC_STEP_US)
                            }
                        }
                        interactionTick++
                        true
                    }
                    key == Key.H && !ctrl && !alt && !shift && !hKeyPressed -> {
                        hKeyPressed = true
                        // Toggle the actual visibility, not only the saved preference:
                        // controls can also be hidden by the auto-hide timer.
                        val showControls = !controlsVisible
                        persistControlPreferences(
                            controlPreferences.copy(showPlayerControls = showControls)
                        )
                        controlsPinned = false
                        controlsVisible = showControls
                        latestOnControlsVisibilityChange.value(showControls)
                        interactionTick++
                        lastVisibilityInteractionTick = interactionTick
                        true
                    }
                    key == Key.H && ctrl && !alt && !shift -> {
                        player.submit {
                            runCatching {
                                player.subpictures().setDelay(player.subpictures().delay() + SYNC_STEP_US)
                            }
                        }
                        interactionTick++
                        true
                    }
                    key == Key.Z && shift && !ctrl && !alt -> {
                        scaling = VideoScaling.FIT
                        interactionTick++
                        true
                    }
                    key == Key.Z && !ctrl && !alt && !shift -> {
                        scaling = VideoScaling.ZOOM
                        interactionTick++
                        true
                    }
                    // VLC uses A to cycle aspect ratio. The embedded Compose player
                    // exposes the matching fit/zoom/fill display modes.
                    key == Key.A && !ctrl && !alt && !shift -> {
                        scaling = scaling.next()
                        interactionTick++
                        true
                    }
                    // VLC's O shortcut toggles automatic scaling.
                    key == Key.O && !ctrl && !alt && !shift -> {
                        scaling = if (scaling == VideoScaling.FIT) {
                            VideoScaling.FILL
                        } else {
                            VideoScaling.FIT
                        }
                        interactionTick++
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerHoverIcon(
                if (controlsVisible || !controlPreferences.showPlayerControls) {
                    PointerIcon.Default
                } else {
                    blankCursor
                }
            )
            .pointerInput(player, playing, finished, isAudio) {
                detectTapGestures(
                    onTap = { toggleControlsVisibility() }
                )
            }
            .pointerInput(Unit) {
                var lastPosition = Offset.Unspecified
                awaitPointerEventScope {
                    while (true) {
                        // Final pass runs after child buttons/sliders process the
                        // click. Reclaim focus here so Space and the player's other shortcuts
                        // continue working even when a control was just clicked.
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        val position = event.changes.firstOrNull()?.position ?: continue
                        when (event.type) {
                            PointerEventType.Press -> {
                                playerFocusRequester.requestFocus()
                                interactionTick++
                            }
                            PointerEventType.Move -> {
                                if (lastPosition != Offset.Unspecified &&
                                    (position - lastPosition).getDistance() > MOVE_THRESHOLD_PX
                                ) {
                                    interactionTick++
                                }
                                lastPosition = position
                            }

                            else -> Unit
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isAudio) {
            AudioStage(
                player = player,
                mediaId = mediaId,
                fallbackTitle = mediaTitle.ifBlank {
                    queueItems.firstOrNull { it.id == currentQueueItemId }?.name
                        ?: queueItems.firstOrNull()?.name
                        ?: ""
                }
            )
        } else {
            frame?.let { current ->
                Image(
                    bitmap = current,
                    contentDescription = null,
                    modifier = Modifier
                        // The video surface fills the fixed stage region and
                        // NEVER changes layout size: showing/hiding the
                        // controls must not resize or move it (PC-8). The
                        // subtitle safe area is applied purely as a layer
                        // transform below (see videoPictureTransform), in the
                        // same pass that knows the controls are visible — no
                        // measured size can lag behind and overflow the stage.
                        .fillMaxSize()
                        .pointerInput(subtitleTrackActive, subtitleAppearance.verticalPosition) {
                            var dragActive = false
                            var startY = 0f
                            var lastY = 0f
                            var startPosition = 0f
                            detectVerticalDragGestures(
                                onDragStart = { offset ->
                                    dragActive = false
                                    if (subtitleTrackActive && size.height > 0) {
                                        val bandHalf = 21.dp.toPx()
                                        val centreY =
                                            subtitleAppearance.verticalPosition * size.height
                                        if (kotlin.math.abs(offset.y - centreY) <= bandHalf) {
                                            dragActive = true
                                            startY = offset.y
                                            lastY = offset.y
                                            startPosition = subtitleAppearance.verticalPosition
                                        }
                                    }
                                },
                                onVerticalDrag = { change, _ ->
                                    if (dragActive) {
                                        lastY = change.position.y
                                        change.consume()
                                    }
                                },
                                onDragEnd = {
                                    if (dragActive && size.height > 0) {
                                        val moved = (startPosition +
                                            (lastY - startY) / size.height)
                                            .coerceIn(0.08f, 0.92f)
                                        if (kotlin.math.abs(
                                                moved - subtitleAppearance.verticalPosition
                                            ) > 0.004f
                                        ) {
                                            applySubtitleAppearance(
                                                subtitleAppearance.copy(
                                                    verticalPosition = moved
                                                )
                                            )
                                        }
                                    }
                                    dragActive = false
                                },
                                onDragCancel = { dragActive = false }
                            )
                        }
                        .graphicsLayer {
                            // Manual scale presets (the VLC-style replacement for
                            // the removed pinch zoom), composed with the
                            // FIT/ZOOM/FILL mode and with the subtitle safe
                            // area: while the controls are visible the picture
                            // is rendered into the area above them, so cues
                            // painted into the frame are never hidden behind
                            // the bar — but the surface itself is the full
                            // fixed stage, and the transform is computed
                            // analytically from the stage size (see
                            // videoPictureTransform), so it cannot jump or
                            // spill over the stage edge when the bar toggles.
                            // The transform NEVER depends on the controls'
                            // visibility (PC-14): the bar overlays the
                            // picture, and the picture stays exactly where
                            // it is when the bar shows or hides. (An earlier
                            // design shifted the picture up by a subtitle
                            // safe area while the bar was visible; on
                            // screen that shift IS the jump the user
                            // recorded, so the inset is permanently zero.)
                            val insetPx = 0f
                            val transform = videoPictureTransform(
                                preset = scalePreset,
                                stageWidth = stageSize.width,
                                stageHeight = stageSize.height,
                                bottomInsetPx = insetPx.roundToInt(),
                                videoWidth = current.width,
                                videoHeight = current.height,
                                resizeMode = scaling
                            )
                            scaleX = transform.scaleX
                            scaleY = transform.scaleY
                            translationY = transform.translationYPx
                        },
                    contentScale = scaling.contentScale
                )
            }
        }

        if (buffering && !finished) {
            CircularWavyProgressIndicator(modifier = Modifier.size(56.dp))
        }

        AnimatedVisibility(
            visible = controlsVisible || seekFeedbackVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            // Anchor the complete control surface to the player viewport, independent of
            // window focus changes, top-bar visibility, and responsive FlowRow wrapping.
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            PlayerControlsBar(
                player = player,
                isAudio = isAudio,
                playing = playing,
                finished = finished,
                timeMs = timeMs,
                durationMs = durationMs,
                muted = muted,
                volume = volume,
                brightness = brightness,
                brightnessAvailable = brightnessAvailable,
                brightnessMonitor = brightnessMonitor,
                rate = rate,
                repeatOne = repeatOne,
                repeatAll = repeatAll,
                shuffle = shuffle,
                queueItems = queueItems,
                currentQueueItemId = currentQueueItemId,
                sleepTimerLabel = sleepTimerLabel,
                trackTick = trackTick,
                scaling = scaling,
                backgroundPlayback = backgroundPlayback,
                onBackgroundPlaybackChange = onBackgroundPlaybackChange,
                onPrevious = onPrevious,
                onNext = onNext,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onSelectAudioTrack = { track ->
                    player.submit { runCatching { player.audio().setTrack(track.id) } }
                    onPreferredAudioLanguage(track.language)
                    trackTick++
                },
                onSelectTextTrack = { track ->
                    val trackId = track?.id ?: VLC_TRACK_DISABLED
                    player.submit {
                        if (trackId != VLC_TRACK_DISABLED) {
                            runCatching {
                                player.subpictures().setTrack(VLC_TRACK_DISABLED)
                            }
                        }
                        runCatching { player.subpictures().setTrack(trackId) }
                    }
                    onPreferredSubtitleLanguage(track?.language.orEmpty())
                    trackTick++
                },
                onInteraction = { interactionTick++ },
                onPlayPause = togglePlayback,
                onSeekTo = { millis ->
                    player.submit { runCatching { player.controls().setTime(millis) } }
                },
                onToggleMute = toggleMute,
                onVolumeChange = { setPlayerVolume(it) },
                onBrightnessChange = {
                    val requested = it.coerceIn(0, MAX_BRIGHTNESS)
                    brightness = requested
                    brightnessLastInputAt = System.currentTimeMillis()
                    brightnessUpdates.trySend(requested.toFloat())
                    interactionTick++
                },
                onBrightnessDragging = { dragging ->
                    brightnessDragging = dragging
                    if (!dragging) brightnessLastInputAt = System.currentTimeMillis()
                },
                onControlsKeepVisible = { controlsPinned = it },
                onToggleControlsVisibility = {
                    persistControlPreferences(
                        controlPreferences.copy(showPlayerControls = false)
                    )
                    controlsPinned = false
                    controlsVisible = false
                    latestOnControlsVisibilityChange.value(false)
                    lastVisibilityInteractionTick = interactionTick
                },
                onRate = ::setPlaybackRate,
                onToggleRepeat = {
                    repeatOne = !repeatOne
                    if (repeatOne && repeatAll) onToggleRepeatAll()
                    player.submit { runCatching { player.controls().repeat = repeatOne } }
                },
                onToggleRepeatAll = onToggleRepeatAll,
                onToggleShuffle = onToggleShuffle,
                onSelectQueueItem = onSelectQueueItem,
                onSetSleepTimer = onSetSleepTimer,
                onSleepAtTrackEnd = onSleepAtTrackEnd,
                onClearSleepTimer = onClearSleepTimer,
                onScaling = { scaling = it },
                controlPreferences = controlPreferences,
                onControlPreferencesChange = { updated ->
                    // Setting changes are infrequent, so persist them normally. Enabling
                    // memory captures the current slider value for future media/app launches.
                    persistControlPreferences(updated)
                    if (controlPreferences.rememberVolume) {
                        RememberedPlayerVolume.update(volume, persist = true)
                    }
                    interactionTick++
                },
                subtitleSettingsOpen = showSubtitleSettings,
                onOpenSubtitleSettings = { showSubtitleSettings = true },
                onSubtitlesOnChange = { subtitleTrackActive = it },
                scalePreset = scalePreset,
                onScalePresetChange = { scalePreset = it }
            )
        }

        if (showSubtitleSettings) {
            SubtitleAppearanceDialog(
                appearance = subtitleAppearance,
                onDismiss = { showSubtitleSettings = false },
                onApply = { applySubtitleAppearance(it) }
            )
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PlayerControlsBar(
    player: MediaPlayer,
    isAudio: Boolean,
    playing: Boolean,
    finished: Boolean,
    timeMs: Long,
    durationMs: Long,
    muted: Boolean,
    volume: Int,
    brightness: Int,
    brightnessAvailable: Boolean,
    brightnessMonitor: String,
    rate: Float,
    repeatOne: Boolean,
    repeatAll: Boolean,
    shuffle: Boolean,
    queueItems: List<PlayerQueueItem>,
    currentQueueItemId: String?,
    sleepTimerLabel: String,
    trackTick: Int,
    scaling: VideoScaling,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onInteraction: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleMute: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onBrightnessChange: (Int) -> Unit,
    onBrightnessDragging: (Boolean) -> Unit,
    onControlsKeepVisible: (Boolean) -> Unit,
    onToggleControlsVisibility: () -> Unit,
    onRate: (Float) -> Unit,
    onToggleRepeat: () -> Unit,
    onToggleRepeatAll: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSelectQueueItem: (String) -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onSleepAtTrackEnd: () -> Unit,
    onClearSleepTimer: () -> Unit,
    onSelectAudioTrack: (VlcTrack) -> Unit,
    onSelectTextTrack: (VlcTrack?) -> Unit,
    onScaling: (VideoScaling) -> Unit,
    controlPreferences: PlayerControlPreferences,
    onControlPreferencesChange: (PlayerControlPreferences) -> Unit,
    backgroundPlayback: Boolean = false,
    onBackgroundPlaybackChange: (Boolean) -> Unit = {},
    subtitleSettingsOpen: Boolean,
    onOpenSubtitleSettings: () -> Unit,
    onSubtitlesOnChange: (Boolean) -> Unit,
    scalePreset: VideoScalePreset,
    onScalePresetChange: (VideoScalePreset) -> Unit
) {
    var showRateMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var showScaleMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    var showQueueMenu by remember { mutableStateOf(false) }
    var showSleepMenu by remember { mutableStateOf(false) }
    var showCustomSleepDialog by remember { mutableStateOf(false) }
    var customSleepHours by remember { mutableStateOf("0") }
    var customSleepMinutes by remember { mutableStateOf("30") }
    var scrubTarget by remember { mutableStateOf<Float?>(null) }
    val fullscreen = LocalFullscreenController.current
    val trackWord = stringResource(Res.string.player_track)
    var audioTracks by remember(player) { mutableStateOf(emptyList<VlcTrack>()) }
    var textTracks by remember(player) { mutableStateOf(emptyList<VlcTrack>()) }

    // Track enumeration crosses the native LibVLC boundary. Keep it off the Compose
    // thread and serialize it with the rest of this player's native operations.
    LaunchedEffect(player, trackTick, trackWord) {
        val result = CompletableDeferred<Pair<List<VlcTrack>, List<VlcTrack>>>()
        runCatching {
            player.submit {
                val audio = runCatching { player.audioTracks(trackWord) }.getOrDefault(emptyList())
                val text = runCatching { player.textTracks(trackWord) }.getOrDefault(emptyList())
                result.complete(audio to text)
            }
        }.onFailure {
            result.complete(emptyList<VlcTrack>() to emptyList())
        }
        withTimeoutOrNull(PLAYER_NATIVE_CALL_TIMEOUT_MS) { result.await() }?.let { tracks ->
            audioTracks = tracks.first
            textTracks = tracks.second
        }
    }
    val subtitlesOn = textTracks.any { it.selected }
    LaunchedEffect(subtitlesOn) { onSubtitlesOnChange(subtitlesOn) }

    if (showCustomSleepDialog) {
        AlertDialog(
            onDismissRequest = { showCustomSleepDialog = false },
            title = { Text("Custom sleep timer") },
            text = {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = customSleepHours,
                        onValueChange = { customSleepHours = it.filter(Char::isDigit).take(3) },
                        label = { Text("Hours") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = customSleepMinutes,
                        onValueChange = { customSleepMinutes = it.filter(Char::isDigit).take(2) },
                        label = { Text("Minutes") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val hours = customSleepHours.toLongOrNull() ?: 0L
                        val minutes = customSleepMinutes.toLongOrNull() ?: 0L
                        val durationMs = (hours * 60L + minutes) * 60_000L
                        if (durationMs > 0L) {
                            onSetSleepTimer(durationMs)
                            showCustomSleepDialog = false
                            onInteraction()
                        }
                    }
                ) { Text("Start timer") }
            },
            dismissButton = {
                TextButton(onClick = { showCustomSleepDialog = false }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        val displayedTimeMs = (scrubTarget?.toLong() ?: timeMs)
            .coerceAtLeast(0L)
            .let { position -> if (durationMs > 0L) position.coerceAtMost(durationMs) else position }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = Formatters.duration(displayedTimeMs),
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
            Text(
                text = if (durationMs > 0L) {
                    "-${Formatters.duration((durationMs - displayedTimeMs).coerceAtLeast(0L))}"
                } else {
                    "--:--"
                },
                style = MaterialTheme.typography.labelMedium,
                color = Color.White
            )
        }
        Slider(
            value = scrubTarget ?: if (durationMs > 0) timeMs.toFloat() else 0f,
            onValueChange = { value ->
                onInteraction()
                scrubTarget = value
            },
            onValueChangeFinished = {
                scrubTarget?.let { target -> onSeekTo(target.toLong()) }
                scrubTarget = null
            },
            valueRange = 0f..durationMs.coerceAtLeast(1).toFloat(),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = CircleShape
                        )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )

        var showSliderSettings by remember { mutableStateOf(false) }
        var volumeSliderActive by remember { mutableStateOf(false) }
        var brightnessSliderActive by remember { mutableStateOf(false) }

        LaunchedEffect(
            showSliderSettings,
            showRateMenu,
            showAudioMenu,
            showSubtitleMenu,
            showQueueMenu,
            showSleepMenu,
            showCustomSleepDialog,
            volumeSliderActive,
            brightnessSliderActive,
            subtitleSettingsOpen,
            showScaleMenu,
            showMoreMenu
        ) {
            onControlsKeepVisible(
                showSliderSettings || showRateMenu || showAudioMenu || showSubtitleMenu ||
                    showQueueMenu || showSleepMenu || showCustomSleepDialog ||
                    volumeSliderActive || brightnessSliderActive || subtitleSettingsOpen ||
                    showScaleMenu || showMoreMenu
            )
        }
        DisposableEffect(Unit) {
            onDispose { onControlsKeepVisible(false) }
        }

        // Menu contents shared between the wide control row's own menus and the
        // narrow row's "More" overflow menu.
        val queueMenuItems: @Composable (onChosen: () -> Unit) -> Unit = { onChosen ->
            queueItems.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.name, maxLines = 1) },
                    leadingIcon = {
                        RadioButton(
                            selected = item.id == currentQueueItemId,
                            onClick = null
                        )
                    },
                    onClick = {
                        onChosen()
                        onSelectQueueItem(item.id)
                        onInteraction()
                    }
                )
            }
        }
        val sleepMenuItems: @Composable (onChosen: () -> Unit) -> Unit = { onChosen ->
            listOf(15L, 30L, 45L, 60L).forEach { minutes ->
                DropdownMenuItem(
                    text = { Text("$minutes minutes") },
                    onClick = {
                        onChosen()
                        onSetSleepTimer(minutes * 60_000L)
                        onInteraction()
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Custom duration\u2026") },
                onClick = {
                    onChosen()
                    showCustomSleepDialog = true
                }
            )
            DropdownMenuItem(
                text = { Text("End of current track") },
                onClick = {
                    onChosen()
                    onSleepAtTrackEnd()
                    onInteraction()
                }
            )
            if (sleepTimerLabel != "Sleep timer") {
                DropdownMenuItem(
                    text = { Text("Turn off sleep timer") },
                    onClick = {
                        onChosen()
                        onClearSleepTimer()
                        onInteraction()
                    }
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlayerAutoHideSlider(
                icon = if (muted) Icons.AutoMirrored.Filled.VolumeOff
                else Icons.AutoMirrored.Filled.VolumeUp,
                contentDescription = if (muted) "Unmute video" else "Mute video",
                value = volume.toFloat(),
                valueRange = 0f..MAX_VOLUME.toFloat(),
                autoHide = controlPreferences.autoHideVolumeSlider,
                hideDelayMillis = controlPreferences.hideDelayMillis,
                onButtonClick = {
                    onInteraction()
                    onToggleMute()
                },
                onValueChange = { value ->
                    onInteraction()
                    onVolumeChange(value.roundToInt())
                },
                onActiveChange = { volumeSliderActive = it }
            )

            if (!isAudio && brightnessAvailable) {
                PlayerAutoHideSlider(
                    icon = Icons.Filled.Brightness6,
                    contentDescription = if (brightnessMonitor.isBlank()) {
                        "Display brightness"
                    } else {
                        "Display brightness: $brightnessMonitor"
                    },
                    value = brightness.toFloat(),
                    valueRange = 0f..MAX_BRIGHTNESS.toFloat(),
                    autoHide = controlPreferences.autoHideBrightnessSlider,
                    hideDelayMillis = controlPreferences.hideDelayMillis,
                    onButtonClick = onInteraction,
                    onValueChange = { value ->
                        onInteraction()
                        onBrightnessChange(value.roundToInt())
                    },
                    onDraggingChange = onBrightnessDragging,
                    onActiveChange = { brightnessSliderActive = it },
                    expandOnClick = true
                )
            }

            Box {
                TooltipIconButton(
                    tooltip = "Player slider settings",
                    onClick = {
                        onInteraction()
                        showSliderSettings = true
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = "Player slider settings",
                        tint = Color.White
                    )
                }
                DropdownMenu(
                    expanded = showSliderSettings,
                    onDismissRequest = { showSliderSettings = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Auto-hide volume slider") },
                        leadingIcon = {
                            Checkbox(
                                checked = controlPreferences.autoHideVolumeSlider,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onControlPreferencesChange(
                                controlPreferences.copy(
                                    autoHideVolumeSlider = !controlPreferences.autoHideVolumeSlider
                                )
                            )
                            onInteraction()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Auto-hide brightness slider") },
                        leadingIcon = {
                            Checkbox(
                                checked = controlPreferences.autoHideBrightnessSlider,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onControlPreferencesChange(
                                controlPreferences.copy(
                                    autoHideBrightnessSlider = !controlPreferences.autoHideBrightnessSlider
                                )
                            )
                            onInteraction()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Auto-hide player buttons") },
                        leadingIcon = {
                            Checkbox(
                                checked = controlPreferences.autoHidePlayerControls,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onControlPreferencesChange(
                                controlPreferences.copy(
                                    autoHidePlayerControls = !controlPreferences.autoHidePlayerControls
                                )
                            )
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Show player controls") },
                        leadingIcon = {
                            Checkbox(
                                checked = controlPreferences.showPlayerControls,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onControlPreferencesChange(
                                controlPreferences.copy(
                                    showPlayerControls = !controlPreferences.showPlayerControls
                                )
                            )
                            onControlsKeepVisible(!controlPreferences.showPlayerControls)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Remember volume across media and restarts") },
                        leadingIcon = {
                            Checkbox(
                                checked = controlPreferences.rememberVolume,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onControlPreferencesChange(
                                controlPreferences.copy(
                                    rememberVolume = !controlPreferences.rememberVolume
                                )
                            )
                            onInteraction()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Background playback") },
                        leadingIcon = {
                            Checkbox(
                                checked = backgroundPlayback,
                                onCheckedChange = null
                            )
                        },
                        onClick = {
                            onBackgroundPlaybackChange(!backgroundPlayback)
                            onInteraction()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Auto-hide delay (all enabled controls)") },
                        enabled = false,
                        onClick = {}
                    )
                    listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L).forEach { delay ->
                        val selected = controlPreferences.hideDelayMillis == delay
                        DropdownMenuItem(
                            text = { Text("${delay / 1_000L} ${if (delay == 1_000L) "second" else "seconds"}") },
                            leadingIcon = {
                                RadioButton(selected = selected, onClick = null)
                            },
                            onClick = {
                                onControlPreferencesChange(
                                    controlPreferences.copy(hideDelayMillis = delay)
                                )
                                onInteraction()
                            }
                        )
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TooltipIconButton(
                    tooltip = "Previous video",
                    onClick = { onInteraction(); onPrevious() },
                    enabled = hasPrevious,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipPrevious,
                        contentDescription = stringResource(Res.string.preview_previous),
                        tint = if (hasPrevious) Color.White else Color.White.copy(alpha = 0.35f)
                    )
                }
                TooltipIconButton(
                    tooltip = "Rewind 10 seconds",
                    onClick = {
                        onInteraction()
                        onSeekTo((timeMs - SKIP_MS).coerceAtLeast(0))
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Replay10,
                        contentDescription = stringResource(Res.string.player_skip_back),
                        tint = Color.White
                    )
                }
                TooltipIconButton(
                    tooltip = stringResource(if (finished) Res.string.player_replay else if (playing) Res.string.player_pause else Res.string.player_play),
                    onClick = { onInteraction(); onPlayPause() },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        imageVector = when {
                            finished -> Icons.Filled.Replay
                            playing -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(
                            when {
                                finished -> Res.string.player_replay
                                playing -> Res.string.player_pause
                                else -> Res.string.player_play
                            }
                        ),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
                TooltipIconButton(
                    tooltip = "Forward 10 seconds",
                    onClick = {
                        onInteraction()
                        onSeekTo((timeMs + SKIP_MS).coerceAtMost(durationMs))
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Forward10,
                        contentDescription = stringResource(Res.string.player_skip_forward),
                        tint = Color.White
                    )
                }
                TooltipIconButton(
                    tooltip = "Next video",
                    onClick = { onInteraction(); onNext() },
                    enabled = hasNext,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.SkipNext,
                        contentDescription = stringResource(Res.string.preview_next),
                        tint = if (hasNext) Color.White else Color.White.copy(alpha = 0.35f)
                    )
                }
            }
            // One stable row at desktop widths; below ~720dp the secondary
            // actions move into a "More" overflow menu instead of wrapping
            // (the wrapping FlowRow made the bar reflow while in use).
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val wideControls = maxWidth >= 720.dp
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                Box {
                    TextButton(onClick = {
                        onInteraction()
                        showRateMenu = true
                    }) {
                        Text(text = "${formatPlaybackRate(rate)}x", color = Color.White)
                    }
                    DropdownMenu(
                        expanded = showRateMenu,
                        onDismissRequest = { showRateMenu = false }
                    ) {
                        PLAYBACK_RATE_PRESETS.forEach { value ->
                            DropdownMenuItem(
                                text = { Text("${formatPlaybackRate(value)}x") },
                                onClick = {
                                    showRateMenu = false
                                    onRate(value)
                                }
                            )
                        }
                    }
                }
                TooltipIconButton(
                    tooltip = stringResource(if (repeatOne) Res.string.player_repeat_one else Res.string.player_repeat_off),
                    onClick = { onInteraction(); onToggleRepeat() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = if (repeatOne) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = stringResource(
                            if (repeatOne) Res.string.player_repeat_one else Res.string.player_repeat_off
                        ),
                        tint = if (repeatOne) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                if (wideControls) {
                TooltipIconButton(
                    tooltip = "Repeat all videos",
                    onClick = { onInteraction(); onToggleRepeatAll() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Repeat,
                        contentDescription = "Repeat all videos",
                        tint = if (repeatAll) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                TooltipIconButton(
                    tooltip = "Shuffle playback queue",
                    onClick = { onInteraction(); onToggleShuffle() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shuffle,
                        contentDescription = "Shuffle queue",
                        tint = if (shuffle) MaterialTheme.colorScheme.primary else Color.White
                    )
                }
                Box {
                    TooltipIconButton(
                        tooltip = "Open playback queue (${queueItems.size} items)",
                        onClick = { onInteraction(); showQueueMenu = true },
                        enabled = queueItems.isNotEmpty(),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.QueueMusic,
                            contentDescription = "Open playback queue (${queueItems.size} items)",
                            tint = if (queueItems.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = showQueueMenu,
                        onDismissRequest = { showQueueMenu = false },
                        modifier = Modifier.heightIn(max = 420.dp)
                    ) {
                        queueMenuItems { showQueueMenu = false }
                    }
                }
                Box {
                    TextButton(
                        onClick = { onInteraction(); showSleepMenu = true }
                    ) {
                        Icon(Icons.Filled.Timer, contentDescription = null, tint = Color.White)
                        Text(
                            sleepTimerLabel,
                            color = if (sleepTimerLabel == "Sleep timer") Color.White
                            else MaterialTheme.colorScheme.primary
                        )
                    }
                    DropdownMenu(
                        expanded = showSleepMenu,
                        onDismissRequest = { showSleepMenu = false }
                    ) {
                        sleepMenuItems { showSleepMenu = false }
                    }
                }
                }
                if (!isAudio) {
                    Box {
                        TooltipIconButton(
                            tooltip = stringResource(if (subtitlesOn) Res.string.player_hide_subtitles else Res.string.player_show_subtitles),
                            onClick = { onInteraction(); showSubtitleMenu = true },
                            enabled = textTracks.isNotEmpty(),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = if (subtitlesOn) Icons.Filled.Subtitles else Icons.Filled.SubtitlesOff,
                                contentDescription = stringResource(
                                    if (subtitlesOn) Res.string.player_hide_subtitles
                                    else Res.string.player_show_subtitles
                                ),
                                tint = if (textTracks.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showSubtitleMenu,
                            onDismissRequest = { showSubtitleMenu = false }
                        ) {
                            TrackMenuItem(
                                label = stringResource(Res.string.player_subtitles_off),
                                selected = !subtitlesOn,
                                onClick = {
                                    showSubtitleMenu = false
                                    onSelectTextTrack(null)
                                }
                            )
                            textTracks.forEach { track ->
                                TrackMenuItem(
                                    label = track.label,
                                    selected = track.selected,
                                    onClick = {
                                        showSubtitleMenu = false
                                        onSelectTextTrack(track)
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(Res.string.player_subtitle_appearance))
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Settings,
                                        contentDescription = null
                                    )
                                },
                                onClick = {
                                    showSubtitleMenu = false
                                    onOpenSubtitleSettings()
                                }
                            )
                        }
                    }
                }
                Box {
                    TooltipIconButton(
                        tooltip = stringResource(Res.string.player_audio_track),
                        onClick = { onInteraction(); showAudioMenu = true },
                        enabled = audioTracks.isNotEmpty(),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Audiotrack,
                            contentDescription = stringResource(Res.string.player_audio_track),
                            tint = if (audioTracks.isEmpty()) Color.White.copy(alpha = 0.4f) else Color.White
                        )
                    }
                    DropdownMenu(
                        expanded = showAudioMenu,
                        onDismissRequest = { showAudioMenu = false }
                    ) {
                        audioTracks.forEach { track ->
                            TrackMenuItem(
                                label = track.label,
                                selected = track.selected,
                                onClick = {
                                    showAudioMenu = false
                                    onSelectAudioTrack(track)
                                }
                            )
                        }
                    }
                }
                if (!isAudio) {
                    Box {
                        TooltipIconButton(
                            tooltip = stringResource(Res.string.player_scaling),
                            onClick = { onInteraction(); showScaleMenu = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = when (scaling) {
                                    VideoScaling.FIT -> Icons.Filled.FitScreen
                                    VideoScaling.ZOOM -> Icons.Filled.ZoomOutMap
                                    VideoScaling.FILL -> Icons.Filled.Fullscreen
                                },
                                contentDescription = stringResource(Res.string.player_scaling),
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showScaleMenu,
                            onDismissRequest = { showScaleMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.player_video_scale)) },
                                enabled = false,
                                onClick = {}
                            )
                            VideoScalePreset.entries.forEach { preset ->
                                TrackMenuItem(
                                    label = preset.label,
                                    selected = scalePreset == preset,
                                    onClick = {
                                        showScaleMenu = false
                                        onScalePresetChange(preset)
                                        onInteraction()
                                    }
                                )
                            }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.player_resize_mode)) },
                                enabled = false,
                                onClick = {}
                            )
                            TrackMenuItem(
                                label = stringResource(Res.string.player_resize_fit),
                                selected = scaling == VideoScaling.FIT,
                                onClick = {
                                    showScaleMenu = false
                                    onScaling(VideoScaling.FIT)
                                    onInteraction()
                                }
                            )
                            TrackMenuItem(
                                label = stringResource(Res.string.player_resize_zoom),
                                selected = scaling == VideoScaling.ZOOM,
                                onClick = {
                                    showScaleMenu = false
                                    onScaling(VideoScaling.ZOOM)
                                    onInteraction()
                                }
                            )
                            TrackMenuItem(
                                label = stringResource(Res.string.player_resize_fill),
                                selected = scaling == VideoScaling.FILL,
                                onClick = {
                                    showScaleMenu = false
                                    onScaling(VideoScaling.FILL)
                                    onInteraction()
                                }
                            )
                        }
                    }
                }
                if (!wideControls) {
                    Box {
                        TooltipIconButton(
                            tooltip = stringResource(Res.string.player_more_actions),
                            onClick = { onInteraction(); showMoreMenu = true },
                            modifier = Modifier.size(38.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreHoriz,
                                contentDescription = stringResource(
                                    Res.string.player_more_actions
                                ),
                                tint = Color.White
                            )
                        }
                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Repeat all videos") },
                                leadingIcon = {
                                    Checkbox(checked = repeatAll, onCheckedChange = null)
                                },
                                onClick = {
                                    onToggleRepeatAll()
                                    onInteraction()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Shuffle playback queue") },
                                leadingIcon = {
                                    Checkbox(checked = shuffle, onCheckedChange = null)
                                },
                                onClick = {
                                    onToggleShuffle()
                                    onInteraction()
                                }
                            )
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.player_queue)) },
                                enabled = false,
                                onClick = {}
                            )
                            queueMenuItems { showMoreMenu = false }
                            HorizontalDivider()
                            DropdownMenuItem(
                                text = { Text(sleepTimerLabel) },
                                enabled = false,
                                onClick = {}
                            )
                            sleepMenuItems { showMoreMenu = false }
                        }
                    }
                }
                TooltipIconButton(
                    tooltip = "Hide player controls",
                    onClick = { onToggleControlsVisibility() },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.VisibilityOff,
                        contentDescription = "Hide player controls",
                        tint = Color.White
                    )
                }
                fullscreen?.let { controller ->
                    TooltipIconButton(
                        tooltip = stringResource(if (controller.isFullscreen()) Res.string.player_exit_full_screen else Res.string.player_full_screen),
                        onClick = { onInteraction(); controller.toggle() },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = if (controller.isFullscreen()) {
                                Icons.Filled.FullscreenExit
                            } else {
                                Icons.Filled.Fullscreen
                            },
                            contentDescription = stringResource(
                                if (controller.isFullscreen()) Res.string.player_exit_full_screen
                                else Res.string.player_full_screen
                            ),
                            tint = Color.White
                        )
                    }
                }
                }
            }
        }
    }
}

internal data class PlayerQueueItem(
    val id: String,
    val name: String
)

@Composable
private fun TrackMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { RadioButton(selected = selected, onClick = null) },
        onClick = onClick
    )
}

private val PLAYBACK_RATE_PRESETS = listOf(
    0.5f, 0.6f, 0.7f, 0.8f, 0.9f,
    1.0f, 1.15f, 1.2f, 1.25f, 1.3f, 1.4f, 1.5f, 1.75f,
    2.0f, 2.5f, 3.0f, 4.0f
)

private fun MediaPlayer.submitAudioState(volume: Int, muted: Boolean) {
    val requestedVolume = volume.coerceIn(0, MAX_VOLUME)
    val requestedMute = muted || requestedVolume <= 0
    runCatching {
        submit {
            runCatching {
                audio().setVolume(requestedVolume)
                audio().isMute = requestedMute
            }
        }
    }
}

private const val PLAYER_NATIVE_CALL_TIMEOUT_MS = 2_500L
private const val MOVE_THRESHOLD_PX = 4f
private const val SKIP_MS = 10_000L
private const val SHORT_KEY_SEEK_STEP_MS = 5_000L
private const val KEY_SEEK_STEP_MS = 10_000L
private const val SEEK_FEEDBACK_DURATION_MS = 1_500L
private const val VOLUME_STEP = 5
private const val MAX_VOLUME = 200
private const val MAX_BRIGHTNESS = 100
private const val BRIGHTNESS_POLL_INTERVAL_MS = 2_500L
private const val BRIGHTNESS_READ_GUARD_MS = 1_800L
private const val AUDIO_OUTPUT_REAPPLY_INTERVAL_MS = 250L
private const val AUDIO_OUTPUT_REAPPLY_ATTEMPTS = 3
private fun normalizePlaybackRate(value: Float): Float =
    (value.coerceIn(0.25f, 4f) * 20f).roundToInt() / 20f

private fun formatPlaybackRate(value: Float): String =
    String.format(java.util.Locale.ROOT, "%.2f", normalizePlaybackRate(value))
        .trimEnd('0')
        .trimEnd('.')

private const val FINE_RATE_STEP = 0.05f
private const val SYNC_STEP_US = 50_000L
private val THUMB_WIDTH = 5.dp
private val THUMB_HEIGHT = 26.dp

/** A subtitle-appearance change captured just before the player is recreated. */
private data class StyleRestart(
    /** The media the position was captured on; a restart must never seek a different file. */
    val mediaId: String,
    val positionMs: Long,
    val wasPlaying: Boolean
)


/**
 * Desktop port of Android's AudioStage: album artwork (the file's thumbnail,
 * which for audio is the embedded cover) above title / artist / album lines
 * read from the playing media's metadata, falling back to the file name.
 * Mouse/keyboard behaviour is unchanged — this is layout only; the shared
 * control bar below remains the transport.
 */
@Composable
private fun AudioStage(
    player: MediaPlayer,
    mediaId: String,
    fallbackTitle: String
) {
    var metaTitle by remember(mediaId) { mutableStateOf("") }
    var artist by remember(mediaId) { mutableStateOf("") }
    var album by remember(mediaId) { mutableStateOf("") }
    LaunchedEffect(player, mediaId) {
        // Metadata lands shortly after playback starts; poll briefly for it.
        repeat(12) {
            val meta = runCatching { player.media().meta() }.getOrNull()
            if (meta != null) {
                metaTitle = runCatching { meta.get(uk.co.caprica.vlcj.media.Meta.TITLE) }
                    .getOrNull().orEmpty()
                artist = runCatching { meta.get(uk.co.caprica.vlcj.media.Meta.ARTIST) }
                    .getOrNull().orEmpty()
                album = runCatching { meta.get(uk.co.caprica.vlcj.media.Meta.ALBUM) }
                    .getOrNull().orEmpty()
                if (metaTitle.isNotBlank() || artist.isNotBlank()) return@LaunchedEffect
            }
            delay(500)
        }
    }
    var artworkFailed by remember(mediaId) { mutableStateOf(false) }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.padding(horizontal = 24.dp)
    ) {
        Surface(
            modifier = Modifier.size(260.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh
        ) {
            if (!artworkFailed) {
                coil3.compose.AsyncImage(
                    model = com.drdisagree.teledrive.core.media.ThumbnailModel(mediaId),
                    contentDescription = null,
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { artworkFailed = true }
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Audiotrack,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Text(
            text = metaTitle.ifBlank { fallbackTitle },
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        if (artist.isNotBlank()) {
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
        if (album.isNotBlank()) {
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
