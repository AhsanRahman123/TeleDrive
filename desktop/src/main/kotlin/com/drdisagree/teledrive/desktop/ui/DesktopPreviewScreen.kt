package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.rememberLazyListState
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import com.drdisagree.teledrive.presentation.components.TooltipAnchor
import com.drdisagree.teledrive.presentation.components.TooltipIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.SnapSpec
import coil3.compose.rememberAsyncImagePainter
import coil3.compose.AsyncImagePainter
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.PartedMediaByteSource
import com.drdisagree.teledrive.core.media.TelegramMediaByteSource
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.desktop.media.ExternalMediaPlayer
import com.drdisagree.teledrive.desktop.media.MediaStreamServer
import com.drdisagree.teledrive.desktop.media.player.DesktopMediaPlayer
import com.drdisagree.teledrive.desktop.media.player.PlayerQueueItem
import com.drdisagree.teledrive.desktop.media.player.VlcPlayback
import com.drdisagree.teledrive.desktop.resources.preview_open_externally
import com.drdisagree.teledrive.desktop.resources.preview_open_failed
import com.drdisagree.teledrive.desktop.resources.preview_stream
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.common.LinkedText
import com.drdisagree.teledrive.presentation.common.MarkdownText
import com.drdisagree.teledrive.presentation.common.load
import com.drdisagree.teledrive.presentation.components.ConfirmDialog
import com.drdisagree.teledrive.presentation.components.SelectionCheckbox
import com.drdisagree.teledrive.presentation.components.CollectionShortcuts
import com.drdisagree.teledrive.presentation.components.RegisterWindowSelectAll
import com.drdisagree.teledrive.presentation.components.TextInputFocusTracker
import com.drdisagree.teledrive.presentation.components.collectionCommandShortcuts
import com.drdisagree.teledrive.presentation.components.ctrlClickSelect
import com.drdisagree.teledrive.presentation.components.keyboardCollection
import com.drdisagree.teledrive.presentation.components.keyboardCollectionItem
import com.drdisagree.teledrive.presentation.components.rememberCollectionCommandFocusRequester
import com.drdisagree.teledrive.presentation.components.rememberKeyboardCollection
import com.drdisagree.teledrive.presentation.components.trackTextInputFocus
import com.drdisagree.teledrive.presentation.components.SortMenu
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.ErrorState
import com.drdisagree.teledrive.presentation.components.FileInfoSheet
import com.drdisagree.teledrive.presentation.files.FolderPickerHost
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.presentation.components.RenameDialog
import com.drdisagree.teledrive.presentation.platform.LocalFileRevealer
import com.drdisagree.teledrive.presentation.platform.LocalUrlOpener
import com.drdisagree.teledrive.presentation.preview.ArchiveBatchProgress
import com.drdisagree.teledrive.presentation.preview.ArchiveEntryPreview
import com.drdisagree.teledrive.presentation.preview.ArchiveRowState
import com.drdisagree.teledrive.presentation.preview.allFolderPaths
import com.drdisagree.teledrive.presentation.preview.applyArchiveDragRange
import com.drdisagree.teledrive.presentation.preview.archiveDragAnchorSelected
import com.drdisagree.teledrive.presentation.preview.archiveFilterIndices
import com.drdisagree.teledrive.presentation.preview.archiveRowKey
import com.drdisagree.teledrive.presentation.preview.buildArchiveTreeIndexed
import com.drdisagree.teledrive.presentation.preview.flattenArchive
import com.drdisagree.teledrive.presentation.preview.IndexedArchiveEntry
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_actions
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.common_download
import com.drdisagree.teledrive.resources.note_edit_action
import com.drdisagree.teledrive.resources.files_copy_here
import com.drdisagree.teledrive.resources.files_copy_to
import com.drdisagree.teledrive.resources.files_move_here
import com.drdisagree.teledrive.resources.files_move_to
import com.drdisagree.teledrive.resources.common_free_space
import com.drdisagree.teledrive.resources.common_move_trash
import com.drdisagree.teledrive.resources.common_rename
import com.drdisagree.teledrive.resources.common_rename_file
import com.drdisagree.teledrive.resources.common_upload
import com.drdisagree.teledrive.resources.files_hide
import com.drdisagree.teledrive.resources.files_show_in_file_manager
import com.drdisagree.teledrive.resources.files_unhide
import com.drdisagree.teledrive.resources.preview_add_favorites
import com.drdisagree.teledrive.resources.preview_archive
import com.drdisagree.teledrive.resources.preview_confirm_trash_file_message
import com.drdisagree.teledrive.resources.preview_download_view
import com.drdisagree.teledrive.resources.preview_file_info
import com.drdisagree.teledrive.resources.preview_image_undecodable
import com.drdisagree.teledrive.resources.preview_move_to_trash
import com.drdisagree.teledrive.resources.preview_next
import com.drdisagree.teledrive.resources.preview_no_preview
import com.drdisagree.teledrive.resources.preview_preparing
import com.drdisagree.teledrive.resources.preview_previous
import com.drdisagree.teledrive.resources.preview_progress_bytes
import com.drdisagree.teledrive.resources.preview_remove_favorites
import com.drdisagree.teledrive.resources.preview_requires_download
import com.drdisagree.teledrive.resources.preview_truncated_download_view
import com.drdisagree.teledrive.resources.preview_unarchive
import com.drdisagree.teledrive.resources.preview_zip_download_selected
import com.drdisagree.teledrive.resources.preview_zip_search_entries
import com.drdisagree.teledrive.resources.preview_archive_remote_hint
import com.drdisagree.teledrive.resources.preview_extraction_supported_yet_download
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel
import me.saket.telephoto.ExperimentalTelephotoApi
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable
import java.awt.Desktop
import java.io.File
import com.drdisagree.teledrive.desktop.resources.Res as DesktopRes

