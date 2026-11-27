package com.drdisagree.teledrive.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.desktop.media.player.DesktopMediaPlayer
import com.drdisagree.teledrive.desktop.media.player.VlcPlayback
import com.drdisagree.teledrive.presentation.common.AppBackHandler
import com.drdisagree.teledrive.presentation.common.LinkedText
import com.drdisagree.teledrive.presentation.common.MarkdownText
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.ErrorState
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.presentation.components.TooltipIconButton
import com.drdisagree.teledrive.presentation.platform.LocalFileOpener
import com.drdisagree.teledrive.presentation.platform.LocalUrlOpener
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.preview_no_preview
import com.drdisagree.teledrive.resources.preview_open_with
import com.drdisagree.teledrive.resources.preview_truncated_download_view
import java.io.File
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

/**
 * PC 1.6.26 P6: the desktop in-app viewer for a saved local file that has
 * no drive file row (a completed Transfers archive row — the row menu's
 * "Open in Pranto Drive", mirroring Android's LocalPreviewScreen). Thin by
 * design — no pager, no overflow menu, no DB actions: it resolves the path
 * through [PreviewContentResolver.resolveLocalPath] and renders the
 * content kinds that resolver produces for a local file with the desktop
 * viewer's own renderers. An archive saved as the file itself is NOT
 * browsed here (that needs the preview ViewModel's extraction plumbing);
 * it falls back to "Open with…", as do unknown types — exactly Android's
 * behaviour.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopLocalPreviewScreen(
    localPath: String,
    displayName: String,
    onBack: () -> Unit
) {
    val resolver = koinInject<PreviewContentResolver>()
    val fileOpener = LocalFileOpener.current
    val urlOpener = LocalUrlOpener.current
    val snackbarHostState = remember { SnackbarHostState() }
    val content by remember(localPath, displayName) {
        resolver.resolveLocalPath(localPath, displayName)
    }.collectAsState(initial = PreviewContent.Loading)

    // P2: Esc is a back key on every screen, this one included.
    AppBackHandler { onBack() }

    // PC 1.6.27 N5: the header hides and returns together with the media
    // player's controls, as in the main preview. Before this, the player
    // here was composed without its onControlsVisibilityChange callback
    // and the Scaffold top bar was unconditional, so hiding the player
    // UI left this header floating over the video. Only the player
    // reports here, so every non-media content keeps the header.
    var playerChromeVisible by remember { mutableStateOf(true) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            AnimatedVisibility(
                visible = playerChromeVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
            TopAppBar(
                title = {
                    Text(
                        text = displayName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    TooltipIconButton(
                        tooltip = stringResource(Res.string.common_back),
                        onClick = onBack
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                }
            )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (val current = content) {
                is PreviewContent.Loading -> LoadingState()

                is PreviewContent.Image -> ZoomablePreviewImage(
                    model = current.model,
                    contentDescription = displayName,
                    imageRotationDegrees = 0,
                    mirrorImageHorizontally = false,
                    mirrorImageVertically = false,
                    onPrevious = {},
                    onNext = {},
                    onToggleChrome = {},
                    undecodableFallback = {
                        EmptyState(
                            icon = Icons.Filled.BrokenImage,
                            title = stringResource(Res.string.preview_no_preview)
                        )
                    }
                )

                is PreviewContent.LocalMedia -> if (VlcPlayback.available) {
                    DesktopMediaPlayer(
                        mrl = current.path,
                        isAudio = current.isAudio,
                        mediaId = "local-preview-$localPath",
                        mediaTitle = displayName,
                        onControlsVisibilityChange = { playerChromeVisible = it }
                    )
                } else {
                    OpenWithState(
                        icon = if (current.isAudio) Icons.Filled.Audiotrack else Icons.Filled.Movie,
                        path = current.path,
                        displayName = displayName,
                        fileOpener = fileOpener
                    )
                }

                is PreviewContent.Pdf -> DesktopPdfViewer(path = current.path)

                is PreviewContent.Html -> DesktopHtmlViewer(
                    html = current.text,
                    truncated = current.truncated
                )

                is PreviewContent.PlainText -> {
                    val scrollState = rememberScrollState()
                    Box(modifier = Modifier.fillMaxSize()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(end = 10.dp)
                                .padding(24.dp)
                        ) {
                            if (MimeTypes.isMarkdown(MimeTypes.fromFileName(displayName))) {
                                MarkdownText(
                                    text = current.text,
                                    onOpenUrl = { url -> urlOpener.open(url) }
                                )
                            } else {
                                LinkedText(
                                    text = current.text,
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace)
                                        .copy(color = MaterialTheme.colorScheme.onSurface),
                                    linkColor = MaterialTheme.colorScheme.primary,
                                    onOpenUrl = { url -> urlOpener.open(url) }
                                )
                            }
                            if (current.truncated) {
                                Text(
                                    text = stringResource(
                                        Res.string.preview_truncated_download_view
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        DesktopVerticalScrollbar(
                            state = scrollState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }

                // Archives are not browsed from a Transfers row (Android
                // parity): the saved file itself opens externally.
                is PreviewContent.Archive -> OpenWithState(
                    icon = Icons.Outlined.Description,
                    path = localPath,
                    displayName = displayName,
                    fileOpener = fileOpener
                )

                is PreviewContent.Unsupported -> OpenWithState(
                    icon = Icons.Outlined.Description,
                    path = current.localPath ?: localPath,
                    displayName = displayName,
                    fileOpener = fileOpener
                )

                is PreviewContent.Failed ->
                    ErrorState(message = stringResource(current.messageRes))

                // resolveLocalPath never produces these; they are listed so
                // the when stays exhaustive over the sealed type.
                is PreviewContent.DownloadProgress -> LoadingState()
                is PreviewContent.StreamedMedia -> OpenWithState(
                    icon = Icons.Filled.Movie,
                    path = localPath,
                    displayName = displayName,
                    fileOpener = fileOpener
                )
                is PreviewContent.RequiresDownload -> OpenWithState(
                    icon = Icons.Outlined.Description,
                    path = localPath,
                    displayName = displayName,
                    fileOpener = fileOpener
                )
            }
        }
    }
}

/** The "no in-app renderer for this file" state: hand it to "Open with…". */
@Composable
private fun OpenWithState(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    path: String,
    displayName: String,
    fileOpener: com.drdisagree.teledrive.presentation.platform.FileOpener?
) {
    EmptyState(
        icon = icon,
        title = stringResource(Res.string.preview_no_preview),
        actionLabel = stringResource(Res.string.preview_open_with),
        onAction = {
            if (File(path).isFile) fileOpener?.openWith(path, displayName, null)
        }
    )
}
