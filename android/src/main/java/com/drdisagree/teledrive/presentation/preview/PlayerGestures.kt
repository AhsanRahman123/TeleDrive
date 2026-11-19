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
import androidx.compose.foundation.gestures.AwaitPointerEventScope
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.geometry.Offset
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
import kotlin.math.absoluteValue
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
    zoomEnabled: Boolean = false,
    zoomScale: Float = 1f,
    zoomOffsetX: Float = 0f,
    zoomOffsetY: Float = 0f,
    onZoomChanged: (scale: Float, offsetX: Float, offsetY: Float) -> Unit = { _, _, _ -> },
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
    val applyZoom by rememberUpdatedState(onZoomChanged)
    val currentZoomScale by rememberUpdatedState(zoomScale)
    val currentZoomOffsetX by rememberUpdatedState(zoomOffsetX)
    val currentZoomOffsetY by rememberUpdatedState(zoomOffsetY)
    var subtitleDragging by remember { mutableStateOf(false) }
    var zoomPercent by remember { mutableStateOf<Int?>(null) }

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

    LaunchedEffect(zoomPercent) {
        if (zoomPercent == null) return@LaunchedEffect
        delay(HUD_TIMEOUT_MS.milliseconds)
        zoomPercent = null
    }

    val toggleControlsLabel = stringResource(Res.string.player_toggle_controls)
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .matchParentSize()
                // MX-style pinch zoom, first in the Initial pass (ahead of the subtitle-band
                // block below). It watches every gesture without consuming until a two-finger
                // span change crosses the claim threshold; from then on it consumes all
                // changes, which starves the Main-pass tap/drag detectors for this gesture
                // only. While zoomed in, a one-finger drag pans instead of adjusting
                // brightness/volume (those detectors are likewise starved once the pan
                // passes touch slop and this watcher consumes).
                .pointerInput(zoomEnabled) {
                    if (!zoomEnabled) return@pointerInput
                    awaitEachGesture {
                        awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        var scale = currentZoomScale
                        var panX = currentZoomOffsetX
                        var panY = currentZoomOffsetY
                        var pinching = false
                        var panning = false
                        var twoPointers = false
                        var initialSpan = 0f
                        var startScale = scale
                        var lastCentroid: Offset? = null
                        var lastPanPosition: Offset? = null

                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            when {
                                pressed.size >= 2 -> {
                                    val first = pressed[0].position
                                    val second = pressed[1].position
                                    val span = (first - second).getDistance()
                                    val centroid = (first + second) / 2f
                                    if (!twoPointers) {
                                        // A (new) second finger landed: re-base the pinch.
                                        twoPointers = true
                                        initialSpan = span
                                        startScale = scale
                                        lastCentroid = centroid
                                    }
                                    if (!pinching && initialSpan > 0f) {
                                        val factor = span / initialSpan
                                        if (factor >= PINCH_CLAIM_FACTOR ||
                                            factor <= 1f / PINCH_CLAIM_FACTOR
                                        ) {
                                            pinching = true
                                        }
                                    }
                                    if (pinching) {
                                        event.changes.forEach { it.consume() }
                                        val factor = if (initialSpan > 0f) {
                                            span / initialSpan
                                        } else {
                                            1f
                                        }
                                        scale = (startScale * factor)
                                            .coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
                                        lastCentroid?.let { previous ->
                                            val delta = centroid - previous
                                            panX += delta.x
                                            panY += delta.y
                                        }
                                        val (clampedX, clampedY) =
                                            clampZoomPan(panX, panY, size.width, size.height, scale)
                                        panX = clampedX
                                        panY = clampedY
                                        applyZoom(scale, panX, panY)
                                        zoomPercent = (scale * 100).roundToInt()
                                    }
                                    lastCentroid = centroid
                                }

                                pressed.size == 1 -> {
                                    val change = pressed[0]
                                    if (twoPointers) {
                                        // Dropped from two fingers to one mid-gesture: the
                                        // remaining finger pans from wherever it is now.
                                        twoPointers = false
                                        pinching = false
                                        panning = scale > 1f
                                        lastPanPosition = change.position
                                        if (panning) change.consume()
                                    } else if (scale > 1f) {
                                        val previous = lastPanPosition
                                        if (previous == null) {
                                            lastPanPosition = change.position
                                        } else {
                                            val delta = change.position - previous
                                            if (panning ||
                                                delta.getDistance() > viewConfiguration.touchSlop
                                            ) {
                                                panning = true
                                                panX += delta.x
                                                panY += delta.y
                                                val (clampedX, clampedY) = clampZoomPan(
                                                    panX,
                                                    panY,
                                                    size.width,
                                                    size.height,
                                                    scale
                                                )
                                                panX = clampedX
                                                panY = clampedY
                                                change.consume()
                                                applyZoom(scale, panX, panY)
                                            }
                                            lastPanPosition = change.position
                                        }
                                    }
                                }
                            }
                            if (event.changes.all { !it.pressed }) {
                                finished = true
                                // Snap back: a zoom that settles at (or below) ~100% is no
                                // zoom at all — return to exactly 1x with no offset.
                                if (scale <= ZOOM_SNAP_BACK_SCALE &&
                                    (scale != 1f || panX != 0f || panY != 0f)
                                ) {
                                    applyZoom(1f, 0f, 0f)
                                }
                            }
                        }
                    }
                }
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
                // Brightness (left half) / volume (right half) vertical drag. Hand-rolled
                // instead of detectVerticalDragGestures so two-finger gestures are
                // EXCLUSIVE: the stock detector starts on the first finger alone (a pinch
                // begins as one finger moving, then the second finger lands) and its drag
                // loop then keeps tracking that finger for the whole pinch — the Initial-
                // pass pinch watcher's consumption does not stop it — so volume drifted
                // mid-pinch (Pranto's test video: volume 66% -> 68% while the ZoomHud
                // showed 250% -> 111%). This detector cancels the moment a second pointer
                // is pressed, or the Initial pass claims the gesture, and restores the
                // pre-drag brightness/volume, so a cancelled adjustment applies nothing.
                // While zoomed in it never starts at all — a one-finger drag there pans
                // (the pinch watcher owns it) — so the drag-start side effect (chrome
                // hide via onAdjustStart) cannot fire while zoomed either.
                .pointerInput(dragEnabled, maxVolume) {
                    if (!dragEnabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Main
                        )
                        if (down.isConsumed || currentZoomScale > 1f) {
                            // The subtitle-band block claimed this touch, or the picture
                            // is zoomed and one-finger drags pan: stay out entirely.
                            drainUntilAllPointersUp()
                            return@awaitEachGesture
                        }
                        var onLeftHalf = false
                        var started = false
                        var cancelled = false
                        var snapshotBrightness = 0f
                        var snapshotVolume = 0f
                        var accumulatedX = 0f
                        var accumulatedY = 0f
                        var lastPosition = down.position

                        fun restoreAdjustment() {
                            brightness = snapshotBrightness
                            applyBrightness(activity, snapshotBrightness)
                            volume = snapshotVolume
                            audioManager?.setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                (snapshotVolume * maxVolume).roundToInt(),
                                0
                            )
                            adjustment = null
                        }

                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            // A second finger makes this a pinch, never an adjustment.
                            if (event.changes.count { it.pressed } >= 2) {
                                cancelled = true
                                break
                            }
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                finished = true
                                continue
                            }
                            if (!change.pressed) {
                                // A system-cancelled pointer also undoes the adjustment.
                                if (change.isCanceled) cancelled = true
                                finished = true
                                continue
                            }
                            // The Initial pass claimed this gesture (pinch watcher or
                            // subtitle band): it is not a brightness/volume drag.
                            if (change.isConsumed) {
                                cancelled = true
                                break
                            }
                            val delta = change.position - lastPosition
                            lastPosition = change.position
                            if (!started) {
                                accumulatedX += delta.x
                                accumulatedY += delta.y
                                when {
                                    accumulatedY.absoluteValue > viewConfiguration.touchSlop &&
                                        accumulatedY.absoluteValue >=
                                        accumulatedX.absoluteValue -> {
                                        started = true
                                        onLeftHalf = down.position.x < size.width / 2f
                                        volume = currentVolume(audioManager, maxVolume)
                                        snapshotBrightness = brightness
                                        snapshotVolume = volume
                                        adjustStart()
                                    }

                                    accumulatedX.absoluteValue > viewConfiguration.touchSlop &&
                                        accumulatedX.absoluteValue >
                                        accumulatedY.absoluteValue -> {
                                        // Horizontal-dominant: not an adjustment gesture.
                                        finished = true
                                    }
                                }
                            } else if (delta != Offset.Zero) {
                                change.consume()
                                if (delta.y != 0f) {
                                    val travel = (size.height * DRAG_RANGE).coerceAtLeast(1f)
                                    val step = -delta.y / travel
                                    if (onLeftHalf) {
                                        brightness =
                                            (brightness + step).coerceIn(MIN_BRIGHTNESS, 1f)
                                        applyBrightness(activity, brightness)
                                        adjustment =
                                            AdjustmentLevel(Adjustment.BRIGHTNESS, brightness)
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
                            }
                        }
                        if (cancelled) {
                            if (started) restoreAdjustment()
                            // Swallow the rest of this touch sequence so the second
                            // finger cannot start a fresh adjustment inside it.
                            drainUntilAllPointersUp()
                        }
                    }
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
        AnimatedVisibility(
            visible = zoomPercent != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            zoomPercent?.let { ZoomHud(it) }
        }
    }
}

