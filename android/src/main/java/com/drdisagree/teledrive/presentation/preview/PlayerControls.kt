package com.drdisagree.teledrive.presentation.preview

import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalContext
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import android.provider.Settings
import android.view.WindowManager
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.player_audio_track
import com.drdisagree.teledrive.resources.player_fewer_controls
import com.drdisagree.teledrive.resources.player_hide_subtitles
import com.drdisagree.teledrive.resources.player_lock_controls
import com.drdisagree.teledrive.resources.player_more_controls
import com.drdisagree.teledrive.resources.player_mute
import com.drdisagree.teledrive.resources.player_pause
import com.drdisagree.teledrive.resources.player_picture_in_picture
import com.drdisagree.teledrive.resources.player_play
import com.drdisagree.teledrive.resources.player_repeat_off
import com.drdisagree.teledrive.resources.player_repeat_one
import com.drdisagree.teledrive.resources.player_replay
import com.drdisagree.teledrive.resources.player_show_subtitles
import com.drdisagree.teledrive.resources.player_subtitles
import com.drdisagree.teledrive.resources.player_subtitles_off
import com.drdisagree.teledrive.resources.player_track
import com.drdisagree.teledrive.resources.player_unmute
import com.drdisagree.teledrive.resources.preview_back_10_seconds
import com.drdisagree.teledrive.resources.preview_forward_10_seconds
import com.drdisagree.teledrive.resources.preview_playback_speed
import com.drdisagree.teledrive.presentation.common.Formatters
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/**
 * Not the Media3 controller: it ignores window insets, leaves no side padding and anchors its popup
 * to the top.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerControls(
    player: Player,
    session: PlayerSession,
    onPreviousFile: () -> Unit = {},
    onNextFile: () -> Unit = {},
    hasPreviousFile: Boolean = false,
    hasNextFile: Boolean = false,
    queueItems: List<String> = emptyList(),
    currentQueueIndex: Int = 0,
    onSelectQueueItem: (Int) -> Unit = {},
    visible: Boolean,
    audioOnly: Boolean,
    resizeMode: PlayerResizeMode,
    onCycleResizeMode: () -> Unit,
    onInteraction: () -> Unit,
    onLock: () -> Unit = {},
    onEnterPip: (() -> Unit)? = null,
    onPreferredAudioLanguage: (String) -> Unit = {},
    onPreferredSubtitleLanguage: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var playing by remember(player) { mutableStateOf(player.isPlaying) }
    var ended by remember(player) { mutableStateOf(player.playbackState == Player.STATE_ENDED) }
    var playbackState by remember(player) { mutableIntStateOf(player.playbackState) }
    var playWhenReady by remember(player) { mutableStateOf(player.playWhenReady) }
    var duration by remember(player) { mutableLongStateOf(player.duration.coerceAtLeast(0)) }
    var position by remember(player) { mutableLongStateOf(player.currentPosition.coerceAtLeast(0)) }
    var scrubTarget by remember(player) { mutableStateOf<Float?>(null) }
    var pendingSeek by remember(player) { mutableStateOf<Long?>(null) }
    var speed by session::speed
    var seekStepSeconds by session::seekStepSeconds
    var shuffle by session::shuffle
    var repeatAll by session::repeatAll
    var sleepTimerDeadlineMillis by session::sleepTimerDeadlineMillis
    var volume by remember(player) { mutableFloatStateOf(player.volume) }
    var muted by remember(player) { mutableStateOf(player.volume == 0f) }
    var repeatOne by remember(player) {
        mutableStateOf(player.repeatMode == Player.REPEAT_MODE_ONE)
    }
    var tracks by remember(player) { mutableStateOf(player.currentTracks) }
    var subtitlesOn by remember(player) { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    var showSpeeds by remember { mutableStateOf(false) }
    var showSeekSteps by remember { mutableStateOf(false) }
    var showResizeHint by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var showSleepTimer by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var showAudioTracks by remember { mutableStateOf(false) }
    var showTextTracks by remember { mutableStateOf(false) }
    var showSubtitleSettings by remember { mutableStateOf(false) }
    var subtitleAppearance by session::subtitleAppearance

    val activity = LocalActivity.current
    val context = LocalContext.current
    var brightness by remember(activity) {
        mutableFloatStateOf(
            activity?.window?.attributes?.screenBrightness?.takeIf { it >= 0f }
                ?: runCatching {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
                }.getOrDefault(0.5f)
        )
    }
    var rotation by remember(activity) {
        mutableStateOf(PlayerRotation.of(activity?.requestedOrientation))
    }
    LaunchedEffect(visible) {
        if (visible) rotation = PlayerRotation.of(activity?.requestedOrientation)
    }

    /**
     * A seek on a stream can take a moment, so the button reports waiting for data instead of
     * showing play.
     */
    val waitingForData = playbackState == Player.STATE_BUFFERING ||
            (playWhenReady && !playing && !ended && playbackState != Player.STATE_IDLE)

    val trackWord = stringResource(Res.string.player_track)
    val textTracks = tracks.playerTracks(C.TRACK_TYPE_TEXT, trackWord)
    val audioTracks = tracks.playerTracks(C.TRACK_TYPE_AUDIO, trackWord)

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
            }

            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
                ended = state == Player.STATE_ENDED
                duration = player.duration.coerceAtLeast(0)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                    position = newPosition.positionMs.coerceAtLeast(0)
                    pendingSeek = null
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReadyNow: Boolean, reason: Int) {
                playWhenReady = playWhenReadyNow
            }

            override fun onTracksChanged(current: Tracks) {
                tracks = current
                subtitlesOn = current.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
            }

            override fun onVolumeChanged(volumeNow: Float) {
                volume = volumeNow.coerceIn(0f, 1f)
                muted = volumeNow <= 0.001f
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(player, visible, playing) {
        while (visible) {
            val current = player.currentPosition.coerceAtLeast(0)
            val target = pendingSeek
            if (target == null || abs(current - target) <= SEEK_TOLERANCE_MS) {
                pendingSeek = null
                position = current
            }
            duration = player.duration.coerceAtLeast(0)
            delay(PROGRESS_INTERVAL_MS.milliseconds)
        }
    }

    LaunchedEffect(visible) {
        if (!visible) expanded = false
    }
    LaunchedEffect(sleepTimerDeadlineMillis, player) {
        val deadline = sleepTimerDeadlineMillis
        if (deadline > 0L) {
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0L)
            if (remaining > 0L) delay(remaining)
            if (session.sleepTimerDeadlineMillis == deadline) {
                player.pause()
                session.sleepTimerDeadlineMillis = 0L
            }
        }
    }
    LaunchedEffect(showResizeHint) {
        if (showResizeHint) {
            delay(1_100L)
            showResizeHint = false
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier.fillMaxSize()
    ) {
      CompositionLocalProvider(LocalOverVideo provides !audioOnly) {
        Box(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(
                WindowInsets.systemBars.only(WindowInsetsSides.Horizontal)
            )
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = TOP_BAR_HEIGHT, start = 12.dp, end = 12.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.Top
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompositionLocalProvider(
                            LocalLayoutDirection provides LayoutDirection.Ltr
                        ) {
                            ExtraControl(expanded || audioOnly) {
                                ControlButton(
                                    icon = if (muted) {
                                        Icons.AutoMirrored.Filled.VolumeOff
                                    } else {
                                        Icons.AutoMirrored.Filled.VolumeUp
                                    },
                                    description = if (muted) {
                                        stringResource(Res.string.player_unmute)
                                    } else {
                                        stringResource(Res.string.player_mute)
                                    },
                                    active = muted,
                                    onClick = {
                                        onInteraction()
                                        muted = !muted
                                        player.volume = if (muted) 0f else 1f
                                    }
                                )
                            }
                            if (onEnterPip != null) {
                                ControlButton(
                                    icon = Icons.Filled.PictureInPictureAlt,
                                    description = stringResource(
                                        Res.string.player_picture_in_picture
                                    ),
                                    onClick = {
                                        onInteraction()
                                        onEnterPip()
                                    }
                                )
                            }
                            if (!audioOnly) {
                                ControlButton(
                                    icon = Icons.Filled.Lock,
                                    description = stringResource(Res.string.player_lock_controls),
                                    onClick = {
                                        onInteraction()
                                        onLock()
                                    }
                                )
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = rotation.icon,
                                    description = rotation.description,
                                    active = rotation != PlayerRotation.AUTO,
                                    onClick = {
                                        onInteraction()
                                        rotation = rotation.next()
                                        activity?.requestedOrientation = rotation.orientation
                                    }
                                )
                            }
                            if (!audioOnly) {
                                ExtraControl(expanded) {
                                    ControlButton(
                                        icon = resizeMode.icon,
                                        description = resizeMode.description,
                                        onClick = {
                                            onInteraction()
                                            onCycleResizeMode()
                                            showResizeHint = true
                                        }
                                    )
                                }
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = Icons.Filled.Forward10,
                                    description = "Double-tap seek interval: ${seekStepSeconds}s",
                                    active = seekStepSeconds == 5,
                                    onClick = {
                                        onInteraction()
                                        showSeekSteps = true
                                    }
                                )
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = Icons.Filled.Tune,
                                    description = "Volume and brightness",
                                    onClick = {
                                        onInteraction()
                                        showAdjustments = true
                                    }
                                )
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = Icons.Filled.Shuffle,
                                    description = if (shuffle) "Shuffle on" else "Shuffle off",
                                    active = shuffle,
                                    onClick = {
                                        onInteraction()
                                        shuffle = !shuffle
                                    }
                                )
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = Icons.Filled.Repeat,
                                    description = if (repeatAll) "Repeat all on" else "Repeat all off",
                                    active = repeatAll,
                                    onClick = {
                                        onInteraction()
                                        repeatAll = !repeatAll
                                    }
                                )
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = Icons.Filled.Timer,
                                    description = if (sleepTimerDeadlineMillis > 0L) "Sleep timer active" else "Sleep timer",
                                    active = sleepTimerDeadlineMillis > 0L,
                                    onClick = {
                                        onInteraction()
                                        showSleepTimer = true
                                    }
                                )
                            }
                            ExtraControl(expanded || queueItems.isNotEmpty()) {
                                ControlButton(
                                    icon = Icons.Filled.QueueMusic,
                                    description = "Preview queue",
                                    enabled = queueItems.isNotEmpty(),
                                    onClick = {
                                        onInteraction()
                                        showQueue = true
                                    }
                                )
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = Icons.Filled.SlowMotionVideo,
                                    description = stringResource(Res.string.preview_playback_speed),
                                    active = speed != 1f,
                                    onClick = {
                                        onInteraction()
                                        showSpeeds = true
                                    }
                                )
                            }
                            ExtraControl(expanded) {
                                ControlButton(
                                    icon = if (repeatOne) {
                                        Icons.Filled.RepeatOne
                                    } else {
                                        Icons.Filled.Repeat
                                    },
                                    description = if (repeatOne) stringResource(Res.string.player_repeat_off) else stringResource(
                                        Res.string.player_repeat_one
                                    ),
                                    active = repeatOne,
                                    onClick = {
                                        onInteraction()
                                        repeatOne = !repeatOne
                                        player.repeatMode = if (repeatOne) {
                                            Player.REPEAT_MODE_ONE
                                        } else {
                                            Player.REPEAT_MODE_OFF
                                        }
                                    }
                                )
                            }
                            if (!audioOnly) {
                                ExtraControl(expanded || textTracks.isNotEmpty()) {
                                    ControlButton(
                                        icon = if (subtitlesOn) {
                                            Icons.Filled.Subtitles
                                        } else {
                                            Icons.Filled.SubtitlesOff
                                        },
                                        description = if (subtitlesOn) {
                                            stringResource(Res.string.player_hide_subtitles)
                                        } else {
                                            stringResource(Res.string.player_show_subtitles)
                                        },
                                        active = subtitlesOn,
                                        enabled = true,
                                        onClick = {
                                            onInteraction()
                                            showTextTracks = true
                                        }
                                    )
                                }
                            }
                            ExtraControl(expanded || audioTracks.size > 1) {
                                ControlButton(
                                    icon = Icons.Filled.Audiotrack,
                                    description = stringResource(Res.string.player_audio_track),
                                    enabled = audioTracks.isNotEmpty(),
                                    onClick = {
                                        onInteraction()
                                        showAudioTracks = true
                                    }
                                )
                            }
                        }
                    }
                }
                ControlButton(
                    icon = if (expanded) Icons.Filled.ChevronRight else Icons.Filled.ChevronLeft,
                    description = if (expanded) stringResource(Res.string.player_fewer_controls) else stringResource(
                        Res.string.player_more_controls
                    ),
                    onClick = {
                        onInteraction()
                        expanded = !expanded
                    }
                )
            }

            if (showResizeHint) {
                Surface(
                    modifier = Modifier.align(Alignment.Center).padding(top = 104.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = Color.Black.copy(alpha = 0.78f),
                    contentColor = Color.White
                ) {
                    Text(
                        text = resizeMode.description,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            if (!audioOnly) {
                TransportRow(
                    player = player,
                    playing = playing,
                    ended = ended,
                    waiting = waitingForData,
                    onInteraction = onInteraction,
                    onPreviousFile = onPreviousFile,
                    onNextFile = onNextFile,
                    hasPreviousFile = hasPreviousFile,
                    hasNextFile = hasNextFile,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                MaterialTheme.colorScheme.scrim.copy(alpha = 0f),
                                MaterialTheme.colorScheme.scrim.copy(alpha = SCRIM_ALPHA)
                            )
                        )
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                if (audioOnly) {
                    TransportRow(
                        player = player,
                        playing = playing,
                        ended = ended,
                        waiting = waitingForData,
                        onInteraction = onInteraction,
                        onPreviousFile = onPreviousFile,
                        onNextFile = onNextFile,
                        hasPreviousFile = hasPreviousFile,
                        hasNextFile = hasNextFile,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(bottom = 8.dp)
                    )
                }
                Slider(
                    value = scrubTarget ?: position.toFloat(),
                    onValueChange = { value ->
                        onInteraction()
                        scrubTarget = value
                    },
                    onValueChangeFinished = {
                        scrubTarget?.let { target ->
                            val millis = target.toLong()
                            position = millis
                            pendingSeek = millis
                            player.seekTo(millis)
                        }
                        scrubTarget = null
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    thumb = {
                        Box(
                            modifier = Modifier
                                .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = CircleShape
                                )
                        )
                    }
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = Formatters.duration(scrubTarget?.toLong() ?: position),
                        style = MaterialTheme.typography.labelLarge,
                        color = playerContentColor()
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    if (speed != 1f) {
                        Text(
                            text = speedLabel(speed),
                            style = MaterialTheme.typography.labelLarge,
                            color = playerSecondaryColor()
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Text(
                        text = Formatters.duration(duration),
                        style = MaterialTheme.typography.labelLarge,
                        color = playerSecondaryColor()
                    )
                }
            }
        }
      }
    }

    if (showAdjustments) {
        ModalBottomSheet(onDismissRequest = { showAdjustments = false }) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding()
            ) {
                Text("Playback adjustments", style = MaterialTheme.typography.titleMedium)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Volume", modifier = Modifier.weight(1f))
                    Text("${(volume * 100).roundToInt()}%")
                }
                Slider(
                    value = volume,
                    onValueChange = {
                        volume = it.coerceIn(0f, 1f)
                        player.volume = volume
                        muted = volume <= 0.001f
                        onInteraction()
                    },
                    valueRange = 0f..1f
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Brightness", modifier = Modifier.weight(1f))
                    Text("${(brightness * 100).roundToInt()}%")
                }
                Slider(
                    value = brightness.coerceIn(0.01f, 1f),
                    onValueChange = { value ->
                        brightness = value.coerceIn(0.01f, 1f)
                        activity?.window?.let { window ->
                            window.attributes = window.attributes.apply {
                                screenBrightness = brightness
                            }
                        }
                        onInteraction()
                    },
                    valueRange = 0.01f..1f
                )
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showSleepTimer) {
        ModalBottomSheet(onDismissRequest = { showSleepTimer = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(bottom = 12.dp)
            ) {
                Text(
                    "Sleep timer",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                listOf(15, 30, 45, 60, 90, 120).forEach { minutes ->
                    TextButton(
                        onClick = {
                            sleepTimerDeadlineMillis = System.currentTimeMillis() + minutes * 60_000L
                            showSleepTimer = false
                            onInteraction()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("$minutes minutes")
                    }
                }
                if (sleepTimerDeadlineMillis > 0L) {
                    TextButton(
                        onClick = {
                            sleepTimerDeadlineMillis = 0L
                            showSleepTimer = false
                            onInteraction()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Turn off timer")
                    }
                }
            }
        }
    }

    if (showQueue) {
        ModalBottomSheet(onDismissRequest = { showQueue = false }) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)
                    .navigationBarsPadding()
            ) {
                Text(
                    "Preview queue",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                )
                LazyColumn {
                    itemsIndexed(queueItems) { index, name ->
                        TextButton(
                            onClick = {
                                showQueue = false
                                onSelectQueueItem(index)
                                onInteraction()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (index == currentQueueIndex) "▶  $name" else name,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = if (index == currentQueueIndex) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSpeeds) {
        ModalBottomSheet(onDismissRequest = { showSpeeds = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = stringResource(Res.string.preview_playback_speed),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                )
                SPEEDS.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = speed == option,
                                role = Role.RadioButton,
                                onClick = {
                                    speed = option
                                    player.setPlaybackSpeed(option)
                                    onInteraction()
                                }
                            )
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = speed == option, onClick = null)
                        Text(
                            text = speedLabel(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }
    }

    if (showSeekSteps) {
        ModalBottomSheet(onDismissRequest = { showSeekSteps = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                Text(
                    "Double-tap seek interval",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp)
                )
                listOf(5, 10).forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = seekStepSeconds == option,
                                role = Role.RadioButton,
                                onClick = {
                                    seekStepSeconds = option
                                    showSeekSteps = false
                                    onInteraction()
                                }
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = seekStepSeconds == option, onClick = null)
                        Text(
                            text = "$option seconds",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                Spacer(Modifier.navigationBarsPadding().height(8.dp))
            }
        }
    }

    if (showAudioTracks) {
        TrackPickerSheet(
            title = stringResource(Res.string.player_audio_track),
            tracks = audioTracks,
            offLabel = null,
            offSelected = false,
            onSelectOff = {},
            onSelect = { track ->
                onInteraction()
                showAudioTracks = false
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                    .setOverrideForType(
                        TrackSelectionOverride(track.group.mediaTrackGroup, track.index)
                    )
                    .setPreferredAudioLanguage(track.language.ifEmpty { null })
                    .build()
                onPreferredAudioLanguage(track.language)
            },
            onDismiss = { showAudioTracks = false }
        )
    }

    if (showTextTracks) {
        TrackPickerSheet(
            title = stringResource(Res.string.player_subtitles),
            tracks = textTracks,
            offLabel = stringResource(Res.string.player_subtitles_off),
            offSelected = !subtitlesOn,
            onSelectOff = {
                onInteraction()
                showTextTracks = false
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .setPreferredTextLanguage(null)
                    .build()
                subtitlesOn = false
                onPreferredSubtitleLanguage("")
            },
            onSelect = { track ->
                onInteraction()
                showTextTracks = false
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setOverrideForType(
                        TrackSelectionOverride(track.group.mediaTrackGroup, track.index)
                    )
                    .setPreferredTextLanguage(track.language.ifEmpty { null })
                    .build()
                subtitlesOn = true
                onPreferredSubtitleLanguage(track.language)
            },
            onDismiss = { showTextTracks = false },
            customizeLabel = "Subtitle appearance and position",
            onCustomize = {
                showTextTracks = false
                showSubtitleSettings = true
            }
        )
    }

    if (showSubtitleSettings) {
        SubtitleSettingsSheet(
            appearance = subtitleAppearance,
            onAppearanceChange = {
                subtitleAppearance = it
                onInteraction()
            },
            onDismiss = { showSubtitleSettings = false }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TransportRow(
    player: Player,
    playing: Boolean,
    ended: Boolean,
    waiting: Boolean,
    onInteraction: () -> Unit,
    onPreviousFile: () -> Unit,
    onNextFile: () -> Unit,
    hasPreviousFile: Boolean,
    hasNextFile: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            enabled = hasPreviousFile,
            onClick = { onInteraction(); onPreviousFile() },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = playerSurfaceColor().copy(alpha = TRANSPORT_ALPHA),
                contentColor = playerContentColor()
            )
        ) {
            Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous file")
        }
        IconButton(
            onClick = {
                onInteraction()
                player.seekTo((player.currentPosition - SEEK_STEP_MS).coerceAtLeast(0))
            },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = playerSurfaceColor().copy(alpha = TRANSPORT_ALPHA),
                contentColor = playerContentColor()
            )
        ) {
            Icon(
                Icons.Filled.Replay10,
                contentDescription = stringResource(Res.string.preview_back_10_seconds)
            )
        }
        FilledIconButton(
            onClick = {
                onInteraction()
                when {
                    ended -> {
                        player.seekTo(0)
                        player.play()
                    }

                    player.isPlaying -> player.pause()
                    else -> player.play()
                }
            },
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = playerSurfaceColor().copy(alpha = TRANSPORT_ALPHA),
                contentColor = playerContentColor()
            ),
            modifier = Modifier.size(PLAY_BUTTON_SIZE)
        ) {
            if (waiting) {
                LoadingIndicator(
                    color = LocalContentColor.current,
                    modifier = Modifier.size(PLAY_ICON_SIZE)
                )
            } else {
                Icon(
                    imageVector = when {
                        ended -> Icons.Filled.Replay
                        playing -> Icons.Filled.Pause
                        else -> Icons.Filled.PlayArrow
                    },
                    contentDescription = when {
                        ended -> stringResource(Res.string.player_replay)
                        playing -> stringResource(Res.string.player_pause)
                        else -> stringResource(Res.string.player_play)
                    },
                    modifier = Modifier.size(PLAY_ICON_SIZE)
                )
            }
        }
        IconButton(
            onClick = {
                onInteraction()
                val limit = player.duration.coerceAtLeast(0)
                player.seekTo((player.currentPosition + SEEK_STEP_MS).coerceAtMost(limit))
            },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = playerSurfaceColor().copy(alpha = TRANSPORT_ALPHA),
                contentColor = playerContentColor()
            )
        ) {
            Icon(
                Icons.Filled.Forward10,
                contentDescription = stringResource(Res.string.preview_forward_10_seconds)
            )
        }
        IconButton(
            enabled = hasNextFile,
            onClick = { onInteraction(); onNextFile() },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = playerSurfaceColor().copy(alpha = TRANSPORT_ALPHA),
                contentColor = playerContentColor()
            )
        ) {
            Icon(Icons.Filled.SkipNext, contentDescription = "Next file")
        }
    }
}

@Composable
private fun ExtraControl(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
        exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()
    ) {
        content()
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = if (active) {
                MaterialTheme.colorScheme.primary.copy(alpha = SURFACE_ALPHA)
            } else {
                playerSurfaceColor().copy(alpha = SURFACE_ALPHA)
            },
            contentColor = playerContentColor()
        )
    ) {
        Icon(imageVector = icon, contentDescription = description)
    }
}

