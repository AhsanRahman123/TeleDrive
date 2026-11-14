package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.Icon
import com.drdisagree.teledrive.presentation.components.TooltipIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import com.drdisagree.teledrive.core.common.SafeLog
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.desktop.ui.LocalFullscreenController
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.player_audio_track
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
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
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
    mediaId: String,
    isAudio: Boolean,
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
    onDelete: () -> Unit = {}
) {
    val factory = VlcPlayback.factory ?: return
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
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
                MAX_VOLUME
            }
        )
    }
    var brightness by remember { mutableIntStateOf(100) }
    var rate by remember(mrl) { mutableStateOf(1.0f) }
    var repeatOne by remember { mutableStateOf(false) }
    var trackTick by remember { mutableIntStateOf(0) }
    var scaling by remember { mutableStateOf(VideoScaling.FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var controlsPinned by remember { mutableStateOf(false) }
    var seekFeedbackVisible by remember { mutableStateOf(false) }
    var seekFeedbackTick by remember { mutableIntStateOf(0) }
    var hKeyPressed by remember { mutableStateOf(false) }
    var lastVisibilityInteractionTick by remember { mutableIntStateOf(0) }
    // MediaPlayerEventAdapter outlives individual recompositions; use updated state so
    // a play/resume event always reapplies the current remembered volume and mute state.
    val latestVolume = rememberUpdatedState(volume)
    val latestMuted = rememberUpdatedState(muted)
    val latestTimeMs = rememberUpdatedState(timeMs)
    val latestDurationMs = rememberUpdatedState(durationMs)
    val latestFinished = rememberUpdatedState(finished)
    val latestRepeatOne = rememberUpdatedState(repeatOne)
    val latestSleepAtTrackEnd = rememberUpdatedState(sleepAtTrackEnd)
    val latestOnPlaybackFinished = rememberUpdatedState(onPlaybackFinished)
    val latestOnControlsVisibilityChange = rememberUpdatedState(onControlsVisibilityChange)
    val playerScope = rememberCoroutineScope()
    val savedResumePositionMs = remember(mediaId, isAudio) {
        if (isAudio) 0L else VideoResumeStore.loadPosition(mediaId)
    }
    val resumeRestoreAttempted = remember(mediaId, mrl, isAudio) {
        AtomicBoolean(false)
    }
    val resumeInitializationComplete = remember(mediaId, mrl, isAudio) {
        AtomicBoolean(false)
    }
    val pendingResumeTargetMs = remember(mediaId, mrl, isAudio) { AtomicLong(0L) }
    val resumeFinished = remember(mediaId, mrl, isAudio) { AtomicBoolean(false) }
    // Drop late callbacks from the outgoing MRL while VLC is stopping it and attaching the next
    // media. The new source becomes active only after VLC reports that it has started playing.
    val acceptsCurrentMediaEvents = remember(mrl, mediaId, isAudio) { AtomicBoolean(false) }
    val playerDisposed = remember(mrl, mediaId, isAudio) { AtomicBoolean(false) }

    // VLC calls its render callback from a native thread. Conflate pending frames before
    // publishing them to Compose so a slow UI frame cannot build an unbounded render backlog.
    // Each ImageBitmap already owns an independent pixel buffer (see ComposeVideoSurface).
    val frameUpdates = remember(mrl, mediaId, isAudio) {
        Channel<VideoFramePixels>(capacity = Channel.CONFLATED)
    }
    LaunchedEffect(frameUpdates) {
        for (nextFrame in frameUpdates) {
            // Bitmap installation can be expensive for full-resolution frames. Keep that work
            // off the UI dispatcher; only publish the completed immutable image on Compose Main.
            val image = withContext(Dispatchers.Default) {
                nextFrame.toImageBitmap()
            } ?: continue
            frame = image
        }
    }
    DisposableEffect(frameUpdates) {
        onDispose { frameUpdates.close() }
    }

    // Keep high-frequency playback position notifications bounded as well.
    val timeUpdates = remember(mrl, mediaId, isAudio) {
        Channel<Long>(capacity = Channel.CONFLATED)
    }
    LaunchedEffect(timeUpdates, mediaId, isAudio) {
        for (position in timeUpdates) {
            timeMs = position
            if (!isAudio && resumeInitializationComplete.get() && !resumeFinished.get()) {
                val pendingResume = pendingResumeTargetMs.get()
                if (pendingResume > 0L) {
                    // Ignore opening-time events until VLC reports the restored seek position.
                    if (isVideoResumePositionConfirmed(position, pendingResume) &&
                        pendingResumeTargetMs.compareAndSet(pendingResume, 0L)
                    ) {
                        VideoResumeStore.savePosition(mediaId, position, durationMs)
                    }
                } else {
                    VideoResumeStore.savePosition(mediaId, position, durationMs)
                }
            }
        }
    }
    DisposableEffect(timeUpdates) {
        onDispose { timeUpdates.close() }
    }

    fun markManualVideoSeek() {
        if (isAudio) return
        // An intentional user seek takes precedence over the saved resume point, including
        // when it happens while VLC is still reporting the initial opening position.
        resumeRestoreAttempted.set(true)
        pendingResumeTargetMs.set(0L)
        resumeInitializationComplete.set(true)
    }

    fun initializeVideoResume(mediaPlayer: MediaPlayer, mediaDurationMs: Long) {
        if (isAudio || mediaDurationMs <= 0L ||
            !resumeRestoreAttempted.compareAndSet(false, true)
        ) return

        val resumeAt = videoResumeTarget(savedResumePositionMs, mediaDurationMs)
        if (resumeAt == null) {
            if (savedResumePositionMs > 0L) VideoResumeStore.clear(mediaId)
            pendingResumeTargetMs.set(0L)
            resumeInitializationComplete.set(true)
            return
        }

        // Seek only after VLC has opened the media and reported a valid duration. Keep position
        // persistence gated until the native seek has completed so an initial 0:00 time event
        // cannot overwrite the saved resume point while restoration is still in flight.
        pendingResumeTargetMs.set(resumeAt)
        mediaPlayer.submit {
            val restored = runCatching {
                mediaPlayer.controls().setTime(resumeAt)
            }.isSuccess
            if (restored) {
                resumeInitializationComplete.set(true)
            } else {
                pendingResumeTargetMs.set(0L)
            }
        }
    }

    // Give every MRL an isolated native VLC player. Reusing one decoder/audio-output
    // instance across unrelated media caused corrupted frames and unstable audio on switches.
    val player = remember(mrl, mediaId, isAudio) {
        factory.mediaPlayers().newEmbeddedMediaPlayer().apply {
            if (!isAudio) {
                videoSurface().set(composeVideoSurface { image ->
                    if (acceptsCurrentMediaEvents.get()) frameUpdates.trySend(image)
                })
            }
            events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                // vlcj event callbacks run on native/event threads. Compose state is only
                // mutated from the player scope, never directly from those callback threads.
                override fun playing(mediaPlayer: MediaPlayer) {
                    acceptsCurrentMediaEvents.set(true)
                    playerScope.launch {
                        playing = true
                        finished = false
                        resumeFinished.set(false)
                        buffering = false
                        // VLC can report the media length before the first playing event. Read
                        // the status here as a fallback so the remaining-time label and seekbar
                        // are initialized even when lengthChanged arrived during opening.
                        val reportedLength = runCatching { mediaPlayer.status().length() }
                            .getOrNull()
                        if (reportedLength != null && reportedLength > 0L) {
                            durationMs = reportedLength
                            initializeVideoResume(mediaPlayer, reportedLength)
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
                    // Windows can replace the default output when USB/Bluetooth earphones are
                    // attached. Re-apply audio state from the Compose scope.
                    playerScope.launch { trackTick++ }
                }

                override fun elementaryStreamAdded(
                    mediaPlayer: MediaPlayer,
                    type: TrackType,
                    id: Int
                ) {
                    playerScope.launch { trackTick++ }
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    if (acceptsCurrentMediaEvents.get()) {
                        playerScope.launch { playing = false }
                    }
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    if (!acceptsCurrentMediaEvents.getAndSet(false)) return
                    playerScope.launch {
                        playing = false
                        finished = true
                        if (!isAudio) VideoResumeStore.clear(mediaId)
                        resumeFinished.set(true)
                        if (!latestRepeatOne.value || latestSleepAtTrackEnd.value) {
                            latestOnPlaybackFinished.value()
                        }
                    }
                }

                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                    if (acceptsCurrentMediaEvents.get()) {
                        playerScope.launch { buffering = newCache < 100f }
                    }
                }

                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                    if (acceptsCurrentMediaEvents.get()) timeUpdates.trySend(newTime)
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    if (acceptsCurrentMediaEvents.get()) {
                        playerScope.launch {
                            durationMs = newLength
                            initializeVideoResume(mediaPlayer, newLength)
                        }
                    }
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    acceptsCurrentMediaEvents.set(false)
                    playerScope.launch {
                        playing = false
                        finished = true
                        buffering = false
                    }
                }
            })
        }
    }.also(DesktopVlcPlaybackLifecycle::register)

    // The slider and arrow keys update this dispatcher instead of enqueuing one native
    // command per input event. Intermediate values are coalesced before reaching VLCJ.
    val audioStateDispatcher = remember(player) {
        LatestAudioStateDispatcher(
            enqueue = { task -> player.submit { task() } },
            apply = { requestedVolume, requestedMuted ->
                player.audio().setVolume(requestedVolume)
                player.audio().isMute = requestedMuted
            }
        )
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
    LaunchedEffect(player, mrl, mediaId) {
        // Activate this media only after the outgoing player's queued native commands have
        // drained, stop() has completed, and its native resources have been released.
        acceptsCurrentMediaEvents.set(false)
        playing = false
        finished = false
        buffering = true
        timeMs = 0L
        durationMs = 0L
        trackTick++
        try {
            DesktopVlcPlaybackLifecycle.activate(player) {
                player.submitAndAwait {
                    if (!playerDisposed.get()) {
                        // This is a fresh VLC player with no previous media to stop. The
                        // lifecycle coordinator has already stopped/released the outgoing
                        // player, so calling stop() here can leave a new player stuck before
                        // its first opening/playing event.
                        acceptsCurrentMediaEvents.set(true)
                        runCatching { player.media().play(mrl) }
                            .onFailure {
                                acceptsCurrentMediaEvents.set(false)
                                playerScope.launch {
                                    playing = false
                                    finished = true
                                    buffering = false
                                }
                            }
                    }
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            acceptsCurrentMediaEvents.set(false)
            playerScope.launch {
                playing = false
                finished = true
                buffering = false
            }
            SafeLog.w("DesktopMediaPlayer", "Could not activate VLC media source", error)
        }
    }

    DisposableEffect(player, mediaId, isAudio) {
        onDispose {
            // Save the most recent known video position before navigating to another file or
            // leaving the viewer. Periodic time updates already persist progress while playing.
            if (
                !isAudio &&
                resumeInitializationComplete.get() &&
                pendingResumeTargetMs.get() == 0L &&
                !resumeFinished.get() &&
                !latestFinished.value
            ) {
                VideoResumeStore.savePosition(
                    mediaId = mediaId,
                    positionMs = latestTimeMs.value,
                    durationMs = latestDurationMs.value,
                    force = true
                )
            }
            // Close the event gate immediately. The shared lifecycle coordinator then waits for
            // this player's queued work and synchronously stops/releases it before another
            // media player's start is allowed to proceed.
            playerDisposed.set(true)
            acceptsCurrentMediaEvents.set(false)
            DesktopVlcPlaybackLifecycle.dispose(player)
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
                audioStateDispatcher.submit(
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
        audioStateDispatcher.submit(volume, muted)
    }
    val toggleControlsVisibility: () -> Unit = {
        val showControls = !(controlsVisible || seekFeedbackVisible)
        val updated = controlPreferences.copy(showPlayerControls = showControls)
        controlPreferences = updated
        controlsPinned = false
        seekFeedbackVisible = false
        PlayerControlPreferencesStore.save(updated)
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
        // Update shared in-memory volume immediately so a fast media switch uses the
        // latest value; disk persistence is debounced on an IO thread.
        RememberedPlayerVolume.update(
            nextVolume,
            persist = controlPreferences.rememberVolume
        )
        audioStateDispatcher.submit(nextVolume, muted)
    }
    fun adjustPlayerVolume(delta: Int) {
        setPlayerVolume(volume + delta, unmute = delta > 0)
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

    var languagesApplied by remember(mrl) { mutableStateOf(false) }
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
                            ?.let { runCatching { player.subpictures().setTrack(it.id) } }
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
            .focusRequester(playerFocusRequester)
            .onPreviewKeyEvent { event ->
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
                    key == Key.S && shift && !ctrl && !alt -> {
                        saveVideoSnapshot()
                        interactionTick++
                        true
                    }
                    key == Key.MediaStop || (key == Key.S && !shift && !ctrl && !alt) -> {
                        acceptsCurrentMediaEvents.set(false)
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
                        controlPreferences = updated
                        controlsPinned = updated.showPlayerControls
                        PlayerControlPreferencesStore.save(updated)
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
                        markManualVideoSeek()
                        player.submit { runCatching { player.controls().skipTime(-SHORT_KEY_SEEK_STEP_MS) } }
                        revealControlsForSeek()
                        true
                    }
                    key == Key.N && !ctrl && !alt && !shift -> {
                        markManualVideoSeek()
                        player.submit { runCatching { player.controls().skipTime(SHORT_KEY_SEEK_STEP_MS) } }
                        revealControlsForSeek()
                        true
                    }
                    key == Key.DirectionLeft && !ctrl && !alt && !shift -> {
                        markManualVideoSeek()
                        player.submit { runCatching { player.controls().skipTime(-KEY_SEEK_STEP_MS) } }
                        revealControlsForSeek()
                        true
                    }
                    key == Key.DirectionRight && !ctrl && !alt && !shift -> {
                        markManualVideoSeek()
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
                            markManualVideoSeek()
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
                        val updated = controlPreferences.copy(showPlayerControls = showControls)
                        controlPreferences = updated
                        controlsPinned = false
                        PlayerControlPreferencesStore.save(updated)
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
                if (!isAudio) {
                    detectTapGestures(
                        onTap = { toggleControlsVisibility() }
                    )
                }
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
            Icon(
                imageVector = Icons.Filled.Audiotrack,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            frame?.let { current ->
                Image(
                    bitmap = current,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
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
                    player.submit { runCatching { player.subpictures().setTrack(trackId) } }
                    onPreferredSubtitleLanguage(track?.language.orEmpty())
                    trackTick++
                },
                onInteraction = { interactionTick++ },
                onPlayPause = togglePlayback,
                onSeekTo = { millis ->
                    markManualVideoSeek()
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
                    val updated = controlPreferences.copy(showPlayerControls = false)
                    controlPreferences = updated
                    controlsPinned = false
                    PlayerControlPreferencesStore.save(updated)
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
                    val withCurrentVolume = updated.copy(volume = volume)
                    controlPreferences = withCurrentVolume
                    PlayerControlPreferencesStore.save(withCurrentVolume)
                    if (withCurrentVolume.rememberVolume) {
                        RememberedPlayerVolume.update(volume, persist = true)
                    }
                    interactionTick++
                }
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
    onControlPreferencesChange: (PlayerControlPreferences) -> Unit
) {
    var showRateMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
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
            brightnessSliderActive
        ) {
            onControlsKeepVisible(
                showSliderSettings || showRateMenu || showAudioMenu || showSubtitleMenu ||
                    showQueueMenu || showSleepMenu || showCustomSleepDialog ||
                    volumeSliderActive || brightnessSliderActive
            )
        }
        DisposableEffect(Unit) {
            onDispose { onControlsKeepVisible(false) }
        }

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
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
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
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
                                    showQueueMenu = false
                                    onSelectQueueItem(item.id)
                                    onInteraction()
                                }
                            )
                        }
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
                        listOf(15L, 30L, 45L, 60L).forEach { minutes ->
                            DropdownMenuItem(
                                text = { Text("$minutes minutes") },
                                onClick = {
                                    showSleepMenu = false
                                    onSetSleepTimer(minutes * 60_000L)
                                    onInteraction()
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Custom duration…") },
                            onClick = {
                                showSleepMenu = false
                                showCustomSleepDialog = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("End of current track") },
                            onClick = {
                                showSleepMenu = false
                                onSleepAtTrackEnd()
                                onInteraction()
                            }
                        )
                        if (sleepTimerLabel != "Sleep timer") {
                            DropdownMenuItem(
                                text = { Text("Turn off sleep timer") },
                                onClick = {
                                    showSleepMenu = false
                                    onClearSleepTimer()
                                    onInteraction()
                                }
                            )
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
                    TooltipIconButton(
                        tooltip = stringResource(Res.string.player_scaling),
                        onClick = { onInteraction(); onScaling(scaling.next()) },
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