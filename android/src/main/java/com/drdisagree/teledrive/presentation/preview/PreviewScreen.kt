package com.drdisagree.teledrive.presentation.preview

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.random.Random
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import androidx.media3.common.util.UnstableApi
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.PipController
import com.drdisagree.teledrive.core.media.TelegramDataSourceFactory
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.model.LinkMetadata
import com.drdisagree.teledrive.domain.model.PreviewBackgroundMode
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.common.LinkedText
import com.drdisagree.teledrive.presentation.common.MarkdownText
import com.drdisagree.teledrive.presentation.common.add
import com.drdisagree.teledrive.presentation.common.normalizeUrl
import com.drdisagree.teledrive.presentation.common.scaledBy
import com.drdisagree.teledrive.presentation.common.soleUrlOf
import com.drdisagree.teledrive.presentation.components.ArchiveEntryInfoSheet
import com.drdisagree.teledrive.presentation.components.ConfirmDialog
import com.drdisagree.teledrive.presentation.files.FolderPickerHost
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.ErrorState
import com.drdisagree.teledrive.presentation.components.FileInfoSheet
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.presentation.components.SelectionCheckbox
import com.drdisagree.teledrive.presentation.components.SortMenu
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import com.drdisagree.teledrive.presentation.components.RenameDialog
import com.drdisagree.teledrive.presentation.components.iconFor
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.drdisagree.teledrive.core.archive.ArchiveEntryThumbnailPolicy
import com.drdisagree.teledrive.core.media.ArchiveEntryThumbnailStore
import com.drdisagree.teledrive.core.media.ArchiveThumbState
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_actions
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.common_cancel
import com.drdisagree.teledrive.resources.common_select_all
import com.drdisagree.teledrive.resources.common_collapse
import com.drdisagree.teledrive.resources.common_download
import com.drdisagree.teledrive.resources.files_copy_here
import com.drdisagree.teledrive.resources.files_search
import com.drdisagree.teledrive.resources.files_sort
import com.drdisagree.teledrive.resources.picker_selected_count
import com.drdisagree.teledrive.resources.preview_archive_download_selected
import com.drdisagree.teledrive.resources.preview_archive_entry_saving
import com.drdisagree.teledrive.resources.files_copy_to
import com.drdisagree.teledrive.resources.files_move_here
import com.drdisagree.teledrive.resources.files_move_to
import com.drdisagree.teledrive.resources.common_expand
import com.drdisagree.teledrive.resources.common_free_space
import com.drdisagree.teledrive.resources.common_move_trash
import com.drdisagree.teledrive.resources.common_rename
import com.drdisagree.teledrive.resources.common_rename_file
import com.drdisagree.teledrive.resources.common_upload
import com.drdisagree.teledrive.resources.file_count
import com.drdisagree.teledrive.resources.files_hide
import com.drdisagree.teledrive.resources.files_unhide
import com.drdisagree.teledrive.resources.folder_count
import com.drdisagree.teledrive.resources.item_count
import com.drdisagree.teledrive.resources.note_edit_action
import com.drdisagree.teledrive.resources.preview_add_favorites
import com.drdisagree.teledrive.resources.player_play
import com.drdisagree.teledrive.resources.preview_archive
import com.drdisagree.teledrive.resources.preview_archive_format_title
import com.drdisagree.teledrive.resources.preview_archive_packed_summary
import com.drdisagree.teledrive.resources.preview_archive_packed_summary_saved
import com.drdisagree.teledrive.resources.preview_chevron
import com.drdisagree.teledrive.resources.preview_confirm_trash_file_message
import com.drdisagree.teledrive.resources.preview_download_view
import com.drdisagree.teledrive.resources.preview_archive_remote_hint
import com.drdisagree.teledrive.resources.preview_extraction_supported_yet_download
import com.drdisagree.teledrive.resources.preview_file_info
import com.drdisagree.teledrive.resources.preview_move_to_trash
import com.drdisagree.teledrive.resources.preview_no_preview
import com.drdisagree.teledrive.resources.preview_open_with
import com.drdisagree.teledrive.resources.preview_open_with_failed
import com.drdisagree.teledrive.resources.preview_preparing
import com.drdisagree.teledrive.resources.preview_progress_bytes
import com.drdisagree.teledrive.resources.preview_remove_favorites
import com.drdisagree.teledrive.resources.preview_requires_download
import com.drdisagree.teledrive.resources.preview_share_chooser_title
import com.drdisagree.teledrive.resources.preview_share_copy
import com.drdisagree.teledrive.resources.preview_truncated_download_view
import com.drdisagree.teledrive.resources.preview_unarchive
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PreviewScreen(
    onBack: () -> Unit,
    onEditNote: (String, String) -> Unit,
    viewModel: PreviewViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val infoTarget by viewModel.infoTarget.collectAsStateWithLifecycle()
    val archiveInfoTarget by viewModel.archiveInfoTarget.collectAsStateWithLifecycle()
    val backgroundPlayback by viewModel.backgroundPlayback.collectAsStateWithLifecycle()
    val preferredAudioLanguage by viewModel.preferredAudioLanguage.collectAsStateWithLifecycle()
    val preferredSubtitleLanguage by
    viewModel.preferredSubtitleLanguage.collectAsStateWithLifecycle()
    val storedTextScale by viewModel.textScale.collectAsStateWithLifecycle()
    val archiveSort by viewModel.archiveSort.collectAsStateWithLifecycle()
    val previewBackgroundMode by viewModel.previewBackgroundMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val shareChooserTitle = stringResource(Res.string.preview_share_chooser_title)
    val dataSourceFactory = koinInject<TelegramDataSourceFactory>()

    var renameTarget by remember { mutableStateOf<DriveFile?>(null) }
    var deleteTarget by remember { mutableStateOf<DriveFile?>(null) }
    var copyTarget by remember { mutableStateOf<DriveFile?>(null) }
    var moveTarget by remember { mutableStateOf<DriveFile?>(null) }
    var showOverflow by remember { mutableStateOf(false) }

    val deleteConsentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.retryLocalCopyRemoval()
    }

    // The archive list (when the current pager page shows one) registers its internal
    // step-back here: it consumes a back press for search / selection / folder steps
    // and returns false when it is already at the zip root with nothing active.
    var archiveBackHandler by remember { mutableStateOf<(() -> Boolean)?>(null) }

    // The ONE step-back for this screen, used by the top-bar back arrow, the in-zip
    // preview's back arrow and the system back button (BackHandler below) alike, so
    // they can never drift apart. It walks the viewer's internal state in the
    // priority order archiveBackStep() encodes: close an open in-zip preview first,
    // then let the archive list clear its search, exit selection mode, or go up one
    // in-zip folder level — and only leave the viewer when nothing internal consumed
    // the press. For non-archive previews no handler is registered and no preview is
    // open, so this is exactly the old plain onBack().
    val stepBack: () -> Unit = {
        if (state.archivePreview != null) {
            viewModel.dismissArchivePreview()
        } else if (archiveBackHandler?.invoke() != true) {
            onBack()
        }
    }
    BackHandler { stepBack() }

    CollectSnackbarMessages(viewModel.messages, snackbarHostState)
    LaunchedEffect(Unit) {
        viewModel.deleteConsentRequests.collect { request ->
            deleteConsentLauncher.launch(IntentSenderRequest.Builder(request).build())
        }
    }
    LaunchedEffect(state.closed) {
        if (state.closed) onBack()
    }

    if (!state.ready) {
        LoadingState()
        return
    }
    if (state.files.isEmpty()) {
        onBack()
        return
    }

    val previewScope = rememberCoroutineScope()
    val pagerState = rememberPagerState(
        initialPage = state.initialIndex.coerceAtMost(state.files.lastIndex),
        pageCount = { state.files.size }
    )
    LaunchedEffect(state.files, state.initialIndex) {
        if (state.ready && state.files.isNotEmpty()) {
            val nextPage = state.initialIndex.coerceIn(0, state.files.lastIndex)
            if (pagerState.currentPage != nextPage) pagerState.scrollToPage(nextPage)
        }
    }
    val pagedFile = state.files[pagerState.currentPage.coerceAtMost(state.files.lastIndex)]
    // The pager holds a snapshot, so the open row is read live and edits made elsewhere show at
    // once.
    val liveFile by viewModel.observeFile(pagedFile.id)
        .collectAsStateWithLifecycle(initialValue = pagedFile)
    val currentFile = liveFile ?: pagedFile
    var previewFolderName by remember { mutableStateOf("Files") }
    LaunchedEffect(currentFile.id, currentFile.folderId) {
        previewFolderName = viewModel.parentFolderName(currentFile)
    }

    val immersive = currentFile.category == FileCategory.IMAGE ||
            currentFile.category == FileCategory.VIDEO
    var chromeVisible by remember { mutableStateOf(true) }
    val playerSession = remember {
        PlayerSession().apply {
            subtitleAppearance = SubtitleAppearanceStore.load(context)
        }
    }
    val previewQueue = remember(state.files) { state.files.map { it.name } }
    val inPipMode by koinInject<PipController>().inPipMode.collectAsStateWithLifecycle()
    val chromeShown = (chromeVisible || !immersive) && !inPipMode

    val view = LocalView.current
    val activity = LocalActivity.current
    val configuration = LocalConfiguration.current

    LaunchedEffect(chromeShown, activity, configuration.orientation) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (chromeShown) controller.show(WindowInsetsCompat.Type.systemBars())
        else controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    DisposableEffect(activity) {
        onDispose {
            val window = activity?.window ?: return@onDispose
            WindowInsetsControllerCompat(window, view)
                .show(WindowInsetsCompat.Type.systemBars())
            if (activity?.requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
                activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
            if (window.attributes.screenBrightness >= 0f) {
                window.attributes = window.attributes.apply {
                    screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                }
            }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        snackbarHost = {
            // The preview draws edge to edge and this scaffold takes no window insets, so
            // without this padding the snackbar sits under the system navigation bar and
            // its lower half (and its action) is cut off. Respect the navigation-bar inset
            // so the whole message is visible.
            SnackbarHost(
                snackbarHostState,
                modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
            )
        }
    ) { _ ->
        Box(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                beyondViewportPageCount = 0,
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (immersive && previewBackgroundMode == PreviewBackgroundMode.BLACK) {
                            ComposeColor.Black
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLowest
                        }
                    ),
                key = { state.files[it].id }
            ) { page ->
                val file = state.files[page]
                val content by viewModel.contentFor(file).collectAsStateWithLifecycle()
                PreviewPage(
                    loadLinkMetadata = { url -> viewModel.linkPreview(url) },
                    storedTextScale = storedTextScale,
                    onTextScaleChange = viewModel::setTextScale,
                    file = file,
                    content = content,
                    isActivePage = pagerState.currentPage == page,
                    previewBackgroundMode = previewBackgroundMode,
                    dataSourceFactory = dataSourceFactory,
                    onDownload = { viewModel.download(file) },
                    onPreviousFile = {
                        val target = when {
                            page > 0 -> page - 1
                            playerSession.repeatAll -> state.files.lastIndex
                            else -> null
                        }
                        if (target != null) previewScope.launch { pagerState.animateScrollToPage(target) }
                    },
                    onNextFile = {
                        val target = when {
                            playerSession.shuffle && state.files.size > 1 -> {
                                val candidate = Random.nextInt(state.files.size - 1)
                                if (candidate >= page) candidate + 1 else candidate
                            }
                            page < state.files.lastIndex -> page + 1
                            playerSession.repeatAll -> 0
                            else -> null
                        }
                        if (target != null) previewScope.launch { pagerState.animateScrollToPage(target) }
                    },
                    hasPreviousFile = page > 0 || playerSession.repeatAll,
                    hasNextFile = page < state.files.lastIndex ||
                            (playerSession.shuffle && state.files.size > 1) ||
                            playerSession.repeatAll,
                    queueItems = previewQueue,
                    currentQueueIndex = page,
                    onSelectQueueItem = { index ->
                        if (index in state.files.indices) {
                            previewScope.launch { pagerState.animateScrollToPage(index) }
                        }
                    },
                    onPlaybackFinished = onNextFileForPage@{
                        val target = when {
                            playerSession.shuffle && state.files.size > 1 -> {
                                val candidate = Random.nextInt(state.files.size - 1)
                                if (candidate >= page) candidate + 1 else candidate
                            }
                            page < state.files.lastIndex -> page + 1
                            playerSession.repeatAll -> 0
                            else -> null
                        }
                        if (target != null) previewScope.launch { pagerState.animateScrollToPage(target) }
                    },
                    chromeVisible = chromeVisible,
                    allowBackgroundPlayback = backgroundPlayback,
                    preferredAudioLanguage = preferredAudioLanguage,
                    preferredSubtitleLanguage = preferredSubtitleLanguage,
                    onPreferredAudioLanguage = viewModel::setPreferredAudioLanguage,
                    onPreferredSubtitleLanguage = viewModel::setPreferredSubtitleLanguage,
                    onToggleChrome = { chromeVisible = !chromeVisible },
                    onChromeRequested = { visible -> chromeVisible = visible },
                    playerSession = playerSession,
                    onArchiveEntryDownload = { entryIndex -> viewModel.downloadArchiveEntry(file, entryIndex) },
                    onArchiveEntryTap = { entryIndex -> viewModel.onArchiveEntryTap(file, entryIndex) },
                    onArchiveDownloadSelected = { entries, folders ->
                        viewModel.downloadArchiveSelection(file, entries, folders)
                    },
                    archiveEntryBusyIndex = state.archiveEntryBusyIndex,
                    archiveBatchProgress = state.archiveBatchProgress,
                    archiveSortField = archiveSort.first,
                    archiveSortDirection = archiveSort.second,
                    onArchiveSortChange = viewModel::setArchiveSort,
                    onRegisterArchiveBackStep = { handler -> archiveBackHandler = handler }
                )
            }

            AnimatedVisibility(
                visible = chromeShown,
                enter = slideInVertically { height -> -height } + fadeIn(),
                exit = slideOutVertically { height -> -height } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                currentFile.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                "$previewFolderName · ${pagerState.currentPage + 1}/${state.files.size}",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = stepBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.common_back)
                            )
                        }
                    },
                    actions = {
                        if (MimeTypes.isText(currentFile.mimeType)) {
                            IconButton(
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
                        IconButton(onClick = { viewModel.showInfo(currentFile) }) {
                            Icon(
                                Icons.Filled.Info,
                                contentDescription = stringResource(Res.string.preview_file_info)
                            )
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(Res.string.common_actions)
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentFile.isFavorite) stringResource(Res.string.preview_remove_favorites)
                                            else stringResource(Res.string.preview_add_favorites)
                                        )
                                    },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.setFavorite(
                                            currentFile,
                                            !currentFile.isFavorite
                                        )
                                    }
                                )
                                if (currentFile.hasLocalCopy) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.preview_share_copy)) },
                                        onClick = {
                                            showOverflow = false
                                            shareFile(context, currentFile, shareChooserTitle)
                                        }
                                    )
                                }
                                if (currentFile.hasRemoteCopy && !currentFile.hasLocalCopy) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.common_download)) },
                                        onClick = {
                                            showOverflow = false
                                            viewModel.download(currentFile)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.files_copy_to)) },
                                    onClick = {
                                        showOverflow = false
                                        copyTarget = currentFile
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.files_move_to)) },
                                    onClick = {
                                        showOverflow = false
                                        moveTarget = currentFile
                                    }
                                )
                                if (!currentFile.hasRemoteCopy) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.common_upload)) },
                                        onClick = {
                                            showOverflow = false
                                            viewModel.upload(currentFile)
                                        }
                                    )
                                }
                                if (currentFile.hasRemoteCopy && currentFile.hasLocalCopy) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(Res.string.common_free_space)) },
                                        onClick = {
                                            showOverflow = false
                                            viewModel.freeUpSpace(currentFile)
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentFile.isHidden) stringResource(Res.string.files_unhide)
                                            else stringResource(Res.string.files_hide)
                                        )
                                    },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.setHidden(currentFile, !currentFile.isHidden)
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (currentFile.isArchived) stringResource(Res.string.preview_unarchive)
                                            else stringResource(Res.string.preview_archive)
                                        )
                                    },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.setArchived(
                                            currentFile,
                                            !currentFile.isArchived
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.common_rename)) },
                                    onClick = {
                                        showOverflow = false
                                        renameTarget = currentFile
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(Res.string.common_move_trash)) },
                                    onClick = {
                                        showOverflow = false
                                        deleteTarget = currentFile
                                    }
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
                    )
                )
            }
            state.archivePreview?.let { preview ->
                ArchiveEntryPreviewOverlay(
                    preview = preview,
                    pagerCount = state.archivePreviewEntries.size.coerceAtLeast(1),
                    pagerPosition = state.archivePreviewPosition.coerceAtLeast(0),
                    pagerResyncCount = state.archivePreviewResyncCount,
                    onPageSelected = viewModel::onArchivePreviewPageSelected,
                    onStepVideo = viewModel::stepArchiveVideo,
                    videoQueuePosition = state.archiveVideoQueuePosition,
                    videoQueueLastIndex = state.archiveVideoQueue.lastIndex,
                    onPlayVideo = viewModel::playCurrentArchiveVideo,
                    onDownload = viewModel::downloadCurrentArchiveEntry,
                    onInfo = viewModel::showArchiveEntryInfo,
                    downloadBusy = state.archiveEntryBusyIndex != null,
                    dataSourceFactory = dataSourceFactory,
                    onDismiss = stepBack
                )
            }
        }
    }

    renameTarget?.let { file ->
        RenameDialog(
            title = stringResource(Res.string.common_rename_file),
            initialValue = file.name,
            confirmLabel = stringResource(Res.string.common_rename),
            onConfirm = {
                viewModel.rename(file, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null }
        )
    }
    deleteTarget?.let { file ->
        ConfirmDialog(
            title = stringResource(Res.string.preview_move_to_trash),
            message = stringResource(Res.string.preview_confirm_trash_file_message, file.name),
            confirmLabel = stringResource(Res.string.common_move_trash),
            destructive = true,
            onConfirm = {
                viewModel.moveToTrash(file)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null }
        )
    }

    copyTarget?.let { file ->
        FolderPickerHost(
            title = stringResource(Res.string.files_copy_to),
            confirmLabel = stringResource(Res.string.files_copy_here),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            onConfirm = { target ->
                viewModel.copyTo(file, target)
                copyTarget = null
            },
            onDismiss = { copyTarget = null }
        )
    }
    moveTarget?.let { file ->
        FolderPickerHost(
            title = stringResource(Res.string.files_move_to),
            confirmLabel = stringResource(Res.string.files_move_here),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            onConfirm = { target ->
                viewModel.moveTo(file, target, pagerState.currentPage)
                moveTarget = null
            },
            onDismiss = { moveTarget = null }
        )
    }
    infoTarget?.let { file ->
        FileInfoSheet(file = file, onDismiss = viewModel::dismissInfo)
    }
    archiveInfoTarget?.let { info ->
        ArchiveEntryInfoSheet(info = info, onDismiss = viewModel::dismissArchiveEntryInfo)
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PreviewPage(
    file: DriveFile,
    content: PreviewContent,
    isActivePage: Boolean,
    previewBackgroundMode: PreviewBackgroundMode,
    dataSourceFactory: TelegramDataSourceFactory,
    onDownload: () -> Unit,
    onPreviousFile: () -> Unit,
    onNextFile: () -> Unit,
    hasPreviousFile: Boolean,
    hasNextFile: Boolean,
    queueItems: List<String>,
    currentQueueIndex: Int,
    onSelectQueueItem: (Int) -> Unit,
    onPlaybackFinished: () -> Unit,
    chromeVisible: Boolean,
    allowBackgroundPlayback: Boolean,
    preferredAudioLanguage: String,
    preferredSubtitleLanguage: String,
    onPreferredAudioLanguage: (String) -> Unit,
    onPreferredSubtitleLanguage: (String) -> Unit,
    onToggleChrome: () -> Unit,
    onChromeRequested: (Boolean) -> Unit,
    loadLinkMetadata: suspend (String) -> LinkMetadata?,
    playerSession: PlayerSession,
    storedTextScale: Float,
    onTextScaleChange: (Float) -> Unit,
    onArchiveEntryDownload: (Int) -> Unit,
    onArchiveEntryTap: (Int) -> Unit,
    onArchiveDownloadSelected: (Set<Int>, Set<String>) -> Unit,
    archiveEntryBusyIndex: Int?,
    archiveBatchProgress: ArchiveBatchProgress?,
    archiveSortField: FileSortField,
    archiveSortDirection: SortDirection,
    onArchiveSortChange: (FileSortField, SortDirection) -> Unit,
    onRegisterArchiveBackStep: ((() -> Boolean)?) -> Unit = {}
) {
    // One state for the screen's life: re-creating it on save would leave the gesture writing to a
    // state nothing renders.
    var textScale by remember { mutableFloatStateOf(storedTextScale) }
    LaunchedEffect(storedTextScale) {
        if (storedTextScale != textScale) textScale = storedTextScale
    }
    val pinchToScale = Modifier.pointerInput(file.id) {
        awaitEachGesture {
            do {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } >= 2) {
                    val zoom = event.calculateZoom()
                    if (zoom != 1f) {
                        textScale = (textScale * zoom).coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
                        event.changes.forEach { it.consume() }
                    }
                }
            } while (event.changes.any { it.pressed })
        }
    }
    // Written once the pinch settles, not on every frame of the gesture.
    LaunchedEffect(textScale) {
        if (textScale != storedTextScale) {
            delay(TEXT_SCALE_SAVE_DELAY_MS.milliseconds)
            onTextScaleChange(textScale)
        }
    }
    when (content) {
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
            if (content.total > 0) {
                LinearWavyProgressIndicator(
                    progress = {
                        (content.transferred.toFloat() / content.total).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = stringResource(
                    Res.string.preview_progress_bytes,
                    Formatters.bytes(content.transferred),
                    Formatters.bytes(content.total)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        is PreviewContent.Image -> ZoomableImage(
            model = content.model,
            contentDescription = file.name,
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (previewBackgroundMode == PreviewBackgroundMode.BLACK) {
                        ComposeColor.Black
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerLowest
                    }
                ),
            controlsVisible = chromeVisible,
            onTap = onToggleChrome
        )

        is PreviewContent.LocalMedia,
        is PreviewContent.StreamedMedia ->
            MediaPlayer(
                content = content,
                title = file.name,
                fileId = file.id,
                dataSourceFactory = dataSourceFactory,
                isActivePage = isActivePage,
                onPreviousFile = onPreviousFile,
                onNextFile = onNextFile,
                hasPreviousFile = hasPreviousFile,
                hasNextFile = hasNextFile,
                queueItems = queueItems,
                currentQueueIndex = currentQueueIndex,
                onSelectQueueItem = onSelectQueueItem,
                onPlaybackFinished = onPlaybackFinished,
                controlsVisible = chromeVisible,
                allowBackgroundPlayback = allowBackgroundPlayback,
                previewBackgroundMode = previewBackgroundMode,
                preferredAudioLanguage = preferredAudioLanguage,
                preferredSubtitleLanguage = preferredSubtitleLanguage,
                onPreferredAudioLanguage = onPreferredAudioLanguage,
                onPreferredSubtitleLanguage = onPreferredSubtitleLanguage,
                onControlsVisibilityChanged = onChromeRequested,
                session = playerSession
            )

        is PreviewContent.Pdf -> PdfPreview(path = content.path)
        is PreviewContent.Html -> HtmlPreview(html = content.text)
        is PreviewContent.PlainText -> if (soleUrlOf(content.text) != null) {
            val url = normalizeUrl(soleUrlOf(content.text).orEmpty())
            val linkContext = LocalContext.current
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .then(pinchToScale)
                    .padding(previewContentPadding())
            ) {
                SavedLink(
                    textScale = textScale,
                    url = url,
                    onOpen = { openUrl(linkContext, url) },
                    loadMetadata = loadLinkMetadata
                )
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .then(pinchToScale)
                .padding(previewContentPadding())
        ) {
            val linkContext = LocalContext.current
            if (MimeTypes.isMarkdown(file.mimeType)) {
                MarkdownText(
                    text = content.text,
                    textScale = textScale,
                    onOpenUrl = { url -> openUrl(linkContext, url) }
                )
            } else {
                LinkedText(
                    text = content.text,
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace)
                        .copy(color = MaterialTheme.colorScheme.onSurface)
                        .scaledBy(textScale),
                    linkColor = MaterialTheme.colorScheme.primary,
                    onOpenUrl = { url -> openUrl(linkContext, url) }
                )
            }
            if (content.truncated) {
                Text(
                    text = stringResource(Res.string.preview_truncated_download_view),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        is PreviewContent.Archive -> ArchiveList(
            archive = content,
            archiveFile = if (content.remote) file else null,
            busyEntryIndex = archiveEntryBusyIndex,
            batchProgress = archiveBatchProgress,
            onEntryTap = onArchiveEntryTap,
            onEntryDownload = onArchiveEntryDownload,
            onDownloadSelected = onArchiveDownloadSelected,
            sortField = archiveSortField,
            sortDirection = archiveSortDirection,
            onSortChange = onArchiveSortChange,
            registerBackStep = onRegisterArchiveBackStep,
            isActivePage = isActivePage
        )
        is PreviewContent.RequiresDownload -> EmptyState(
            icon = Icons.Filled.Download,
            title = stringResource(Res.string.preview_download_view),
            description = stringResource(
                Res.string.preview_requires_download,
                Formatters.bytes(content.sizeBytes)
            ),
            actionLabel = stringResource(Res.string.common_download),
            onAction = onDownload
        )

        is PreviewContent.Unsupported -> {
            val localPath = content.localPath
            val context = LocalContext.current
            val openWithTitle = stringResource(Res.string.preview_open_with)
            val openFailedMessage = stringResource(Res.string.preview_open_with_failed)
            EmptyState(
                icon = Icons.Outlined.Description,
                title = stringResource(Res.string.preview_no_preview),
                description = stringResource(content.reasonRes),
                actionLabel = when {
                    localPath != null -> openWithTitle
                    !file.hasLocalCopy -> stringResource(Res.string.common_download)
                    else -> null
                },
                onAction = when {
                    localPath != null -> ({
                        openFileWith(
                            context = context,
                            displayName = file.name,
                            localPath = localPath,
                            mimeType = file.mimeType,
                            chooserTitle = openWithTitle,
                            failureMessage = openFailedMessage
                        )
                    })
                    !file.hasLocalCopy -> onDownload
                    else -> null
                }
            )
        }

        is PreviewContent.Failed -> ErrorState(
            message = content.messageText ?: stringResource(content.messageRes)
        )
    }
}

/**
 * An archive entry opened in place: the verified temp extraction shown with the app's own
 * image viewer / audio player / text view. Read-only; closing deletes the temp file.
 *
 * The content sits in the same HorizontalPager the Files viewer uses: swiping steps
 * through the zip's previewable entries (the view model extracts each page on demand
 * and publishes it as the current preview), a page that is not the current preview yet
 * shows the preview's own loading state, and the current page keeps its exact previous
 * rendering — zoomable image, audio player, scrolling text.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun ArchiveEntryPreviewOverlay(
    preview: ArchiveEntryPreview,
    pagerCount: Int,
    pagerPosition: Int,
    pagerResyncCount: Int,
    onPageSelected: (Int) -> Unit,
    onStepVideo: (Int) -> Unit = {},
    videoQueuePosition: Int = -1,
    videoQueueLastIndex: Int = -1,
    onPlayVideo: () -> Unit = {},
    onDownload: () -> Unit = {},
    onInfo: () -> Unit = {},
    downloadBusy: Boolean = false,
    dataSourceFactory: TelegramDataSourceFactory,
    onDismiss: () -> Unit
) {
    val overlaySession = remember(preview) { PlayerSession() }
    val pagerState = rememberPagerState(
        initialPage = pagerPosition,
        pageCount = { pagerCount }
    )
    // A settled swipe is reported once; the view model extracts that entry and only
    // then moves pagerPosition, so this also scrolls the pager if a step was refused
    // or the user swiped back to the page already shown.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .collect { page -> onPageSelected(page) }
    }
    // Keyed on the resync count as well as the position: a failed or refused step
    // never changes pagerPosition, so the count is what re-runs this effect and
    // scrolls the pager back to the published page after such a step.
    LaunchedEffect(pagerPosition, pagerResyncCount) {
        if (pagerState.currentPage != pagerPosition && !pagerState.isScrollInProgress) {
            pagerState.scrollToPage(pagerPosition)
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalPager(
            state = pagerState,
            beyondViewportPageCount = 0,
            modifier = Modifier.fillMaxSize()
        ) { page ->
            if (page != pagerPosition) {
                LoadingState()
            } else {
                when (preview) {
                    is ArchiveEntryPreview.Image -> ZoomableImage(
                        model = File(preview.path),
                        contentDescription = preview.name,
                        modifier = Modifier.fillMaxSize()
                    )

                    is ArchiveEntryPreview.Audio -> MediaPlayer(
                        content = PreviewContent.LocalMedia(preview.path, isAudio = true),
                        title = preview.name,
                        fileId = "archive-entry-${preview.name}",
                        dataSourceFactory = dataSourceFactory,
                        isActivePage = true,
                        session = overlaySession
                    )

                    is ArchiveEntryPreview.Pdf -> PdfPreview(path = preview.path)

                    // In-archive video (vc181 B11): the audio branch with
                    // isAudio = false — the same existing player, fed the
                    // verified temp extraction as a plain local file.
                    is ArchiveEntryPreview.Video -> MediaPlayer(
                        content = PreviewContent.LocalMedia(preview.path, isAudio = false),
                        title = preview.name,
                        fileId = "archive-entry-${preview.name}",
                        dataSourceFactory = dataSourceFactory,
                        isActivePage = true,
                        // Prev/next step through the archive's video queue
                        // (vc182) via the player's existing SkipPrevious /
                        // SkipNext buttons — not the swipe pager, which
                        // videos stay out of. No onPlaybackFinished: finishing
                        // a video does not auto-advance.
                        onPreviousFile = { onStepVideo(-1) },
                        onNextFile = { onStepVideo(+1) },
                        // vc183: prev/next follow the unified sequence, so
                        // availability is the pager position/count, not the
                        // old video-only queue (kept in state only as a
                        // synced mirror).
                        hasPreviousFile = pagerPosition > 0,
                        hasNextFile = pagerPosition < pagerCount - 1,
                        session = overlaySession
                    )

                    is ArchiveEntryPreview.VideoPlaceholder -> Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(previewContentPadding()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // vc183: a swiped-to video page extracts nothing yet.
                        // Play is the deliberate tap that pays the whole-file
                        // extraction (with the free-space preflight).
                        Text(
                            text = preview.name,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = Formatters.bytes(preview.sizeBytes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp, bottom = 20.dp)
                        )
                        Button(onClick = onPlayVideo) {
                            Text(text = stringResource(Res.string.player_play))
                        }
                    }

                    is ArchiveEntryPreview.Text -> if (preview.isHtml) {
                        // The WebView scrolls itself; it must not sit inside the
                        // scrolling text column below.
                        HtmlPreview(html = preview.text)
                    } else Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(previewContentPadding())
                    ) {
                        val overlayContext = LocalContext.current
                        if (preview.isMarkdown) {
                            MarkdownText(
                                text = preview.text,
                                onOpenUrl = { url -> openUrl(overlayContext, url) }
                            )
                        } else {
                            Text(
                                text = preview.text,
                                style = MaterialTheme.typography.bodySmall
                                    .copy(fontFamily = FontFamily.Monospace)
                                    .copy(color = MaterialTheme.colorScheme.onSurface)
                            )
                        }
                        if (preview.truncated) {
                            Text(
                                text = stringResource(Res.string.preview_truncated_download_view),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    // Top-only insets (vc182): the full systemBars padding also
                    // added the bottom navigation inset, making this band tall
                    // enough to cover the media player's own top control row
                    // (statusBarsPadding + the 64dp top bar) — the player's
                    // Fit/subtitles/PiP/Lock/⋮ row rendered greyed underneath
                    // this surface and its taps were eaten by it.
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top))
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.common_back)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preview.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (pagerCount > 1) {
                        Text(
                            text = "${pagerPosition + 1}/$pagerCount",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                IconButton(onClick = onDownload, enabled = !downloadBusy) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = stringResource(Res.string.common_download)
                    )
                }
                IconButton(onClick = onInfo) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = stringResource(Res.string.preview_file_info)
                    )
                }
            }
        }
    }
}

/**
 * Previews draw edge to edge under the toolbar and system bars, so scrollable ones pad themselves.
 */
@Composable
private fun previewContentPadding(): PaddingValues =
    WindowInsets.systemBars
        .asPaddingValues()
        .add(horizontal = 16.dp, top = PreviewTopBarHeight + 8.dp, bottom = 16.dp)

@Composable
private fun ArchiveList(
    archive: PreviewContent.Archive,
    archiveFile: DriveFile? = null,
    busyEntryIndex: Int? = null,
    batchProgress: ArchiveBatchProgress? = null,
    onEntryTap: (Int) -> Unit = {},
    onEntryDownload: (Int) -> Unit = {},
    onDownloadSelected: (Set<Int>, Set<String>) -> Unit = { _, _ -> },
    sortField: FileSortField = FileSortField.NAME,
    sortDirection: SortDirection = SortDirection.ASCENDING,
    onSortChange: (FileSortField, SortDirection) -> Unit = { _, _ -> },
    registerBackStep: ((() -> Boolean)?) -> Unit = {},
    isActivePage: Boolean = true
) {
    var query by remember(archive) { mutableStateOf("") }
    var expanded by remember(archive) { mutableStateOf(emptySet<String>()) }
    var selectedEntries by remember(archive) { mutableStateOf(emptySet<Int>()) }
    var selectedFolders by remember(archive) { mutableStateOf(emptySet<String>()) }
    var rangeBaseEntries by remember(archive) { mutableStateOf<Set<Int>?>(null) }
    var rangeBaseFolders by remember(archive) { mutableStateOf<Set<String>?>(null) }
    var showSortMenu by remember(archive) { mutableStateOf(false) }

    val filtering = query.isNotBlank()
    // Filtering is purely local over the loaded listing; indices always refer to the
    // FULL listing, so a row's identity survives filtering and re-sorting.
    val visibleIndices = remember(archive, query) {
        if (filtering) archiveFilterIndices(archive.entries, query) else archive.entries.indices.toSet()
    }
    val root = remember(archive, visibleIndices) {
        buildArchiveTreeIndexed(
            archive.entries.mapIndexedNotNull { index, entry ->
                if (index in visibleIndices) IndexedArchiveEntry(index, entry) else null
            }
        )
    }
    // Mirrors SearchViewModel: when the results change, the selection is pruned to what
    // still matches — a selection cannot outlive the result set it was made from.
    LaunchedEffect(query) {
        if (filtering) {
            selectedEntries = pruneArchiveSelection(selectedEntries, visibleIndices)
            selectedFolders = selectedFolders intersect allFolderPaths(root)
        }
    }
    val effectiveExpanded = if (filtering) allFolderPaths(root) else expanded
    val rows = remember(root, effectiveExpanded, sortField, sortDirection) {
        flattenArchive(root, effectiveExpanded, sortField, sortDirection)
    }
    val files = remember(archive) { archive.entries.filterNot { it.isDirectory } }
    val folderCount = remember(archive) { countFolders(buildArchiveTree(archive.entries)) }
    val originalBytes = remember(files) { files.sumOf { it.sizeBytes } }
    val storedBytes = remember(files) { files.sumOf { it.compressedBytes } }
    val saved = remember(originalBytes, storedBytes) {
        if (originalBytes <= 0) 0 else (100 - storedBytes * 100 / originalBytes).toInt()
    }
    val selectionMode = selectedEntries.isNotEmpty() || selectedFolders.isNotEmpty()
    val busy = busyEntryIndex != null
    val listState = rememberLazyListState()
    // vc182 item 6: on-demand entry thumbnails for remote archives only (local
    // archives keep generic icons). The store holds per-key state entirely
    // outside this list's UI state; rows ask it individually as they compose.
    val thumbnailStore = if (archiveFile != null) {
        koinInject<ArchiveEntryThumbnailStore>()
    } else {
        null
    }

    // This list's internal steps of the screen's shared step-back (see PreviewScreen):
    // the preview step is handled one level up, so the decision here starts at the
    // search step. Returns true when a step consumed the press; false at the zip root
    // with nothing active, letting the press leave the viewer.
    val handleArchiveBackStep: () -> Boolean = {
        when (
            archiveBackStep(
                hasPreview = false,
                searchActive = query.isNotBlank(),
                selectionActive = selectionMode,
                hasExpandedFolder = expanded.isNotEmpty()
            )
        ) {
            ArchiveBackStep.CLEAR_SEARCH -> {
                query = ""
                true
            }
            ArchiveBackStep.EXIT_SELECTION -> {
                selectedEntries = emptySet()
                selectedFolders = emptySet()
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
            else -> false
        }
    }
    val currentArchiveBackStep by rememberUpdatedState(handleArchiveBackStep)
    DisposableEffect(isActivePage) {
        if (isActivePage) {
            registerBackStep { currentArchiveBackStep() }
            onDispose { registerBackStep(null) }
        } else {
            onDispose { }
        }
    }

    // The main browser's drag selection, on the shared helper: long-press paints a range
    // from a snapshot taken at drag start (base ± range), with edge auto-scroll.
    // LazyColumn item 0 is the header card and the last item is the hint, so the
    // list-index → row translation lives in applyArchiveDragRange /
    // archiveDragAnchorSelected (ArchiveNode.kt), shared by both callbacks.
    val dragSelect = rememberDragSelect(
        listState = listState,
        onStart = {
            rangeBaseEntries = selectedEntries
            rangeBaseFolders = selectedFolders
        },
        isSelected = { listIndex ->
            archiveDragAnchorSelected(rows, listIndex, selectedEntries, selectedFolders)
        },
        onRange = { range, shouldSelect ->
            val painted = applyArchiveDragRange(
                rows = rows,
                listRange = range,
                baseEntries = rangeBaseEntries ?: selectedEntries,
                baseFolders = rangeBaseFolders ?: selectedFolders,
                shouldSelect = shouldSelect
            )
            selectedEntries = painted.entries
            selectedFolders = painted.folders
        },
        onEnd = {
            rangeBaseEntries = null
            rangeBaseFolders = null
        },
        resolveNearestVisibleItem = true
    )

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().then(if (archive.remote) dragSelect else Modifier),
            contentPadding = previewContentPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(
                                    Res.string.preview_archive_format_title,
                                    archive.format
                                ),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            Box {
                                IconButton(onClick = { showSortMenu = true }) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.Sort,
                                        contentDescription = stringResource(Res.string.files_sort)
                                    )
                                }
                                SortMenu(
                                    expanded = showSortMenu,
                                    current = sortField,
                                    direction = sortDirection,
                                    onDismiss = { showSortMenu = false },
                                    onSelect = { field, direction ->
                                        showSortMenu = false
                                        onSortChange(field, direction)
                                    },
                                    fields = ARCHIVE_SORT_FIELDS
                                )
                            }
                        }
                        Text(
                            text = buildString {
                                append(
                                    pluralStringResource(
                                        Res.plurals.file_count,
                                        files.size,
                                        files.size
                                    )
                                )
                                if (folderCount > 0) {
                                    append(", ")
                                    append(
                                        pluralStringResource(
                                            Res.plurals.folder_count,
                                            folderCount,
                                            folderCount
                                        )
                                    )
                                }
                            },
                            style = MaterialTheme.typography.headlineSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = if (saved > 0) {
                                stringResource(
                                    Res.string.preview_archive_packed_summary_saved,
                                    Formatters.bytes(storedBytes),
                                    Formatters.bytes(originalBytes),
                                    saved
                                )
                            } else {
                                stringResource(
                                    Res.string.preview_archive_packed_summary,
                                    Formatters.bytes(storedBytes),
                                    Formatters.bytes(originalBytes)
                                )
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            placeholder = { Text(stringResource(Res.string.files_search)) },
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null)
                            },
                            trailingIcon = if (filtering) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = stringResource(Res.string.common_cancel)
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                        )
                        if (batchProgress != null) {
                            LinearProgressIndicator(
                                progress = {
                                    if (batchProgress.total > 0) {
                                        batchProgress.done.toFloat() / batchProgress.total
                                    } else {
                                        0f
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 12.dp)
                            )
                            Text(
                                text = stringResource(
                                    Res.string.preview_archive_entry_saving,
                                    "${batchProgress.done}/${batchProgress.total}"
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
            items(
                rows,
                key = { row -> archiveRowKey(row) }
            ) { row ->
                val entryIndex = row.node.entryIndex
                val selected = if (row.node.isDirectory) {
                    row.node.path in selectedFolders
                } else {
                    entryIndex != null && entryIndex in selectedEntries
                }
                ArchiveRow(
                    row = row,
                    thumbnailStore = thumbnailStore,
                    archiveFile = archiveFile,
                    canDownload = archive.remote && !row.node.isDirectory,
                    isBusy = busyEntryIndex != null && busyEntryIndex == entryIndex,
                    selectionMode = selectionMode,
                    selected = selected,
                    onFileClick = {
                        if (busy) return@ArchiveRow
                        if (selectionMode) {
                            entryIndex?.let {
                                selectedEntries = toggleArchiveSelection(selectedEntries, it)
                            }
                        } else {
                            entryIndex?.let(onEntryTap)
                        }
                    },
                    onDownloadClick = {
                        if (!busy) entryIndex?.let(onEntryDownload)
                    },
                    onToggleSelection = {
                        if (row.node.isDirectory) {
                            selectedFolders = if (row.node.path in selectedFolders) {
                                selectedFolders - row.node.path
                            } else {
                                selectedFolders + row.node.path
                            }
                        } else {
                            entryIndex?.let {
                                selectedEntries = toggleArchiveSelection(selectedEntries, it)
                            }
                        }
                    },
                    onToggle = {
                        if (selectionMode) {
                            selectedFolders = if (row.node.path in selectedFolders) {
                                selectedFolders - row.node.path
                            } else {
                                selectedFolders + row.node.path
                            }
                        } else {
                            expanded = if (row.node.path in expanded) {
                                expanded - row.node.path
                            } else {
                                expanded + row.node.path
                            }
                        }
                    }
                )
            }
            item {
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

        // The touch selection action bar, mirroring the main browser's: a floating
        // surface above the system navigation bar. The zip viewer is read-only, so
        // Select all and Download selected are its only actions.
        if (selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                tonalElevation = 4.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            selectedEntries = emptySet()
                            selectedFolders = emptySet()
                        }
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.common_cancel)
                        )
                    }
                    Text(
                        text = stringResource(
                            Res.string.picker_selected_count,
                            selectedEntries.size + selectedFolders.size
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = {
                            val all = selectAllArchiveVisible(
                                rows,
                                ArchiveSelectionSet(selectedEntries, selectedFolders)
                            )
                            selectedEntries = all.entryIndices
                            selectedFolders = all.folderPaths
                        }
                    ) {
                        Text(stringResource(Res.string.common_select_all))
                    }
                    Button(
                        enabled = !busy,
                        onClick = {
                            val entries = selectedEntries
                            val folders = selectedFolders
                            selectedEntries = emptySet()
                            selectedFolders = emptySet()
                            onDownloadSelected(entries, folders)
                        }
                    ) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = stringResource(Res.string.preview_archive_download_selected),
                            modifier = Modifier.padding(start = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/** The main browser's sort keys that archive entries carry metadata for. */
private val ARCHIVE_SORT_FIELDS = listOf(
    FileSortField.NAME,
    FileSortField.SIZE,
    FileSortField.DATE_MODIFIED,
    FileSortField.TYPE
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ArchiveRow(
    row: ArchiveRowState,
    onToggle: () -> Unit,
    thumbnailStore: ArchiveEntryThumbnailStore? = null,
    archiveFile: DriveFile? = null,
    modifier: Modifier = Modifier,
    canDownload: Boolean = false,
    isBusy: Boolean = false,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onFileClick: () -> Unit = {},
    onDownloadClick: () -> Unit = {},
    onToggleSelection: () -> Unit = {}
) {
    val node = row.node
    val icon = if (node.isDirectory) {
        if (row.expanded) Icons.Outlined.FolderOpen else Icons.Outlined.Folder
    } else {
        iconFor(FileCategory.fromMimeType(MimeTypes.fromFileName(node.name)))
    }
    val chevronRotation by animateFloatAsState(
        targetValue = if (row.expanded) 180f else 0f,
        animationSpec = tween(CHEVRON_ROTATE_MS),
        label = stringResource(Res.string.preview_chevron)
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = INDENT_STEP * row.depth)
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.secondaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerLow
                }
            )
            .then(
                when {
                    // combinedClickable with an explicit no-op long-click, like every
                    // other row under the shared drag-select helper (the main browser's
                    // FileListItem/FolderRow and the helper's own pointer tests): the
                    // long-press that starts drag selection is absorbed here as a
                    // long-click, so it cannot ALSO complete as a click on release.
                    // A plain clickable has no long-press handling — the press that
                    // started the drag fired onToggle/onFileClick when the finger
                    // lifted, toggling a second row's selection on top of the range
                    // the drag had painted.
                    node.isDirectory -> Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = { onToggle() },
                        onLongClick = {}
                    )
                    canDownload -> Modifier.combinedClickable(
                        role = Role.Button,
                        onClick = { onFileClick() },
                        onLongClick = {}
                    )
                    else -> Modifier
                }
            )
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (selectionMode || selected) {
            SelectionCheckbox(
                checked = selected,
                onClick = onToggleSelection,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        // vc182 item 6: for image entries of a remote archive, the 40dp slot shows
        // a real thumbnail once the store has one; until then (and on any failure)
        // it shows exactly the generic icon it always showed. The key is derived
        // from this row's own listing identity only — no list-level state.
        val entryIndex = node.entryIndex
        val thumbnailKey = remember(
            archiveFile?.id, entryIndex, node.sizeBytes, node.compressedBytes, node.modifiedAtMillis
        ) {
            val file = archiveFile
            if (file != null && entryIndex != null && !node.isDirectory &&
                ArchiveEntryThumbnailPolicy.isImageEntryName(node.name)
            ) {
                ArchiveEntryThumbnailPolicy.cacheKey(
                    archiveFileId = file.id,
                    entryIndex = entryIndex,
                    sizeBytes = node.sizeBytes,
                    compressedBytes = node.compressedBytes,
                    modifiedAtMillis = node.modifiedAtMillis
                )
            } else {
                null
            }
        }
        val genericIcon: @Composable () -> Unit = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (node.isDirectory) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(20.dp)
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(
                    if (node.isDirectory) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceContainerHighest
                    }
                )
        ) {
            if (thumbnailKey != null && thumbnailStore != null && archiveFile != null &&
                entryIndex != null
            ) {
                ArchiveEntryThumbnail(
                    store = thumbnailStore,
                    archiveFile = archiveFile,
                    entryIndex = entryIndex,
                    entryName = node.name,
                    sizeBytes = node.sizeBytes,
                    cacheKey = thumbnailKey,
                    placeholder = genericIcon
                )
            } else {
                genericIcon()
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                // Keep the row's height identical with and without the 48.dp
                // selection checkbox, so flipping selection mode never resizes rows.
                .defaultMinSize(minHeight = 48.dp)
                .padding(start = 14.dp)
        ) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (node.isDirectory) {
                    val items = node.children.size
                    val label = pluralStringResource(Res.plurals.item_count, items, items)
                    "$label · ${Formatters.bytes(node.sizeBytes)}"
                } else {
                    Formatters.bytes(node.sizeBytes)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        if (node.isDirectory) {
            Icon(
                imageVector = Icons.Filled.ExpandMore,
                contentDescription = if (row.expanded) stringResource(Res.string.common_collapse) else stringResource(
                    Res.string.common_expand
                ),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.rotate(chevronRotation)
            )
        } else if (isBusy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Text(
                text = Formatters.bytes(node.compressedBytes),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (canDownload && !selectionMode) {
                IconButton(onClick = onDownloadClick) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = stringResource(Res.string.common_download)
                    )
                }
            }
        }
    }
}

/**
 * One archive row's thumbnail (vc182 item 6): asks [ArchiveEntryThumbnailStore]
 * for this entry's thumbnail while composed and releases the request on dispose,
 * so a row that scrolls off cancels its fetch if nobody else waits on it. Shows
 * [placeholder] (the row's generic icon) until the thumbnail is ready, and keeps
 * showing it on failure. Crossfade is disabled for these rows: the global image
 * loader fades in, which reads as flicker in a fast-scrolling list.
 */
@Composable
private fun ArchiveEntryThumbnail(
    store: ArchiveEntryThumbnailStore,
    archiveFile: DriveFile,
    entryIndex: Int,
    entryName: String,
    sizeBytes: Long,
    cacheKey: String,
    placeholder: @Composable () -> Unit
) {
    val state by store.state(cacheKey).collectAsStateWithLifecycle()
    var loadFailed by remember(cacheKey) { mutableStateOf(false) }
    LaunchedEffect(cacheKey) {
        store.request(cacheKey, archiveFile, entryIndex, entryName, sizeBytes)
    }
    DisposableEffect(cacheKey) {
        onDispose { store.release(cacheKey) }
    }
    val readyFile = (state as? ArchiveThumbState.Ready)?.file
    if (readyFile != null && !loadFailed) {
        val context = LocalContext.current
        AsyncImage(
            model = remember(readyFile) {
                ImageRequest.Builder(context)
                    .data(readyFile)
                    .crossfade(false)
                    .build()
            },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { loadFailed = true },
            modifier = Modifier.fillMaxSize()
        )
    } else {
        placeholder()
    }
}

private const val CHEVRON_ROTATE_MS = 220
private val INDENT_STEP = 16.dp

internal fun openFileWith(
    context: Context,
    displayName: String,
    localPath: String,
    mimeType: String,
    chooserTitle: String,
    failureMessage: String
) {
    runCatching {
        val uri = when {
            localPath.startsWith("content://") || localPath.startsWith("file://") ->
                Uri.parse(localPath)
            else -> FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                File(localPath)
            )
        }
        val resolvedMimeType = MimeTypes.resolve(displayName, mimeType)
            .takeUnless { it == MimeTypes.GENERIC }
            ?: "*/*"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, resolvedMimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, displayName, uri)
        }
        context.startActivity(Intent.createChooser(intent, chooserTitle))
    }.onFailure {
        Toast.makeText(context, failureMessage, Toast.LENGTH_LONG).show()
    }
}

private fun shareFile(context: Context, file: DriveFile, chooserTitle: String) {
    val localPath = file.localPath ?: return
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(localPath)
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = file.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(
        Intent.createChooser(
            intent,
            chooserTitle
        )
    )
}

val PreviewTopBarHeight = 64.dp

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

private const val TEXT_SCALE_MIN = 0.7f
private const val TEXT_SCALE_MAX = 3f
private const val TEXT_SCALE_SAVE_DELAY_MS = 400L