private val LocalOverVideo = compositionLocalOf { true }

@Composable
private fun playerSurfaceColor(): Color =
    if (LocalOverVideo.current) Color.Black else MaterialTheme.colorScheme.surface

@Composable
private fun playerContentColor(): Color =
    if (LocalOverVideo.current) Color.White else MaterialTheme.colorScheme.onSurface

@Composable
private fun playerSecondaryColor(): Color =
    if (LocalOverVideo.current) {
        Color.White.copy(alpha = SECONDARY_ALPHA)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

private fun speedLabel(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}x" else "${speed}x"

private val SPEEDS = listOf(
    0.5f, 0.6f, 0.7f, 0.8f, 0.9f,
    1f, 1.15f, 1.2f, 1.25f, 1.3f, 1.4f, 1.5f, 1.75f,
    2f, 2.5f, 3f, 4f
)
private const val SEEK_STEP_MS = 10_000L
private const val PROGRESS_INTERVAL_MS = 250L
private const val SEEK_TOLERANCE_MS = 500L
private const val SCRIM_ALPHA = 0.35f
private const val SURFACE_ALPHA = 0.35f
private const val SECONDARY_ALPHA = 0.7f
private const val TRANSPORT_ALPHA = 0.7f
private val THUMB_WIDTH = 5.dp
private val THUMB_HEIGHT = 26.dp
private val PLAY_BUTTON_SIZE = 72.dp
private val PLAY_ICON_SIZE = 36.dp
private val TOP_BAR_HEIGHT = 64.dp
