package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ZoomOutMap
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularWavyProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import uk.co.caprica.vlcj.media.TrackType
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DesktopMediaPlayer(
    mrl: String,
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
    onControlsVisibilityChange: (Boolean) -> Unit = {}
) {
    val factory = VlcPlayback.factory ?: return
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var playing by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var timeMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var muted by remember { mutableStateOf(false) }
    var volume by remember(mrl) { mutableIntStateOf(100) }
    var brightness by remember(mrl) { mutableIntStateOf(100) }
    var rate by remember(mrl) { mutableStateOf(1.0f) }
    var repeatOne by remember { mutableStateOf(false) }
    var trackTick by remember { mutableIntStateOf(0) }
    var scaling by remember { mutableStateOf(VideoScaling.FIT) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }
    var controlsPinned by remember { mutableStateOf(false) }
    var controlPreferences by remember { mutableStateOf(PlayerControlPreferencesStore.load()) }
    // MediaPlayerEventAdapter outlives individual recompositions; use updated state so
    // a play/resume event cannot reapply the initial 100% / unmuted values.
    val latestVolume = rememberUpdatedState(volume)
    val latestMuted = rememberUpdatedState(muted)

    val player = remember(mrl, isAudio) {
        factory.mediaPlayers().newEmbeddedMediaPlayer().apply {
            if (!isAudio) videoSurface().set(composeVideoSurface { frame = it })
            events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    playing = true
                    finished = false
                    buffering = false
                    // Do not call LibVLC from this native callback thread. The event thread
                    // must return immediately; volume restoration is queued below via submit().
                    trackTick++
                }

                override fun audioDeviceChanged(mediaPlayer: MediaPlayer, audioDevice: String) {
                    // Windows can replace the default output when USB/Bluetooth earphones are
                    // attached. Only publish a state tick here; never call LibVLC in this callback.
                    trackTick++
                }

                override fun elementaryStreamAdded(
                    mediaPlayer: MediaPlayer,
                    type: TrackType,
                    id: Int
                ) {
                    trackTick++
                }

                override fun paused(mediaPlayer: MediaPlayer) {
                    playing = false
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    playing = false
                    finished = true
                }

                override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
                    buffering = newCache < 100f
                }

                override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
                    timeMs = newTime
                }

                override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
                    durationMs = newLength
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    playing = false
                    finished = true
                    buffering = false
                }
            })
        }
    }

    var brightnessAvailable by remember(mrl, isAudio) { mutableStateOf(false) }
    var brightnessMonitor by remember(mrl) { mutableStateOf("") }
    var brightnessDragging by remember(mrl) { mutableStateOf(false) }
    var brightnessLastInputAt by remember(mrl) { mutableLongStateOf(0L) }
    val brightnessUpdates = remember(mrl) { Channel<Float>(Channel.CONFLATED) }

    // Mirror Monitorian: read physical monitor brightness, but never let a polling
    // result overwrite an in-progress drag or a value just sent to the monitor.
    LaunchedEffect(mrl, isAudio) {
        if (isAudio) {
            brightnessAvailable = false
            return@LaunchedEffect
        }
        while (isActive) {
            val shouldRead = !brightnessDragging &&
                System.currentTimeMillis() - brightnessLastInputAt >= BRIGHTNESS_READ_GUARD_MS
            if (shouldRead) {
                val reading = withContext(Dispatchers.IO) {
                    WindowsMonitorBrightness.read()
                }
                val stillIdle = !brightnessDragging &&
                    System.currentTimeMillis() - brightnessLastInputAt >= BRIGHTNESS_READ_GUARD_MS
                if (stillIdle) {
                    brightnessAvailable = reading != null
                    if (reading != null) {
                        brightness = reading.percent.roundToInt().coerceIn(0, MAX_BRIGHTNESS)
                        brightnessMonitor = reading.monitorDescription
                    }
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
    DisposableEffect(player, mrl) {
        player.media().play(mrl)
        onDispose {
            // Release VLC off the Compose UI thread so switching files stays responsive.
            val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            teardownScope.launch {
                try {
                    runCatching { player.controls().stop() }
                    runCatching { player.release() }
                } finally {
                    teardownScope.cancel()
                }
            }
            fullscreenController?.let { controller ->
                if (controller.isFullscreen()) controller.toggle()
            }
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
    LaunchedEffect(mrl) { playerFocusRequester.requestFocus() }

    val togglePlayback: () -> Unit = {
        when {
            finished -> {
                finished = false
                player.controls().play()
            }
            playing -> player.controls().pause()
            else -> player.controls().play()
        }
    }
    val toggleMute: () -> Unit = {
        muted = !muted
        player.audio().isMute = muted
    }
    fun setPlayerVolume(value: Int, unmute: Boolean = true) {
        val nextVolume = value.coerceIn(0, MAX_VOLUME)
        volume = nextVolume
        player.audio().setVolume(nextVolume)
        if (nextVolume == 0) {
            muted = true
            player.audio().isMute = true
        } else if (unmute) {
            muted = false
            player.audio().isMute = false
        }
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
        player.snapshots().setSnapshotDirectory(snapshotDirectory.absolutePath)
        player.snapshots().save()
    }
    fun setPlaybackRate(value: Float) {
        rate = normalizePlaybackRate(value)
        player.controls().setRate(rate)
        interactionTick++
    }
    fun cycleAudioTrack() {
        val tracks = player.audioTracks(trackWord)
        if (tracks.isEmpty()) return
        val selected = tracks.indexOfFirst { it.selected }
        val next = tracks[(selected + 1).coerceAtLeast(0) % tracks.size]
        player.audio().setTrack(next.id)
        onPreferredAudioLanguage(next.language)
        trackTick++
    }
    fun cycleSubtitleTrack(reverse: Boolean = false) {
        val tracks = player.textTracks(trackWord)
        if (tracks.isEmpty()) return
        val selected = tracks.indexOfFirst { it.selected }
        val nextIndex = if (reverse) {
            if (selected <= 0) tracks.lastIndex else selected - 1
        } else {
            (selected + 1) % tracks.size
        }
        val next = tracks[nextIndex]
        player.subpictures().setTrack(next.id)
        onPreferredSubtitleLanguage(next.language)
        trackTick++
    }
    fun toggleSubtitles() {
        val tracks = player.textTracks(trackWord)
        if (tracks.isEmpty()) return
        val selected = tracks.firstOrNull { it.selected }
        if (selected != null) {
            player.subpictures().setTrack(VLC_TRACK_DISABLED)
        } else {
            val next = tracks.firstOrNull { it.language == preferredSubtitleLanguage }
                ?: tracks.first()
            player.subpictures().setTrack(next.id)
            onPreferredSubtitleLanguage(next.language)
        }
        trackTick++
    }

    var languagesApplied by remember(mrl) { mutableStateOf(false) }
    LaunchedEffect(mrl, trackTick, preferredAudioLanguage, preferredSubtitleLanguage) {
        if (languagesApplied || trackTick == 0) return@LaunchedEffect
        val audio = player.audioTracks(trackWord)
        val text = player.textTracks(trackWord)
        if (audio.isEmpty() && text.isEmpty()) return@LaunchedEffect
        languagesApplied = true
        if (preferredAudioLanguage.isNotEmpty()) {
            audio.firstOrNull { it.language == preferredAudioLanguage }
                ?.let { player.audio().setTrack(it.id) }
        }
        if (preferredSubtitleLanguage.isNotEmpty()) {
            text.firstOrNull { it.language == preferredSubtitleLanguage }
                ?.let { player.subpictures().setTrack(it.id) }
        }
        trackTick++
    }

    LaunchedEffect(
        mrl,
        playing,
        interactionTick,
        controlsPinned,
        controlPreferences.autoHidePlayerControls,
        controlPreferences.hideDelayMillis
    ) {
        controlsVisible = true
        onControlsVisibilityChange(true)
        if (!controlPreferences.autoHidePlayerControls || controlsPinned) {
            return@LaunchedEffect
        }
        // Hide the complete player chrome after inactivity even while paused.
        // Previously the !playing early return left every button permanently visible.
        delay(controlPreferences.hideDelayMillis.milliseconds)
        if (!controlsPinned) {
            controlsVisible = false
            onControlsVisibilityChange(false)
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
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val key = event.key
                val ctrl = event.isCtrlPressed
                val alt = event.isAltPressed
                val shift = event.isShiftPressed

                when {
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
                        player.controls().play()
                        interactionTick++
                        true
                    }
                    key == Key.MediaPause -> {
                        player.controls().pause()
                        interactionTick++
                        true
                    }
                    key == Key.S && shift && !ctrl && !alt -> {
                        saveVideoSnapshot()
                        interactionTick++
                        true
                    }
                    key == Key.MediaStop || (key == Key.S && !shift && !ctrl && !alt) -> {
                        player.controls().stop()
                        playing = false
                        finished = true
                        timeMs = 0L
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
                    // W/E are reserved for media navigation; Left/Right remain seek keys.
                    key == Key.W && !ctrl && !alt && !shift -> {
                        if (hasPrevious) onPrevious()
                        interactionTick++
                        true
                    }
                    key == Key.E && !ctrl && !alt && !shift -> {
                        if (hasNext) onNext()
                        interactionTick++
                        true
                    }
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
                    key == Key.DirectionLeft && !ctrl && !alt && !shift -> {
                        player.controls().skipTime(-KEY_SEEK_STEP_MS)
                        interactionTick++
                        true
                    }
                    key == Key.DirectionRight && !ctrl && !alt && !shift -> {
                        player.controls().skipTime(KEY_SEEK_STEP_MS)
                        interactionTick++
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
                            player.controls().skipTime(direction * jumpMs)
                            interactionTick++
                            true
                        }
                    }
                    key == Key.L && !ctrl && !alt && !shift -> {
                        repeatOne = !repeatOne
                        player.controls().repeat = repeatOne
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
                        player.audio().setDelay(player.audio().delay() - SYNC_STEP_US)
                        interactionTick++
                        true
                    }
                    key == Key.K && !ctrl && !alt && !shift -> {
                        player.audio().setDelay(player.audio().delay() + SYNC_STEP_US)
                        interactionTick++
                        true
                    }
                    key == Key.G && !ctrl && !alt && !shift -> {
                        player.subpictures().setDelay(player.subpictures().delay() - SYNC_STEP_US)
                        interactionTick++
                        true
                    }
                    key == Key.H && !ctrl && !alt && !shift -> {
                        player.subpictures().setDelay(player.subpictures().delay() + SYNC_STEP_US)
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
            .pointerHoverIcon(if (controlsVisible) PointerIcon.Default else blankCursor)
            .pointerInput(player, playing, finished, isAudio) {
                if (!isAudio) {
                    detectTapGestures(
                        onTap = {
                            togglePlayback()
                            interactionTick++
                        }
                    )
                }
            }
            .pointerInput(Unit) {
                var lastPosition = Offset.Unspecified
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
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
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
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
                trackTick = trackTick,
                scaling = scaling,
                onPrevious = onPrevious,
                onNext = onNext,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                onSelectAudioTrack = { track ->
                    player.audio().setTrack(track.id)
                    onPreferredAudioLanguage(track.language)
                    trackTick++
                },
                onSelectTextTrack = { track ->
                    player.subpictures().setTrack(track?.id ?: VLC_TRACK_DISABLED)
                    onPreferredSubtitleLanguage(track?.language.orEmpty())
                    trackTick++
                },
                onInteraction = { interactionTick++ },
                onPlayPause = togglePlayback,
                onSeekTo = { millis -> player.controls().setTime(millis) },
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
                onRate = ::setPlaybackRate,
                onToggleRepeat = {
                    repeatOne = !repeatOne
                    player.controls().repeat = repeatOne
                },
                onScaling = { scaling = it },
                controlPreferences = controlPreferences,
                onControlPreferencesChange = { updated ->
                    controlPreferences = updated
                    PlayerControlPreferencesStore.save(updated)
                    interactionTick++
                }
            )
        }
    }
}

@Composable
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
    onRate: (Float) -> Unit,
    onToggleRepeat: () -> Unit,
    onSelectAudioTrack: (VlcTrack) -> Unit,
    onSelectTextTrack: (VlcTrack?) -> Unit,
    onScaling: (VideoScaling) -> Unit,
    controlPreferences: PlayerControlPreferences,
    onControlPreferencesChange: (PlayerControlPreferences) -> Unit
) {
    var showRateMenu by remember { mutableStateOf(false) }
    var showAudioMenu by remember { mutableStateOf(false) }
    var showSubtitleMenu by remember { mutableStateOf(false) }
    var scrubTarget by remember { mutableStateOf<Float?>(null) }
    val fullscreen = LocalFullscreenController.current
    val trackWord = stringResource(Res.string.player_track)
    val audioTracks = remember(player, trackTick) { player.audioTracks(trackWord) }
    val textTracks = remember(player, trackTick) { player.textTracks(trackWord) }
    val subtitlesOn = textTracks.any { it.selected }

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
            volumeSliderActive,
            brightnessSliderActive
        ) {
            onControlsKeepVisible(
                showSliderSettings || showRateMenu || showAudioMenu || showSubtitleMenu ||
                    volumeSliderActive || brightnessSliderActive
            )
        }
        DisposableEffect(Unit) {
            onDispose { onControlsKeepVisible(false) }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
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
                IconButton(
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

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Row(
                modifier = Modifier.align(Alignment.Center),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
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
                IconButton(
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
                IconButton(
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
                IconButton(
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
                IconButton(
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

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
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
                IconButton(
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
                if (!isAudio) {
                    Box {
                        IconButton(
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
                    IconButton(
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
                    IconButton(
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
                fullscreen?.let { controller ->
                    IconButton(
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

private const val MOVE_THRESHOLD_PX = 4f
private const val SKIP_MS = 10_000L
private const val KEY_SEEK_STEP_MS = 10_000L
private const val VOLUME_STEP = 5
private const val MAX_VOLUME = 200
private const val MAX_BRIGHTNESS = 100
private const val BRIGHTNESS_POLL_INTERVAL_MS = 2_500L
private const val BRIGHTNESS_READ_GUARD_MS = 1_800L
private const val AUDIO_OUTPUT_REAPPLY_INTERVAL_MS = 250L
private const val AUDIO_OUTPUT_REAPPLY_ATTEMPTS = 6
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