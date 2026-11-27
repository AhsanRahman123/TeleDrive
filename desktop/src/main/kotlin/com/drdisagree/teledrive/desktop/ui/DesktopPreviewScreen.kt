package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material.icons.filled.Rotate90DegreesCcw
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
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
import com.drdisagree.teledrive.domain.model.ArchiveThumbnailSize
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.common.LinkedText
import com.drdisagree.teledrive.presentation.common.MarkdownText
import com.drdisagree.teledrive.presentation.common.load
import com.drdisagree.teledrive.presentation.common.scaledBy
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
import com.drdisagree.teledrive.presentation.preview.ArchiveBackStep
import com.drdisagree.teledrive.presentation.preview.ArchiveBatchProgress
import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
import com.drdisagree.teledrive.presentation.preview.ArchiveEntryPreview
import com.drdisagree.teledrive.presentation.preview.ArchivePreviewLoading
import com.drdisagree.teledrive.presentation.preview.archiveUnifiedPreviewIndices
import com.drdisagree.teledrive.core.archive.ArchiveEntryThumbnailPolicy
import com.drdisagree.teledrive.desktop.media.ArchiveThumbState
import com.drdisagree.teledrive.desktop.media.DesktopArchiveEntryThumbnailStore
import com.drdisagree.teledrive.presentation.preview.archiveImagePagerIndices
import com.drdisagree.teledrive.presentation.preview.archiveImagePagerNeighbour
import com.drdisagree.teledrive.presentation.preview.comicPageSequence
import com.drdisagree.teledrive.presentation.preview.archiveBackStep
import com.drdisagree.teledrive.presentation.preview.deepestExpandedArchiveFolder
import com.drdisagree.teledrive.presentation.preview.TEXT_PREVIEW_SCALE_MAX
import com.drdisagree.teledrive.presentation.preview.TEXT_PREVIEW_SCALE_MIN
import com.drdisagree.teledrive.presentation.preview.textPreviewScaleZoomIn
import com.drdisagree.teledrive.presentation.preview.textPreviewScaleZoomOut
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
import com.drdisagree.teledrive.resources.preview_archive_downloads_queued
import com.drdisagree.teledrive.resources.preview_archive_remote_hint
import com.drdisagree.teledrive.resources.preview_extraction_supported_yet_download
import kotlin.math.roundToInt
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

    // The ZIP pane registers its internal back step here while it is composed
    // (1.6.24 note 1), mirroring Android's PreviewScreen: one back press first
    // closes an in-zip preview, clears search, exits selection, or collapses the
    // deepest expanded in-zip folder — and only leaves the viewer at the zip root.
    var archiveBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }

    // True only while an in-zip MEDIA entry preview (video or audio) is
    // open — the only archive state in which a media player is composed
    // and can own an Escape press (MF-1 decline scope, delta re-review).
    // Fullscreen is a window-level state also enterable via F11 with no
    // player composed (zip contents list, image/text/PDF entry preview,
    // VLC-unavailable fallback); in those states nothing benefits from
    // declining, so Escape must run the back ladder exactly as baseline.
    val archiveMediaPreviewOpen =
        state.archivePreview is ArchiveEntryPreview.Video ||
            state.archivePreview is ArchiveEntryPreview.Audio

    // The ZIP pane's unified in-zip step (PC 1.6.26 item P3 — the two-level
    // switching rule), registered the same way as the back step. While an
    // in-zip entry preview is open, the handler consumes W/E and steps
    // WITHIN the zip's entries; with no entry open it declines, and W/E
    // keep paging the folder's files from the zip's own position. Before
    // this routing existed, the Scaffold W/E handler below moved the OUTER
    // folder index even while an entry preview was open — W/E inside a zip
    // jumped zip-to-zip through the outer folder sequence.
    var archiveStepHandler by remember { mutableStateOf<((Int) -> Boolean)?>(null) }

    // The id of the file whose pane registered [archiveStepHandler]
    // (PC 1.6.26 second-pass review, MF-2). The pane-side registration is
    // deliberately NOT cleared on dispose (see DesktopArchivePane: during
    // an AnimatedContent transition the outgoing pane's dispose would
    // null the incoming pane's handler), so a disposed pane's handler can
    // outlive its pane — a CBZ's edge pager buttons stay enabled while an
    // entry preview is open (the buttons hide only for .zip files), so
    // the folder can be paged away from a CBZ with its preview still
    // open in the shared ViewModel, and the dead pane's handler would
    // otherwise consume every later W/E and step that invisible preview.
    // The screen therefore only invokes the handler while the file it was
    // registered for is the currently displayed one; a stale registration
    // is ignored (never invoked), and the next archive pane to compose
    // overwrites it.
    var archiveStepHandlerFileId by remember { mutableStateOf<String?>(null) }

    // True while a text/document viewer is composed (PC 1.6.26 item P5),
    // reported by the pane: the normal PlainText/Html contents or an in-zip
    // Text entry preview. The screen's Ctrl+plus/minus/0 routing steps the
    // shared text scale only then, so those keys keep their existing
    // meaning everywhere else. A plain visibility flag (not a handler) on
    // purpose: AnimatedContent keeps the outgoing pane composed during the
    // page transition, and a register/unregister handler pair would let
    // the outgoing pane unregister the incoming pane's handler.
    var textViewerShowing by remember { mutableStateOf(false) }
    val textScale by viewModel.textScale.collectAsState()

    // True while the archive pane's search field holds focus, reported by the
    // pane (1.6.25 item 3): Backspace there must keep deleting text, so the
    // screen-level keyboard back below stays out of its way.
    var archiveTextInputFocused by remember { mutableStateOf(false) }

    fun stepBack() {
        if (archiveBackHandler?.invoke() != true) leavePreview()
    }

    LaunchedEffect(state.closed) {
        if (state.closed) leavePreview()
    }

    var index by remember(state.files, state.initialIndex) {
        mutableStateOf(state.initialIndex.coerceIn(0, (state.files.size - 1).coerceAtLeast(0)))
    }
    val file = state.files.getOrNull(index.coerceIn(0, (state.files.size - 1).coerceAtLeast(0)))

    /**
     * Runs the registered in-zip step for [delta], but only while the
     * pane that registered it belongs to the currently displayed file
     * (MF-2, see [archiveStepHandlerFileId]): a stale registration left
     * behind by a disposed pane — reachable by paging the folder away
     * from a CBZ whose entry preview is still open — is ignored, so the
     * press pages the folder instead of stepping the invisible preview.
     */
    fun invokeArchiveStep(delta: Int): Boolean {
        val handler = archiveStepHandler ?: return false
        if (archiveStepHandlerFileId != file?.id) return false
        return handler(delta)
    }

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
    // The in-zip media player's chrome (PC 1.6.27 N5), reported by the
    // archive pane's player through the same onControlsVisibilityChange
    // channel the route player uses for [playerChromeVisible]. Before
    // this, the in-zip player reported nowhere, so while an in-zip video
    // or audio entry played, the header fell through to the
    // always-visible branch and stayed up when the player UI hid.
    var archivePlayerChromeVisible by remember { mutableStateOf(true) }
    // Keep the image-toolbar preference for the whole preview session, including when
    // moving between image files. H restores hidden controls; the visible toolbar icon hides them.
    var imageChromeVisible by remember { mutableStateOf(true) }
    val previewChromeVisible = previewHeaderVisible(
        mediaCategory = mediaCategory,
        archiveMediaPreviewOpen = archiveMediaPreviewOpen,
        fileCategory = file?.category,
        playerChromeVisible = playerChromeVisible,
        archivePlayerChromeVisible = archivePlayerChromeVisible,
        imageChromeVisible = imageChromeVisible
    )
    // Leaving the in-zip media preview (or stepping to a non-media
    // entry) restores the header for the zip contents list / next
    // surface: a hidden state must never outlive the player that set it.
    LaunchedEffect(archiveMediaPreviewOpen) {
        if (!archiveMediaPreviewOpen) archivePlayerChromeVisible = true
    }
    val viewerFocusRequester = remember { FocusRequester() }
    val windowInfo = LocalWindowInfo.current
    var imageHKeyPressed by remember { mutableStateOf(false) }
    // Reclaim the Scaffold's focus whenever an in-zip entry preview closes
    // (PC 1.6.26 item P3): the overlay that held focus is disposed by the
    // close, and without a deterministic new focus owner the next W/E
    // press can land nowhere — folder-level switching after exiting an
    // entry was intermittent for exactly this reason (the same class of
    // focus loss the overlay focus requesters fix for the open state).
    // Steps between entries (preview stays non-null) do not trigger this;
    // the incoming overlay's own requester owns those.
    val screenPreviewUiState by viewModel.uiState.collectAsState()
    LaunchedEffect(screenPreviewUiState.archivePreview) {
        if (screenPreviewUiState.archivePreview == null && mediaCategory == null) {
            viewerFocusRequester.requestFocus()
        }
    }
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
                // Keyboard back for the archive pane (1.6.25 item 3). This
                // Scaffold node is where focus actually rests during mouse
                // use: the screen requests focus for it on entry and reclaims
                // it on every pointer press (see the pointerInput below), so
                // a Backspace / Alt+Left press after clicking through zip
                // folders lands HERE — never on the archive list, whose
                // keyboard-collection back handler only fires while a list
                // row holds focus. On 1.6.24 the press therefore did nothing
                // at all for a mouse user (the old test focus-walked a row
                // first, a state production never reaches). Route the press
                // through the SAME stepBack() as the top-bar button, but only
                // while an archive pane is composed and its search field is
                // not being edited (Backspace must delete text there).
                if (event.type == KeyEventType.KeyDown &&
                    archiveBackHandler != null &&
                    !archiveTextInputFocused
                ) {
                    // Escape runs the same ladder (PC 1.6.26 item P3): from
                    // an open entry preview it exits to the ZIP's contents
                    // list (folder level), and only at the zip root does it
                    // leave the viewer — the Back/Esc pair the two-level
                    // rule names. Before this, Escape reached only the
                    // in-zip IMAGE overlay's own focus handler, which is
                    // dead after mouse use for the same reason Backspace
                    // was in 1.6.24; text/audio/video/PDF overlays had no
                    // Escape at all.
                    if (event.key == Key.Escape) {
                        // MF-1 (PC 1.6.26 second-pass review): while the
                        // player is fullscreen, Escape belongs to the
                        // player — its own onPreviewKeyEvent exits
                        // fullscreen (DesktopMediaPlayer). Key dispatch
                        // runs this preview phase root-to-leaf, so
                        // consuming here would step the back ladder and
                        // leave the window fullscreen over the zip list.
                        // Decline so the player's handler consumes it;
                        // the ladder runs on the next Escape, once
                        // fullscreen is off. The bubble-phase handler
                        // below applies the same decline for the case
                        // where the player is not in the focus path.
                        // Scoped to an in-zip MEDIA preview actually
                        // being open (archiveMediaPreviewOpen): with no
                        // player composed there is nothing to own the
                        // press, so the ladder runs even while the
                        // window is fullscreen (e.g. via F11).
                        if (archiveMediaPreviewOpen &&
                            fullscreenController?.isFullscreen() == true
                        ) {
                            return@onPreviewKeyEvent false
                        }
                        stepBack()
                        return@onPreviewKeyEvent true
                    }
                    val plainBackspace = event.key == Key.Backspace &&
                        !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed
                    val altLeft = event.key == Key.DirectionLeft &&
                        event.isAltPressed && !event.isCtrlPressed && !event.isShiftPressed
                    if (plainBackspace || altLeft) {
                        stepBack()
                        return@onPreviewKeyEvent true
                    }
                }
                // Text/document zoom keys (PC 1.6.26 item P5): Ctrl+plus /
                // Ctrl+minus step the shared text scale, Ctrl+0 resets it —
                // but only while a text viewer is composed (the pane reports
                // that as textViewerShowing) and never while the archive
                // search field is being edited.
                if (event.type == KeyEventType.KeyDown &&
                    event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed &&
                    !archiveTextInputFocused && textViewerShowing
                ) {
                    val zoomed = when (event.key) {
                        Key.Equals, Key.NumPadAdd -> {
                            viewModel.setTextScale(textPreviewScaleZoomIn(textScale))
                            true
                        }
                        Key.Minus, Key.NumPadSubtract -> {
                            viewModel.setTextScale(textPreviewScaleZoomOut(textScale))
                            true
                        }
                        Key.Zero, Key.NumPad0 -> {
                            viewModel.setTextScale(1f)
                            true
                        }
                        else -> false
                    }
                    if (zoomed) return@onPreviewKeyEvent true
                }
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
                        // Two-level switching (PC 1.6.26 item P3): while an
                        // in-zip entry preview is open, W steps INSIDE the
                        // zip (the pane's handler returns true) and the outer
                        // folder index — the zip's own position — does not
                        // move; after Back/Esc closes the entry, W/E resume
                        // the folder sequence from that position.
                        val steppedInZip = !archiveTextInputFocused &&
                            invokeArchiveStep(-1)
                        if (!steppedInZip && index > 0) index--
                        true
                    }
                    event.key == Key.E -> {
                        val steppedInZip = !archiveTextInputFocused &&
                            invokeArchiveStep(1)
                        if (!steppedInZip && index < state.files.lastIndex) index++
                        true
                    }
                    else -> false
                }
            }
            .onKeyEvent { event ->
                // P2 (1.6.26): Escape is a back key in the preview too,
                // running the SAME stepBack() as the top-bar button (in-zip
                // ladder first, leave the viewer at the zip root / for
                // normal files). This is the BUBBLE phase on purpose: a
                // descendant that owns Escape consumes it first — the VLC
                // surface exits fullscreen, the in-zip image overlay closes
                // itself, a focused archive row clears its selection — and
                // only an unconsumed Escape reaches this handler.
                // MF-1 (PC 1.6.26 second-pass review): one exception —
                // while the player is fullscreen and an archive pane is
                // composed, an Escape that reaches this bubble handler
                // was NOT consumed by the player (it is not in the focus
                // path, e.g. after mouse use reclaimed focus to the
                // Scaffold). Running the ladder here would close the
                // in-zip preview under a still-fullscreen window, the
                // exact failure the preview-phase decline prevents when
                // the player does hold focus — so decline here too.
                // When fullscreen is off, the ladder runs exactly as
                // before. The decline is scoped to an in-zip MEDIA
                // preview actually being open (archiveMediaPreviewOpen):
                // with no player composed (contents list, image/text/PDF
                // preview, VLC-unavailable fallback) Escape runs the
                // ladder even while the window is fullscreen.
                if (event.type == KeyEventType.KeyDown &&
                    event.key == Key.Escape &&
                    !event.isCtrlPressed && !event.isAltPressed && !event.isShiftPressed &&
                    !(archiveBackHandler != null &&
                        archiveMediaPreviewOpen &&
                        fullscreenController?.isFullscreen() == true)
                ) {
                    stepBack()
                    true
                } else {
                    false
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
                    TooltipIconButton(tooltip = stringResource(Res.string.common_back), onClick = ::stepBack) {
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
                        // PC 1.6.25 item 8: top-bar Download beside Info for the
                        // normal photo/video viewers (previously only inside the
                        // overflow menu), routing through the existing normal
                        // download path. Shown under the same condition the
                        // overflow uses (viewerShowsDownloadAction, PC 1.6.27
                        // N1): a photo/video offers Download whenever a remote
                        // copy exists — the old `!hasLocalCopy` half of the
                        // condition dropped the action entirely for a
                        // Gallery-album video that already had a local copy.
                        if (viewerShowsDownloadAction(currentFile)) {
                            TooltipIconButton(
                                tooltip = stringResource(Res.string.common_download),
                                onClick = { viewModel.download(currentFile) }
                            ) {
                                Icon(
                                    Icons.Filled.Download,
                                    contentDescription = stringResource(Res.string.common_download)
                                )
                            }
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
                onPlayerControlsVisibilityChange = { playerChromeVisible = it },
                onArchivePlayerControlsVisibilityChange = { archivePlayerChromeVisible = it },
                onRegisterArchiveBackStep = { handler -> archiveBackHandler = handler },
                onRegisterArchiveStep = { handler ->
                    // MF-2: bind the step registration to THIS pane's
                    // file; the screen invokes it only while that file
                    // is the displayed one (see archiveStepHandlerFileId).
                    archiveStepHandler = handler
                    archiveStepHandlerFileId = if (handler != null) pageFile.id else null
                },
                onTextViewerVisibilityChanged = { showing -> textViewerShowing = showing },
                onArchiveTextInputFocusChanged = { focused ->
                    archiveTextInputFocused = focused
                },
                onExitViewer = ::leavePreview,
                playerSpaceSuppressed = showOverflow || archiveTextInputFocused
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
        if (viewerShowsDownloadAction(file)) {
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

/** Test tag for the ZIP list's thumbnail-size switcher (PC 1.6.26 item P7). */
const val ARCHIVE_THUMBNAIL_SIZE_TAG = "archive-thumbnail-size"

/** Test tags for the text/document zoom controls (PC 1.6.26 item P5). */
const val TEXT_ZOOM_IN_TAG = "text-zoom-in"
const val TEXT_ZOOM_OUT_TAG = "text-zoom-out"
const val TEXT_ZOOM_PERCENT_TAG = "text-zoom-percent"

/**
 * The text/document viewer's zoom controls (PC 1.6.26 item P5): zoom-out
 * button, the current percentage (click resets to 100%), zoom-in button.
 * Every text viewer — the normal PlainText/Html contents and the in-zip
 * Text overlay — renders this same row over the one shared persisted
 * scale, so the size never differs between viewers. No pinch anywhere
 * (standing rule): buttons, Ctrl+wheel and Ctrl+plus/minus/0 are the
 * complete input set.
 */
@Composable
internal fun TextZoomControls(
    scale: Float,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TooltipIconButton(
            tooltip = "Zoom out",
            onClick = onZoomOut,
            enabled = scale > TEXT_PREVIEW_SCALE_MIN,
            modifier = Modifier.testTag(TEXT_ZOOM_OUT_TAG)
        ) {
            Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
        }
        TextButton(
            onClick = onReset,
            modifier = Modifier.testTag(TEXT_ZOOM_PERCENT_TAG)
        ) {
            Text(text = "${(scale * 100).roundToInt()}%")
        }
        TooltipIconButton(
            tooltip = "Zoom in",
            onClick = onZoomIn,
            enabled = scale < TEXT_PREVIEW_SCALE_MAX,
            modifier = Modifier.testTag(TEXT_ZOOM_IN_TAG)
        ) {
            Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in")
        }
    }
}

/**
 * Ctrl+mouse-wheel zoom for a text viewer surface (PC 1.6.26 item P5),
 * following the shared grid zoom's proven Initial-pass pattern: a Ctrl
 * scroll steps the scale one ladder step and is consumed (so the view
 * does not also scroll); a plain scroll is left completely untouched for
 * the view's own scrolling. NOT a pinch/touch gesture — desktop wheel
 * only, per the standing no-pinch rule.
 */
@Composable
internal fun Modifier.textZoomWheel(
    scale: Float,
    onScaleChange: (Float) -> Unit
): Modifier {
    val latestScale = rememberUpdatedState(scale)
    val latestOnChange = rememberUpdatedState(onScaleChange)
    return pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.type != PointerEventType.Scroll) continue
                if (!event.keyboardModifiers.isCtrlPressed) continue
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                when {
                    delta < 0f -> latestOnChange.value(
                        textPreviewScaleZoomIn(latestScale.value)
                    )
                    delta > 0f -> latestOnChange.value(
                        textPreviewScaleZoomOut(latestScale.value)
                    )
                    else -> continue
                }
                event.changes.forEach { it.consume() }
            }
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
    onPlayerControlsVisibilityChange: (Boolean) -> Unit,
    onArchivePlayerControlsVisibilityChange: (Boolean) -> Unit = {},
    onRegisterArchiveBackStep: ((() -> Boolean)?) -> Unit = {},
    onRegisterArchiveStep: (((Int) -> Boolean)?) -> Unit = {},
    onTextViewerVisibilityChanged: (Boolean) -> Unit = {},
    onArchiveTextInputFocusChanged: (Boolean) -> Unit = {},
    onExitViewer: () -> Unit = {},
    /** Screen-level Space suppression for the players (PC 1.6.27 N8 delta). */
    playerSpaceSuppressed: Boolean = false
) {
    val content by viewModel.contentFor(file).collectAsState()
    val backgroundPlayback by viewModel.backgroundPlayback.collectAsState()
    val previewUiState by viewModel.uiState.collectAsState()
    val archiveSort by viewModel.archiveSort.collectAsState()
    val (archiveSortField, archiveSortDirection) = archiveSort
    // Text/document zoom (PC 1.6.26 item P5): the shared persisted scale —
    // the same preference Android's viewer pinches. One value for every
    // text viewer below (normal route and in-zip), so the size is
    // consistent across viewers and files for the whole session.
    val textScale by viewModel.textScale.collectAsState()
    val archiveThumbnailSize by viewModel.archiveThumbnailSize.collectAsState()
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

    // Reports text-viewer visibility up to the screen's Ctrl+plus/minus/0
    // routing (item P5): a text viewer is showing for the normal
    // PlainText/Html contents, or while an in-zip Text entry preview is
    // open over the archive list. Reported on change only — a pane being
    // disposed during a page transition never reports again, so it cannot
    // clobber the incoming pane's report.
    val textViewerShowing = displayedContent is PreviewContent.PlainText ||
        displayedContent is PreviewContent.Html ||
        previewUiState.archivePreview is ArchiveEntryPreview.Text
    LaunchedEffect(textViewerShowing) {
        onTextViewerVisibilityChanged(textViewerShowing)
    }
    val preferredAudioLanguage by viewModel.preferredAudioLanguage.collectAsState()
    val preferredSubtitleLanguage by viewModel.preferredSubtitleLanguage.collectAsState()

    // Resolve local and streamed sources before the content branch so the native VLC player
    // remains at one stable composition call site while moving between cached and streamed files.
    val streamServer = koinInject<MediaStreamServer>()
    val archiveThumbnailStore = koinInject<DesktopArchiveEntryThumbnailStore>()
    // Settled-page priority (PC 1.6.27 N4): while an in-zip preview
    // extraction is running, row thumbnail fetches pause. Both share the
    // archive file's ONE TDLib download window (report 52 §1b): a
    // thumbnail's ranged request re-targets that window, so an eager
    // thumbnail could steal the window from the very page the user is
    // waiting on and leave it blank behind a spinner.
    LaunchedEffect(previewUiState.archiveEntryBusyIndex) {
        archiveThumbnailStore.setPreviewPriorityActive(
            previewUiState.archiveEntryBusyIndex != null
        )
    }
    // Never leak the priority hold into the next archive view if this pane
    // leaves composition while an extraction is still running.
    DisposableEffect(archiveThumbnailStore) {
        onDispose { archiveThumbnailStore.setPreviewPriorityActive(false) }
    }
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
                ZoomablePreviewImage(
                    model = current.model,
                    contentDescription = file.name,
                    imageRotationDegrees = imageRotationDegrees,
                    mirrorImageHorizontally = mirrorImageHorizontally,
                    mirrorImageVertically = mirrorImageVertically,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onToggleChrome = onToggleImageChrome,
                    undecodableFallback = {
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
                    }
                )
            }

            is PreviewContent.LocalMedia -> if (!VlcPlayback.available) {
                OpenExternallyState(
                    icon = if (current.isAudio) Icons.Filled.Audiotrack else Icons.Filled.Movie,
                    file = displayedFile,
                    path = current.path,
                    snackbarHostState = snackbarHostState
                )
            }

            is PreviewContent.Pdf -> DesktopPdfViewer(path = current.path)

            is PreviewContent.Html -> DesktopHtmlViewer(
                html = current.text,
                truncated = current.truncated,
                textScale = textScale,
                onTextScaleChange = viewModel::setTextScale
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
                // Every 1.6.25 text/document viewer lands here on the normal
                // route — EPUB and OOXML extracted text, Markdown, plain
                // text, subtitles and pretty-printed JSON/XML all publish
                // PlainText — so this one branch carries the P5 zoom for all
                // of them: buttons, Ctrl+wheel and Ctrl+plus/minus/0 all
                // step the one shared scale (see TextPreviewZoom.kt).
                val previewTextScrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .textZoomWheel(textScale, viewModel::setTextScale)
                ) {
                    TextZoomControls(
                        scale = textScale,
                        onZoomIn = {
                            viewModel.setTextScale(textPreviewScaleZoomIn(textScale))
                        },
                        onZoomOut = {
                            viewModel.setTextScale(textPreviewScaleZoomOut(textScale))
                        },
                        onReset = { viewModel.setTextScale(1f) },
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(end = 16.dp, top = 8.dp)
                    )
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
                                    onOpenUrl = { url -> urlOpener.open(url) },
                                    textScale = textScale
                                )
                            } else {
                                LinkedText(
                                    text = current.text,
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(fontFamily = FontFamily.Monospace)
                                        .copy(color = MaterialTheme.colorScheme.onSurface)
                                        .scaledBy(textScale),
                                    linkColor = MaterialTheme.colorScheme.primary,
                                    onOpenUrl = { url -> urlOpener.open(url) }
                                )
                            }
                            if (current.truncated) {
                                Text(
                                    text = stringResource(
                                        Res.string.preview_truncated_download_view
                                    ),
                                    style = MaterialTheme.typography.bodySmall
                                        .copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        .scaledBy(textScale)
                                )
                            }
                        }
                        DesktopVerticalScrollbar(
                            state = previewTextScrollState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            }

            is PreviewContent.Archive -> {
                DesktopArchivePane(
                    file = displayedFile,
                    archive = current,
                    busyEntryIndex = previewUiState.archiveEntryBusyIndex,
                    batchProgress = previewUiState.archiveBatchProgress,
                    queuedDownloads = previewUiState.archiveQueuedDownloads,
                    archivePreview = previewUiState.archivePreview,
                    archivePreviewLoading = previewUiState.archivePreviewLoading,
                    thumbnailStore = if (current.remote) archiveThumbnailStore else null,
                    onRegisterBackStep = onRegisterArchiveBackStep,
                    onRegisterStepPreview = onRegisterArchiveStep,
                    thumbnailSize = archiveThumbnailSize,
                    playerSpaceSuppressed = playerSpaceSuppressed,
                    onThumbnailSizeChange = viewModel::setArchiveThumbnailSize,
                    textScale = textScale,
                    onTextScaleChange = viewModel::setTextScale,
                    onTextInputFocusChanged = onArchiveTextInputFocusChanged,
                    onExitViewer = onExitViewer,
                    sortField = archiveSortField,
                    sortDirection = archiveSortDirection,
                    onSortChange = { field, direction ->
                        viewModel.setArchiveSort(field, direction)
                    },
                    onEntryTap = { entryIndex ->
                        viewModel.onArchiveEntryTap(displayedFile, entryIndex)
                    },
                    onPreviewEntry = { entryIndex ->
                        viewModel.previewArchiveEntry(displayedFile, entryIndex)
                    },
                    onVideoEntryTap = { entryIndex ->
                        viewModel.previewArchiveVideoEntry(displayedFile, entryIndex)
                    },
                    onEntryDownload = { entryIndex ->
                        viewModel.downloadArchiveEntry(displayedFile, entryIndex)
                    },
                    onDownloadSelection = { entryIndices, folderPaths ->
                        viewModel.downloadArchiveSelection(
                            displayedFile, entryIndices, folderPaths
                        )
                    },
                    onPlayerControlsVisibilityChange = onArchivePlayerControlsVisibilityChange,
                    onDismissPreview = { viewModel.dismissArchivePreview() },
                    onStepArchivePreview = { delta, sequence ->
                        viewModel.stepArchivePreview(displayedFile, delta, sequence)
                    },
                    onDownloadCurrentEntry = { viewModel.downloadCurrentArchiveEntry() },
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
                spaceSuppressed = playerSpaceSuppressed,
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

/**
 * The desktop photo viewer's zoomable surface, shared verbatim by the real
 * viewer (PreviewContent.Image) and the in-zip photo viewer (1.6.24 note 7 —
 * parity by reuse, not by a forked copy): telephoto zoom capped at 4x, the
 * vertical zoom rail with mouse wheel, the left-drag vertical zoom /
 * right-drag pan / right-swipe navigation mouse behaviour, and the
 * rotate/flip transform applied from caller-owned state. Pinch zoom is NOT
 * added: desktop input stays wheel/buttons/rail exactly as the real viewer
 * has it (standing rule — pinch zoom stays removed).
 */
@OptIn(ExperimentalTelephotoApi::class)
@Composable
internal fun ZoomablePreviewImage(
    model: Any,
    contentDescription: String,
    imageRotationDegrees: Int,
    mirrorImageHorizontally: Boolean,
    mirrorImageVertically: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggleChrome: () -> Unit,
    undecodableFallback: @Composable () -> Unit
) {
    var undecodable by remember(model) { mutableStateOf(false) }
    val zoomableState = rememberZoomableState(
        zoomSpec = ZoomSpec(maxZoomFactor = 4f)
    )
    val zoomScope = rememberCoroutineScope()
    Box(modifier = Modifier.fillMaxSize()) {
        if (undecodable) {
            undecodableFallback()
        } else if (isGifImageModel(model)) {
            // GIF animation (1.6.25 item 5): Coil has no GIF decoder on
            // desktop (coil-gif is Android-only), so GIFs play through the
            // Skia-Codec frame player instead of the Coil painter. The
            // telephoto zoom surface does not apply — rotate/mirror and the
            // chrome toggle behave as in the regular viewer.
            AnimatedGifImage(
                model = model,
                contentDescription = contentDescription,
                imageRotationDegrees = imageRotationDegrees,
                mirrorImageHorizontally = mirrorImageHorizontally,
                mirrorImageVertically = mirrorImageVertically,
                onToggleChrome = onToggleChrome,
                onUndecodable = { undecodable = true },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // AsyncImage sizes the decoded bitmap to the viewport, which makes
            // zooming a low-resolution preview look blurred. The painter intentionally
            // requests the original source dimensions for the viewing surface; grid
            // thumbnails continue to use the separate bounded thumbnail pipeline.
            val imagePainter = rememberAsyncImagePainter(
                model = model,
                onState = { imageState ->
                    if (imageState is AsyncImagePainter.State.Error) undecodable = true
                },
                filterQuality = FilterQuality.High
            )
            Image(
                painter = imagePainter,
                contentDescription = contentDescription,
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
                        onClick = { _ -> onToggleChrome() },
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

/**
 * The in-zip photo viewer (1.6.24 note 7): the extracted temp photo shown with
 * the REAL desktop photo viewer's feature set — parity by reuse, not by a
 * forked copy. The zoom surface, rail, wheel and mouse behaviour come from
 * [ZoomablePreviewImage] (the same composable the real viewer renders); the
 * rotate/flip controls use the same transform-state approach as the real
 * viewer's toolbar (rotation in degrees, two mirror flags), held per photo
 * and reset on switch, like the real viewer resets per file. On top of that,
 * what the in-zip context honestly has: prev/next paging through the photos
 * of the currently displayed zip folder (arrows + keyboard, extraction
 * serialised by the ViewModel's busy gate, a busy spinner while the
 * neighbour extracts, failure keeps the current photo), an info panel built
 * from the archive entry's own metadata, and Edit.
 *
 * Edit verdict: the real viewer's only Edit is the note editor for TEXT
 * files (onEditNote, keyed by DriveFile id); images have no in-app editor
 * there and nothing edit-and-replaces a DriveFile from the image viewer.
 * So for an extracted temp photo the honest equivalent is opening the temp
 * file in the system's default app — the same openExternally route the
 * undecodable fallback uses. Edits land in the temp copy and are NOT saved
 * back into the zip; there is deliberately no fake save-back here.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ArchiveImagePreview(
    preview: ArchiveEntryPreview.Image,
    entry: ArchiveEntry?,
    hasPrevious: Boolean,
    hasNext: Boolean,
    busy: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDismiss: () -> Unit,
    onDownload: () -> Unit = {},
    snackbarHostState: SnackbarHostState
) {
    var rotationDegrees by remember(preview.path) { mutableIntStateOf(0) }
    var mirrorHorizontally by remember(preview.path) { mutableStateOf(false) }
    var mirrorVertically by remember(preview.path) { mutableStateOf(false) }
    var chromeVisible by remember(preview.path) { mutableStateOf(true) }
    var infoOpen by remember(preview.path) { mutableStateOf(false) }
    var openFailed by remember(preview.path) { mutableStateOf(false) }
    if (openFailed) {
        LaunchedEffect(preview.path) {
            snackbarHostState.showSnackbar(getString(DesktopRes.string.preview_open_failed))
            openFailed = false
        }
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(preview.path) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.DirectionLeft, Key.W -> {
                        if (hasPrevious && !busy) onPrevious()
                        true
                    }
                    Key.DirectionRight, Key.E -> {
                        if (hasNext && !busy) onNext()
                        true
                    }
                    Key.H -> {
                        chromeVisible = !chromeVisible
                        true
                    }
                    Key.Escape -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            }
    ) {
        ZoomablePreviewImage(
            model = File(preview.path),
            contentDescription = preview.name,
            imageRotationDegrees = rotationDegrees,
            mirrorImageHorizontally = mirrorHorizontally,
            mirrorImageVertically = mirrorVertically,
            onPrevious = { if (hasPrevious && !busy) onPrevious() },
            onNext = { if (hasNext && !busy) onNext() },
            onToggleChrome = { chromeVisible = !chromeVisible },
            undecodableFallback = {
                EmptyState(
                    icon = Icons.Filled.BrokenImage,
                    title = stringResource(Res.string.preview_image_undecodable),
                    actionLabel = stringResource(DesktopRes.string.preview_open_externally),
                    onAction = { if (!openExternally(preview.path)) openFailed = true }
                )
            }
        )
        if (chromeVisible) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = preview.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp)
                )
                TooltipIconButton(
                    tooltip = "Rotate counter-clockwise",
                    onClick = { rotationDegrees = (rotationDegrees + 270) % 360 }
                ) {
                    Icon(
                        Icons.Filled.Rotate90DegreesCcw,
                        contentDescription = "Rotate counter-clockwise"
                    )
                }
                TooltipIconButton(
                    tooltip = "Rotate clockwise",
                    onClick = { rotationDegrees = (rotationDegrees + 90) % 360 }
                ) {
                    Icon(
                        Icons.Filled.Rotate90DegreesCw,
                        contentDescription = "Rotate clockwise"
                    )
                }
                TooltipIconButton(
                    tooltip = "Mirror horizontally",
                    onClick = { mirrorHorizontally = !mirrorHorizontally }
                ) {
                    Icon(
                        Icons.Filled.Flip,
                        contentDescription = "Mirror horizontally",
                        tint = if (mirrorHorizontally) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                TooltipIconButton(
                    tooltip = "Mirror vertically",
                    onClick = { mirrorVertically = !mirrorVertically }
                ) {
                    Icon(
                        Icons.Filled.FlipCameraAndroid,
                        contentDescription = "Mirror vertically",
                        tint = if (mirrorVertically) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
                TooltipIconButton(
                    tooltip = "Edit",
                    onClick = { if (!openExternally(preview.path)) openFailed = true }
                ) {
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
                TooltipIconButton(
                    tooltip = stringResource(Res.string.common_download),
                    onClick = onDownload,
                    enabled = !busy
                ) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = stringResource(Res.string.common_download)
                    )
                }
                TooltipIconButton(
                    tooltip = stringResource(Res.string.preview_file_info),
                    onClick = { infoOpen = true }
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = stringResource(Res.string.preview_file_info)
                    )
                }
                TooltipIconButton(tooltip = "Close", onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
            if (hasPrevious || hasNext) {
                PreviewPagerButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    contentDescription = stringResource(Res.string.preview_previous),
                    enabled = hasPrevious && !busy,
                    onClick = onPrevious,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp)
                )
                PreviewPagerButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(Res.string.preview_next),
                    enabled = hasNext && !busy,
                    onClick = onNext,
                    // Leave the outermost right edge for the image zoom rail.
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 72.dp)
                )
            }
        }
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
    if (infoOpen) {
        ArchiveEntryInfoDialog(
            previewName = preview.name,
            entry = entry,
            onDismiss = { infoOpen = false }
        )
    }
}

/**
 * File info for an archive entry, adapted from the real viewer's FileInfoSheet
 * to what an entry honestly has: its name, its path inside the zip, its size
 * and compressed size, its stored date, and its type. (No DriveFile identity,
 * no local/remote copy state, no resolution/duration — the entry does not
 * carry them.)
 */
@Composable
private fun ArchiveEntryInfoDialog(
    previewName: String,
    entry: ArchiveEntry?,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(previewName) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry == null) {
                    Text("No entry details are available for this photo.")
                } else {
                    ArchiveInfoRow("Path in archive", entry.name)
                    ArchiveInfoRow("Size", Formatters.bytes(entry.sizeBytes))
                    ArchiveInfoRow(
                        "Compressed size",
                        Formatters.bytes(entry.compressedBytes)
                    )
                    ArchiveInfoRow(
                        "Modified",
                        if (entry.modifiedAtMillis > 0) {
                            Formatters.dateTime(entry.modifiedAtMillis)
                        } else {
                            "Unknown"
                        }
                    )
                    ArchiveInfoRow("Type", MimeTypes.fromFileName(entry.name))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun ArchiveInfoRow(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
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
 *   (`onArchiveEntryTap(file, entryIndex)`): images reach the full photo viewer
 *   overlay (note 7), audio the desktop player (open-externally when VLC is
 *   unavailable), text the read-only overlay, and everything else the tracked
 *   download path. Video taps are intercepted before that shared routing and
 *   play in the desktop player instead (note 9, desktop-only routing; the
 *   row's Download action still downloads). Failures are the ViewModel's
 *   app-authored messages; no raw exception text is surfaced here.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DesktopArchivePane(
    file: DriveFile,
    archive: PreviewContent.Archive,
    busyEntryIndex: Int?,
    batchProgress: ArchiveBatchProgress?,
    queuedDownloads: Int = 0,
    archivePreview: ArchiveEntryPreview?,
    /** Progressive in-zip video start state (PC 1.6.27 N4); see PreviewUiState. */
    archivePreviewLoading: ArchivePreviewLoading? = null,
    thumbnailStore: DesktopArchiveEntryThumbnailStore? = null,
    onRegisterBackStep: ((() -> Boolean)?) -> Unit = {},
    /**
     * Registers the pane's unified in-zip step for the screen-level W/E
     * routing (PC 1.6.26 item P3), mirroring [onRegisterBackStep]: the
     * handler returns true while an entry preview is open (the press is
     * consumed inside the zip, stepped or at the sequence end) and false
     * when no preview is open, letting the press page the outer folder.
     */
    onRegisterStepPreview: (((Int) -> Boolean)?) -> Unit = {},
    /** The persisted ZIP-list thumbnail size (PC 1.6.26 item P7). */
    thumbnailSize: ArchiveThumbnailSize = ArchiveThumbnailSize.MEDIUM,
    onThumbnailSizeChange: (ArchiveThumbnailSize) -> Unit = {},
    /** The shared text zoom scale for the in-zip Text overlay (item P5). */
    textScale: Float = 1f,
    onTextScaleChange: (Float) -> Unit = {},
    /** Reports whether the pane's search field holds focus (1.6.25 item 3). */
    onTextInputFocusChanged: (Boolean) -> Unit = {},
    onExitViewer: () -> Unit = {},
    sortField: FileSortField,
    sortDirection: SortDirection,
    onSortChange: (FileSortField, SortDirection) -> Unit,
    onEntryTap: (Int) -> Unit,
    /** Direct preview request (the in-zip photo pager's neighbour switch). */
    onPreviewEntry: (Int) -> Unit = onEntryTap,
    /**
     * A video entry tap (1.6.24 note 9): the desktop routes it to its own
     * player instead of the shared tap routing, where video stays DOWNLOAD.
     * Defaults to the plain tap so existing callers keep the old behaviour.
     */
    onVideoEntryTap: (Int) -> Unit = onEntryTap,
    onEntryDownload: (Int) -> Unit,
    onDownloadSelection: (Set<Int>, Set<String>) -> Unit,
    onDismissPreview: () -> Unit,
    /** Unified in-zip stepping (item 7): (delta, unified sequence) -> step. */
    onStepArchivePreview: (Int, List<Int>) -> Unit = { _, _ -> },
    /** Download-while-viewing for the open in-zip preview (item 8). */
    onDownloadCurrentEntry: () -> Unit = {},
    /**
     * The in-zip media player's chrome visibility (PC 1.6.27 N5),
     * forwarded to the screen so its header hides and returns together
     * with the player controls, exactly as the route player drives the
     * same header through the pane's onPlayerControlsVisibilityChange.
     */
    onPlayerControlsVisibilityChange: (Boolean) -> Unit = {},
    /** Screen-level Space suppression, forwarded to the in-zip players (N8 delta). */
    playerSpaceSuppressed: Boolean = false,
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
    val urlOpener = LocalUrlOpener.current
    var query by remember(file.id) { mutableStateOf("") }
    var expanded by remember(file.id) { mutableStateOf(emptySet<String>()) }
    val selection = remember(file.id) { ArchiveSelectionState() }
    var rangeBase by remember(file.id) { mutableStateOf<Pair<Set<Int>, Set<String>>?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }
    var thumbnailMenuOpen by remember { mutableStateOf(false) }
    // The in-zip media player's chrome (PC 1.6.27 N5): the video/audio
    // overlays' own ArchiveOverlayBar follows the same visibility the
    // player reports, and every change is also forwarded to the screen
    // (onPlayerControlsVisibilityChange) so the screen header follows
    // too. A newly opened entry starts with chrome shown; the player's
    // own visibility sync reports its real state as soon as it composes.
    var overlayChromeVisible by remember { mutableStateOf(true) }
    LaunchedEffect(archivePreview) {
        overlayChromeVisible = true
    }
    val reportPlayerChrome: (Boolean) -> Unit = { visible ->
        overlayChromeVisible = visible
        onPlayerControlsVisibilityChange(visible)
    }

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

    // The pane's internal back step (1.6.24 note 1), using the SAME shared
    // decision as Android's ArchiveList (archiveBackStep in ArchiveSelection.kt):
    // close an open in-zip preview, clear search, exit selection, then collapse
    // the deepest expanded folder — one folder level per press. Returns true
    // when a step consumed the press; false at the zip root with nothing
    // active, letting the press leave the viewer. Android handles the preview
    // step one level up because its overlay lives in the screen; the desktop
    // overlay lives in this pane, so the pane takes that step itself — the
    // user-visible sequence is identical.
    val handleArchiveBackStep: () -> Boolean = {
        when (
            archiveBackStep(
                hasPreview = archivePreview != null,
                searchActive = query.isNotBlank(),
                selectionActive = selectionMode,
                hasExpandedFolder = expanded.isNotEmpty()
            )
        ) {
            ArchiveBackStep.CLOSE_PREVIEW -> {
                onDismissPreview()
                true
            }
            ArchiveBackStep.CLEAR_SEARCH -> {
                query = ""
                true
            }
            ArchiveBackStep.EXIT_SELECTION -> {
                selection.clear()
                true
            }
            ArchiveBackStep.COLLAPSE_FOLDER -> {
                val target = deepestExpandedArchiveFolder(expanded)
                if (target == null) {
                    false
                } else {
                    expanded = expanded - target
                    true
                }
            }
            ArchiveBackStep.EXIT_VIEWER -> false
        }
    }
    val currentArchiveBackStep by rememberUpdatedState(handleArchiveBackStep)
    DisposableEffect(Unit) {
        onRegisterBackStep { currentArchiveBackStep() }
        onDispose { onRegisterBackStep(null) }
    }

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
        // The keyboard collection's back keys (Alt+Left / Backspace) run the
        // same step ladder as the top-bar back button; at the zip root the
        // ladder reports false and the press exits the viewer, as Android's
        // system back does.
        onBack = {
            if (!handleArchiveBackStep()) onExitViewer()
        }
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
                        .then(dragSelect)
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
                                        // 1.6.25 item 3: the screen-level
                                        // keyboard back must not steal
                                        // Backspace from this field.
                                        .onFocusChanged {
                                            onTextInputFocusChanged(it.isFocused)
                                        }
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
                                Box {
                                    // Thumbnail size switcher (PC 1.6.26 item
                                    // P7): Big / Medium / Small for this
                                    // list's thumbnail slots, persisted via
                                    // the shared `archive_thumbnail_size`
                                    // preference — the Gallery layout
                                    // switcher's pattern applied to the ZIP
                                    // list. PC 1.6.27 (N6): Big is a wide
                                    // 152x112 dp preview tile decoding at
                                    // 512 px; Small / Medium keep the round
                                    // slot and the bounded 256 px decode.
                                    TooltipIconButton(
                                        tooltip = "Thumbnail size",
                                        onClick = { thumbnailMenuOpen = true },
                                        modifier = Modifier.testTag(
                                            ARCHIVE_THUMBNAIL_SIZE_TAG
                                        )
                                    ) {
                                        Icon(
                                            Icons.Filled.PhotoSizeSelectLarge,
                                            contentDescription = "Thumbnail size"
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = thumbnailMenuOpen,
                                        onDismissRequest = { thumbnailMenuOpen = false }
                                    ) {
                                        ArchiveThumbnailSize.entries.forEach { size ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        when (size) {
                                                            ArchiveThumbnailSize.SMALL ->
                                                                "Small"
                                                            ArchiveThumbnailSize.MEDIUM ->
                                                                "Medium"
                                                            ArchiveThumbnailSize.BIG ->
                                                                "Big"
                                                        }
                                                    )
                                                },
                                                onClick = {
                                                    thumbnailMenuOpen = false
                                                    onThumbnailSizeChange(size)
                                                },
                                                trailingIcon = if (size == thumbnailSize) {
                                                    {
                                                        Icon(
                                                            Icons.Filled.Check,
                                                            contentDescription = null
                                                        )
                                                    }
                                                } else {
                                                    null
                                                },
                                                modifier = Modifier.testTag(
                                                    "$ARCHIVE_THUMBNAIL_SIZE_TAG-" +
                                                        size.name.lowercase()
                                                )
                                            )
                                        }
                                    }
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
                            }
                            if (queuedDownloads > 0) {
                                // Accepted downloads waiting behind the running one
                                // (1.6.24 note 2), accounted for in the same
                                // small-header-line pattern as the batch progress.
                                Text(
                                    text = stringResource(
                                        Res.string.preview_archive_downloads_queued,
                                        queuedDownloads
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                            if (batchProgress == null && selectionMode) {
                                Text(
                                    text = "${selection.count} selected",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                    }
                    // No animateItem() here, ever (vc177): the default placement
                    // spring draws rows at displaced offsets during fast scrolls —
                    // the doubled/overlapping row text seen as scroll ghosting.
                    // Row height is likewise constant (see DesktopArchiveRow's
                    // 48.dp text column), so rows never resize mid-scroll either.
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
                            thumbnailSize = thumbnailSize,
                            thumbnailStore = thumbnailStore,
                            archiveFile = file,
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
                                    !busy -> entryIndex?.let { tappedIndex ->
                                        // Desktop-only routing (note 9): a video
                                        // entry plays in the desktop player. The
                                        // shared tap routing still maps video to
                                        // DOWNLOAD (Android unchanged), and the
                                        // row's Download action stays available.
                                        val tappedEntry = listing.getOrNull(tappedIndex)
                                        if (tappedEntry != null &&
                                            MimeTypes.isVideo(
                                                MimeTypes.fromFileName(tappedEntry.name)
                                            )
                                        ) {
                                            onVideoEntryTap(tappedIndex)
                                        } else {
                                            onEntryTap(tappedIndex)
                                        }
                                    }
                                    else -> Unit
                                }
                            },
                            onToggleSelection = { toggleRow(row) },
                            onDownloadClick = {
                                // No busy gate (1.6.24 note 2): a download tap
                                // while another download runs queues behind it
                                // in the ViewModel instead of being dropped.
                                entryIndex?.let { onEntryDownload(it) }
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
                        // Enabled whenever the bar shows — the bar only exists in
                        // selection mode, i.e. something is selected (1.6.24 note
                        // 2). A batch started while a download runs queues behind
                        // it in the ViewModel instead of being refused.
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
        // The UNIFIED in-zip stepping sequence (PC 1.6.25 item 7): ALL
        // previewable entries among the rows as currently displayed (tree +
        // sort + filter applied), in display order, across types — stepping
        // from any in-zip preview (photo / video / audio / text / PDF) moves to
        // the neighbouring previewable entry and opens its own surface. Desktop
        // has no touch swipe: the keyboard arrows / W-E keys and the on-screen
        // prev/next controls (image arrows, player Skip buttons, overlay
        // buttons) are the desktop equivalent and drive this same sequence.
        val unifiedPreviewIndices = remember(rows, listing) {
            archiveUnifiedPreviewIndices(listing, rows.mapNotNull { it.node.entryIndex })
        }
        // The in-zip photo pager's stops for a CBZ (1.6.25 item 5, Android
        // parity): image pages only, in natural path (comic) order — not the
        // archive list's sort and not the unified display order. The Image
        // overlay below pages through THIS sequence for a CBZ; every other
        // archive (and every non-image overlay) uses the unified sequence.
        val displayedImageIndices = remember(rows, listing, archive.format) {
            if (archive.format == "CBZ") {
                comicPageSequence(listing)
            } else {
                archiveImagePagerIndices(listing, rows.mapNotNull { it.node.entryIndex })
            }
        }
        // The in-zip photo pager's sequence: CBZ pages step in comic
        // (natural) order; all other archives step the unified
        // display-order sequence. Hoisted out of the Image overlay (PC
        // 1.6.26 item P3) so the screen-level W/E step handler below and
        // the overlay's own arrows/buttons drive ONE stepping rule.
        val imageSequence = if (archive.format == "CBZ") {
            displayedImageIndices
        } else {
            unifiedPreviewIndices
        }
        // The photo pager steps through onPreviewEntry — the direct
        // preview request this pane's parameter documents as "the in-zip
        // photo pager's neighbour switch". A step that lands on a VIDEO
        // entry (the unified sequence includes video) routes to
        // onVideoEntryTap instead — the same desktop-only video routing
        // the row tap uses, and the same split
        // PreviewViewModel.stepArchivePreview applies — so a video stop
        // opens the player, never the shared download routing.
        val stepImagePager: (Int, Int) -> Unit = { currentEntryIndex, delta ->
            archiveImagePagerNeighbour(
                imageSequence, currentEntryIndex, delta
            )?.let { targetIndex ->
                val targetEntry = listing.getOrNull(targetIndex)
                if (targetEntry != null &&
                    MimeTypes.isVideo(
                        MimeTypes.fromFileName(targetEntry.name)
                    )
                ) {
                    onVideoEntryTap(targetIndex)
                } else {
                    onPreviewEntry(targetIndex)
                }
            }
        }
        // The two-level switching rule (PC 1.6.26 item P3): while an entry
        // preview is open, the viewer is at ZIP level — a step moves ONLY
        // within this zip's entries (the unified sequence, or the photo
        // pager's sequence for an image preview) and the press is consumed
        // even at the sequence ends and while a neighbour extracts, so it
        // can never leak out and page the folder's files. With no preview
        // open the handler declines and the press pages the folder, where
        // this zip is one ordinary stop whose contents list is showing.
        val handleArchiveStep: (Int) -> Boolean = { delta ->
            val preview = archivePreview
            if (preview == null) {
                false
            } else {
                if (busyEntryIndex == null) {
                    if (preview is ArchiveEntryPreview.Image) {
                        stepImagePager(preview.entryIndex, delta)
                    } else {
                        onStepArchivePreview(delta, unifiedPreviewIndices)
                    }
                }
                true
            }
        }
        val currentArchiveStep by rememberUpdatedState(handleArchiveStep)
        // Registered by effect, NOT by a dispose-clearing DisposableEffect:
        // paging folder-level from one zip straight to another keeps both
        // panes composed during the AnimatedContent transition, and the
        // outgoing pane's dispose would null the incoming pane's handler
        // (the back-step registration above predates this concern and is
        // left exactly as field-passed). A stale registration left behind
        // by a disposed pane is NOT harmless on its own — a CBZ's edge
        // pager buttons stay enabled while an entry preview is open, so
        // the folder can be paged away with this pane's preview still
        // open, and the captured handler would keep consuming steps
        // (MF-2, PC 1.6.26 second-pass review). The bound lives on the
        // screen side instead: the registration is keyed by this pane's
        // file and the screen invokes it only while that file is the
        // displayed one, so a stale handler is never invoked, and the
        // next archive pane's effect overwrites it.
        LaunchedEffect(currentArchiveStep) {
            onRegisterStepPreview { delta -> currentArchiveStep(delta) }
        }
        // In-archive preview overlay: image in the full desktop photo viewer
        // (note 7), audio AND video in the desktop player (notes 9 — video is
        // routed here by the desktop pane, never by the shared tap action),
        // text read-only.
        when (val preview = archivePreview) {
            is ArchiveEntryPreview.Image -> {
                // The sequence and the stepping rule are the hoisted ones
                // above (shared with the screen-level W/E handler, item P3):
                // CBZ pages step in comic (natural) order; all other
                // archives step the unified display-order sequence through
                // onPreviewEntry, with video targets routed to the player.
                val pagerPosition = imageSequence.indexOf(preview.entryIndex)
                ArchiveImagePreview(
                    preview = preview,
                    entry = listing.getOrNull(preview.entryIndex),
                    hasPrevious = pagerPosition > 0,
                    hasNext = pagerPosition >= 0 &&
                        pagerPosition < imageSequence.lastIndex,
                    busy = busyEntryIndex != null,
                    onPrevious = { stepImagePager(preview.entryIndex, -1) },
                    onNext = { stepImagePager(preview.entryIndex, +1) },
                    onDismiss = onDismissPreview,
                    onDownload = onDownloadCurrentEntry,
                    snackbarHostState = snackbarHostState
                )
            }

            is ArchiveEntryPreview.Video -> {
                val stepPosition = unifiedPreviewIndices.indexOf(preview.entryIndex)
                val stepHasPrevious = stepPosition > 0
                val stepHasNext = stepPosition >= 0 &&
                    stepPosition < unifiedPreviewIndices.lastIndex
                var infoOpen by remember(preview.entryIndex) { mutableStateOf(false) }
                val overlayFocusRequester = rememberOverlayFocusRequester(preview.entryIndex)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .focusRequester(overlayFocusRequester)
                        .focusable()
                ) {
                    if (VlcPlayback.available) {
                        DesktopMediaPlayer(
                            mrl = preview.path,
                            isAudio = false,
                            mediaId = "archive-video-${preview.name}",
                            mediaTitle = preview.name,
                            spaceSuppressed = playerSpaceSuppressed || sortMenuOpen || thumbnailMenuOpen,
                            preferredAudioLanguage = preferredAudioLanguage,
                            preferredSubtitleLanguage = preferredSubtitleLanguage,
                            onPreferredAudioLanguage = onPreferredAudioLanguage,
                            onPreferredSubtitleLanguage = onPreferredSubtitleLanguage,
                            // Item 7: the player's own SkipPrevious/SkipNext
                            // buttons step the UNIFIED sequence (a step can
                            // land on an image/audio/text entry, which then
                            // opens its own surface instead of this player).
                            onPrevious = { onStepArchivePreview(-1, unifiedPreviewIndices) },
                            onNext = { onStepArchivePreview(+1, unifiedPreviewIndices) },
                            hasPrevious = stepHasPrevious && busyEntryIndex == null,
                            hasNext = stepHasNext && busyEntryIndex == null,
                            onControlsVisibilityChange = reportPlayerChrome,
                            backgroundPlayback = backgroundPlayback,
                            onBackgroundPlaybackChange = onBackgroundPlaybackChange
                        )
                    } else {
                        OpenExternallyState(
                            icon = Icons.Filled.Movie,
                            file = file,
                            path = preview.path,
                            snackbarHostState = snackbarHostState
                        )
                    }
                    AnimatedVisibility(
                        visible = overlayChromeVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        ArchiveOverlayBar(
                            name = preview.name,
                            busy = busyEntryIndex != null,
                            hasPrevious = stepHasPrevious,
                            hasNext = stepHasNext,
                            onPrevious = { onStepArchivePreview(-1, unifiedPreviewIndices) },
                            onNext = { onStepArchivePreview(+1, unifiedPreviewIndices) },
                            onDownload = onDownloadCurrentEntry,
                            onInfo = { infoOpen = true },
                            onDismiss = onDismissPreview
                        )
                    }
                    // Background fetch still running behind playback
                    // (PC 1.6.27 N4): a slim, honest progress line. The
                    // copy being played is a growing temp file — not yet
                    // complete and not yet integrity-checked — and this
                    // label never presents it as a finished download.
                    val videoLoading = archivePreviewLoading
                    if (videoLoading != null &&
                        videoLoading.entryIndex == preview.entryIndex &&
                        videoLoading.totalBytes > 0L
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                                )
                                .padding(horizontal = 16.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "Still loading in the background… " +
                                    "${Formatters.bytes(videoLoading.producedBytes)} of " +
                                    Formatters.bytes(videoLoading.totalBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = {
                                    (videoLoading.producedBytes.toFloat() /
                                        videoLoading.totalBytes.toFloat())
                                        .coerceIn(0f, 1f)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                if (infoOpen) {
                    ArchiveEntryInfoDialog(
                        previewName = preview.name,
                        entry = listing.getOrNull(preview.entryIndex),
                        onDismiss = { infoOpen = false }
                    )
                }
            }

            is ArchiveEntryPreview.Audio -> {
                val stepPosition = unifiedPreviewIndices.indexOf(preview.entryIndex)
                val stepHasPrevious = stepPosition > 0
                val stepHasNext = stepPosition >= 0 &&
                    stepPosition < unifiedPreviewIndices.lastIndex
                var infoOpen by remember(preview.entryIndex) { mutableStateOf(false) }
                val overlayFocusRequester = rememberOverlayFocusRequester(preview.entryIndex)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .focusRequester(overlayFocusRequester)
                        .focusable()
                ) {
                    if (VlcPlayback.available) {
                        DesktopMediaPlayer(
                            mrl = preview.path,
                            isAudio = true,
                            mediaId = "archive-audio-${preview.name}",
                            mediaTitle = preview.name,
                            spaceSuppressed = playerSpaceSuppressed || sortMenuOpen || thumbnailMenuOpen,
                            preferredAudioLanguage = preferredAudioLanguage,
                            preferredSubtitleLanguage = preferredSubtitleLanguage,
                            onPreferredAudioLanguage = onPreferredAudioLanguage,
                            onPreferredSubtitleLanguage = onPreferredSubtitleLanguage,
                            onPrevious = { onStepArchivePreview(-1, unifiedPreviewIndices) },
                            onNext = { onStepArchivePreview(+1, unifiedPreviewIndices) },
                            hasPrevious = stepHasPrevious && busyEntryIndex == null,
                            hasNext = stepHasNext && busyEntryIndex == null,
                            onControlsVisibilityChange = reportPlayerChrome,
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
                    AnimatedVisibility(
                        visible = overlayChromeVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier.align(Alignment.TopCenter)
                    ) {
                        ArchiveOverlayBar(
                            name = preview.name,
                            busy = busyEntryIndex != null,
                            hasPrevious = stepHasPrevious,
                            hasNext = stepHasNext,
                            onPrevious = { onStepArchivePreview(-1, unifiedPreviewIndices) },
                            onNext = { onStepArchivePreview(+1, unifiedPreviewIndices) },
                            onDownload = onDownloadCurrentEntry,
                            onInfo = { infoOpen = true },
                            onDismiss = onDismissPreview
                        )
                    }
                }
                if (infoOpen) {
                    ArchiveEntryInfoDialog(
                        previewName = preview.name,
                        entry = listing.getOrNull(preview.entryIndex),
                        onDismiss = { infoOpen = false }
                    )
                }
            }

            is ArchiveEntryPreview.Pdf -> {
                val stepPosition = unifiedPreviewIndices.indexOf(preview.entryIndex)
                val stepHasPrevious = stepPosition > 0
                val stepHasNext = stepPosition >= 0 &&
                    stepPosition < unifiedPreviewIndices.lastIndex
                var infoOpen by remember(preview.entryIndex) { mutableStateOf(false) }
                val overlayFocusRequester = rememberOverlayFocusRequester(preview.entryIndex)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .focusRequester(overlayFocusRequester)
                        .focusable()
                ) {
                    DesktopPdfViewer(path = preview.path)
                    ArchiveOverlayBar(
                        name = preview.name,
                        busy = busyEntryIndex != null,
                        hasPrevious = stepHasPrevious,
                        hasNext = stepHasNext,
                        onPrevious = { onStepArchivePreview(-1, unifiedPreviewIndices) },
                        onNext = { onStepArchivePreview(+1, unifiedPreviewIndices) },
                        onDownload = onDownloadCurrentEntry,
                        onInfo = { infoOpen = true },
                        onDismiss = onDismissPreview,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
                if (infoOpen) {
                    ArchiveEntryInfoDialog(
                        previewName = preview.name,
                        entry = listing.getOrNull(preview.entryIndex),
                        onDismiss = { infoOpen = false }
                    )
                }
            }

            is ArchiveEntryPreview.Text -> {
                val stepPosition = unifiedPreviewIndices.indexOf(preview.entryIndex)
                val stepHasPrevious = stepPosition > 0
                val stepHasNext = stepPosition >= 0 &&
                    stepPosition < unifiedPreviewIndices.lastIndex
                var infoOpen by remember(preview.entryIndex) { mutableStateOf(false) }
                val overlayFocusRequester = rememberOverlayFocusRequester(preview.entryIndex)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .focusRequester(overlayFocusRequester)
                        .focusable()
                        // Ctrl+wheel zoom for the non-HTML text surface
                        // (item P5); the HTML viewer below installs its own.
                        .then(
                            if (preview.isHtml) {
                                Modifier
                            } else {
                                Modifier.textZoomWheel(textScale, onTextScaleChange)
                            }
                        )
                ) {
                    if (preview.isHtml) {
                        // The HTML viewer scrolls itself; it must not sit
                        // inside the scrolling text column below.
                        DesktopHtmlViewer(
                            html = preview.text,
                            truncated = preview.truncated,
                            textScale = textScale,
                            onTextScaleChange = onTextScaleChange
                        )
                    } else {
                        val textScrollState = rememberScrollState()
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(textScrollState)
                                .padding(start = 24.dp, end = 24.dp, top = 64.dp, bottom = 24.dp)
                        ) {
                            if (preview.isMarkdown) {
                                MarkdownText(
                                    text = preview.text,
                                    onOpenUrl = { url -> urlOpener.open(url) },
                                    textScale = textScale
                                )
                            } else {
                                Text(
                                    text = preview.text,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace
                                    ).scaledBy(textScale),
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (preview.truncated) {
                                Text(
                                    text = stringResource(
                                        Res.string.preview_truncated_download_view
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        // The same zoom controls as the normal text viewer
                        // (item P5), over the same shared scale.
                        TextZoomControls(
                            scale = textScale,
                            onZoomIn = {
                                onTextScaleChange(textPreviewScaleZoomIn(textScale))
                            },
                            onZoomOut = {
                                onTextScaleChange(textPreviewScaleZoomOut(textScale))
                            },
                            onReset = { onTextScaleChange(1f) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp)
                                .background(
                                    MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                    MaterialTheme.shapes.medium
                                )
                                .padding(horizontal = 4.dp)
                        )
                    }
                    ArchiveOverlayBar(
                        name = preview.name,
                        busy = busyEntryIndex != null,
                        hasPrevious = stepHasPrevious,
                        hasNext = stepHasNext,
                        onPrevious = { onStepArchivePreview(-1, unifiedPreviewIndices) },
                        onNext = { onStepArchivePreview(+1, unifiedPreviewIndices) },
                        onDownload = onDownloadCurrentEntry,
                        onInfo = { infoOpen = true },
                        onDismiss = onDismissPreview,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
                if (infoOpen) {
                    ArchiveEntryInfoDialog(
                        previewName = preview.name,
                        entry = listing.getOrNull(preview.entryIndex),
                        onDismiss = { infoOpen = false }
                    )
                }
            }

            null -> {
                // Progressive video start (PC 1.6.27 N4): no preview is
                // published yet, but the load is running — show its real
                // state instead of the bare list behind a row spinner.
                // The delayed case (moov-at-end MP4) says plainly why this
                // one cannot start early; the numbers are produced bytes
                // of THIS entry, never a fake percentage.
                val loading = archivePreviewLoading
                if (loading != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    ) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                text = loading.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (loading.totalBytes > 0L) {
                                    "Loading… ${Formatters.bytes(loading.producedBytes)} of " +
                                        Formatters.bytes(loading.totalBytes)
                                } else {
                                    "Loading…"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val loadingNote = loading.note
                            if (loading.delayedUntilComplete && loadingNote != null) {
                                Text(
                                    text = loadingNote,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        ArchiveOverlayBar(
                            name = loading.name,
                            busy = true,
                            hasPrevious = false,
                            hasNext = false,
                            onPrevious = {},
                            onNext = {},
                            onDownload = onDownloadCurrentEntry,
                            onInfo = {},
                            onDismiss = onDismissPreview,
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Focus for a non-image in-zip overlay (PC 1.6.26 item P3): the image
 * overlay has always requested focus for itself on mount, which is what
 * keeps keyboard switching alive while it is open. The video/audio/PDF/
 * text overlays never did — so stepping from an image entry onto one of
 * them disposed the focused image surface and left NOTHING focused, and
 * a keyboard user's W/E presses went nowhere until the next mouse click
 * (proven by DesktopTwoLevelSwitchingTest's diagnostic probe: the key
 * step failed while the same step from the overlay's Next button
 * worked). Each overlay now requests focus for its own root whenever it
 * opens or steps to another entry, exactly like the image overlay.
 */
@Composable
private fun rememberOverlayFocusRequester(entryIndex: Int): FocusRequester {
    val requester = remember { FocusRequester() }
    LaunchedEffect(entryIndex) { requester.requestFocus() }
    return requester
}

/**
 * Top bar for the non-image in-zip overlays (video / audio / text), PC 1.6.25
 * items 7+8: title, unified-sequence prev/next, Download-while-viewing and
 * Info — the actions the image overlay's toolbar already had — plus Close.
 * The image overlay keeps its own richer toolbar (rotate/flip/Edit) and gains
 * the same Download button there.
 */
@Composable
private fun ArchiveOverlayBar(
    name: String,
    busy: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDownload: () -> Unit,
    onInfo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp)
        )
        TooltipIconButton(
            tooltip = stringResource(Res.string.preview_previous),
            onClick = onPrevious,
            enabled = hasPrevious && !busy
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(Res.string.preview_previous)
            )
        }
        TooltipIconButton(
            tooltip = stringResource(Res.string.preview_next),
            onClick = onNext,
            enabled = hasNext && !busy
        ) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(Res.string.preview_next)
            )
        }
        TooltipIconButton(
            tooltip = stringResource(Res.string.common_download),
            onClick = onDownload,
            enabled = !busy
        ) {
            Icon(
                Icons.Filled.Download,
                contentDescription = stringResource(Res.string.common_download)
            )
        }
        TooltipIconButton(
            tooltip = stringResource(Res.string.preview_file_info),
            onClick = onInfo
        ) {
            Icon(
                Icons.Filled.Info,
                contentDescription = stringResource(Res.string.preview_file_info)
            )
        }
        TooltipIconButton(tooltip = "Close", onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close")
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
    /** The persisted thumbnail step (PC 1.6.26 item P7; PC steps re-mapped in 1.6.27 item N6). */
    thumbnailSize: ArchiveThumbnailSize = ArchiveThumbnailSize.MEDIUM,
    thumbnailStore: DesktopArchiveEntryThumbnailStore? = null,
    archiveFile: DriveFile? = null,
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
        // PC 1.6.25 item 6 (Android vc182 design, report 55 §6): for image
        // entries of a remote archive, the 56 dp slot shows a real thumbnail
        // once the store has one; until then (and on any failure) it shows
        // exactly the generic icon it always showed. The key derives from
        // this row's own listing identity only — no list-level state, and
        // only rows actually composed (visible) ever request a fetch.
        val thumbnailKey = remember(
            archiveFile?.id, node.entryIndex, node.sizeBytes, node.compressedBytes,
            node.modifiedAtMillis
        ) {
            val owner = archiveFile
            val idx = node.entryIndex
            if (owner != null && idx != null && !node.isDirectory &&
                ArchiveEntryThumbnailPolicy.isImageEntryName(node.name)
            ) {
                ArchiveEntryThumbnailPolicy.cacheKey(
                    archiveFileId = owner.id,
                    entryIndex = idx,
                    sizeBytes = node.sizeBytes,
                    compressedBytes = node.compressedBytes,
                    modifiedAtMillis = node.modifiedAtMillis
                )
            } else {
                null
            }
        }
        val isBigThumbnail = thumbnailSize == ArchiveThumbnailSize.BIG
        val genericIcon: @Composable () -> Unit = {
            Icon(
                imageVector = if (node.isDirectory) {
                    Icons.Filled.Folder
                } else {
                    Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = null,
                modifier = Modifier.size(if (isBigThumbnail) 40.dp else 20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                // The switchable slot (item P7; PC steps re-mapped by
                // 1.6.27 item N6): Small / Medium keep the round slot at
                // 40 / 56 dp; Big is a wide 152x112 dp rounded-rectangle
                // preview tile, so the thumbnail — not empty row space —
                // dominates the row, and the store decodes it at the
                // step's own 512 px cap so the tile is actually sharp.
                .size(
                    width = thumbnailSize.widthDp.dp,
                    height = thumbnailSize.dp.dp
                )
                .testTag(
                    if (node.entryIndex != null) {
                        "archive-thumbnail-${node.entryIndex}"
                    } else {
                        "archive-thumbnail-folder"
                    }
                )
                .clip(
                    if (isBigThumbnail) {
                        RoundedCornerShape(12.dp)
                    } else {
                        CircleShape
                    }
                )
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            if (thumbnailKey != null && thumbnailStore != null && archiveFile != null &&
                node.entryIndex != null
            ) {
                DesktopArchiveEntryThumbnail(
                    store = thumbnailStore,
                    archiveFile = archiveFile,
                    entryIndex = node.entryIndex!!,
                    entryName = node.name,
                    sizeBytes = node.sizeBytes,
                    cacheKey = thumbnailKey,
                    decodePx = thumbnailSize.decodePx,
                    placeholder = genericIcon
                )
            } else {
                genericIcon()
            }
        }
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


/**
 * One archive row's thumbnail (PC 1.6.25 item 6): asks
 * [DesktopArchiveEntryThumbnailStore] for this entry's thumbnail while composed
 * and releases the request on dispose, so a row that scrolls off cancels its
 * fetch if nobody else waits on it. Shows [placeholder] (the row's generic
 * icon) until the thumbnail is ready, and keeps showing it on failure. No
 * crossfade: per-row fades during a fling read as flicker.
 */
@Composable
private fun DesktopArchiveEntryThumbnail(
    store: DesktopArchiveEntryThumbnailStore,
    archiveFile: DriveFile,
    entryIndex: Int,
    entryName: String,
    sizeBytes: Long,
    cacheKey: String,
    decodePx: Int = ArchiveEntryThumbnailPolicy.THUMBNAIL_SIZE_PX,
    placeholder: @Composable () -> Unit
) {
    val state by store.state(cacheKey, decodePx).collectAsState()
    var loadFailed by remember(cacheKey, decodePx) { mutableStateOf(false) }
    LaunchedEffect(cacheKey, decodePx) {
        store.request(cacheKey, archiveFile, entryIndex, entryName, sizeBytes, decodePx)
    }
    DisposableEffect(cacheKey, decodePx) {
        onDispose { store.release(cacheKey, decodePx) }
    }
    val readyFile = (state as? ArchiveThumbState.Ready)?.file
    if (readyFile != null && !loadFailed) {
        val painter = rememberAsyncImagePainter(
            model = readyFile,
            onState = { imageState ->
                if (imageState is AsyncImagePainter.State.Error) loadFailed = true
            }
        )
        Image(
            painter = painter,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
    } else {
        placeholder()
    }
}