/** Live pinch-zoom readout ("123%"), modelled on the brightness/volume [AdjustmentHud]. */
@Composable
private fun ZoomHud(percent: Int) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = Color.Black.copy(alpha = HUD_ALPHA),
        contentColor = Color.White
    ) {
        Text(
            text = "$percent%",
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
            style = MaterialTheme.typography.titleLarge
        )
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

/**
 * Clamps a pinch-zoom pan offset to the scaled picture's half-extents. The half-extents
 * are floored at 0: below 100% zoom (scale < 1) the raw `size * (scale - 1) / 2` goes
 * NEGATIVE, which inverts the `coerceIn(-max, max)` range — and Float.coerceIn throws
 * IllegalArgumentException on an inverted range. That exception, thrown inside the
 * pinch watcher the first event a pinch crossed below 100%, is what force-closed the
 * app (Pranto's crash: ZoomHud 100%/102%, then black, then the launcher). At or below
 * 100% there is nothing to pan, so the offset is simply pinned to 0.
 */
internal fun clampZoomPan(
    x: Float,
    y: Float,
    width: Int,
    height: Int,
    scale: Float
): Pair<Float, Float> {
    val maxX = (width * (scale - 1f) / 2f).coerceAtLeast(0f)
    val maxY = (height * (scale - 1f) / 2f).coerceAtLeast(0f)
    return x.coerceIn(-maxX, maxX) to y.coerceIn(-maxY, maxY)
}

/** Awaits (without acting on) the rest of the current gesture, until every pointer is up. */
private suspend fun AwaitPointerEventScope.drainUntilAllPointersUp() {
    var allUp = false
    while (!allUp) {
        val event = awaitPointerEvent(PointerEventPass.Main)
        if (event.changes.all { !it.pressed }) allUp = true
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
private const val PINCH_CLAIM_FACTOR = 1.08f
private const val MIN_ZOOM_SCALE = 0.4f
private const val MAX_ZOOM_SCALE = 2.5f
private const val ZOOM_SNAP_BACK_SCALE = 1.02f
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
