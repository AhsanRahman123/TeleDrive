package com.drdisagree.teledrive.presentation.preview

import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.drdisagree.teledrive.presentation.common.add
import com.drdisagree.teledrive.presentation.components.ErrorState
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_page_number
import com.drdisagree.teledrive.resources.preview_pdf_open_failed
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/**
 * Rendering is serialized because PdfRenderer is not thread-safe; bitmaps are kept only while
 * visible.
 */
@Composable
fun PdfPreview(path: String, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // vc186 A7: plain snapshot state, not Animatable. The gesture handler
    // writes these directly (no coroutine launched per pointer event, no
    // MutatorMutex contention between competing snapTo calls), and they are
    // read only inside the LazyColumn's graphicsLayer lambda below, so a
    // pinch frame invalidates the draw phase only — the same transform-only
    // update path the image viewer's zoom uses. The old code launched two
    // coroutines per transform event to snapTo Animatable values.
    var scale by remember(path) { mutableFloatStateOf(1f) }
    var offset by remember(path) { mutableStateOf(Offset.Zero) }
    var viewport by remember(path) { mutableStateOf(IntSize.Zero) }
    val rendererState = remember(path) { mutableStateOf<PdfRenderer?>(null) }
    val renderMutex = remember(path) { Mutex() }
    val error = remember(path) { mutableStateOf<String?>(null) }

    val openFailedMessage = stringResource(Res.string.preview_pdf_open_failed)
    DisposableEffect(path) {
        try {
            val descriptor = ParcelFileDescriptor.open(
                File(path),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            rendererState.value = PdfRenderer(descriptor)
        } catch (_: Exception) {
            error.value = openFailedMessage
        }
        onDispose {
            runCatching { rendererState.value?.close() }
            rendererState.value = null
        }
    }

    error.value?.let {
        ErrorState(message = it, modifier = modifier)
        return
    }
    val renderer = rendererState.value ?: run {
        LoadingState(modifier = modifier)
        return
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it }
            .pointerInput(path) {
                detectTapGestures(
                    onDoubleTap = { tapOffset ->
                        val zoomedIn = scale > 1f
                        val target = if (zoomedIn) 1f else DOUBLE_TAP_SCALE
                        val focus = if (zoomedIn) {
                            Offset.Zero
                        } else {
                            clampPdfOffset(
                                Offset(
                                    (viewport.width / 2f - tapOffset.x) * (target - 1f),
                                    (viewport.height / 2f - tapOffset.y) * (target - 1f)
                                ),
                                target,
                                viewport
                            )
                        }
                        val startScale = scale
                        val startOffset = offset
                        scope.launch {
                            launch {
                                animate(
                                    startScale,
                                    target,
                                    animationSpec = tween(ZOOM_ANIMATION_MS)
                                ) { value, _ -> scale = value }
                            }
                            launch {
                                animate(
                                    Offset.VectorConverter,
                                    startOffset,
                                    focus,
                                    Offset.Zero,
                                    animationSpec = tween(ZOOM_ANIMATION_MS)
                                ) { value, _ -> offset = value }
                            }
                        }
                    }
                )
            }
            // vc186 A8: NOT detectTransformGestures. That detector consumes
            // every position change once past touch slop — including a plain
            // single-finger horizontal swipe at scale 1, where the pan it
            // reports is discarded (offset stays Zero). The pager's swipe was
            // eaten here and the viewer was trapped on the PDF page. This
            // handler consumes a change only when the transform actually
            // spends it:
            //  - two or more pointers (a real pinch), or
            //  - zoomed in past 1x and the zoom/pan really moves the layer
            //    (a pan already clamped at the page edge does NOT consume,
            //    so a swipe at the edge still escapes to the pager).
            // Anything else is left unconsumed for the LazyColumn (vertical
            // page scroll) and the pager (horizontal file switching), exactly
            // like the text viewer's pinch handler.
            .pointerInput(path) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var event = awaitPointerEvent()
                    while (event.changes.any { it.pressed }) {
                        val pressed = event.changes.filter { it.pressed }
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        val target = pdfZoomTarget(scale, zoomChange)
                        val moved = if (target > 1f) {
                            clampPdfOffset(offset + panChange, target, viewport)
                        } else {
                            Offset.Zero
                        }
                        val spendsDrag = pressed.size >= 2 ||
                            (target > 1f && (zoomChange != 1f || moved != offset))
                        if (spendsDrag && pressed.any { !it.isConsumed }) {
                            scale = target
                            offset = moved
                            event.changes.forEach { it.consume() }
                        }
                        event = awaitPointerEvent()
                    }
                }
            }
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentPadding = WindowInsets.systemBars
                .asPaddingValues()
                .add(top = PreviewTopBarHeight + 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(count = renderer.pageCount, key = { it }) { pageIndex ->
                PdfPage(
                    renderer = renderer,
                    renderMutex = renderMutex,
                    pageIndex = pageIndex,
                    onJumpToPage = { target ->
                        scope.launch { listState.animateScrollToItem(target) }
                    }
                )
            }
        }
    }
}

