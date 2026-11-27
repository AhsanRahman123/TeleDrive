package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.ErrorState
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_page_number
import com.drdisagree.teledrive.resources.preview_pdf_open_failed
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.jetbrains.compose.resources.stringResource

/** Load state of [DesktopPdfViewer]: a failed open is distinct from still loading. */
private sealed interface PdfLoadState {
    data object Loading : PdfLoadState
    data object Failed : PdfLoadState
    data class Ready(val document: PDDocument) : PdfLoadState
}

/** Outcome of one [PdfDocumentSession.load] generation. */
internal sealed interface PdfLoadResult<out T : Closeable> {
    /** The load is the current generation; the session now owns [document]. */
    data class Loaded<T : Closeable>(val document: T) : PdfLoadResult<T>

    /** The loader failed while this generation was still current. */
    data object Failed : PdfLoadResult<Nothing>

    /**
     * A newer load started (or the session was closed) before this load
     * finished. A document produced by the stale load has already been
     * closed by the session; the caller must publish nothing for it.
     */
    data object Stale : PdfLoadResult<Nothing>
}

/**
 * Owns the document produced by a blocking PDF load end-to-end
 * (1.6.25 second-pass MF-3).
 *
 * [Loader.loadPDF] is blocking and cannot be interrupted by coroutine
 * cancellation, so tying the document's close to whatever a snapshot
 * state happened to hold at dispose time leaked every load that
 * finished after its viewer left (or after the path changed A→B), and
 * let a late A load publish A's document over B's state. Here instead:
 * every [load] takes a generation token, the blocking load runs under
 * [NonCancellable] so its result is always received by this session
 * (never discarded unseen by a cancelled continuation), and a result
 * whose generation is no longer current is closed immediately and
 * reported [PdfLoadResult.Stale]. The session owns the one current
 * document and closes it when a newer load publishes over it or when
 * [close] runs — each produced document is closed exactly here, exactly
 * once.
 */
internal class PdfDocumentSession<T : Closeable>(private val loader: (String) -> T) {
    private val lock = Any()
    private var generation = 0
    private var owned: T? = null

    suspend fun load(path: String): PdfLoadResult<T> {
        val token = synchronized(lock) { ++generation }
        val document = try {
            withContext(NonCancellable + Dispatchers.IO) { loader(path) }
        } catch (t: Throwable) {
            return if (isCurrent(token)) PdfLoadResult.Failed else PdfLoadResult.Stale
        }
        return if (publish(token, document)) {
            PdfLoadResult.Loaded(document)
        } else {
            PdfLoadResult.Stale
        }
    }

    private fun isCurrent(token: Int): Boolean = synchronized(lock) { token == generation }

    /** Takes ownership of [document] iff [token] is still the current generation. */
    private fun publish(token: Int, document: T): Boolean = synchronized(lock) {
        if (token != generation) {
            runCatching { document.close() }
            false
        } else {
            owned?.let { previous -> runCatching { previous.close() } }
            owned = document
            true
        }
    }

    /** Closes the owned document and makes every in-flight load stale. */
    fun close() {
        synchronized(lock) {
            generation++
            owned?.let { runCatching { it.close() } }
            owned = null
        }
    }
}

/**
 * The in-app PDF viewer (1.6.25 item 5): Apache PDFBox renders each page on
 * demand as its row composes — no eager whole-document rasterisation — in a
 * vertical page list with the same chrome as the other desktop viewers.
 * PDFBox is the desktop renderer because Android's PdfRenderer has no JVM
 * equivalent; the shared layer only ever hands this viewer a real local
 * file (a local copy, a preview-cache fetch within the 50 MiB cap, or a
 * verified in-zip temp extraction), exactly as on Android.
 */
@Composable
fun DesktopPdfViewer(path: String, modifier: Modifier = Modifier) {
    // The session owns every document it loads for the viewer's whole
    // lifetime: it closes the current document when the viewer leaves,
    // and closes (without ever publishing) any load that finishes after
    // its path has been superseded (MF-3).
    val session = remember {
        PdfDocumentSession<PDDocument> { loadPath -> Loader.loadPDF(File(loadPath)) }
    }
    DisposableEffect(session) {
        onDispose { session.close() }
    }
    var state by remember(path) { mutableStateOf<PdfLoadState>(PdfLoadState.Loading) }
    LaunchedEffect(path) {
        state = PdfLoadState.Loading
        when (val result = session.load(path)) {
            is PdfLoadResult.Loaded -> state = PdfLoadState.Ready(result.document)
            PdfLoadResult.Failed -> state = PdfLoadState.Failed
            // Superseded (or the viewer is gone): publish nothing — the
            // newer generation owns the state, and the session already
            // closed this load's document if it produced one.
            PdfLoadResult.Stale -> Unit
        }
    }
    when (val current = state) {
        PdfLoadState.Loading -> LoadingState(modifier)
        PdfLoadState.Failed -> ErrorState(
            message = stringResource(Res.string.preview_pdf_open_failed),
            modifier = modifier
        )
        is PdfLoadState.Ready -> DesktopPdfPages(document = current.document, modifier = modifier)
    }
}

@Composable
private fun DesktopPdfPages(document: PDDocument, modifier: Modifier = Modifier) {
    val renderer = remember(document) { PDFRenderer(document) }
    val listState = rememberLazyListState()
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 10.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp)
        ) {
            items(document.numberOfPages, key = { it }) { pageIndex ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    DesktopPdfPage(document = document, renderer = renderer, pageIndex = pageIndex)
                    Text(
                        text = stringResource(Res.string.preview_page_number, pageIndex + 1),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
        DesktopVerticalScrollbar(
            state = listState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

/** One page, rendered on demand at a fixed readable DPI when its row composes. */
@Composable
private fun DesktopPdfPage(document: PDDocument, renderer: PDFRenderer, pageIndex: Int) {
    var bitmap by remember(pageIndex) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(pageIndex) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                // PDFRenderer is not thread-safe: all rendering is serialised
                // on the document, like Android's PdfPreview mutex.
                synchronized(document) {
                    renderer.renderImageWithDPI(pageIndex, PDF_RENDER_DPI, ImageType.RGB)
                        .toComposeImageBitmap()
                }
            }.getOrNull()
        }
    }
    val pageBitmap = bitmap
    if (pageBitmap == null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = 48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(Res.string.preview_page_number, pageIndex + 1),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        Image(
            bitmap = pageBitmap,
            contentDescription = stringResource(Res.string.preview_page_number, pageIndex + 1),
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
        )
    }
}

private const val PDF_RENDER_DPI = 150f
