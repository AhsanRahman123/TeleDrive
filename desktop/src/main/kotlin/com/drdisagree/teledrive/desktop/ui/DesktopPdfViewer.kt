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
import java.io.File
import kotlinx.coroutines.Dispatchers
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
    var state by remember(path) { mutableStateOf<PdfLoadState>(PdfLoadState.Loading) }
    LaunchedEffect(path) {
        state = withContext(Dispatchers.IO) {
            runCatching { Loader.loadPDF(File(path)) }
                .fold(
                    onSuccess = { PdfLoadState.Ready(it) },
                    onFailure = { PdfLoadState.Failed }
                )
        }
    }
    DisposableEffect(path) {
        onDispose {
            (state as? PdfLoadState.Ready)?.let { runCatching { it.document.close() } }
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
