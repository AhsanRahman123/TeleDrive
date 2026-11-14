package com.drdisagree.teledrive.presentation.preview

import android.app.Activity
import android.media.AudioManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.BrightnessLow
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.player_brightness
import com.drdisagree.teledrive.resources.player_toggle_controls
import com.drdisagree.teledrive.resources.player_volume
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Left half sets brightness, right half volume; the brightness override lives on the window, so it
 * reverts when the player leaves.
 */
@Composable
fun PlayerGestureArea(
    onTap: () -> Unit,
    onDoubleTap: (forward: Boolean) -> Unit = {},
    onLongPressStart: () -> Unit = {},
    onLongPressEnd: () -> Unit = {},
    onAdjustStart: () -> Unit,
    dragEnabled: Boolean,
    chromeVisible: Boolean,
    subtitleDragEnabled: Boolean = false,
    subtitlePosition: Float = DEFAULT_SUBTITLE_VERTICAL_POSITION,
    subtitleHitWidthFraction: Float = DEFAULT_SUBTITLE_HIT_WIDTH_FRACTION,
    onSubtitlePositionChange: (Float) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val tap by rememberUpdatedState(onTap)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val longPressStart by rememberUpdatedState(onLongPressStart)
    val longPressEnd by rememberUpdatedState(onLongPressEnd)
    val adjustStart by rememberUpdatedState(onAdjustStart)
    val currentSubtitlePosition by rememberUpdatedState(subtitlePosition)
    val currentSubtitleHitWidth by rememberUpdatedState(subtitleHitWidthFraction)
    val updateSubtitlePosition by rememberUpdatedState(onSubtitlePositionChange)
    var subtitleDragging by remember { mutableStateOf(false) }

    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember(audioManager) {
        (audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 1).coerceAtLeast(1)
    }

    var brightness by remember(activity) { mutableFloatStateOf(systemBrightness(activity)) }
    var volume by remember(audioManager) {
        mutableFloatStateOf(
            currentVolume(
                audioManager,
                maxVolume
            )
        )
    }
    var adjustment by remember { mutableStateOf<AdjustmentLevel?>(null) }

    LaunchedEffect(chromeVisible) {
        if (chromeVisible) adjustment = null
    }

    LaunchedEffect(adjustment) {
        if (adjustment == null) return@LaunchedEffect
        delay(HUD_TIMEOUT_MS.milliseconds)
        adjustment = null
    }

    val toggleControlsLabel = stringResource(Res.string.player_toggle_controls)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                // This runs in the Initial pass, ahead of the general tap/long-press detector.
                // Only a touch on the visible subtitle band is claimed; all other touches continue
                // to the regular player gestures, including the 2x long-press speed boost.
                .pointerInput(subtitleDragEnabled) {
                    if (!subtitleDragEnabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        val hitWidth = size.width * currentSubtitleHitWidth.coerceIn(
                            MIN_SUBTITLE_HIT_WIDTH_FRACTION,
                            MAX_SUBTITLE_HIT_WIDTH_FRACTION
                        )
                        val hitCenterY = size.height * currentSubtitlePosition.coerceIn(
                            MIN_SUBTITLE_VERTICAL_POSITION,
                            MAX_SUBTITLE_VERTICAL_POSITION
                        )
                        val hitHeight = SUBTITLE_DRAG_TARGET_HEIGHT.toPx()
                        val insideSubtitleBand =
                            kotlin.math.abs(down.position.x - size.width / 2f) <= hitWidth / 2f &&
                            kotlin.math.abs(down.position.y - hitCenterY) <= hitHeight / 2f

                        if (insideSubtitleBand) {
                            down.consume()
                            subtitleDragging = true
                            try {
                                var finished = false
                                while (!finished) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: break
                                    if (change.position != change.previousPosition) {
                                        change.consume()
                                        if (size.height > 0) {
                                            updateSubtitlePosition(
                                                (change.position.y / size.height).coerceIn(
                                                    MIN_SUBTITLE_VERTICAL_POSITION,
                                                    MAX_SUBTITLE_VERTICAL_POSITION
                                                )
                                            )
                                        }
                                    }
                                    if (!change.pressed) finished = true
                                }
                            } finally {
                                subtitleDragging = false
                            }
                        }
                    }
                }
                .semantics {
                    onClick(label = toggleControlsLabel) {
                        tap()
                        true
                    }
                }
                .pointerInput(Unit) {
                    var longPressStarted = false
                    detectTapGestures(
                        onPress = {
                            longPressStarted = false
                            tryAwaitRelease()
                            if (longPressStarted) longPressEnd()
                        },
                        onTap = { tap() },
                        onDoubleTap = { offset ->
                            doubleTap(offset.x >= size.width / 2f)
                        },
                        onLongPress = {
                            longPressStarted = true
                            longPressStart()
                        }
                    )
                }
                .pointerInput(dragEnabled, maxVolume) {
                    if (!dragEnabled) return@pointerInput
                    var onLeftHalf = false
                    detectVerticalDragGestures(
                        onDragStart = { offset ->
                            onLeftHalf = offset.x < size.width / 2f
                            volume = currentVolume(audioManager, maxVolume)
                            adjustStart()
                        },
                        onVerticalDrag = { change, dragAmount ->
                            change.consume()
                            val travel = (size.height * DRAG_RANGE).coerceAtLeast(1f)
                            val step = -dragAmount / travel
                            if (onLeftHalf) {
                                brightness = (brightness + step).coerceIn(MIN_BRIGHTNESS, 1f)
                                applyBrightness(activity, brightness)
                                adjustment = AdjustmentLevel(Adjustment.BRIGHTNESS, brightness)
                            } else {
                                volume = (volume + step).coerceIn(0f, 1f)
                                audioManager?.setStreamVolume(
                                    AudioManager.STREAM_MUSIC,
                                    (volume * maxVolume).roundToInt(),
                                    0
                                )
                                adjustment = AdjustmentLevel(Adjustment.VOLUME, volume)
                            }
                        }
                    )
                }
        )
        if (subtitleDragging) {
            val handleShape = RoundedCornerShape(10.dp)
            val handleHeight = SUBTITLE_DRAG_HANDLE_HEIGHT
            val handleTop = (
                maxHeight * currentSubtitlePosition - handleHeight / 2
            ).coerceIn(0.dp, (maxHeight - handleHeight).coerceAtLeast(0.dp))
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = handleTop)
                    .fillMaxWidth(
                        currentSubtitleHitWidth.coerceIn(
                            MIN_SUBTITLE_HIT_WIDTH_FRACTION,
                            MAX_SUBTITLE_HIT_WIDTH_FRACTION
                        )
                    )
                    .height(handleHeight)
                    .background(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
                        handleShape
                    )
                    .border(1.5.dp, MaterialTheme.colorScheme.primary, handleShape)
            )
        }
        AnimatedVisibility(
            visible = adjustment != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            adjustment?.let { AdjustmentHud(it) }
        }
    }
}

