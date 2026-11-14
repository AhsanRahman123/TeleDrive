package com.drdisagree.teledrive.presentation.preview

import androidx.compose.material3.Button
import com.drdisagree.teledrive.resources.common_retry
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Rational
import android.util.Log
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.drdisagree.teledrive.core.media.PipController
import com.drdisagree.teledrive.core.media.TelegramDataSourceFactory
import com.drdisagree.teledrive.core.media.ThumbnailModel
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.player_codec_unsupported
import com.drdisagree.teledrive.resources.player_playback_failed
import com.drdisagree.teledrive.resources.player_unlock_controls
import java.io.File
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import kotlin.time.Duration.Companion.milliseconds

/**
 * PlayerView renders only the video; controls are Compose so they follow the theme and window
 * insets.
 */
@OptIn(UnstableApi::class)
@Composable
fun MediaPlayer(
    content: PreviewContent,
    title: String,
    fileId: String,
    dataSourceFactory: TelegramDataSourceFactory,
    isActivePage: Boolean,
    onPreviousFile: () -> Unit = {},
    onNextFile: () -> Unit = {},
    hasPreviousFile: Boolean = false,
    hasNextFile: Boolean = false,
    queueItems: List<String> = emptyList(),
    currentQueueIndex: Int = 0,
    onSelectQueueItem: (Int) -> Unit = {},
    onPlaybackFinished: () -> Unit = {},
    controlsVisible: Boolean = true,
    allowBackgroundPlayback: Boolean = false,
    preferredAudioLanguage: String = "",
    preferredSubtitleLanguage: String = "",
    onPreferredAudioLanguage: (String) -> Unit = {},
    onPreferredSubtitleLanguage: (String) -> Unit = {},
    onControlsVisibilityChanged: (Boolean) -> Unit = {},
    session: PlayerSession = remember { PlayerSession() },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activePage by rememberUpdatedState(isActivePage)
    val notifyControls by rememberUpdatedState(onControlsVisibilityChanged)
    val audioLanguage by rememberUpdatedState(preferredAudioLanguage)
    val subtitleLanguage by rememberUpdatedState(preferredSubtitleLanguage)
    val playbackFinished by rememberUpdatedState(onPlaybackFinished)

    val holder = remember(content) { mutableStateOf<ExoPlayer?>(null) }
    val player = holder.value
    val failedDecoders = remember(content) { mutableSetOf<String>() }
    val subtitleAppearance = session.subtitleAppearance
    var resumeReady by remember(player) { mutableStateOf(player?.playbackState == Player.STATE_READY) }
    val resumeReadyForListener by rememberUpdatedState(resumeReady)

    LaunchedEffect(holder, isActivePage) {
        if (!isActivePage || holder.value != null) return@LaunchedEffect
        val renderers = DefaultRenderersFactory(context)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
                MediaCodecSelector.DEFAULT
                    .getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder)
                    .filterNot { it.name in failedDecoders }
            }
        holder.value = ExoPlayer.Builder(context, renderers).build().apply {
            trackSelectionParameters = trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguage(audioLanguage.ifEmpty { null })
                .setPreferredTextLanguage(subtitleLanguage.ifEmpty { null })
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, subtitleLanguage.isEmpty())
                .build()
            when (content) {
                is PreviewContent.LocalMedia -> {
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(File(content.path))))
                }

                is PreviewContent.StreamedMedia -> {
                    val factory = if (content.parts.isNotEmpty()) {
                        dataSourceFactory.createParted(
                            parts = content.parts,
                            encrypted = content.encrypted
                        )
                    } else {
                        dataSourceFactory.create(
                            content.remoteFileId,
                            content.chatId,
                            content.messageId
                        )
                    }
                    val source = ProgressiveMediaSource.Factory(factory)
                        .createMediaSource(MediaItem.fromUri("telegram://stream"))
                    setMediaSource(source)
                }

                else -> Unit
            }
            val resumePosition = PlaybackResumeStore.load(context, fileId)
            if (resumePosition > 0L) seekTo(resumePosition)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(holder) {
        onDispose {
            holder.value?.let { activePlayer ->
                if (resumeReadyForListener) {
                    PlaybackResumeStore.save(
                        context,
                        fileId,
                        activePlayer.currentPosition,
                        activePlayer.duration
                    )
                }
                activePlayer.release()
            }
        }
    }

    DisposableEffect(player, isActivePage, resumeReady) {
        if (!isActivePage) {
            if (player != null && resumeReady) {
                PlaybackResumeStore.save(context, fileId, player.currentPosition, player.duration)
            }
            player?.pause()
        }
        onDispose {
            if (player != null && resumeReady) {
                PlaybackResumeStore.save(context, fileId, player.currentPosition, player.duration)
            }
        }
    }

    LaunchedEffect(player, isActivePage, resumeReady, fileId) {
        val activePlayer = player ?: return@LaunchedEffect
        if (!isActivePage || !resumeReady) return@LaunchedEffect
        while (true) {
            delay(1_000L)
            PlaybackResumeStore.save(
                context,
                fileId,
                activePlayer.currentPosition,
                activePlayer.duration
            )
        }
    }

    LaunchedEffect(subtitleAppearance) {
        delay(220L)
        SubtitleAppearanceStore.save(context, subtitleAppearance)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    val backgroundAllowed by rememberUpdatedState(allowBackgroundPlayback)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (resumeReadyForListener && player != null) {
                    PlaybackResumeStore.save(context, fileId, player.currentPosition, player.duration)
                }
                if (!backgroundAllowed) player?.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val audioOnly = when (content) {
        is PreviewContent.LocalMedia -> content.isAudio
        is PreviewContent.StreamedMedia -> content.isAudio
        else -> false
    }

    var interactionTick by remember { mutableIntStateOf(0) }
    var locked by remember(content) { mutableStateOf(false) }
    var lockHintVisible by remember(content) { mutableStateOf(false) }
    val visibleControls by rememberUpdatedState(controlsVisible)
    var resizeMode by session::resizeMode
    LaunchedEffect(player) { player?.setPlaybackSpeed(session.speed) }
    var playing by remember(player) { mutableStateOf(player?.isPlaying == true) }
    var sized by remember(player) { mutableStateOf((player?.videoSize?.width ?: 0) > 0) }
    var videoRatio by remember(player) {
        mutableStateOf(player?.videoSize?.toRational())
    }
    var decoderFailed by remember(player) { mutableStateOf(false) }
    var playbackFailed by remember(player) { mutableStateOf(false) }
    var playbackErrorDetails by remember(player) { mutableStateOf<String?>(null) }
    var seekFeedback by remember(player) { mutableStateOf<String?>(null) }
    var speedBoostActive by remember(player) { mutableStateOf(false) }
    var hasActiveSubtitles by remember(player) {
        mutableStateOf(
            player?.currentCues?.cues?.any { !it.text.isNullOrBlank() } == true
        )
    }
    var subtitleHitWidthFraction by remember(player) {
        mutableFloatStateOf(DEFAULT_SUBTITLE_HIT_WIDTH_FRACTION)
    }

    DisposableEffect(player) {
        if (player == null) return@DisposableEffect onDispose { }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) resumeReady = true
                if (playbackState == Player.STATE_ENDED) {
                    PlaybackResumeStore.clear(context, fileId)
                    if (session.repeatAll && queueItems.size == 1) {
                        player.seekTo(0)
                        player.play()
                    } else {
                        playbackFinished()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playing = isPlaying
                if (!isPlaying && resumeReadyForListener) {
                    PlaybackResumeStore.save(context, fileId, player.currentPosition, player.duration)
                }
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                sized = videoSize.width > 0 && videoSize.height > 0
                videoRatio = videoSize.toRational()
            }

            override fun onCues(cueGroup: CueGroup) {
                val textCues = cueGroup.cues.mapNotNull { it.text?.toString() }
                    .filter { it.isNotBlank() }
                hasActiveSubtitles = textCues.isNotEmpty()
                if (textCues.isNotEmpty()) {
                    val longestLine = textCues.maxOfOrNull { text ->
                        text.lineSequence().maxOfOrNull { it.length } ?: 0
                    } ?: 0
                    subtitleHitWidthFraction =
                        (0.12f + longestLine * 0.018f).coerceIn(
                            MIN_SUBTITLE_HIT_WIDTH_FRACTION,
                            MAX_SUBTITLE_HIT_WIDTH_FRACTION
                        )
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                playbackErrorDetails = buildString {
                    append("Media3 error ${error.errorCode}: ${error.message ?: error.javaClass.simpleName}")
                    val causes = generateSequence<Throwable>(error.cause) { it.cause }
                        .take(5)
                        .map { "${it.javaClass.simpleName}: ${it.message ?: "no details"}" }
                        .toList()
                    if (causes.isNotEmpty()) {
                        append("\nCause: ${causes.joinToString(" → ")}")
                    }
                    error.failedDecoderName()?.let { decoder ->
                        append("\nDecoder: $decoder")
                    }
                }
                Log.e("TeleDriveMediaPlayer", "Playback failed: ${playbackErrorDetails}", error)
                when (error.errorCode) {
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED -> {
                        val decoder = error.failedDecoderName()
                        if (decoder != null && failedDecoders.add(decoder)) {
                            Log.w("TeleDriveMediaPlayer", "Retrying with decoder fallback; excluded $decoder")
                            player.prepare()
                        } else {
                            decoderFailed = true
                        }
                    }

                    else -> playbackFailed = true
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) {
            delay(750L)
            seekFeedback = null
        }
    }

    LaunchedEffect(controlsVisible, playing, interactionTick, activePage, audioOnly) {
        if (!controlsVisible || !playing || !activePage || audioOnly) return@LaunchedEffect
        delay(CONTROLLER_TIMEOUT_MS.milliseconds)
        notifyControls(false)
    }

    LaunchedEffect(locked, lockHintVisible) {
        if (!locked) {
            lockHintVisible = false
            return@LaunchedEffect
        }
        if (!lockHintVisible) return@LaunchedEffect
        delay(CONTROLLER_TIMEOUT_MS.milliseconds)
        lockHintVisible = false
    }

    val pipController = koinInject<PipController>()
    val inPipMode by pipController.inPipMode.collectAsStateWithLifecycle()
    val pipAvailable by pipController.available.collectAsStateWithLifecycle()

    val pipOwner = remember { Any() }
    DisposableEffect(pipController, pipOwner, videoRatio, activePage, audioOnly) {
        pipController.offerVideo(pipOwner, videoRatio.takeIf { activePage && !audioOnly })
        onDispose { pipController.offerVideo(pipOwner, null) }
    }

    val activity = LocalActivity.current
    DisposableEffect(activity, playing, activePage, audioOnly) {
        val window = activity?.window
        val holding = playing && activePage && !audioOnly
        if (holding) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (holding) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < LIGHT_LUMINANCE
    val stageColor = when {
        audioOnly -> ComposeColor.Transparent
        darkTheme -> ComposeColor.Black
        else -> MaterialTheme.colorScheme.surfaceContainerLowest
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(stageColor)
    ) {
        if (audioOnly && player != null) {
            AudioStage(
                player = player,
                fallbackTitle = title,
                modifier = Modifier.align(Alignment.Center)
            )
        }
        if (!audioOnly && player != null) {
            AndroidView(
                factory = { viewContext ->
                    PlayerView(viewContext).apply {
                        useController = false
                        this.player = player
                        setBackgroundColor(Color.TRANSPARENT)
                        setShutterBackgroundColor(Color.TRANSPARENT)
                        addOnLayoutChangeListener { changed, _, _, _, _, _, _, _, _ ->
                            val host = changed as PlayerView
                            host.subtitleView?.let { subtitleView ->
                                subtitleView.translationY =
                                    (session.subtitleAppearance.verticalPosition - DEFAULT_SUBTITLE_VERTICAL_POSITION) *
                                        host.height
                                applySubtitleAppearance(subtitleView, viewContext, session.subtitleAppearance)
                            }
                        }
                    }
                },
                update = { view ->
                    view.player = player
                    view.subtitleView?.let { subtitleView ->
                        subtitleView.translationY =
                            (subtitleAppearance.verticalPosition - DEFAULT_SUBTITLE_VERTICAL_POSITION) * view.height
                        applySubtitleAppearance(subtitleView, view.context, subtitleAppearance)
                    }
                    val targetResizeMode = when (resizeMode) {
                        PlayerResizeMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                        PlayerResizeMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        PlayerResizeMode.FILL -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                    }
                    if (view.resizeMode != targetResizeMode) {
                        view.resizeMode = targetResizeMode
                        view.requestLayout()
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }
        if (!audioOnly && !sized) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(stageColor)
            ) {
                AsyncImage(
                    model = ThumbnailModel(fileId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        if (decoderFailed || playbackFailed) {
            Column(
                modifier = Modifier
                    .matchParentSize()
                    .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f))
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(
                        if (decoderFailed) {
                            Res.string.player_codec_unsupported
                        } else {
                            Res.string.player_playback_failed
                        }
                    ),
                    modifier = Modifier.padding(top = 16.dp),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )
                playbackErrorDetails?.let { details ->
                    Text(
                        text = details,
                        modifier = Modifier.padding(top = 10.dp),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (playbackFailed && player != null) {
                    Button(
                        onClick = {
                            playbackErrorDetails = null
                            playbackFailed = false
                            player.prepare()
                            player.play()
                        },
                        modifier = Modifier.padding(top = 20.dp),
                        shapes = ButtonDefaults.shapes()
                    ) {
                        Text(stringResource(Res.string.common_retry))
                    }
                }
            }
        }
        if (!inPipMode) {
            PlayerGestureArea(
                onTap = {
                    if (locked) {
                        lockHintVisible = !lockHintVisible
                    } else {
                        notifyControls(!visibleControls)
                    }
                },
                onLongPressStart = {
                    val activePlayer = player
                    if (!locked && activePlayer != null) {
                        speedBoostActive = true
                        session.speed = 2f
                        activePlayer.setPlaybackSpeed(2f)
                        interactionTick++
                    }
                },
                onLongPressEnd = {
                    if (speedBoostActive) {
                        speedBoostActive = false
                        session.speed = 1f
                        player?.setPlaybackSpeed(1f)
                        interactionTick++
                    }
                },
                onDoubleTap = { forward ->
                    val activePlayer = player
                    if (!locked && activePlayer != null) {
                        val stepMs = session.seekStepSeconds.coerceIn(5, 10).toLong() * 1_000L
                        val limit = activePlayer.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                        val target = if (forward) {
                            (activePlayer.currentPosition + stepMs).coerceAtMost(limit)
                        } else {
                            (activePlayer.currentPosition - stepMs).coerceAtLeast(0L)
                        }
                        activePlayer.seekTo(target)
                        interactionTick++
                        seekFeedback = (if (forward) "+" else "−") + "${stepMs / 1_000L}s"
                    }
                },
                onAdjustStart = { if (visibleControls) notifyControls(false) },
                dragEnabled = !locked && !audioOnly,
                chromeVisible = controlsVisible,
                subtitleDragEnabled = hasActiveSubtitles && !locked && !audioOnly && !inPipMode,
                subtitlePosition = subtitleAppearance.verticalPosition,
                subtitleHitWidthFraction = subtitleHitWidthFraction,
                onSubtitlePositionChange = {
                    session.subtitleAppearance = session.subtitleAppearance.copy(verticalPosition = it)
                },
                modifier = Modifier.matchParentSize()
            )
        }
        if (speedBoostActive) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = MaterialTheme.shapes.extraLarge,
                color = ComposeColor.Black.copy(alpha = 0.78f),
                contentColor = ComposeColor.White
            ) {
                Text(
                    "2× speed",
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
        seekFeedback?.let { feedback ->
            AnimatedVisibility(
                visible = true,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = ComposeColor.Black.copy(alpha = 0.78f),
                    contentColor = ComposeColor.White
                ) {
                    Text(
                        feedback,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }
        }
        if (player != null) {
            PlayerControls(
                player = player,
                session = session,
                onPreviousFile = onPreviousFile,
                onNextFile = onNextFile,
                hasPreviousFile = hasPreviousFile,
                hasNextFile = hasNextFile,
                queueItems = queueItems,
                currentQueueIndex = currentQueueIndex,
                onSelectQueueItem = onSelectQueueItem,
                visible = controlsVisible &&
                        !locked &&
                        !inPipMode &&
                        !decoderFailed &&
                        !playbackFailed,
                audioOnly = audioOnly,
                resizeMode = resizeMode,
                onCycleResizeMode = { resizeMode = resizeMode.next() },
                onInteraction = { interactionTick++ },
                onEnterPip = activity
                    ?.takeIf { pipAvailable }
                    ?.let { host -> { pipController.enter(host) } },
                onLock = {
                    locked = true
                    lockHintVisible = true
                    notifyControls(false)
                },
                onPreferredAudioLanguage = onPreferredAudioLanguage,
                onPreferredSubtitleLanguage = onPreferredSubtitleLanguage
            )
        }
        if (locked) {
            AnimatedVisibility(
                visible = lockHintVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .navigationBarsPadding()
                    .padding(start = 24.dp)
            ) {
                FilledIconButton(
                    onClick = {
                        locked = false
                        lockHintVisible = false
                        notifyControls(true)
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = ComposeColor.Black.copy(alpha = LOCK_ALPHA),
                        contentColor = ComposeColor.White
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.LockOpen,
                        contentDescription = stringResource(Res.string.player_unlock_controls)
                    )
                }
            }
        }
    }
}

/** Embedded cover art is used when present, with the track tags falling back to the file name. */
@Composable
private fun AudioStage(player: Player, fallbackTitle: String, modifier: Modifier = Modifier) {
    var metadata by remember(player) { mutableStateOf(player.mediaMetadata) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                metadata = mediaMetadata
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    val artwork = remember(metadata) {
        metadata.artworkData?.let { bytes ->
            runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            modifier = Modifier.size(ARTWORK_SIZE)
        ) {
            if (artwork != null) {
                Image(
                    bitmap = artwork,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(AUDIO_ICON_SIZE)
                    )
                }
            }
        }
        Text(
            text = metadata.title?.toString() ?: fallbackTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 24.dp)
        )
        metadata.artist?.toString()?.let { artist ->
            Text(
                text = artist,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        metadata.albumTitle?.toString()?.let { album ->
            Text(
                text = album,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun VideoSize.toRational(): Rational? =
    if (width > 0 && height > 0) Rational(width, height) else null

private const val CONTROLLER_TIMEOUT_MS = 3_000L
private const val LOCK_ALPHA = 0.7f
private const val LIGHT_LUMINANCE = 0.5f

private val AUDIO_ICON_SIZE = 96.dp

private val ARTWORK_SIZE = 260.dp

@OptIn(UnstableApi::class)
private fun PlaybackException.failedDecoderName(): String? =
    generateSequence<Throwable>(this) { it.cause }
        .firstNotNullOfOrNull { cause ->
            when (cause) {
                is MediaCodecRenderer.DecoderInitializationException -> cause.codecInfo?.name
                is MediaCodecDecoderException -> cause.codecInfo?.name
                else -> null
            }
        }