/**
 * Media never plays in the window; anything the shared resolver cannot render inline opens in the
 * system viewer.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalComposeUiApi::class)
@Composable
fun DesktopPreviewScreen(
    onBack: () -> Unit,
    onEditNote: (String, String) -> Unit = { _, _ -> },
    viewModel: PreviewViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val fullscreenController = LocalFullscreenController.current
    val streamServer = koinInject<MediaStreamServer>()

    // Leaving the preview ends any streamed playback: stop the stream server so
    // the active stream's byte source is closed and its TDLib ranged download
    // does not run on in the background. Paging between files keeps this
    // composable alive, so in-preview navigation is unaffected.
    DisposableEffect(Unit) {
        onDispose { streamServer.stop() }
    }

    fun leavePreview() {
        // Back navigates to the previous screen; fullscreen is a window-level preference
        // and must not be silently exited as a side effect of navigation.
        onBack()
    }

    LaunchedEffect(state.closed) {
        if (state.closed) leavePreview()
    }

    var index by remember(state.files, state.initialIndex) {
        mutableStateOf(state.initialIndex.coerceIn(0, (state.files.size - 1).coerceAtLeast(0)))
    }
    val file = state.files.getOrNull(index.coerceIn(0, (state.files.size - 1).coerceAtLeast(0)))

    val displayIndex = if (state.files.isEmpty()) 0 else index.coerceIn(0, state.files.lastIndex)
    var previewFolderName by remember { mutableStateOf("Files") }
    LaunchedEffect(file?.id, file?.folderId) {
        previewFolderName = file?.let { viewModel.parentFolderName(it) } ?: "Files"
    }
    var imageRotationDegrees by remember(file?.id) { mutableIntStateOf(0) }
    var mirrorImageHorizontally by remember(file?.id) { mutableStateOf(false) }
    var mirrorImageVertically by remember(file?.id) { mutableStateOf(false) }
    val mediaCategory = file?.category?.takeIf {
        it == FileCategory.AUDIO || it == FileCategory.VIDEO
    }
    val queueFiles = remember(state.files, mediaCategory) {
        if (mediaCategory == null) emptyList()
        else state.files.filter { it.category == mediaCategory }
    }
    val queueItems = remember(queueFiles) { queueFiles.map { PlayerQueueItem(it.id, it.name) } }
    val queueIndex = queueFiles.indexOfFirst { it.id == file?.id }

    var repeatAll by remember(state.files, mediaCategory) { mutableStateOf(false) }
    var shuffle by remember(state.files, mediaCategory) { mutableStateOf(false) }
    var shuffleHistory by remember(state.files, mediaCategory) { mutableStateOf(emptyList<String>()) }
    var shuffleCursor by remember(state.files, mediaCategory) { mutableIntStateOf(-1) }

    LaunchedEffect(shuffle, file?.id, queueFiles) {
        if (!shuffle || file == null || queueFiles.isEmpty()) return@LaunchedEffect
        val existing = shuffleHistory.indexOf(file.id)
        if (existing >= 0) {
            shuffleCursor = existing
        } else {
            val retained = shuffleHistory.take(shuffleCursor + 1)
            shuffleHistory = retained + file.id
            shuffleCursor = shuffleHistory.lastIndex
        }
    }

    fun selectQueueFile(fileId: String) {
        val target = state.files.indexOfFirst { it.id == fileId }
        if (target >= 0 && target != index) index = target
    }

    fun previousMedia() {
        if (queueFiles.size <= 1 || queueIndex < 0) return
        if (shuffle) {
            if (shuffleCursor > 0) {
                val previousId = shuffleHistory[shuffleCursor - 1]
                shuffleCursor--
                selectQueueFile(previousId)
            } else if (repeatAll) {
                val currentId = file?.id ?: return
                val candidates = queueFiles.filter { it.id != currentId }
                val next = candidates.randomOrNull() ?: return
                shuffleHistory = listOf(next.id, currentId)
                shuffleCursor = 0
                selectQueueFile(next.id)
            }
        } else if (queueIndex > 0) {
            selectQueueFile(queueFiles[queueIndex - 1].id)
        } else if (repeatAll) {
            selectQueueFile(queueFiles.last().id)
        }
    }

    fun nextMedia() {
        if (queueFiles.size <= 1 || queueIndex < 0) return
        if (shuffle) {
            if (shuffleCursor < shuffleHistory.lastIndex) {
                shuffleCursor++
                selectQueueFile(shuffleHistory[shuffleCursor])
                return
            }
            val played = shuffleHistory.toSet()
            val candidates = queueFiles.filter { it.id !in played }
                .ifEmpty {
                    if (repeatAll) queueFiles.filter { it.id != file?.id } else emptyList()
                }
            val next = candidates.randomOrNull() ?: return
            if (next.id in played) {
                shuffleHistory = listOf(file!!.id, next.id)
                shuffleCursor = 1
            } else {
                shuffleHistory = shuffleHistory.take(shuffleCursor + 1) + next.id
                shuffleCursor = shuffleHistory.lastIndex
            }
            selectQueueFile(next.id)
        } else if (queueIndex < queueFiles.lastIndex) {
            selectQueueFile(queueFiles[queueIndex + 1].id)
        } else if (repeatAll) {
            selectQueueFile(queueFiles.first().id)
        }
    }

    fun toggleShuffle() {
        shuffle = !shuffle
        shuffleHistory = listOfNotNull(file?.id)
        shuffleCursor = shuffleHistory.lastIndex
    }

    var sleepDeadline by remember(state.files) { mutableStateOf<Long?>(null) }
    var sleepAtTrackEnd by remember(state.files) { mutableStateOf(false) }
    var sleepClock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sleepStopRequest by remember(state.files) { mutableIntStateOf(0) }

    LaunchedEffect(sleepDeadline) {
        val deadline = sleepDeadline ?: return@LaunchedEffect
        while (true) {
            val now = System.currentTimeMillis()
            sleepClock = now
            val remaining = deadline - now
            if (remaining <= 0L) {
                sleepDeadline = null
                sleepAtTrackEnd = false
                sleepStopRequest++
                break
            }
            delay(remaining.coerceAtMost(1_000L))
        }
    }

    fun handlePlaybackFinished() {
        if (sleepAtTrackEnd) {
            sleepAtTrackEnd = false
            sleepDeadline = null
            return
        }
        if (repeatAll || shuffle) nextMedia()
    }

    val sleepTimerLabel = when {
        sleepAtTrackEnd -> "End of current track"
        sleepDeadline != null -> {
            val remaining = (sleepDeadline!! - sleepClock).coerceAtLeast(0L) / 1_000L
            val hours = remaining / 3_600L
            val minutes = (remaining % 3_600L) / 60L
            val seconds = remaining % 60L
            if (hours > 0L) "Sleep · ${hours}h ${minutes}m"
            else "Sleep · ${minutes}m ${seconds}s"
        }
        else -> "Sleep timer"
    }

    val infoTarget by viewModel.infoTarget.collectAsState()
    var showOverflow by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<DriveFile?>(null) }
    var trashTarget by remember { mutableStateOf<DriveFile?>(null) }
    var copyTarget by remember { mutableStateOf<DriveFile?>(null) }
    var moveTarget by remember { mutableStateOf<DriveFile?>(null) }
    var playerChromeVisible by remember { mutableStateOf(true) }
    // Keep the image-toolbar preference for the whole preview session, including when
    // moving between image files. H restores hidden controls; the visible toolbar icon hides them.
    var imageChromeVisible by remember { mutableStateOf(true) }
    val previewChromeVisible = when {
        mediaCategory != null -> playerChromeVisible
        file?.category == FileCategory.IMAGE -> imageChromeVisible
        else -> true
    }
    val viewerFocusRequester = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    var imageHKeyPressed by remember { mutableStateOf(false) }
    LaunchedEffect(file?.id, mediaCategory) {
        // Images/documents use this preview shortcut target. The VLC surface owns focus
        // for audio/video and must not have its keyboard controls stolen by the parent.
        if (mediaCategory == null) viewerFocusRequester.requestFocus()
    }
    LaunchedEffect(windowInfo.isWindowFocused, mediaCategory) {
        if (!windowInfo.isWindowFocused) {
            imageHKeyPressed = false
        } else if (mediaCategory == null) {
            viewerFocusRequester.requestFocus()
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    CollectSnackbarMessages(viewModel.messages, snackbarHostState)

    Scaffold(
        modifier = Modifier
            .focusRequester(viewerFocusRequester)
            .onPreviewKeyEvent { event ->
                // Match the video player's key lifecycle: consume H key-up to reset the
                // repeat guard, and toggle only once for a physical key press.
                if (event.key == Key.H && event.type == KeyEventType.KeyUp) {
                    imageHKeyPressed = false
                    return@onPreviewKeyEvent file?.category == FileCategory.IMAGE
                }
                if (event.type != KeyEventType.KeyDown ||
                    event.isCtrlPressed || event.isAltPressed || event.isShiftPressed
                ) {
                    return@onPreviewKeyEvent false
                }
                when {
                    // Video keeps ownership of its own H toggle; this avoids two toggles
                    // fighting when the preview and VLC surface are both in the key path.
                    event.key == Key.H && file?.category == FileCategory.IMAGE -> {
                        if (!imageHKeyPressed) {
                            imageHKeyPressed = true
                            imageChromeVisible = !imageChromeVisible
                        }
                        true
                    }
                    event.key == Key.Delete && file != null && mediaCategory == null -> {
                        if (trashTarget == null) trashTarget = file
                        true
                    }
                    event.key == Key.W -> {
                        if (index > 0) index--
                        true
                    }
                    event.key == Key.E -> {
                        if (index < state.files.lastIndex) index++
                        true
                    }
                    else -> false
                }
            }
            .focusable()
            .pointerInput(viewerFocusRequester) {
                // A toolbar button can lose focus when H removes its own row. Reclaim the
                // stable preview target after pointer dispatch so H/W/E remain available.
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        if (
                            event.type == PointerEventType.Press &&
                            mediaCategory == null
                        ) {
                            viewerFocusRequester.requestFocus()
                        }
                    }
                }
            },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        @Composable
        fun PreviewTopBar() {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = file?.name.orEmpty(),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (file != null && state.files.isNotEmpty()) {
                            Text(
                                text = "$previewFolderName · ${displayIndex + 1}/${state.files.size}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    TooltipIconButton(tooltip = stringResource(Res.string.common_back), onClick = ::leavePreview) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                },
                actions = {
                    file?.let { currentFile ->
                        if (currentFile.category == FileCategory.IMAGE) {
                            TooltipIconButton(
                                tooltip = "Toggle image controls",
                                onClick = { imageChromeVisible = !imageChromeVisible }
                            ) {
                                Icon(
                                    Icons.Filled.VisibilityOff,
                                    contentDescription = "Toggle image controls"
                                )
                            }
                            TooltipIconButton(
                                tooltip = "Rotate counter-clockwise",
                                onClick = {
                                    imageRotationDegrees = (imageRotationDegrees + 270) % 360
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Rotate90DegreesCcw,
                                    contentDescription = "Rotate counter-clockwise"
                                )
                            }
                            TooltipIconButton(
                                tooltip = "Rotate clockwise",
                                onClick = {
                                    imageRotationDegrees = (imageRotationDegrees + 90) % 360
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Rotate90DegreesCw,
                                    contentDescription = "Rotate clockwise"
                                )
                            }
                            TooltipIconButton(
                                tooltip = "Mirror horizontally",
                                onClick = { mirrorImageHorizontally = !mirrorImageHorizontally }
                            ) {
                                Icon(
                                    Icons.Filled.Flip,
                                    contentDescription = "Mirror horizontally",
                                    tint = if (mirrorImageHorizontally) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                            TooltipIconButton(
                                tooltip = "Mirror vertically",
                                onClick = { mirrorImageVertically = !mirrorImageVertically }
                            ) {
                                Icon(
                                    Icons.Filled.FlipCameraAndroid,
                                    contentDescription = "Mirror vertically",
                                    tint = if (mirrorImageVertically) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        if (MimeTypes.isText(currentFile.mimeType)) {
                            TooltipIconButton(
                                tooltip = stringResource(Res.string.note_edit_action),
                                onClick = {
                                    onEditNote(
                                        currentFile.id,
                                        currentFile.name.substringBeforeLast('.')
                                    )
                                }
                            ) {
                                Icon(
                                    Icons.Filled.Edit,
                                    contentDescription = stringResource(Res.string.note_edit_action)
                                )
                            }
                        }
                        TooltipIconButton(
                            tooltip = stringResource(Res.string.common_move_trash),
                            onClick = { if (trashTarget == null) trashTarget = currentFile }
                        ) {
                            Icon(
                                Icons.Filled.DeleteOutline,
                                contentDescription = stringResource(Res.string.common_move_trash)
                            )
                        }
                        TooltipIconButton(tooltip = stringResource(Res.string.preview_file_info), onClick = { viewModel.showInfo(currentFile) }) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(Res.string.preview_file_info)
                            )
                        }
                        Box {
                            TooltipIconButton(tooltip = stringResource(Res.string.common_actions), onClick = { showOverflow = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(Res.string.common_actions)
                                )
                            }
                            PreviewOverflowMenu(
                                expanded = showOverflow,
                                file = currentFile,
                                viewModel = viewModel,
                                onDismiss = { showOverflow = false },
                                onRename = { renameTarget = it },
                                onTrash = { trashTarget = it },
                                onCopy = { copyTarget = it },
                                onMove = { moveTarget = it }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                )
            )
        }
        @Composable
        fun RenderPreviewPane(pageFile: DriveFile) {
            PreviewPane(
                viewModel = viewModel,
                file = pageFile,
                snackbarHostState = snackbarHostState,
                imageRotationDegrees = imageRotationDegrees,
                mirrorImageHorizontally = mirrorImageHorizontally,
                mirrorImageVertically = mirrorImageVertically,
                hasPrevious = if (mediaCategory != null) {
                    queueIndex > 0 || (repeatAll && queueFiles.size > 1) ||
                        (shuffle && shuffleCursor > 0)
                } else index > 0,
                hasNext = if (mediaCategory != null) {
                    (shuffle && shuffleHistory.size < queueFiles.size) ||
                        (shuffle && shuffleCursor < shuffleHistory.lastIndex) ||
                        (repeatAll && queueFiles.size > 1) ||
                        queueIndex in 0 until queueFiles.lastIndex
                } else index < state.files.lastIndex,
                onPrevious = if (mediaCategory != null) ::previousMedia
                else { { if (index > 0) index-- } },
                onNext = if (mediaCategory != null) ::nextMedia
                else { { if (index < state.files.lastIndex) index++ } },
                queueItems = queueItems,
                currentQueueItemId = pageFile.id,
                repeatAll = repeatAll,
                shuffle = shuffle,
                sleepTimerLabel = sleepTimerLabel,
                sleepStopRequest = sleepStopRequest,
                sleepAtTrackEnd = sleepAtTrackEnd,
                onToggleRepeatAll = { repeatAll = !repeatAll },
                onToggleShuffle = ::toggleShuffle,
                onSelectQueueItem = ::selectQueueFile,
                onSetSleepTimer = { duration ->
                    sleepAtTrackEnd = false
                    sleepDeadline = System.currentTimeMillis() + duration.coerceAtLeast(1L)
                },
                onSleepAtTrackEnd = {
                    sleepDeadline = null
                    sleepAtTrackEnd = true
                },
                onClearSleepTimer = {
                    sleepDeadline = null
                    sleepAtTrackEnd = false
                },
                onPlaybackFinished = ::handlePlaybackFinished,
                onDelete = { if (trashTarget == null) trashTarget = pageFile },
                onToggleImageChrome = { imageChromeVisible = !imageChromeVisible },
                onPlayerControlsVisibilityChange = { playerChromeVisible = it }
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                !state.ready || file == null -> LoadingState()
                else -> {
                    // VLC players own native resources. Do not keep outgoing and incoming
                    // video players alive together during AnimatedContent transitions.
                    if (mediaCategory != null) {
                        // Keep the media player composable at a stable call site while the
                        // queue changes its file arguments. DesktopMediaPlayer replaces the
                        // MRL in the retained VLC instance instead of flashing a new surface.
                        RenderPreviewPane(file)
                    } else {
                        AnimatedContent(
                            targetState = index to file,
                            modifier = Modifier.fillMaxSize(),
                            transitionSpec = {
                                val forward = targetState.first >= initialState.first
                                val enter = slideInHorizontally { width ->
                                    if (forward) width else -width
                                } + fadeIn()
                                val exit = slideOutHorizontally { width ->
                                    if (forward) -width else width
                                } + fadeOut()
                                enter togetherWith exit
                            },
                            contentKey = { it.second.id }
                        ) { (_, pageFile) ->
                            RenderPreviewPane(pageFile)
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = previewChromeVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                PreviewTopBar()
            }
            val currentFileIsZip = file?.let {
                it.mimeType == "application/zip" || it.extension.equals("zip", ignoreCase = true)
            } == true
            if (
                state.files.size > 1 &&
                previewChromeVisible &&
                !currentFileIsZip &&
                file?.category !in setOf(FileCategory.AUDIO, FileCategory.VIDEO)
            ) {
                PreviewPagerButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(Res.string.preview_previous),
                    enabled = index > 0,
                    onClick = { index-- },
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                )
                PreviewPagerButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.preview_next),
                    enabled = index < state.files.lastIndex,
                    onClick = { index++ },
                    // Leave the outermost right edge for the image zoom rail.
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 72.dp)
                )
            }
        }
    }

    renameTarget?.let { target ->
        RenameDialog(
            title = stringResource(Res.string.common_rename_file),
            initialValue = target.name,
            confirmLabel = stringResource(Res.string.common_rename),
            onConfirm = {
                viewModel.rename(target, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
    trashTarget?.let { target ->
        ConfirmDialog(
            title = stringResource(Res.string.preview_move_to_trash),
            message = stringResource(Res.string.preview_confirm_trash_file_message, target.name),
            confirmLabel = stringResource(Res.string.common_move_trash),
            destructive = true,
            confirmOnEnter = true,
            onConfirm = {
                viewModel.moveToTrash(target)
                trashTarget = null
            },
            onDismiss = { trashTarget = null }
        )
    }

    copyTarget?.let { target ->
        FolderPickerHost(
            title = stringResource(Res.string.files_copy_to),
            confirmLabel = stringResource(Res.string.files_copy_here),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            onConfirm = { destination ->
                viewModel.copyTo(target, destination)
                copyTarget = null
            },
            onDismiss = { copyTarget = null }
        )
    }
    moveTarget?.let { target ->
        FolderPickerHost(
            title = stringResource(Res.string.files_move_to),
            confirmLabel = stringResource(Res.string.files_move_here),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            onConfirm = { destination ->
                viewModel.moveTo(target, destination, index)
                moveTarget = null
            },
            onDismiss = { moveTarget = null }
        )
    }
    infoTarget?.let { target ->
        FileInfoSheet(file = target, onDismiss = viewModel::dismissInfo)
    }
}

@Composable
private fun PreviewOverflowMenu(
    expanded: Boolean,
    file: DriveFile,
    viewModel: PreviewViewModel,
    onDismiss: () -> Unit,
    onRename: (DriveFile) -> Unit,
    onTrash: (DriveFile) -> Unit,
    onCopy: (DriveFile) -> Unit,
    onMove: (DriveFile) -> Unit
) {
    val fileRevealer = LocalFileRevealer.current
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = {
                Text(
                    if (file.isFavorite) stringResource(Res.string.preview_remove_favorites)
                    else stringResource(Res.string.preview_add_favorites)
                )
            },
            onClick = {
                onDismiss()
                viewModel.setFavorite(file, !file.isFavorite)
            }
        )
        val localPath = file.localPath
        if (fileRevealer != null && localPath != null) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.files_show_in_file_manager)) },
                enabled = remember(localPath) { File(localPath).exists() },
                onClick = {
                    onDismiss()
                    fileRevealer.reveal(localPath)
                }
            )
        }
        if (file.hasRemoteCopy && !file.hasLocalCopy) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_download)) },
                onClick = {
                    onDismiss()
                    viewModel.download(file)
                }
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.files_copy_to)) },
            onClick = {
                onDismiss()
                onCopy(file)
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.files_move_to)) },
            onClick = {
                onDismiss()
                onMove(file)
            }
        )
        if (!file.hasRemoteCopy) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_upload)) },
                onClick = {
                    onDismiss()
                    viewModel.upload(file)
                }
            )
        }
        if (file.hasRemoteCopy && file.hasLocalCopy) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.common_free_space)) },
                onClick = {
                    onDismiss()
                    viewModel.freeUpSpace(file)
                }
            )
        }
        DropdownMenuItem(
            text = {
                Text(
                    if (file.isHidden) stringResource(Res.string.files_unhide)
                    else stringResource(Res.string.files_hide)
                )
            },
            onClick = {
                onDismiss()
                viewModel.setHidden(file, !file.isHidden)
            }
        )
        DropdownMenuItem(
            text = {
                Text(
                    if (file.isArchived) stringResource(Res.string.preview_unarchive)
                    else stringResource(Res.string.preview_archive)
                )
            },
            onClick = {
                onDismiss()
                viewModel.setArchived(file, !file.isArchived)
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.common_rename)) },
            onClick = {
                onDismiss()
                onRename(file)
            }
        )
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.common_move_trash)) },
            onClick = {
                onDismiss()
                onTrash(file)
            }
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VerticalZoomSlider(
    zoomFraction: Float,
    onZoomFractionChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val latestFraction = rememberUpdatedState(zoomFraction.coerceIn(0f, 1f))
    val latestOnChange = rememberUpdatedState(onZoomFractionChange)
    val thumbColor = MaterialTheme.colorScheme.primary
    val trackColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(
        modifier = modifier
            .width(38.dp)
            .height(196.dp)
            .pointerInput(Unit) {
                detectTapGestures { position ->
                    val range = (size.height - 20.dp.toPx()).coerceAtLeast(1f)
                    val value = (1f - ((position.y - 10.dp.toPx()) / range)).coerceIn(0f, 1f)
                    latestOnChange.value(value)
                }
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { position ->
                        val range = (size.height - 20.dp.toPx()).coerceAtLeast(1f)
                        val value = (1f - ((position.y - 10.dp.toPx()) / range)).coerceIn(0f, 1f)
                        latestOnChange.value(value)
                    }
                ) { change, _ ->
                    change.consume()
                    val range = (size.height - 20.dp.toPx()).coerceAtLeast(1f)
                    val value = (1f - ((change.position.y - 10.dp.toPx()) / range)).coerceIn(0f, 1f)
                    latestOnChange.value(value)
                }
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) {
                                // Desktop wheel-up is a negative Y delta: move toward maximum zoom.
                                val step = if (delta < 0f) 0.08f else -0.08f
                                latestOnChange.value(
                                    (latestFraction.value + step).coerceIn(0f, 1f)
                                )
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val horizontalCenter = size.width / 2f
            val top = 10.dp.toPx()
            val bottom = size.height - top
            val fraction = zoomFraction.coerceIn(0f, 1f)
            val thumbY = bottom - fraction * (bottom - top)
            drawLine(
                color = trackColor,
                start = androidx.compose.ui.geometry.Offset(horizontalCenter, top),
                end = androidx.compose.ui.geometry.Offset(horizontalCenter, bottom),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawLine(
                color = thumbColor,
                start = androidx.compose.ui.geometry.Offset(horizontalCenter, thumbY),
                end = androidx.compose.ui.geometry.Offset(horizontalCenter, bottom),
                strokeWidth = 4.dp.toPx(),
                cap = StrokeCap.Round
            )
            drawCircle(
                color = surfaceColor,
                radius = 9.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(horizontalCenter, thumbY)
            )
            drawCircle(
                color = thumbColor,
                radius = 7.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(horizontalCenter, thumbY)
            )
        }
    }
}

@Composable
private fun PreviewPagerButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Alignment belongs on the outer layout surface, not inside TooltipAnchor's wrapper.
    Box(modifier = modifier.size(48.dp)) {
        TooltipAnchor(tooltip = contentDescription) {
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                modifier = Modifier.fillMaxSize(),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.8f),
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.3f)
                )
            ) {
                Icon(imageVector = icon, contentDescription = contentDescription)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalTelephotoApi::class)
@Composable
private fun PreviewPane(
    viewModel: PreviewViewModel,
    file: DriveFile,
    snackbarHostState: SnackbarHostState,
    imageRotationDegrees: Int,
    mirrorImageHorizontally: Boolean,
    mirrorImageVertically: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    queueItems: List<PlayerQueueItem>,
    currentQueueItemId: String?,
    repeatAll: Boolean,
    shuffle: Boolean,
    sleepTimerLabel: String,
    sleepStopRequest: Int,
    sleepAtTrackEnd: Boolean,
    onToggleRepeatAll: () -> Unit,
    onToggleShuffle: () -> Unit,
    onSelectQueueItem: (String) -> Unit,
    onSetSleepTimer: (Long) -> Unit,
    onSleepAtTrackEnd: () -> Unit,
    onClearSleepTimer: () -> Unit,
    onPlaybackFinished: () -> Unit,
    onDelete: () -> Unit,
    onToggleImageChrome: () -> Unit,
    onPlayerControlsVisibilityChange: (Boolean) -> Unit
) {
    val content by viewModel.contentFor(file).collectAsState()
    val backgroundPlayback by viewModel.backgroundPlayback.collectAsState()
    val previewUiState by viewModel.uiState.collectAsState()
    val archiveSort by viewModel.archiveSort.collectAsState()
    val (archiveSortField, archiveSortDirection) = archiveSort
    // Keep the old local media source mounted while the next queued video resolves. This
    // preserves the single VLC surface across the transient Loading state and prevents the
    // black flash that otherwise occurs between adjacent local videos.
    var lastReadyMedia by remember { mutableStateOf<Pair<DriveFile, PreviewContent>?>(null) }
    LaunchedEffect(file.id, content) {
        when (val resolved = content) {
            is PreviewContent.LocalMedia,
            is PreviewContent.StreamedMedia -> lastReadyMedia = file to resolved
            is PreviewContent.Loading -> Unit
            else -> lastReadyMedia = null
        }
    }
    val retainPreviousMedia = file.category in setOf(FileCategory.AUDIO, FileCategory.VIDEO) &&
            content is PreviewContent.Loading && lastReadyMedia != null
    val displayedFile = if (retainPreviousMedia) lastReadyMedia!!.first else file
    val displayedContent = if (retainPreviousMedia) lastReadyMedia!!.second else content
    val urlOpener = LocalUrlOpener.current
    val preferredAudioLanguage by viewModel.preferredAudioLanguage.collectAsState()
    val preferredSubtitleLanguage by viewModel.preferredSubtitleLanguage.collectAsState()

    // Resolve local and streamed sources before the content branch so the native VLC player
    // remains at one stable composition call site while moving between cached and streamed files.
    val streamServer = koinInject<MediaStreamServer>()
    val telegramClient = koinInject<TelegramClient>()
    val streamCrypto = koinInject<StreamCrypto>()
    val wrappedKeyRepository = koinInject<WrappedKeyRepository>()
    val streamedContent = displayedContent as? PreviewContent.StreamedMedia
    val streamedMrl = remember(
        displayedFile.id,
        displayedFile.name,
        displayedFile.mimeType,
        streamedContent
    ) {
        streamedContent?.let { media ->
            streamServer.serve(displayedFile.name, displayedFile.mimeType) {
                if (media.parts.isEmpty()) {
                    TelegramMediaByteSource(
                        telegramClient,
                        media.remoteFileId,
                        media.chatId,
                        media.messageId
                    )
                } else {
                    PartedMediaByteSource(
                        telegramClient = telegramClient,
                        streamCrypto = streamCrypto,
                        parts = media.parts,
                        encrypted = media.encrypted,
                        contentKey = if (media.encrypted) {
                            wrappedKeyRepository.get(CryptoKeys.CONTENT)
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }
    val mediaMrl = when (val media = displayedContent) {
        is PreviewContent.LocalMedia -> media.path
        is PreviewContent.StreamedMedia -> streamedMrl
        else -> null
    }
    val mediaIsAudio = when (val media = displayedContent) {
        is PreviewContent.LocalMedia -> media.isAudio
        is PreviewContent.StreamedMedia -> media.isAudio
        else -> false
    }

    val edgeToEdge = when (displayedContent) {
        is PreviewContent.Image,
        is PreviewContent.LocalMedia,
        is PreviewContent.StreamedMedia -> true

        else -> false
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = if (edgeToEdge) 0.dp else TOP_BAR_INSET)
    ) {
        when (val current = displayedContent) {
            is PreviewContent.Loading -> LoadingState()

            is PreviewContent.DownloadProgress -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(Res.string.preview_preparing, file.name),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 20.dp)
                )
                if (current.total > 0) {
                    LinearWavyProgressIndicator(
                        progress = {
                            (current.transferred.toFloat() / current.total).coerceIn(0f, 1f)
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                Text(
                    text = stringResource(
                        Res.string.preview_progress_bytes,
                        Formatters.bytes(current.transferred),
                        Formatters.bytes(current.total)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            is PreviewContent.Image -> {
                var undecodable by remember(current.model) { mutableStateOf(false) }
                val zoomableState = rememberZoomableState(
                    zoomSpec = ZoomSpec(maxZoomFactor = 4f)
                )
                val zoomScope = rememberCoroutineScope()
                Box(modifier = Modifier.fillMaxSize()) {
                    if (undecodable) {
                        val imagePath = (current.model as? File)
                            ?.takeIf { it.isFile }
                            ?.absolutePath
                            ?: file.localPath?.takeIf { File(it).isFile }
                        if (imagePath != null) {
                            OpenExternallyState(
                                icon = Icons.Filled.BrokenImage,
                                file = file,
                                path = imagePath,
                                snackbarHostState = snackbarHostState
                            )
                        } else {
                            EmptyState(
                                icon = Icons.Filled.BrokenImage,
                                title = stringResource(Res.string.preview_image_undecodable)
                            )
                        }
                    } else {
                        // AsyncImage sizes the decoded bitmap to the viewport, which makes
                        // zooming a low-resolution preview look blurred. The painter intentionally
                        // requests the original source dimensions for the viewing surface; grid
                        // thumbnails continue to use the separate bounded thumbnail pipeline.
                        val imagePainter = rememberAsyncImagePainter(
                            model = current.model,
                            onState = { imageState ->
                                if (imageState is AsyncImagePainter.State.Error) undecodable = true
                            },
                            filterQuality = FilterQuality.High
                        )
                        Image(
                            painter = imagePainter,
                            contentDescription = file.name,
                            modifier = Modifier
                                .fillMaxSize()
                                // Left mouse is reserved for vertical zoom. Right mouse
                                // handles horizontal image navigation at fit zoom and pans the
                                // image in any direction when zoomed. A plain left click is left
                                // untouched so the existing header/control toggle still works.
                                .pointerInput(zoomableState, onPrevious, onNext) {
                                    awaitEachGesture {
                                        var downEvent = awaitPointerEvent(PointerEventPass.Initial)
                                        var down = downEvent.changes.firstOrNull { it.pressed }
                                        while (
                                            downEvent.type != PointerEventType.Press ||
                                            down == null
                                        ) {
                                            downEvent = awaitPointerEvent(PointerEventPass.Initial)
                                            down = downEvent.changes.firstOrNull { it.pressed }
                                        }

                                        val mouseDown = down ?: return@awaitEachGesture
                                        if (mouseDown.type != PointerType.Mouse) {
                                            return@awaitEachGesture
                                        }
                                        val isLeftButton = downEvent.buttons.isPrimaryPressed
                                        val isRightButton = downEvent.buttons.isSecondaryPressed
                                        if (!isLeftButton && !isRightButton) {
                                            return@awaitEachGesture
                                        }

                                        val initialZoomFraction =
                                            (zoomableState.zoomFraction ?: 0f).coerceIn(0f, 1f)
                                        var lastPosition = mouseDown.position
                                        var totalX = 0f
                                        var totalY = 0f
                                        var leftZoomStarted = false
                                        var leftDragConsumed = false
                                        var rightSwipeStarted = false
                                        var rightPanStarted = false
                                        var rightDragConsumed = false
                                        var lastAppliedZoomFraction = initialZoomFraction

                                        fun updateZoomFromVerticalDrag(force: Boolean = false) {
                                            val dragRange = (size.height * 0.65f).coerceAtLeast(1f)
                                            val targetFraction =
                                                (initialZoomFraction - totalY / dragRange)
                                                    .coerceIn(0f, 1f)

                                            // Snap small residual zoom back to the exact fit state.
                                            // This also resets any pan offset left over from zooming.
                                            if (targetFraction <= IMAGE_FIT_SNAP_FRACTION) {
                                                if (lastAppliedZoomFraction > IMAGE_FIT_SNAP_FRACTION || force) {
                                                    lastAppliedZoomFraction = 0f
                                                    zoomScope.launch {
                                                        zoomableState.resetZoom(animationSpec = SnapSpec())
                                                    }
                                                } else {
                                                    lastAppliedZoomFraction = 0f
                                                }
                                                return
                                            }

                                            if (
                                                !force &&
                                                kotlin.math.abs(targetFraction - lastAppliedZoomFraction) < 0.012f
                                            ) {
                                                return
                                            }

                                            lastAppliedZoomFraction = targetFraction
                                            zoomScope.launch {
                                                zoomableState.zoomTo(
                                                    zoomFactor = 1f + 3f * targetFraction,
                                                    animationSpec = SnapSpec()
                                                )
                                            }
                                        }

                                        // Consume the right-button press immediately so it cannot
                                        // be interpreted as the normal left-click header toggle.
                                        if (isRightButton) mouseDown.consume()

                                        while (true) {
                                            val event = awaitPointerEvent(PointerEventPass.Initial)
                                            val change = event.changes.firstOrNull { it.id == mouseDown.id }
                                                ?: break
                                            val delta = change.position - lastPosition
                                            lastPosition = change.position
                                            totalX += delta.x
                                            totalY += delta.y

                                            if (!change.pressed) {
                                                if (leftZoomStarted) {
                                                    updateZoomFromVerticalDrag(force = true)
                                                }
                                                if (
                                                    leftZoomStarted || leftDragConsumed ||
                                                    rightSwipeStarted || rightPanStarted || rightDragConsumed
                                                ) {
                                                    change.consume()
                                                }
                                                break
                                            }

                                            val slop = viewConfiguration.touchSlop
                                            val horizontalDistance = kotlin.math.abs(totalX)
                                            val verticalDistance = kotlin.math.abs(totalY)

                                            if (isLeftButton) {
                                                if (!leftZoomStarted && !leftDragConsumed) {
                                                    when {
                                                        verticalDistance > slop &&
                                                            verticalDistance > horizontalDistance * 1.2f -> {
                                                            leftZoomStarted = true
                                                            change.consume()
                                                            updateZoomFromVerticalDrag()
                                                        }
                                                        horizontalDistance > slop &&
                                                            horizontalDistance > verticalDistance * 1.2f -> {
                                                            // Prevent the default zoomable gesture from
                                                            // using the left button to pan the image.
                                                            leftDragConsumed = true
                                                            change.consume()
                                                        }
                                                    }
                                                } else if (leftZoomStarted) {
                                                    change.consume()
                                                    updateZoomFromVerticalDrag()
                                                } else if (leftDragConsumed) {
                                                    change.consume()
                                                }
                                            } else if (isRightButton) {
                                                if (initialZoomFraction > IMAGE_FIT_SNAP_FRACTION) {
                                                    // At non-fit zoom, right-drag pans the image in
                                                    // both axes using Telephoto's own pan constraints.
                                                    val distance = kotlin.math.sqrt(
                                                        totalX * totalX + totalY * totalY
                                                    )
                                                    if (!rightPanStarted && distance > slop) {
                                                        rightPanStarted = true
                                                        change.consume()
                                                        val panDelta = androidx.compose.ui.geometry.Offset(totalX, totalY)
                                                        zoomScope.launch {
                                                            zoomableState.panBy(
                                                                panDelta,
                                                                animationSpec = SnapSpec()
                                                            )
                                                        }
                                                    } else if (rightPanStarted) {
                                                        change.consume()
                                                        val panDelta = delta
                                                        zoomScope.launch {
                                                            zoomableState.panBy(
                                                                panDelta,
                                                                animationSpec = SnapSpec()
                                                            )
                                                        }
                                                    }
                                                } else if (!rightSwipeStarted && !rightDragConsumed) {
                                                    when {
                                                        horizontalDistance > slop &&
                                                            horizontalDistance > verticalDistance * 1.2f -> {
                                                            rightSwipeStarted = true
                                                            change.consume()
                                                        }
                                                        verticalDistance > slop &&
                                                            verticalDistance > horizontalDistance * 1.2f -> {
                                                            // A vertical right-drag at fit zoom is
                                                            // intentionally ignored, not treated as zoom.
                                                            rightDragConsumed = true
                                                            change.consume()
                                                        }
                                                    }
                                                } else if (rightSwipeStarted || rightDragConsumed) {
                                                    change.consume()
                                                }
                                            }
                                        }

                                        if (rightSwipeStarted && initialZoomFraction <= IMAGE_FIT_SNAP_FRACTION) {
                                            val swipeThreshold = maxOf(
                                                72.dp.toPx(),
                                                size.width * 0.12f
                                            )
                                            when {
                                                totalX >= swipeThreshold -> onPrevious()
                                                totalX <= -swipeThreshold -> onNext()
                                            }
                                        }
                                    }
                                }
                                .zoomable(
                                    state = zoomableState,
                                    // Telephoto routes clicks through this callback so its
                                    // own double-click zoom and pan/pinch gestures remain intact.
                                    onClick = { _ -> onToggleImageChrome() },
                                    onDoubleClick = { state, centroid ->
                                        zoomScope.launch {
                                            if ((state.zoomFraction ?: 0f) > 0f) {
                                                state.resetZoom()
                                            } else {
                                                state.zoomBy(zoomFactor = 1.35f, centroid = centroid)
                                            }
                                        }
                                    }
                                )
                                .graphicsLayer {
                                    val intrinsic = imagePainter.intrinsicSize
                                    val intrinsicIsUsable = intrinsic.width.isFinite() &&
                                        intrinsic.height.isFinite() &&
                                        intrinsic.width > 0f && intrinsic.height > 0f
                                    val sourceAspect = if (intrinsicIsUsable) {
                                        intrinsic.width / intrinsic.height
                                    } else {
                                        size.width / size.height.coerceAtLeast(1f)
                                    }
                                    val isQuarterTurn = imageRotationDegrees % 180 != 0
                                    val fitScale = if (isQuarterTurn && sourceAspect > 0f) {
                                        val fittedWidth: Float
                                        val fittedHeight: Float
                                        val viewportAspect = size.width / size.height.coerceAtLeast(1f)
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
                                    rotationZ = imageRotationDegrees.toFloat()
                                    scaleX = fitScale * if (mirrorImageHorizontally) -1f else 1f
                                    scaleY = fitScale * if (mirrorImageVertically) -1f else 1f
                                },
                            contentScale = ContentScale.Fit
                        )
                        VerticalZoomSlider(
                            zoomFraction = zoomableState.zoomFraction ?: 0f,
                            onZoomFractionChange = { fraction ->
                                zoomScope.launch {
                                    if (fraction <= 0.001f) {
                                        zoomableState.resetZoom(animationSpec = SnapSpec())
                                    } else {
                                        zoomableState.zoomTo(
                                            zoomFactor = 1f + 3f * fraction,
                                            animationSpec = SnapSpec()
                                        )
                                    }
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 14.dp)
                        )
                    }
                }
            }

            is PreviewContent.LocalMedia -> if (!VlcPlayback.available) {
                OpenExternallyState(
                    icon = if (current.isAudio) Icons.Filled.Audiotrack else Icons.Filled.Movie,
                    file = displayedFile,
                    path = current.path,
                    snackbarHostState = snackbarHostState
                )
            }

            is PreviewContent.Pdf -> OpenExternallyState(
                icon = Icons.Filled.PictureAsPdf,
                file = file,
                path = current.path,
                snackbarHostState = snackbarHostState
            )

            is PreviewContent.StreamedMedia -> if (!VlcPlayback.available) {
                StreamState(
                    file = displayedFile,
                    content = current,
                    onDownload = { viewModel.download(displayedFile) },
                    snackbarHostState = snackbarHostState
                )
            }

            is PreviewContent.PlainText -> {
                val previewTextScrollState = rememberScrollState()
                Box(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(previewTextScrollState)
                            .padding(end = 10.dp)
                            .padding(24.dp)
                    ) {
                if (MimeTypes.isMarkdown(file.mimeType)) {
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
                        text = stringResource(Res.string.preview_truncated_download_view),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                    }
                    DesktopVerticalScrollbar(
                        state = previewTextScrollState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }

            is PreviewContent.Archive -> {
                DesktopArchivePane(
                    file = displayedFile,
                    archive = current,
                    busyEntryIndex = previewUiState.archiveEntryBusyIndex,
                    batchProgress = previewUiState.archiveBatchProgress,
                    archivePreview = previewUiState.archivePreview,
                    sortField = archiveSortField,
                    sortDirection = archiveSortDirection,
                    onSortChange = { field, direction ->
                        viewModel.setArchiveSort(field, direction)
                    },
                    onEntryTap = { entryIndex ->
                        viewModel.onArchiveEntryTap(displayedFile, entryIndex)
                    },
                    onEntryDownload = { entryIndex ->
                        viewModel.downloadArchiveEntry(displayedFile, entryIndex)
                    },
                    onDownloadSelection = { entryIndices, folderPaths ->
                        viewModel.downloadArchiveSelection(
                            displayedFile, entryIndices, folderPaths
                        )
                    },
                    onDismissPreview = { viewModel.dismissArchivePreview() },
                    preferredAudioLanguage = preferredAudioLanguage,
                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                    onPreferredAudioLanguage = viewModel::setPreferredAudioLanguage,
                    onPreferredSubtitleLanguage = viewModel::setPreferredSubtitleLanguage,
                    backgroundPlayback = backgroundPlayback,
                    onBackgroundPlaybackChange = { enabled ->
                        viewModel.setBackgroundPlayback(enabled)
                    },
                    snackbarHostState = snackbarHostState
                )
            }

            is PreviewContent.RequiresDownload -> DownloadState(
                sizeBytes = current.sizeBytes,
                onDownload = { viewModel.download(file) }
            )

            is PreviewContent.Unsupported -> {
                // Keep broad desktop file support useful even when TeleDrive has no native
                // renderer for a particular document/container. The original local file remains
                // untouched and is opened with the user's registered Windows application.
                val localPath = current.localPath?.takeIf { File(it).isFile }
                    ?: file.localPath?.takeIf { File(it).isFile }
                if (localPath != null) {
                    OpenExternallyState(
                        icon = Icons.Outlined.Description,
                        file = file,
                        path = localPath,
                        snackbarHostState = snackbarHostState
                    )
                } else {
                    EmptyState(
                        icon = Icons.Outlined.Description,
                        title = stringResource(Res.string.preview_no_preview),
                        description = stringResource(current.reasonRes),
                        actionLabel = stringResource(Res.string.common_download),
                        onAction = { viewModel.download(file) }
                    )
                }
            }

            is PreviewContent.Failed -> ErrorState(message = stringResource(current.messageRes))
        }

        // The same player subtree now handles both local and streamed sources. Only the MRL
        // changes between files; Compose no longer removes one player branch and mounts another.
        if (VlcPlayback.available && mediaMrl != null) {
            DesktopMediaPlayer(
                mrl = mediaMrl,
                isAudio = mediaIsAudio,
                mediaId = displayedFile.id,
                mediaTitle = displayedFile.name,
                preferredAudioLanguage = preferredAudioLanguage,
                preferredSubtitleLanguage = preferredSubtitleLanguage,
                onPreferredAudioLanguage = viewModel::setPreferredAudioLanguage,
                onPreferredSubtitleLanguage = viewModel::setPreferredSubtitleLanguage,
                onPrevious = onPrevious,
                onNext = onNext,
                hasPrevious = hasPrevious,
                hasNext = hasNext,
                queueItems = queueItems,
                currentQueueItemId = currentQueueItemId,
                repeatAll = repeatAll,
                shuffle = shuffle,
                sleepTimerLabel = sleepTimerLabel,
                sleepStopRequest = sleepStopRequest,
                sleepAtTrackEnd = sleepAtTrackEnd,
                onToggleRepeatAll = onToggleRepeatAll,
                onToggleShuffle = onToggleShuffle,
                onSelectQueueItem = onSelectQueueItem,
                onSetSleepTimer = onSetSleepTimer,
                onSleepAtTrackEnd = onSleepAtTrackEnd,
                onClearSleepTimer = onClearSleepTimer,
                onPlaybackFinished = onPlaybackFinished,
                onControlsVisibilityChange = onPlayerControlsVisibilityChange,
                onDelete = onDelete,
                backgroundPlayback = backgroundPlayback,
                onBackgroundPlaybackChange = { enabled ->
                    viewModel.setBackgroundPlayback(enabled)
                }
            )
        }
    }
}

private const val IMAGE_FIT_SNAP_FRACTION = 0.05f

private val TOP_BAR_INSET = 72.dp

@Composable
private fun OpenExternallyState(
    icon: ImageVector,
    file: DriveFile,
    path: String,
    snackbarHostState: SnackbarHostState
) {
    var openFailed by remember(path) { mutableStateOf(false) }
    if (openFailed) {
        LaunchedEffect(path) {
            snackbarHostState.showSnackbar(getString(DesktopRes.string.preview_open_failed))
            openFailed = false
        }
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = Formatters.bytes(file.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = { if (!openExternally(path)) openFailed = true }) {
            Text(stringResource(DesktopRes.string.preview_open_externally))
        }
    }
}

@Composable
private fun StreamState(
    file: DriveFile,
    content: PreviewContent.StreamedMedia,
    onDownload: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val streamServer = koinInject<MediaStreamServer>()
    val player = koinInject<ExternalMediaPlayer>()
    val telegramClient = koinInject<TelegramClient>()
    val streamCrypto = koinInject<StreamCrypto>()
    val wrappedKeyRepository = koinInject<WrappedKeyRepository>()

    var playFailed by remember(file.id) { mutableStateOf(false) }
    if (playFailed) {
        LaunchedEffect(file.id) {
            snackbarHostState.showSnackbar(getString(DesktopRes.string.preview_open_failed))
            playFailed = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = if (content.isAudio) Icons.Filled.Audiotrack else Icons.Filled.Movie,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = Formatters.bytes(file.sizeBytes),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Button(onClick = {
            val url = streamServer.serve(file.name, file.mimeType) {
                if (content.parts.isEmpty()) {
                    TelegramMediaByteSource(
                        telegramClient,
                        content.remoteFileId,
                        content.chatId,
                        content.messageId
                    )
                } else {
                    PartedMediaByteSource(
                        telegramClient = telegramClient,
                        streamCrypto = streamCrypto,
                        parts = content.parts,
                        encrypted = content.encrypted,
                        contentKey = if (content.encrypted) {
                            wrappedKeyRepository.get(CryptoKeys.CONTENT)
                        } else {
                            null
                        }
                    )
                }
            }
            if (!player.play(url)) playFailed = true
        }) {
            Text(stringResource(DesktopRes.string.preview_stream))
        }
        OutlinedButton(onClick = onDownload) {
            Text(stringResource(Res.string.common_download))
        }
    }
}

@Composable
private fun DownloadState(sizeBytes: Long, onDownload: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.Download,
        title = stringResource(Res.string.preview_download_view),
        description = stringResource(
            Res.string.preview_requires_download,
            Formatters.bytes(sizeBytes)
        ),
        actionLabel = stringResource(Res.string.common_download),
        onAction = onDownload
    )
}

private fun openExternally(path: String): Boolean = runCatching {
    val target = File(path)
    check(target.exists())
    Desktop.getDesktop().open(target)
}.isSuccess

/** Test tag of the desktop archive list, so composed tests can aim gestures at it. */
internal const val ARCHIVE_LIST_TEST_TAG = "desktop-archive-list"