@Composable
private fun AdjustmentHud(adjustment: AdjustmentLevel) {
    val brightnessLabel = stringResource(Res.string.player_brightness)
    val volumeLabel = stringResource(Res.string.player_volume)
    val label = when (adjustment.kind) {
        Adjustment.BRIGHTNESS -> brightnessLabel
        Adjustment.VOLUME -> volumeLabel
    }
    val icon = when (adjustment.kind) {
        Adjustment.BRIGHTNESS -> when {
            adjustment.level <= MIN_BRIGHTNESS -> Icons.Filled.BrightnessLow
            adjustment.level <= HALF -> Icons.Filled.BrightnessMedium
            else -> Icons.Filled.BrightnessHigh
        }

        Adjustment.VOLUME -> when {
            adjustment.level <= 0f -> Icons.AutoMirrored.Filled.VolumeOff
            adjustment.level <= HALF -> Icons.AutoMirrored.Filled.VolumeDown
            else -> Icons.AutoMirrored.Filled.VolumeUp
        }
    }

    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Black.copy(alpha = HUD_ALPHA),
        contentColor = Color.White
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp)
            )
            LinearProgressIndicator(
                progress = { adjustment.level },
                modifier = Modifier.width(HUD_BAR_WIDTH)
            )
            Text(
                text = "${(adjustment.level * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

private fun currentVolume(audioManager: AudioManager?, maxVolume: Int): Float {
    val current = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    return (current.toFloat() / maxVolume).coerceIn(0f, 1f)
}

private fun systemBrightness(activity: Activity?): Float {
    val override = activity?.window?.attributes?.screenBrightness ?: -1f
    if (override >= 0f) return override
    val resolver = activity?.contentResolver ?: return DEFAULT_BRIGHTNESS
    val value = runCatching {
        Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS)
    }.getOrNull() ?: return DEFAULT_BRIGHTNESS
    return (value / MAX_SYSTEM_BRIGHTNESS).coerceIn(0f, 1f)
}

private fun applyBrightness(activity: Activity?, value: Float) {
    val window = activity?.window ?: return
    window.attributes = window.attributes.apply { screenBrightness = value }
}

private const val DRAG_RANGE = 0.7f
private const val MIN_SUBTITLE_VERTICAL_POSITION = 0.08f
private const val MAX_SUBTITLE_VERTICAL_POSITION = 0.92f
internal const val MIN_SUBTITLE_HIT_WIDTH_FRACTION = 0.22f
internal const val MAX_SUBTITLE_HIT_WIDTH_FRACTION = 0.94f
private val SUBTITLE_DRAG_TARGET_HEIGHT = 76.dp
private val SUBTITLE_DRAG_HANDLE_HEIGHT = 64.dp
private const val MIN_BRIGHTNESS = 0.01f
private const val DEFAULT_BRIGHTNESS = 0.5f
private const val MAX_SYSTEM_BRIGHTNESS = 255f
private const val HALF = 0.5f
private const val HUD_ALPHA = 0.85f
private const val HUD_TIMEOUT_MS = 900L
private val HUD_BAR_WIDTH = 120.dp
