package com.drdisagree.teledrive.presentation.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_image_undecodable
import com.drdisagree.teledrive.resources.preview_mirror_horizontally
import com.drdisagree.teledrive.resources.preview_mirror_vertically
import com.drdisagree.teledrive.resources.preview_rotate_clockwise
import com.drdisagree.teledrive.resources.preview_rotate_counter_clockwise
import com.drdisagree.teledrive.resources.preview_zoom_in
import com.drdisagree.teledrive.resources.preview_zoom_out
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Tiles are decoded at the current zoom, so a photo stays sharp without holding the whole image in
 * memory. Telephoto owns pinch and double-tap gestures; the buttons drive the same zoom state.
 *
 * Rotate / flip (vc183 N3, ported from PC 1.6.24): view-only transforms held
 * per image (reset when the model changes, like the PC viewer's per-file
 * state) and applied through a graphicsLayer — rotation in degrees plus two
 * mirror flags, with the same quarter-turn fit-scale compensation the PC
 * viewer computes from the image's intrinsic aspect, so a rotated photo
 * still fits the viewport instead of spilling out of it. The file itself is
 * never modified: in the in-zip viewer the transforms are for viewing only
 * and the ZIP stays untouched. Pinch zoom is NOT re-added — telephoto's
 * configuration is unchanged.
 */
@Composable
fun ZoomableImage(
    model: Any,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    controlsVisible: Boolean = true,
    onTap: () -> Unit = {}
) {
    var undecodable by remember(model) { mutableStateOf(false) }
    if (undecodable) {
        EmptyState(
            icon = Icons.Filled.BrokenImage,
            title = stringResource(Res.string.preview_image_undecodable),
            modifier = modifier.fillMaxSize()
        )
        return
    }

    val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = MAX_ZOOM))
    val zoomScope = rememberCoroutineScope()
    val context = LocalContext.current
    var rotationDegrees by remember(model) { mutableIntStateOf(0) }
    var mirrorHorizontally by remember(model) { mutableStateOf(false) }
    var mirrorVertically by remember(model) { mutableStateOf(false) }
    // The decoded image's intrinsic aspect (width / height), for the
    // quarter-turn fit scale; null until the image lands.
    var intrinsicAspect by remember(model) { mutableFloatStateOf(0f) }
    val request = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .listener(
                onError = { _, _ -> undecodable = true },
                onSuccess = { _, result ->
                    val image = result.image
                    if (image.width > 0 && image.height > 0) {
                        intrinsicAspect = image.width.toFloat() / image.height.toFloat()
                    }
                }
            )
            .build()
    }

    Box(modifier = modifier.fillMaxSize()) {
        ZoomableAsyncImage(
            model = request,
            contentDescription = contentDescription,
            state = rememberZoomableImageState(zoomableState),
            onClick = { onTap() },
            onDoubleClick = { state, centroid ->
                zoomScope.launch {
                    if ((state.zoomFraction ?: 0f) > 0f) {
                        state.resetZoom()
                    } else {
                        state.zoomBy(zoomFactor = 2f, centroid = centroid)
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // The PC viewer's transform, verbatim in shape: at a
                    // quarter turn the fitted footprint swaps axes, so the
                    // layer is scaled by however much makes the rotated
                    // footprint fit the viewport again.
                    val isQuarterTurn = rotationDegrees % 180 != 0
                    val fitScale = if (isQuarterTurn) {
                        val viewportAspect = size.width / size.height.coerceAtLeast(1f)
                        val sourceAspect = intrinsicAspect.takeIf { it > 0f } ?: viewportAspect
                        val fittedWidth: Float
                        val fittedHeight: Float
                        if (sourceAspect > viewportAspect) {
                            fittedWidth = size.width
                            fittedHeight = size.width / sourceAspect
                        } else {
                            fittedHeight = size.height
                            fittedWidth = size.height * sourceAspect
                        }
                        minOf(
                            size.width / fittedHeight.coerceAtLeast(1f),
                            size.height / fittedWidth.coerceAtLeast(1f)
                        )
                    } else {
                        1f
                    }
                    rotationZ = rotationDegrees.toFloat()
                    scaleX = fitScale * if (mirrorHorizontally) -1f else 1f
                    scaleY = fitScale * if (mirrorVertically) -1f else 1f
                }
        )
        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { zoomScope.launch { zoomableState.zoomBy(zoomFactor = 1.2f) } }) {
                    Icon(
                        imageVector = Icons.Filled.ZoomIn,
                        contentDescription = stringResource(Res.string.preview_zoom_in)
                    )
                }
                IconButton(onClick = { zoomScope.launch { zoomableState.zoomBy(zoomFactor = 1f / 1.2f) } }) {
                    Icon(
                        imageVector = Icons.Filled.ZoomOut,
                        contentDescription = stringResource(Res.string.preview_zoom_out)
                    )
                }
                IconButton(onClick = { rotationDegrees = (rotationDegrees + 270) % 360 }) {
                    Icon(
                        imageVector = Icons.Filled.Rotate90DegreesCcw,
                        contentDescription = stringResource(Res.string.preview_rotate_counter_clockwise)
                    )
                }
                IconButton(onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }) {
                    Icon(
                        imageVector = Icons.Filled.Rotate90DegreesCw,
                        contentDescription = stringResource(Res.string.preview_rotate_clockwise)
                    )
                }
                IconButton(onClick = { mirrorHorizontally = !mirrorHorizontally }) {
                    Icon(
                        imageVector = Icons.Filled.Flip,
                        contentDescription = stringResource(Res.string.preview_mirror_horizontally),
                        tint = if (mirrorHorizontally) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
                IconButton(onClick = { mirrorVertically = !mirrorVertically }) {
                    Icon(
                        imageVector = Icons.Filled.FlipCameraAndroid,
                        contentDescription = stringResource(Res.string.preview_mirror_vertically),
                        tint = if (mirrorVertically) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                }
            }
        }
    }
}

private const val MAX_ZOOM = 6f