/**
 * The desktop ZIP viewer's archive pane, reworked onto the merged (Android-lineage)
 * ViewModel API and the desktop Files section's selection model:
 *
 * - Rows are the shared archive TREE (folders are synthesized from entry paths, as on
 *   Android), keyed by listing index for files and exact path for folders.
 * - Selection lives in ONE [ArchiveSelectionState]: checkbox ticks, row highlight and
 *   the "N selected" count all read it, so they agree by construction. It is local to
 *   this pane, keyed by the ZIP's file id, and independent of Files selection state.
 * - Interactions mirror Files: [SelectionCheckbox] presentation with a constant row
 *   height (the text column keeps a 48.dp minimum, so composing the checkbox never
 *   resizes a row), Ctrl+click toggle via [ctrlClickSelect], drag paint over a
 *   drag-start snapshot via the shared `rememberDragSelect` + the shared tree
 *   translation, and the Files keyboard collection (arrows, Space toggle, Escape
 *   clear, Ctrl+A toggle-select-all through both the container shortcuts and the
 *   window-level Ctrl+A registry, with the search field tracked as a text input so
 *   Ctrl+A there still selects text).
 * - Rows use `combinedClickable` with a no-op long-click, so the long-press that
 *   starts a drag can never also fire a click on release.
 * - A tap on a file row routes through the merged ViewModel
 *   (`onArchiveEntryTap(file, entryIndex)`): images reach the image overlay, audio
 *   the desktop player (open-externally when VLC is unavailable), text the read-only
 *   overlay, and video/downloads go through the tracked download path. Failures are
 *   the ViewModel's app-authored messages; no raw exception text is surfaced here.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DesktopArchivePane(
    file: DriveFile,
    archive: PreviewContent.Archive,
    busyEntryIndex: Int?,
    batchProgress: ArchiveBatchProgress?,
    archivePreview: ArchiveEntryPreview?,
    sortField: FileSortField,
    sortDirection: SortDirection,
    onSortChange: (FileSortField, SortDirection) -> Unit,
    onEntryTap: (Int) -> Unit,
    onEntryDownload: (Int) -> Unit,
    onDownloadSelection: (Set<Int>, Set<String>) -> Unit,
    onDismissPreview: () -> Unit,
    preferredAudioLanguage: String,
    preferredSubtitleLanguage: String,
    onPreferredAudioLanguage: (String) -> Unit,
    onPreferredSubtitleLanguage: (String) -> Unit,
    backgroundPlayback: Boolean,
    onBackgroundPlaybackChange: (Boolean) -> Unit,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val listing = archive.entries
    var query by remember(file.id) { mutableStateOf("") }
    var expanded by remember(file.id) { mutableStateOf(emptySet<String>()) }
    val selection = remember(file.id) { ArchiveSelectionState() }
    var rangeBase by remember(file.id) { mutableStateOf<Pair<Set<Int>, Set<String>>?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    val filtering = query.isNotBlank()
    val visibleIndices = remember(listing, query) {
        if (filtering) archiveFilterIndices(listing, query) else listing.indices.toSet()
    }
    val root = remember(listing, visibleIndices) {
        buildArchiveTreeIndexed(
            listing.mapIndexedNotNull { index, entry ->
                if (index in visibleIndices) IndexedArchiveEntry(index, entry) else null
            }
        )
    }
    // Filtering prunes the selection to what is still visible, mirroring Files search.
    LaunchedEffect(query) {
        if (filtering) selection.prune(visibleIndices, allFolderPaths(root))
    }
    val effectiveExpanded = if (filtering) allFolderPaths(root) else expanded
    val rows = remember(root, effectiveExpanded, sortField, sortDirection) {
        flattenArchive(root, effectiveExpanded, sortField, sortDirection)
    }
    val selectionMode = selection.isActive
    val busy = busyEntryIndex != null || batchProgress != null
    val listState = rememberLazyListState()

    fun toggleRow(row: ArchiveRowState) {
        if (row.node.isDirectory) {
            selection.toggleFolder(row.node.path)
        } else {
            row.node.entryIndex?.let { selection.toggleEntry(it) }
        }
    }

    val displayedEntryIndices = remember(rows) {
        rows.mapNotNull { row -> row.node.entryIndex?.takeUnless { row.node.isDirectory } }.toSet()
    }
    val displayedFolderPaths = remember(rows) {
        rows.filter { it.node.isDirectory }.map { it.node.path }.toSet()
    }

    /** The pane's one select-all toggle; every Ctrl+A path runs this same action. */
    fun toggleSelectAll(): Boolean {
        if (rows.isEmpty()) return false
        selection.toggleSelectAll(displayedEntryIndices, displayedFolderPaths)
        return true
    }

    val keyboard = rememberKeyboardCollection(listState)
    val shortcuts = CollectionShortcuts(
        onToggleSelect = { position -> rows.getOrNull(position)?.let { toggleRow(it) } },
        onSelectAll = { toggleSelectAll() },
        onClear = if (selectionMode) selection::clear else null,
        onBack = if (selectionMode) selection::clear else null
    )
    val textInputTracker = remember { TextInputFocusTracker() }
    RegisterWindowSelectAll(
        isTextInputFocused = { textInputTracker.anyFocused },
        onToggleSelectAll = { toggleSelectAll() }
    )
    val commandFocusRequester = rememberCollectionCommandFocusRequester()

    // Drag selection mirrors Files: the shared rememberDragSelect over a base-snapshot
    // range model — a drag paints a range onto the snapshot taken when the drag started,
    // translated through the same shared tree helpers the Android viewer uses.
    val dragSelect = rememberDragSelect(
        listState = listState,
        onStart = { rangeBase = selection.entries to selection.folders },
        isSelected = { listIndex ->
            archiveDragAnchorSelected(rows, listIndex, selection.entries, selection.folders)
        },
        onRange = { range, shouldSelect ->
            val painted = applyArchiveDragRange(
                rows = rows,
                listRange = range,
                baseEntries = rangeBase?.first ?: selection.entries,
                baseFolders = rangeBase?.second ?: selection.folders,
                shouldSelect = shouldSelect
            )
            selection.set(painted.entries, painted.folders)
        },
        onEnd = { rangeBase = null },
        mouseDragAddsToSelection = false,
        resolveNearestVisibleItem = true
    )

    // Leaving the archive view closes any in-archive preview and deletes its temp file.
    DisposableEffect(Unit) {
        onDispose { onDismissPreview() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        // Escape clears the selection, as in Files; it is handled at
                        // the pane boundary so it works wherever focus sits in the list.
                        if (event.type == KeyEventType.KeyDown &&
                            event.key == Key.Escape && selection.isActive
                        ) {
                            selection.clear()
                            true
                        } else {
                            false
                        }
                    }
                    .collectionCommandShortcuts(
                        focusRequester = commandFocusRequester,
                        onSelectAll = { toggleSelectAll() }
                    )
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp)
                        .keyboardCollection(keyboard, rows.size, shortcuts, label = "ZIP contents")
                        .then(if (archive.remote) dragSelect else Modifier)
                        .testTag(ARCHIVE_LIST_TEST_TAG),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item(key = "archive-header") {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = query,
                                    onValueChange = { query = it },
                                    placeholder = {
                                        Text(stringResource(Res.string.preview_zip_search_entries))
                                    },
                                    singleLine = true,
                                    modifier = Modifier
                                        .weight(1f)
                                        .trackTextInputFocus(textInputTracker)
                                )
                                Box {
                                    TooltipIconButton(
                                        tooltip = "Sort",
                                        onClick = { sortMenuOpen = true }
                                    ) {
                                        Icon(Icons.Filled.Sort, contentDescription = "Sort")
                                    }
                                    SortMenu(
                                        expanded = sortMenuOpen,
                                        current = sortField,
                                        direction = sortDirection,
                                        onDismiss = { sortMenuOpen = false },
                                        onSelect = { field, direction ->
                                            sortMenuOpen = false
                                            onSortChange(field, direction)
                                        },
                                        fields = listOf(
                                            FileSortField.NAME,
                                            FileSortField.SIZE,
                                            FileSortField.DATE_MODIFIED,
                                            FileSortField.TYPE
                                        )
                                    )
                                }
                            }
                            if (batchProgress != null) {
                                Text(
                                    text = "Downloading ${batchProgress.done} of " +
                                        "${batchProgress.total} files…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            } else if (selectionMode) {
                                Text(
                                    text = "${selection.count} selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                    }
                    items(
                        count = rows.size,
                        key = { position -> archiveRowKey(rows[position]) }
                    ) { position ->
                        val row = rows[position]
                        val entryIndex = row.node.entryIndex
                        val selected = if (row.node.isDirectory) {
                            selection.isFolderSelected(row.node.path)
                        } else {
                            entryIndex != null && selection.isEntrySelected(entryIndex)
                        }
                        DesktopArchiveRow(
                            row = row,
                            selected = selected,
                            selectionMode = selectionMode,
                            isBusy = busyEntryIndex != null && busyEntryIndex == entryIndex,
                            canDownload = archive.remote && !row.node.isDirectory,
                            onRowClick = {
                                when {
                                    selectionMode -> toggleRow(row)
                                    row.node.isDirectory -> {
                                        expanded = if (row.node.path in expanded) {
                                            expanded - row.node.path
                                        } else {
                                            expanded + row.node.path
                                        }
                                    }
                                    !busy -> entryIndex?.let { onEntryTap(it) }
                                    else -> Unit
                                }
                            },
                            onToggleSelection = { toggleRow(row) },
                            onDownloadClick = {
                                if (!busy) entryIndex?.let { onEntryDownload(it) }
                            },
                            modifier = Modifier
                                .keyboardCollectionItem(keyboard, position)
                                .ctrlClickSelect { toggleRow(row) }
                        )
                    }
                    item(key = "archive-hint") {
                        Text(
                            text = stringResource(
                                if (archive.remote) {
                                    Res.string.preview_archive_remote_hint
                                } else {
                                    Res.string.preview_extraction_supported_yet_download
                                }
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp, bottom = 96.dp)
                        )
                    }
                }
                DesktopVerticalScrollbar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
            if (selectionMode) {
                // The viewer's only selection action: Download selected.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp, 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            val entryIndices = selection.entries
                            val folderPaths = selection.folders
                            selection.clear()
                            onDownloadSelection(entryIndices, folderPaths)
                        }
                    ) {
                        Text(stringResource(Res.string.preview_zip_download_selected))
                    }
                    OutlinedButton(
                        onClick = { selection.clear() },
                        modifier = Modifier.padding(start = 12.dp)
                    ) {
                        Text("Clear")
                    }
                }
            }
        }
        // In-archive preview overlay: image in the desktop image viewer, audio in the
        // desktop player, text read-only. Video never reaches here — its tap action
        // is DOWNLOAD in the shared ViewModel.
        when (val preview = archivePreview) {
            is ArchiveEntryPreview.Image -> {
                val painter = rememberAsyncImagePainter(File(preview.path))
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Image(
                        painter = painter,
                        contentDescription = preview.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                    FilledIconButton(
                        onClick = onDismissPreview,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            }

            is ArchiveEntryPreview.Audio -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    if (VlcPlayback.available) {
                        DesktopMediaPlayer(
                            mrl = preview.path,
                            isAudio = true,
                            mediaId = "archive-audio-${preview.name}",
                            mediaTitle = preview.name,
                            preferredAudioLanguage = preferredAudioLanguage,
                            preferredSubtitleLanguage = preferredSubtitleLanguage,
                            onPreferredAudioLanguage = onPreferredAudioLanguage,
                            onPreferredSubtitleLanguage = onPreferredSubtitleLanguage,
                            backgroundPlayback = backgroundPlayback,
                            onBackgroundPlaybackChange = onBackgroundPlaybackChange
                        )
                    } else {
                        OpenExternallyState(
                            icon = Icons.Filled.Audiotrack,
                            file = file,
                            path = preview.path,
                            snackbarHostState = snackbarHostState
                        )
                    }
                    FilledIconButton(
                        onClick = onDismissPreview,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            }

            is ArchiveEntryPreview.Text -> {
                val textScrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(textScrollState)
                            .padding(24.dp)
                    ) {
                        Text(
                            text = preview.text,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    FilledIconButton(
                        onClick = onDismissPreview,
                        modifier = Modifier.align(Alignment.TopEnd).padding(16.dp)
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            }

            null -> Unit
        }
    }
}

/**
 * One archive row. The checkbox is composed only in selection mode (or when the row is
 * selected), exactly as in Files, but the text column keeps a 48.dp minimum height so a
 * row's height is identical in and out of selection mode.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DesktopArchiveRow(
    row: ArchiveRowState,
    selected: Boolean,
    selectionMode: Boolean,
    isBusy: Boolean,
    canDownload: Boolean,
    onRowClick: () -> Unit,
    onToggleSelection: () -> Unit,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val node = row.node
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag(
                if (node.isDirectory) {
                    "archive-folder-${node.path}"
                } else {
                    "archive-entry-${node.entryIndex}"
                }
            )
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    Color.Transparent
                }
            )
            .combinedClickable(onClick = onRowClick, onLongClick = {})
            .padding(start = 14.dp + (16 * row.depth).dp, end = 14.dp)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectionMode || selected) {
            SelectionCheckbox(
                checked = selected,
                onClick = onToggleSelection,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Icon(
            imageVector = if (node.isDirectory) {
                Icons.Filled.Folder
            } else {
                Icons.AutoMirrored.Filled.InsertDriveFile
            },
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            modifier = Modifier
                .weight(1f)
                // Keep the row's height identical with and without the 48.dp selection
                // checkbox, so flipping selection mode never resizes rows (Files rule).
                .defaultMinSize(minHeight = 48.dp)
                .padding(start = 12.dp)
        ) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row {
                if (!node.isDirectory && node.modifiedAtMillis > 0) {
                    Text(
                        text = Formatters.date(node.modifiedAtMillis),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 12.dp)
                    )
                }
                Text(
                    text = if (node.isDirectory) {
                        "${node.children.size} items · ${Formatters.bytes(node.sizeBytes)}"
                    } else {
                        Formatters.bytes(node.sizeBytes)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (node.isDirectory) {
            Icon(
                imageVector = if (row.expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (row.expanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        } else if (canDownload && !selectionMode) {
            TooltipIconButton(tooltip = "Download", onClick = onDownloadClick) {
                Icon(Icons.Filled.Download, contentDescription = "Download")
            }
        }
    }
}
