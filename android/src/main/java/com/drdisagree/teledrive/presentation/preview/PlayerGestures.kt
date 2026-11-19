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
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
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
import androidx.compose.ui.input.pointer.PointerId
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
import kotlin.math.pow
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
                //
                // Stability model — the same one Android's ScaleGestureDetector uses, and
                // MX Player's zoom is built on that detector: the scale is NEVER recomputed
                // from a baseline captured when the second finger landed. Each event
                // contributes only its own small span ratio (span / previous span), applied
                // through [pinchZoomStep], which scales about the stage centre: the pan
                // is never re-anchored to the fingers, so the picture stays planted
                // while pinching, exactly like MX Player (see [pinchZoomStep]).
                // Any event that changes the pointer set (a finger lands or lifts, or the
                // tracked pair changes) only re-baselines — it applies nothing — so the
                // span collapse as fingers lift off, and digitiser pair swaps, cannot
                // teleport the picture. The travel spent crossing the claim threshold is
                // absorbed into the baseline the same way, so a pinch starts moving
                // smoothly from the current scale instead of jumping by the whole
                // threshold at once. On release the zoom HOLDS at whatever value it
                // reached, above or below 100%, exactly like MX Player; only a new file
                // or an aspect-ratio change resets it.
                //
                // Sensitivity: each event's span ratio is damped by a fixed exponent
                // before it is applied (see ZOOM_SENSITIVITY). Frame-by-frame
                // measurement of this build's predecessor at the raw 1:1 gain showed a
                // single quick pinch sweeping the whole zoom range (129% -> 41% in
                // 0.45 s, the 40% floor hit in six separate episodes, per-0.25 s pill
                // steps up to x1.56 / x0.54) — several times faster than the same
                // gestures ever moved MX Player's zoom in the reference video (steps
                // x0.67..x1.29). Native-frame-rate traces proved the tracking itself
                // was smooth (per-frame steps x0.93..x1.09, no jumps, no drift): the
                // gain was simply too high for this phone's wide finger-span range, so
                // the fix is the reduced rate, not a different tracker.
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
                        // The two tracked pointer ids; empty while fewer than two fingers
                        // are down. Any change to the pair means: re-baseline only.
                        var trackedPair: Set<PointerId> = emptySet()
                        // Span at the moment the current pair formed; the claim threshold
                        // is measured against it. Pre-claim travel is never applied.
                        var claimSpan = 0f
                        var prevSpan = 0f
                        var prevCentroid: Offset? = null
                        var lastPanPosition: Offset? = null

                        var finished = false
                        while (!finished) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.filter { it.pressed }
                            // A finger landing or lifting in this event makes its span and
                            // centroid meaningless for scaling (a lifting finger is already
                            // absent from `pressed`; a landing one has no previous
                            // position): such events only re-baseline, never apply.
                            val pointerSetChanged = event.changes.any {
                                it.pressed != it.previousPressed
                            }
                            when {
                                pressed.size >= 2 -> {
                                    val first = pressed[0]
                                    val second = pressed[1]
                                    val span = (first.position - second.position)
                                        .getDistance()
                                    val centroid = (first.position + second.position) / 2f
                                    val pair = setOf(first.id, second.id)
                                    if (pair != trackedPair || pointerSetChanged) {
                                        // The pair (re)formed or a third finger touched:
                                        // baseline only, apply nothing this event.
                                        trackedPair = pair
                                        claimSpan = span
                                        prevSpan = span
                                        prevCentroid = centroid
                                    } else {
                                        if (!pinching && claimSpan > 0f) {
                                            val factor = span / claimSpan
                                            if (factor >= PINCH_CLAIM_FACTOR ||
                                                factor <= 1f / PINCH_CLAIM_FACTOR
                                            ) {
                                                pinching = true
                                            }
                                        }
                                        if (pinching) {
                                            event.changes.forEach { it.consume() }
                                            val previousCentroid = prevCentroid
                                            if (previousCentroid != null) {
                                                val stepped = pinchZoomStep(
                                                    ZoomTransform(scale, panX, panY),
                                                    prevSpan,
                                                    span,
                                                    previousCentroid,
                                                    centroid,
                                                    size.width,
                                                    size.height
                                                )
                                                scale = stepped.scale
                                                panX = stepped.panX
                                                panY = stepped.panY
                                                applyZoom(scale, panX, panY)
                                                zoomPercent = (scale * 100).roundToInt()
                                            }
                                        }
                                        prevSpan = span
                                        prevCentroid = centroid
                                    }
                                }

                                pressed.size == 1 -> {
                                    val change = pressed[0]
                                    if (trackedPair.isNotEmpty()) {
                                        // Dropped from two fingers to one mid-gesture: the
                                        // remaining finger pans from wherever it is now.
                                        trackedPair = emptySet()
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
                                // No snap-back: the zoom holds at its release value,
                                // above or below 100%, like MX Player. The session
                                // state already holds exactly what was last applied.
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
                                // Pointer up. (A system cancel also surfaces as all
                                // pointers up in Compose; like the stock detector this
                                // replaces — which had no cancel handler — the values
                                // simply stay where they are in that rare case.)
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

/** An immutable pinch-zoom state: scale plus the graphicsLayer translation. */
internal data class ZoomTransform(val scale: Float, val panX: Float, val panY: Float)

/**
 * One incremental pinch step, ScaleGestureDetector-style: the new scale is the current
 * scale times THIS event's span ratio (`span / prevSpan`) only — never a ratio against
 * a baseline captured when the gesture started — so no single event, baseline error or
 * finger-landing position can move the scale by more than the fingers actually moved
 * since the previous event.
 *
 * The ratio is damped first (`factor^ZOOM_SENSITIVITY`): the exponent compresses every
 * step toward 1, so the zoom follows the fingers at a reduced, steadier rate while the
 * product of the steps still telescopes exactly onto the (damped) total span change —
 * no drift is introduced. Damping is multiplicative in log space, so it shrinks fast
 * flicks and slow pinches alike.
 *
 * Anchoring: the zoom scales about the STAGE CENTRE, never about the fingers. The
 * graphicsLayer scales about the stage centre O, so a content point p renders at
 * `O + (p - O) * s + t`; keeping the content point that sits at the centre on the
 * centre means the pan scales with the applied ratio: `t' = t * (s' / s)`, using the
 * ratio that was ACTUALLY applied after clamping to the zoom range. An un-panned
 * picture (t = 0) therefore stays exactly centred through any pinch, wherever the
 * fingers are and however they glide, and a pan the user made deliberately (one
 * finger, while zoomed) is preserved proportionally. The centroid arguments are
 * deliberately unused: earlier builds re-anchored the pan to the moving centroid on
 * every event, which converted ordinary finger spread and glide into picture
 * translation — frame measurement of the MX Player reference showed its picture
 * centre never leaves the stage centre (within ~2 recording px across full-range
 * sweeps), while this build's portrait pinch-in carried the picture centre ~24
 * recording px off centre by 123% zoom. MX-style stillness is centre anchoring.
 * The result is clamped by [clampZoomPan], so the below-100% crash fix applies to
 * every step.
 *
 * A span ratio outside [MIN_EVENT_SPAN_FACTOR, MAX_EVENT_SPAN_FACTOR] cannot be real
 * finger motion at touch sampling rates — it means the digitiser glitched or the
 * tracked pair silently changed — so the step is skipped and the state returned
 * unchanged; the caller re-baselines from this event, so the next step is measured
 * from sane values again.
 */
internal fun pinchZoomStep(
    current: ZoomTransform,
    prevSpan: Float,
    span: Float,
    prevCentroid: Offset,
    centroid: Offset,
    width: Int,
    height: Int
): ZoomTransform {
    if (prevSpan <= 0f || span <= 0f || current.scale <= 0f) return current
    val factor = span / prevSpan
    if (factor < MIN_EVENT_SPAN_FACTOR || factor > MAX_EVENT_SPAN_FACTOR) return current
    val dampedFactor = factor.pow(ZOOM_SENSITIVITY)
    val newScale = (current.scale * dampedFactor).coerceIn(MIN_ZOOM_SCALE, MAX_ZOOM_SCALE)
    val appliedRatio = newScale / current.scale
    // Centre anchoring: the pan scales with the applied ratio, so the content point
    // at the stage centre stays at the stage centre and an un-panned picture never
    // acquires a pan from a pinch, wherever the fingers are.
    val anchoredX = current.panX * appliedRatio
    val anchoredY = current.panY * appliedRatio
    val (clampedX, clampedY) = clampZoomPan(anchoredX, anchoredY, width, height, newScale)
    return ZoomTransform(newScale, clampedX, clampedY)
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
private const val MIN_EVENT_SPAN_FACTOR = 0.5f
private const val MAX_EVENT_SPAN_FACTOR = 2f

/**
 * Pinch-zoom sensitivity: each event's span ratio is raised to this power before it is
 * applied. 1.0 would track the fingers' span change one-to-one; frame measurement of
 * the 1:1 build showed one quick pinch sweeping 129% -> 41% in 0.45 s and per-0.25 s
 * steps up to x1.56 / x0.54, while the same user's MX Player reference stayed within
 * x0.67..x1.29 per step. 0.55 maps those worst observed steps to x1.28 / x0.71 — inside
 * MX's observed envelope — and stretches the full 0.4..2.5 zoom range over a span
 * change of ~28x, far beyond one pinch, so ordinary pinches stop slamming into the
 * floor and ceiling.
 */
private const val ZOOM_SENSITIVITY = 0.55f
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