@Composable
private fun PdfPage(
    renderer: PdfRenderer,
    renderMutex: Mutex,
    pageIndex: Int,
    onJumpToPage: (Int) -> Unit
) {
    val uriHandler = LocalUriHandler.current
    val rendered by produceState<RenderedPage?>(initialValue = null, renderer, pageIndex) {
        value = withContext(Dispatchers.IO) {
            renderMutex.withLock {
                runCatching {
                    renderer.openPage(pageIndex).use { page ->
                        val scale = TARGET_WIDTH.toFloat() / page.width
                        val bitmap = createBitmap(
                            TARGET_WIDTH,
                            (page.height * scale).toInt().coerceAtLeast(1)
                        )
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        RenderedPage(bitmap, linksOf(page))
                    }
                }.getOrNull()
            }
        }
    }

    val page = rendered
    if (page == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(PLACEHOLDER_RATIO)
        )
        return
    }

    val ratio = page.bitmap.width.toFloat() / page.bitmap.height
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(ratio)
    ) {
        Image(
            bitmap = page.bitmap.asImageBitmap(),
            contentDescription = stringResource(Res.string.preview_page_number, pageIndex + 1),
            modifier = Modifier.fillMaxSize()
        )
        page.links.forEach { link ->
            val linkLabel = link.uri
                ?: link.targetPage?.let { stringResource(Res.string.preview_page_number, it + 1) }
            Box(
                modifier = Modifier
                    .offset(x = maxWidth * link.left, y = maxHeight * link.top)
                    .size(
                        width = maxWidth * (link.right - link.left),
                        height = maxHeight * (link.bottom - link.top)
                    )
                    .clickable(role = Role.Button) {
                        link.uri?.let { uriHandler.openUri(it) }
                            ?: link.targetPage?.let(onJumpToPage)
                    }
                    .semantics { linkLabel?.let { contentDescription = it } }
            )
        }
    }
}

/**
 * The platform only exposes link rectangles from Android 15; older releases render without them
 * rather than ship a parser.
 */
private fun linksOf(page: PdfRenderer.Page): List<PdfLink> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return emptyList()
    val width = page.width.toFloat()
    val height = page.height.toFloat()
    if (width <= 0f || height <= 0f) return emptyList()

    return runCatching {
        val external = page.linkContents.flatMap { content ->
            content.bounds.map { bounds -> bounds to content.uri.toString() }
        }.map { (bounds, uri) -> bounds.toLink(width, height, uri = uri) }

        val internal = page.gotoLinks.flatMap { content ->
            content.bounds.map { bounds -> bounds to content.destination.pageNumber }
        }.map { (bounds, target) -> bounds.toLink(width, height, targetPage = target) }

        external + internal
    }.getOrDefault(emptyList())
}

private fun RectF.toLink(
    pageWidth: Float,
    pageHeight: Float,
    uri: String? = null,
    targetPage: Int? = null
): PdfLink = PdfLink(
    left = (left / pageWidth).coerceIn(0f, 1f),
    top = (top / pageHeight).coerceIn(0f, 1f),
    right = (right / pageWidth).coerceIn(0f, 1f),
    bottom = (bottom / pageHeight).coerceIn(0f, 1f),
    uri = uri,
    targetPage = targetPage
)

private const val TARGET_WIDTH = 1080
private const val PLACEHOLDER_RATIO = 0.7f

/** The scale one pinch step lands on, clamped to the viewer's range. */
internal fun pdfZoomTarget(currentScale: Float, zoomChange: Float): Float =
    (currentScale * zoomChange).coerceIn(1f, MAX_SCALE)

internal fun clampPdfOffset(target: Offset, scale: Float, viewport: IntSize): Offset {
    // At (or below) 1x the layer cannot pan at all — return exact zero
    // (a computed -0.0f bound would also fail Offset equality).
    if (scale <= 1f) return Offset.Zero
    val maxX = viewport.width * (scale - 1f) / 2f
    val maxY = viewport.height * (scale - 1f) / 2f
    return Offset(
        x = target.x.coerceIn(-maxX, maxX),
        y = target.y.coerceIn(-maxY, maxY)
    )
}

private const val MAX_SCALE = 6f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_ANIMATION_MS = 250
