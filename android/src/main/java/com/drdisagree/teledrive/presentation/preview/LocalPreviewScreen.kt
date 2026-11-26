package com.drdisagree.teledrive.presentation.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.TelegramDataSourceFactory
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.ErrorState
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.preview_no_preview
import com.drdisagree.teledrive.resources.preview_no_preview_for_type
import com.drdisagree.teledrive.resources.preview_open_with
import com.drdisagree.teledrive.resources.preview_open_with_failed
import com.drdisagree.teledrive.resources.preview_truncated_download_view
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * vc181: the in-app viewer for a saved local file that has no drive file row
 * (a completed Transfers archive row). Deliberately thin — no pager, no
 * overflow menu, no DB actions — it resolves the path through
 * [PreviewContentResolver.resolveLocalPath] and renders exactly the content
 * kinds that resolver can produce for a local file, reusing the Files
 * viewer's own components. An archive saved as the file itself is NOT browsed
 * here (that needs the preview ViewModel's extraction plumbing); it falls
 * back to Unsupported with "Open with…", as do unknown types.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalPreviewScreen(
    localPath: String,
    displayName: String,
    onBack: () -> Unit
) {
    val resolver = koinInject<PreviewContentResolver>()
    val dataSourceFactory = koinInject<TelegramDataSourceFactory>()
    val context = LocalContext.current
    val playerSession = remember {
        PlayerSession().apply {
            subtitleAppearance = SubtitleAppearanceStore.load(context)
        }
    }
    val content by remember(localPath, displayName) {
        resolver.resolveLocalPath(localPath, displayName)
    }.collectAsStateWithLifecycle(initialValue = PreviewContent.Loading)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val current = content) {
                is PreviewContent.Loading,
                is PreviewContent.DownloadProgress -> LoadingState()

                is PreviewContent.Image -> ZoomableImage(
                    model = current.model,
                    contentDescription = displayName,
                    modifier = Modifier.fillMaxSize()
                )

                is PreviewContent.LocalMedia -> MediaPlayer(
                    content = current,
                    title = displayName,
                    fileId = "local-$displayName",
                    dataSourceFactory = dataSourceFactory,
                    isActivePage = true,
                    session = playerSession
                )

                is PreviewContent.Pdf -> PdfPreview(path = current.path)

                is PreviewContent.PlainText -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp)
                ) {
                    Text(
                        text = current.text,
                        style = MaterialTheme.typography.bodySmall
                            .copy(fontFamily = FontFamily.Monospace)
                            .copy(color = MaterialTheme.colorScheme.onSurface)
                    )
                    if (current.truncated) {
                        Text(
                            text = stringResource(Res.string.preview_truncated_download_view),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is PreviewContent.Unsupported -> LocalUnsupported(
                    localPath = current.localPath,
                    displayName = displayName,
                    reason = stringResource(current.reasonRes)
                )

                // Stated limitation: the archive browser is not wired into
                // this screen, so an archive as the saved file itself gets
                // the same Unsupported + "Open with…" fallback.
                is PreviewContent.Archive -> LocalUnsupported(
                    localPath = localPath,
                    displayName = displayName,
                    reason = stringResource(Res.string.preview_no_preview_for_type)
                )

                // Neither can be produced by resolveLocalPath; render them
                // as Unsupported rather than crashing if that ever changes.
                is PreviewContent.StreamedMedia,
                is PreviewContent.RequiresDownload -> LocalUnsupported(
                    localPath = localPath,
                    displayName = displayName,
                    reason = stringResource(Res.string.preview_no_preview_for_type)
                )

                is PreviewContent.Failed -> ErrorState(
                    message = current.messageText ?: stringResource(current.messageRes)
                )
            }
        }
    }
}

@Composable
private fun LocalUnsupported(
    localPath: String?,
    displayName: String,
    reason: String
) {
    val context = LocalContext.current
    val openWithTitle = stringResource(Res.string.preview_open_with)
    val openFailedMessage = stringResource(Res.string.preview_open_with_failed)
    EmptyState(
        icon = Icons.Outlined.Description,
        title = stringResource(Res.string.preview_no_preview),
        description = reason,
        actionLabel = if (localPath != null) openWithTitle else null,
        onAction = if (localPath != null) {
            {
                openFileWith(
                    context = context,
                    displayName = displayName,
                    localPath = localPath,
                    mimeType = MimeTypes.fromFileName(displayName),
                    chooserTitle = openWithTitle,
                    failureMessage = openFailedMessage
                )
            }
        } else {
            null
        }
    )
}
