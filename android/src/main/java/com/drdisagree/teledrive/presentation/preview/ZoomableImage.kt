package com.drdisagree.teledrive.presentation.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.request.ImageRequest
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_image_undecodable
import com.drdisagree.teledrive.resources.preview_zoom_in
import com.drdisagree.teledrive.resources.preview_zoom_out
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.coil3.ZoomableAsyncImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.jetbrains.compose.resources.stringResource

/**
 * Tiles are decoded at the current zoom, so a photo stays sharp without holding the whole image in
 * memory. Telephoto owns pinch and double-tap gestures; the buttons drive the same zoom state.
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
    val context = LocalContext.current
    val request = remember(model) {
        ImageRequest.Builder(context)
            .data(model)
            .listener(onError = { _, _ -> undecodable = true })
            .build()
    }

    Box(modifier = modifier.fillMaxSize()) {
        ZoomableAsyncImage(
            model = request,
            contentDescription = contentDescription,
            state = rememberZoomableImageState(zoomableState),
            onClick = { onTap() },
            modifier = Modifier.fillMaxSize()
        )
        if (controlsVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { zoomableState.zoomBy(zoomFactor = 1.2f) }) {
                    Icon(
                        imageVector = Icons.Filled.ZoomIn,
                        contentDescription = stringResource(Res.string.preview_zoom_in)
                    )
                }
                IconButton(onClick = { zoomableState.zoomBy(zoomFactor = 1f / 1.2f) }) {
                    Icon(
                        imageVector = Icons.Filled.ZoomOut,
                        contentDescription = stringResource(Res.string.preview_zoom_out)
                    )
                }
            }
        }
    }
}

private const val MAX_ZOOM = 6f
