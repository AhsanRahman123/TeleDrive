package com.drdisagree.teledrive.presentation.gallery

import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import com.drdisagree.teledrive.presentation.components.DesktopHorizontalScrollbar
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.VisibilityOff
import com.drdisagree.teledrive.presentation.components.ActionMenu
import com.drdisagree.teledrive.presentation.components.MenuAction
import com.drdisagree.teledrive.resources.common_make_available_offline
import com.drdisagree.teledrive.resources.common_organize
import com.drdisagree.teledrive.resources.common_remove_from_offline
import com.drdisagree.teledrive.resources.common_storage_actions
import com.drdisagree.teledrive.resources.files_archive
import com.drdisagree.teledrive.resources.files_copy
import com.drdisagree.teledrive.resources.files_copy_here
import com.drdisagree.teledrive.resources.files_copy_to
import com.drdisagree.teledrive.resources.files_new_folder
import com.drdisagree.teledrive.resources.files_hide
import com.drdisagree.teledrive.resources.files_sort
import com.drdisagree.teledrive.resources.preview_remove_favorites
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.components.BottomBarSnackbarHost
import com.drdisagree.teledrive.presentation.components.CollectionShortcuts
import com.drdisagree.teledrive.presentation.files.FolderPickerHost
import com.drdisagree.teledrive.presentation.platform.LocalFileSharer
import com.drdisagree.teledrive.presentation.platform.LocalDeleteConsentLauncher
import com.drdisagree.teledrive.resources.files_move_to
import com.drdisagree.teledrive.resources.files_move_here
import com.drdisagree.teledrive.presentation.common.AppBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed as listItemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.koin.compose.viewmodel.koinViewModel
import androidx.window.core.layout.WindowSizeClass
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_actions
import com.drdisagree.teledrive.resources.common_add_favorites
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.common_clear_selection
import com.drdisagree.teledrive.resources.common_create
import com.drdisagree.teledrive.resources.common_confirm_trash_count_title
import com.drdisagree.teledrive.resources.common_deselect_all
import com.drdisagree.teledrive.resources.common_download
import com.drdisagree.teledrive.resources.common_free_space
import com.drdisagree.teledrive.resources.files_delete_local
import com.drdisagree.teledrive.resources.files_delete_local_copies
import com.drdisagree.teledrive.resources.files_only_files_verified_telegram
import com.drdisagree.teledrive.resources.collection_favorites_title
import com.drdisagree.teledrive.resources.collection_offline_title
import com.drdisagree.teledrive.resources.common_move_trash
import com.drdisagree.teledrive.resources.common_rename
import com.drdisagree.teledrive.resources.common_rename_file
import com.drdisagree.teledrive.resources.common_restore_trash_emptied
import com.drdisagree.teledrive.resources.common_select_all
import com.drdisagree.teledrive.resources.common_selection_count
import com.drdisagree.teledrive.resources.common_select
import com.drdisagree.teledrive.resources.common_share
import com.drdisagree.teledrive.resources.common_upload
import com.drdisagree.teledrive.resources.date_days_ago
import com.drdisagree.teledrive.resources.date_today
import com.drdisagree.teledrive.resources.date_yesterday
import com.drdisagree.teledrive.resources.gallery_albums_appear_organise_media
import com.drdisagree.teledrive.resources.gallery_no_albums
import com.drdisagree.teledrive.resources.gallery_no_media
import com.drdisagree.teledrive.resources.gallery_new_album
import com.drdisagree.teledrive.resources.gallery_filter_backed_up
import com.drdisagree.teledrive.resources.gallery_filter_not_backed_up
import com.drdisagree.teledrive.resources.gallery_view_grid
import com.drdisagree.teledrive.resources.gallery_view_list
import com.drdisagree.teledrive.resources.gallery_search_hint
import com.drdisagree.teledrive.resources.gallery_clear_filters
import com.drdisagree.teledrive.resources.gallery_clear_search
import com.drdisagree.teledrive.resources.files_queued_for_download
import com.drdisagree.teledrive.resources.snackbar_view_transfers
import com.drdisagree.teledrive.resources.gallery_photos_videos_appear_here
import com.drdisagree.teledrive.resources.gallery_title
import com.drdisagree.teledrive.resources.info_details
import com.drdisagree.teledrive.resources.nav_gallery
import com.drdisagree.teledrive.resources.preview_share_chooser_title
import com.drdisagree.teledrive.domain.model.MediaAlbum
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.ViewMode
import com.drdisagree.teledrive.presentation.common.DayBucket
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.common.isInitialLoad
import com.drdisagree.teledrive.presentation.components.RefreshAction
import com.drdisagree.teledrive.presentation.components.RefreshableContent
import com.drdisagree.teledrive.presentation.components.ConfirmDialog
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.FileInfoSheet
import com.drdisagree.teledrive.presentation.components.FileGridItem
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.onDesktopContextClick
import com.drdisagree.teledrive.presentation.components.FileListItem
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.presentation.components.RenameDialog
import com.drdisagree.teledrive.presentation.components.SortMenu
import com.drdisagree.teledrive.presentation.components.keyboardCollection
import com.drdisagree.teledrive.presentation.components.keyboardCollectionItem
import com.drdisagree.teledrive.presentation.components.liftedTopAppBarColors
import com.drdisagree.teledrive.presentation.components.pinchZoom
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import com.drdisagree.teledrive.presentation.components.rememberKeyboardCollection
import com.drdisagree.teledrive.presentation.components.rememberToolbarLift
import com.drdisagree.teledrive.presentation.navigation.LocalBottomBarInset
import com.drdisagree.teledrive.presentation.preview.PreviewSequence
import com.drdisagree.teledrive.presentation.common.rememberPosition

@Composable
private fun DayHeaderRow(dayStartMillis: Long, modifier: Modifier = Modifier) {
    Text(
        text = when (val bucket = Formatters.dayBucket(dayStartMillis)) {
            DayBucket.Today -> stringResource(Res.string.date_today)
            DayBucket.Yesterday -> stringResource(Res.string.date_yesterday)
            is DayBucket.DaysAgo -> stringResource(Res.string.date_days_ago, bucket.days)
            is DayBucket.Absolute -> bucket.text
        },
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .semantics { heading() }
            .padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GalleryTabSelector(
    selected: GalleryTab,
    onSelect: (GalleryTab) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        GalleryTab.entries.forEachIndexed { index, tab ->
            ToggleButton(
                checked = tab == selected,
                onCheckedChange = { onSelect(tab) },
                shapes = when (index) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    GalleryTab.entries.lastIndex ->
                        ButtonGroupDefaults.connectedTrailingButtonShapes()

                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(tab.labelRes),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun AlbumGrid(
    albums: List<MediaAlbum>,
    columns: Int,
    viewMode: ViewMode,
    bottomPadding: Dp,
    onOpenAlbum: (MediaAlbum) -> Unit,
    onZoomIn: () -> Unit,
    onZoomOut: () -> Unit
) {
    if (albums.isEmpty()) {
        EmptyState(
            icon = Icons.Outlined.PhotoLibrary,
            title = stringResource(Res.string.gallery_no_albums),
            description = stringResource(Res.string.gallery_albums_appear_organise_media)
        )
        return
    }
    if (viewMode == ViewMode.LIST) {
        val listState = rememberLazyListState()
        val keyboard = rememberKeyboardCollection(listState)
        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 10.dp)
                .keyboardCollection(keyboard, albums.size),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp + bottomPadding
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listItemsIndexed(
                albums,
                key = { _, album -> album.folderId ?: UNFILED_ALBUM_KEY }
            ) { index, album ->
                AlbumListItem(
                    album = album,
                    onClick = { onOpenAlbum(album) },
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, index)
                        .animateItem()
                )
            }
            }
            DesktopVerticalScrollbar(
                state = listState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    } else {
        val gridState = rememberLazyGridState()
        val keyboard = rememberKeyboardCollection(gridState, columns)
        Box(modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 10.dp)
                .keyboardCollection(keyboard, albums.size)
                .pinchZoom(onZoomIn = onZoomIn, onZoomOut = onZoomOut),
            contentPadding = PaddingValues(
                start = 12.dp,
                end = 12.dp,
                top = 12.dp,
                bottom = 12.dp + bottomPadding
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            itemsIndexed(
                albums,
                key = { _, album -> album.folderId ?: UNFILED_ALBUM_KEY }
            ) { index, album ->
                AlbumCard(
                    album = album,
                    onClick = { onOpenAlbum(album) },
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, index)
                        .animateItem()
                )
            }
            }
            DesktopVerticalScrollbar(
                state = gridState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }
}

private const val UNFILED_ALBUM_KEY = "__unfiled__"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onOpenFile: (String, PreviewSequence) -> Unit,
    onOpenAlbum: (MediaAlbum) -> Unit = {},
    onBack: (() -> Unit)? = null,
    onOpenTransfers: () -> Unit = {},
    viewModel: GalleryViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectionModeEnabled by viewModel.selectionModeEnabled.collectAsStateWithLifecycle()
    val selectionMode = state.selectionMode || selectionModeEnabled
    val media = viewModel.pagedMedia.collectAsLazyPagingItems()
    val galleryFiltersScrollState = rememberScrollState()
    // Fit more media previews on wide windows while keeping phone grids unchanged.
    val isWideWindow = currentWindowAdaptiveInfoV2().windowSizeClass
        .isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)
    val mediaGridColumns = if (isWideWindow) (state.gridSize + 2).coerceAtMost(6)
        else state.gridSize
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    var confirmTrash by remember { mutableStateOf(false) }
    var showSelectionOverflow by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var showCopyPicker by remember { mutableStateOf(false) }
    var showCreateAlbum by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var confirmDeleteLocal by remember { mutableStateOf(false) }
    var advancedFiltersExpanded by remember { mutableStateOf(false) }

    val infoTarget by viewModel.infoTarget.collectAsStateWithLifecycle()
    val infoFolderPath by viewModel.infoFolderPath.collectAsStateWithLifecycle()

    LaunchedEffect(selectionMode) {
        if (!selectionMode) showSelectionOverflow = false
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val renameTarget by viewModel.renameTarget.collectAsStateWithLifecycle()
    val fileSharer = LocalFileSharer.current
    val shareChooserTitle = stringResource(Res.string.preview_share_chooser_title)

    LaunchedEffect(Unit) {
        viewModel.shareRequests.collect { request ->
            fileSharer.share(request.paths, request.mimeType, shareChooserTitle)
        }
    }

    val deleteConsentLauncher = LocalDeleteConsentLauncher.current
    LaunchedEffect(Unit) {
        viewModel.deleteConsentRequests.collect { request ->
            deleteConsentLauncher.launch(request) { granted ->
                if (granted) viewModel.retryLocalCopyRemoval()
            }
        }
    }


    val viewTransfersLabel = stringResource(Res.string.snackbar_view_transfers)
    CollectSnackbarMessages(
        messages = viewModel.messages,
        hostState = snackbarHostState,
        actionLabel = { message ->
            if (
                message is UiText.Resource &&
                message.resource == Res.string.files_queued_for_download
            ) {
                viewTransfersLabel
            } else {
                null
            }
        },
        onAction = { onOpenTransfers() }
    )

    if (showMovePicker) {
        FolderPickerHost(
            title = stringResource(Res.string.files_move_to),
            confirmLabel = stringResource(Res.string.files_move_here),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            onConfirm = { target ->
                showMovePicker = false
                viewModel.moveSelected(target)
            },
            onDismiss = { showMovePicker = false }
        )
    }

    if (showCopyPicker) {
        FolderPickerHost(
            title = stringResource(Res.string.files_copy_to),
            confirmLabel = stringResource(Res.string.files_copy_here),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            onConfirm = { target ->
                showCopyPicker = false
                viewModel.copySelected(target)
            },
            onDismiss = { showCopyPicker = false }
        )
    }

    if (showCreateAlbum) {
        RenameDialog(
            title = stringResource(Res.string.gallery_new_album),
            initialValue = "",
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = { name ->
                showCreateAlbum = false
                viewModel.createAlbum(name)
            },
            onDismiss = { showCreateAlbum = false }
        )
    }

    infoTarget?.let { file ->
        FileInfoSheet(
            file = file,
            onDismiss = viewModel::dismissInfo,
            folderPath = infoFolderPath
        )
    }

    if (confirmDeleteLocal) {
        ConfirmDialog(
            title = stringResource(Res.string.files_delete_local_copies),
            message = stringResource(Res.string.files_only_files_verified_telegram),
            confirmLabel = stringResource(Res.string.files_delete_local),
            destructive = true,
            onConfirm = {
                confirmDeleteLocal = false
                viewModel.deleteLocalCopies()
            },
            onDismiss = { confirmDeleteLocal = false }
        )
    }

    if (confirmTrash) {
        ConfirmDialog(
            title = stringResource(
                Res.string.common_confirm_trash_count_title,
                state.selection.size
            ),
            message = stringResource(Res.string.common_restore_trash_emptied),
            confirmLabel = stringResource(Res.string.common_move_trash),
            destructive = true,
            onConfirm = {
                confirmTrash = false
                viewModel.trashSelected()
            },
            onDismiss = { confirmTrash = false }
        )
    }

    renameTarget?.let { file ->
        RenameDialog(
            title = stringResource(Res.string.common_rename_file),
            initialValue = file.name,
            confirmLabel = stringResource(Res.string.common_rename),
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::dismissRename
        )
    }

    AppBackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val mediaListState = rememberSaveable(state.tab, saver = LazyListState.Saver) {
        LazyListState()
    }
    val allSelected by viewModel.allSelected.collectAsStateWithLifecycle()
    val previewSequence by viewModel.previewSequence.collectAsStateWithLifecycle()
    val mediaGridState = rememberSaveable(state.tab, saver = LazyGridState.Saver) {
        LazyGridState()
    }
    val scrolled = if (state.viewMode == ViewMode.LIST) mediaListState else mediaGridState
    if (state.viewMode == ViewMode.LIST) {
        mediaListState.rememberPosition(viewModel.listPosition, media.itemCount)
    } else {
        mediaGridState.rememberPosition(viewModel.listPosition, media.itemCount)
    }
    val lifted by rememberToolbarLift(scrolled)

    Scaffold(
        snackbarHost = {
            BottomBarSnackbarHost(
                hostState = snackbarHostState,
                applyInset = !selectionMode
            )
        },
        topBar = {
            if (selectionMode) {
                TopAppBar(
                    colors = liftedTopAppBarColors(lifted),
                    title = {
                        Text(
                            text = stringResource(
                                Res.string.common_selection_count,
                                state.selection.size
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(Res.string.common_clear_selection)
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                if (allSelected) viewModel.clearSelection() else viewModel.selectAll()
                            }
                        ) {
                            Icon(
                                imageVector = if (allSelected) {
                                    Icons.Filled.Deselect
                                } else {
                                    Icons.Filled.SelectAll
                                },
                                contentDescription = if (allSelected) {
                                    stringResource(Res.string.common_deselect_all)
                                } else {
                                    stringResource(Res.string.common_select_all)
                                }
                            )
                        }
                        if (state.selection.size > 0) {
                            Box {
                                IconButton(onClick = { showSelectionOverflow = true }) {
                                Icon(
                                    Icons.Filled.MoreVert,
                                    contentDescription = stringResource(Res.string.common_actions)
                                )
                            }
                            val storage = buildList {
                                if (state.capabilities.canDownload) {
                                    add(
                                        MenuAction(
                                            label = stringResource(Res.string.common_download),
                                            icon = Icons.Filled.Download
                                        ) { viewModel.downloadSelected() }
                                    )
                                }
                                if (state.capabilities.canUpload) {
                                    add(
                                        MenuAction(
                                            label = stringResource(Res.string.common_upload),
                                            icon = Icons.Filled.Upload
                                        ) { viewModel.uploadSelected() }
                                    )
                                }
                                if (state.capabilities.canFreeUpSpace) {
                                    add(
                                        MenuAction(
                                            label = stringResource(Res.string.common_free_space),
                                            icon = Icons.Filled.CleaningServices
                                        ) { confirmDeleteLocal = true }
                                    )
                                }
                            }
                            val organize = buildList {
                                add(
                                    MenuAction(
                                        label = stringResource(
                                            if (state.allSelectedFavorite) {
                                                Res.string.preview_remove_favorites
                                            } else {
                                                Res.string.common_add_favorites
                                            }
                                        ),
                                        icon = if (state.allSelectedFavorite) {
                                            Icons.Filled.Star
                                        } else {
                                            Icons.Filled.StarOutline
                                        }
                                    ) { viewModel.favoriteSelected(!state.allSelectedFavorite) }
                                )
                                add(
                                    MenuAction(
                                        label = stringResource(
                                            if (state.allSelectedAvailableOffline) {
                                                Res.string.common_remove_from_offline
                                            } else {
                                                Res.string.common_make_available_offline
                                            }
                                        ),
                                        icon = Icons.Filled.OfflinePin
                                    ) { viewModel.setSelectedAvailableOffline(!state.allSelectedAvailableOffline) }
                                )
                                add(
                                    MenuAction(
                                        label = stringResource(Res.string.files_hide),
                                        icon = Icons.Filled.VisibilityOff
                                    ) { viewModel.hideSelected(true) }
                                )
                                add(
                                    MenuAction(
                                        label = stringResource(Res.string.files_archive),
                                        icon = Icons.Filled.Archive
                                    ) { viewModel.archiveSelected(true) }
                                )
                            }
                            val actions = buildList {
                                add(
                                    MenuAction(
                                        label = stringResource(Res.string.common_share),
                                        icon = Icons.Filled.Share
                                    ) { viewModel.shareSelected() }
                                )
                                add(
                                    MenuAction(
                                        label = stringResource(Res.string.files_copy),
                                        icon = Icons.Filled.ContentCopy
                                    ) { showCopyPicker = true }
                                )
                                if (state.selection.size == 1) {
                                    add(
                                        MenuAction(
                                            label = stringResource(Res.string.info_details),
                                            icon = Icons.Outlined.Info
                                        ) { viewModel.showInfoForSelection() }
                                    )
                                    add(
                                        MenuAction(
                                            label = stringResource(Res.string.common_rename),
                                            icon = Icons.Filled.DriveFileRenameOutline
                                        ) { viewModel.requestRenameSelected() }
                                    )
                                }
                                add(
                                    MenuAction(
                                        label = stringResource(Res.string.files_move_to),
                                        icon = Icons.AutoMirrored.Filled.DriveFileMove
                                    ) { showMovePicker = true }
                                )
                                if (storage.isNotEmpty()) {
                                    add(
                                        MenuAction(
                                            label = stringResource(Res.string.common_storage_actions),
                                            icon = Icons.Filled.Storage,
                                            children = storage
                                        )
                                    )
                                }
                                add(
                                    MenuAction(
                                        label = stringResource(Res.string.common_organize),
                                        icon = Icons.Filled.Tune,
                                        children = organize
                                    )
                                )
                                add(
                                    MenuAction(
                                        label = stringResource(Res.string.common_move_trash),
                                        icon = Icons.Filled.DeleteOutline
                                    ) { confirmTrash = true }
                                )
                            }
                            ActionMenu(
                                expanded = showSelectionOverflow,
                                onDismissRequest = { showSelectionOverflow = false },
                                actions = actions
                            )
                            }
                        }
                    }
                )
            } else {
                TopAppBar(
                    colors = liftedTopAppBarColors(lifted),
                    title = { Text(state.albumTitle ?: stringResource(Res.string.gallery_title)) },
                    navigationIcon = {
                        onBack?.let {
                            IconButton(onClick = it) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = stringResource(Res.string.common_back)
                                )
                            }
                        }
                    },
                    actions = {
                        if (state.tab == GalleryTab.ALBUMS && !state.isAlbumView) {
                            IconButton(onClick = { showCreateAlbum = true }) {
                                Icon(
                                    Icons.Filled.CreateNewFolder,
                                    contentDescription = stringResource(Res.string.gallery_new_album)
                                )
                            }
                        }
                        if (state.isAlbumView || state.tab != GalleryTab.ALBUMS) {
                            IconButton(onClick = viewModel::startSelectionMode) {
                                Icon(
                                    Icons.Filled.CheckBoxOutlineBlank,
                                    contentDescription = stringResource(Res.string.common_select)
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                viewModel.setViewMode(
                                    if (state.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                                )
                            }
                        ) {
                            Icon(
                                imageVector = if (state.viewMode == ViewMode.GRID) {
                                    Icons.Filled.ViewList
                                } else {
                                    Icons.Filled.GridView
                                },
                                contentDescription = stringResource(
                                    if (state.viewMode == ViewMode.GRID) {
                                        Res.string.gallery_view_list
                                    } else {
                                        Res.string.gallery_view_grid
                                    }
                                )
                            )
                        }
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(Res.string.files_sort)
                            )
                        }
                        SortMenu(
                            expanded = showSortMenu,
                            current = state.sortField,
                            direction = state.sortDirection,
                            onDismiss = { showSortMenu = false },
                            onSelect = { field, direction ->
                                showSortMenu = false
                                viewModel.setSort(field, direction)
                            },
                            fields = if (state.tab == GalleryTab.ALBUMS && !state.isAlbumView) {
                                listOf(
                                    FileSortField.NAME,
                                    FileSortField.SIZE,
                                    FileSortField.DATE_MODIFIED
                                )
                            } else {
                                FileSortField.entries
                            }
                        )
                        RefreshAction(refreshing = refreshing, onRefresh = viewModel::refresh)
            if (state.tab == GalleryTab.ALBUMS && !state.isAlbumView) {
                            IconButton(onClick = { showCreateAlbum = true }) {
                                Icon(
                                    Icons.Filled.CreateNewFolder,
                                    contentDescription = stringResource(Res.string.gallery_new_album)
                                )
                            }
                        }
                        if (state.isAlbumView || state.tab != GalleryTab.ALBUMS) {
                            IconButton(onClick = viewModel::startSelectionMode) {
                                Icon(
                                    Icons.Filled.CheckBoxOutlineBlank,
                                    contentDescription = stringResource(Res.string.common_select)
                                )
                            }
                        }
                            IconButton(
                                onClick = {
                                    viewModel.setViewMode(
                                        if (state.viewMode == ViewMode.GRID) ViewMode.LIST else ViewMode.GRID
                                    )
                                }
                            ) {
                                Icon(
                                    imageVector = if (state.viewMode == ViewMode.GRID) {
                                        Icons.Filled.ViewList
                                    } else {
                                        Icons.Filled.GridView
                                    },
                                    contentDescription = stringResource(
                                        if (state.viewMode == ViewMode.GRID) {
                                            Res.string.gallery_view_list
                                        } else {
                                            Res.string.gallery_view_grid
                                        }
                                    )
                                )
                            }
                        IconButton(onClick = { showSortMenu = true }) {
                            Icon(
                                Icons.AutoMirrored.Filled.Sort,
                                contentDescription = stringResource(Res.string.files_sort)
                            )
                        }
                        SortMenu(
                            expanded = showSortMenu,
                            current = state.sortField,
                            direction = state.sortDirection,
                            onDismiss = { showSortMenu = false },
                            onSelect = { field, direction ->
                                showSortMenu = false
                                viewModel.setSort(field, direction)
                            },
                            fields = if (state.tab == GalleryTab.ALBUMS && !state.isAlbumView) {
                                listOf(
                                    FileSortField.NAME,
                                    FileSortField.SIZE,
                                    FileSortField.DATE_MODIFIED
                                )
                            } else {
                                FileSortField.entries
                            }
                        )
                        RefreshAction(refreshing = refreshing, onRefresh = viewModel::refresh)
                    }
                )
            }
        }
    ) { padding ->
        var tabsVisible by remember { mutableStateOf(true) }
        val mediaScrolls by remember(scrolled) {
            derivedStateOf { scrolled.canScrollForward || scrolled.canScrollBackward }
        }
        LaunchedEffect(state.tab) { tabsVisible = true }
        LaunchedEffect(mediaScrolls) { if (!mediaScrolls) tabsVisible = true }
        val tabScrollConnection = remember {
            object : NestedScrollConnection {
                @Suppress("SameReturnValue")
                override fun onPreScroll(
                    available: Offset,
                    source: NestedScrollSource
                ): Offset {
                    if (!mediaScrolls) return Offset.Zero
                    when {
                        available.y < -TAB_SCROLL_THRESHOLD -> tabsVisible = false
                        available.y > TAB_SCROLL_THRESHOLD -> tabsVisible = true
                    }
                    return Offset.Zero
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .nestedScroll(tabScrollConnection)
        ) {
            if (!state.isAlbumView) {
                AnimatedVisibility(
                    visible = tabsVisible,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    GalleryTabSelector(
                        selected = state.tab,
                        onSelect = viewModel::setTab
                    )
                }
            }

            OutlinedTextField(
                value = state.filters.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                singleLine = true,
                placeholder = { Text(stringResource(Res.string.gallery_search_hint)) },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null)
                },
                trailingIcon = if (state.filters.searchQuery.isNotEmpty()) {
                    {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(Res.string.gallery_clear_search)
                            )
                        }
                    }
                } else null
            )

            if (state.tab != GalleryTab.ALBUMS) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(galleryFiltersScrollState)
                    .padding(bottom = 8.dp)
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = state.filters.favoritesOnly,
                        onClick = { viewModel.setFavoritesFilter(!state.filters.favoritesOnly) },
                        label = { Text(stringResource(Res.string.collection_favorites_title)) }
                    )
                    FilterChip(
                        selected = state.filters.availableOfflineOnly,
                        onClick = { viewModel.setOfflineFilter(!state.filters.availableOfflineOnly) },
                        label = { Text(stringResource(Res.string.collection_offline_title)) }
                    )
                    FilterChip(
                        selected = state.filters.backedUpOnly,
                        onClick = { viewModel.setBackedUpFilter(!state.filters.backedUpOnly) },
                        label = { Text(stringResource(Res.string.gallery_filter_backed_up)) }
                    )
                    FilterChip(
                        selected = state.filters.notBackedUpOnly,
                        onClick = { viewModel.setNotBackedUpFilter(!state.filters.notBackedUpOnly) },
                        label = { Text(stringResource(Res.string.gallery_filter_not_backed_up)) }
                    )
                    FilterChip(
                        selected = advancedFiltersExpanded,
                        onClick = { advancedFiltersExpanded = !advancedFiltersExpanded },
                        label = { Text("Advanced filters") }
                    )
                    if (state.hasActiveFilters) {
                        TextButton(onClick = viewModel::clearFilters) {
                            Text(stringResource(Res.string.gallery_clear_filters))
                        }
                    }
                }
                    DesktopHorizontalScrollbar(
                        state = galleryFiltersScrollState,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(8.dp)
                    )
                }
            }

            if (advancedFiltersExpanded && state.tab != GalleryTab.ALBUMS) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.filters.extensionQuery,
                            onValueChange = { viewModel.setAdvancedFilters(extension = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Extension") },
                            placeholder = { Text("jpg") }
                        )
                        OutlinedTextField(
                            value = state.filters.minSizeMb,
                            onValueChange = { viewModel.setAdvancedFilters(minSizeMb = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Min size (MB)") }
                        )
                        OutlinedTextField(
                            value = state.filters.maxSizeMb,
                            onValueChange = { viewModel.setAdvancedFilters(maxSizeMb = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Max size (MB)") }
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = state.filters.modifiedAfterDate,
                            onValueChange = { viewModel.setAdvancedFilters(modifiedAfterDate = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Modified from") },
                            placeholder = { Text("YYYY-MM-DD") }
                        )
                        OutlinedTextField(
                            value = state.filters.modifiedBeforeDate,
                            onValueChange = { viewModel.setAdvancedFilters(modifiedBeforeDate = it) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            label = { Text("Modified to") },
                            placeholder = { Text("YYYY-MM-DD") }
                        )
                    }
                    Text(
                        "Use a file extension, size range in MB, and dates in YYYY-MM-DD format.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (state.tab == GalleryTab.ALBUMS && !state.isAlbumView) {
                if (!state.loaded) {
                    LoadingState()
                    return@Column
                }
                AlbumGrid(
                    albums = state.albums,
                    columns = state.albumGridSize,
                    viewMode = state.viewMode,
                    bottomPadding = padding.calculateBottomPadding() + LocalBottomBarInset.current,
                    onOpenAlbum = onOpenAlbum,
                    onZoomIn = viewModel::zoomAlbumsIn,
                    onZoomOut = viewModel::zoomAlbumsOut
                )
                return@Column
            }

            val mediaIdAt: (Int) -> String? = { index ->
                (media.peek(index) as? GalleryListItem.Media)?.file?.id
            }
            val listDragSelect = rememberDragSelect(
                listState = mediaListState,
                onStart = viewModel::startRangeSelection,
                isSelected = { index -> mediaIdAt(index)?.let(state.selection::contains) == true },
                onRange = { range, shouldSelect ->
                    viewModel.extendRangeSelection(range.mapNotNull(mediaIdAt), shouldSelect)
                },
                onEnd = viewModel::endRangeSelection
            )
            val gridDragSelect = rememberDragSelect(
                gridState = mediaGridState,
                onStart = viewModel::startRangeSelection,
                isSelected = { index -> mediaIdAt(index)?.let(state.selection::contains) == true },
                onRange = { range, shouldSelect ->
                    viewModel.extendRangeSelection(range.mapNotNull(mediaIdAt), shouldSelect)
                },
                onEnd = viewModel::endRangeSelection
            )
            val isMedia: (Int) -> Boolean = { index ->
                index < media.itemCount && media.peek(index) is GalleryListItem.Media
            }
            val listKeyboard = rememberKeyboardCollection(mediaListState, isItem = isMedia)
            val gridKeyboard = rememberKeyboardCollection(mediaGridState, mediaGridColumns, isMedia)
            val selectIfNone: (Int) -> Unit = { index ->
                if (!selectionMode) mediaIdAt(index)?.let(viewModel::toggleSelection)
            }
            val shortcuts = CollectionShortcuts(
                onToggleSelect = { index -> mediaIdAt(index)?.let(viewModel::toggleSelection) },
                onSelectAll = viewModel::selectAll,
                onDelete = { index ->
                    selectIfNone(index)
                    if (viewModel.hasSelection()) confirmTrash = true
                },
                onRename = { index -> mediaIdAt(index)?.let(viewModel::requestRename) },
                onCut = { index ->
                    selectIfNone(index)
                    viewModel.cutSelected()
                },
                onBack = onBack,
                onClear = if (selectionMode) viewModel::clearSelection else null
            )
            RefreshableContent(
                isRefreshing = refreshing,
                onRefresh = viewModel::refresh,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    media.isInitialLoad || !state.loaded -> LoadingState()

                    media.itemCount == 0 -> EmptyState(
                        icon = Icons.Outlined.Photo,
                        title = stringResource(Res.string.gallery_no_media),
                        description = stringResource(Res.string.gallery_photos_videos_appear_here)
                    )

                    state.viewMode == ViewMode.LIST -> Box(modifier = Modifier.fillMaxSize()) {
                        LazyColumn(
                        state = mediaListState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 10.dp)
                            .keyboardCollection(
                                listKeyboard,
                                media.itemCount,
                                shortcuts,
                                label = stringResource(Res.string.nav_gallery)
                            )
                            .pinchZoom(
                                onZoomIn = viewModel::zoomIn,
                                onZoomOut = viewModel::zoomOut
                            )
                            .then(listDragSelect),
                        contentPadding = PaddingValues(
                            start = 8.dp,
                            end = 8.dp,
                            top = 8.dp,
                            bottom = 8.dp + padding.calculateBottomPadding() + LocalBottomBarInset.current
                        )
                    ) {
                        items(
                            count = media.itemCount,
                            key = media.itemKey { it.key }
                        ) { index ->
                            when (val item = media[index]) {
                                is GalleryListItem.DayHeader -> DayHeaderRow(
                                    dayStartMillis = item.dayStartMillis,
                                    modifier = Modifier.animateItem()
                                )

                                is GalleryListItem.Media -> {
                                    val file = item.file
                                    FileListItem(
                                        file = file,
                                        selected = file.id in state.selection,
                                        selectionMode = selectionMode,
                                        onClick = {
                                            if (selectionMode) {
                                                viewModel.toggleSelection(file.id)
                                            } else {
                                                onOpenFile(file.id, previewSequence)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleSelection(file.id) },
                                        onToggleSelection = { viewModel.toggleSelection(file.id) },
                                        modifier = Modifier
                                            .keyboardCollectionItem(listKeyboard, index)
                                            .animateItem()
                                            .onDesktopContextClick {
                                                viewModel.selectFileForContext(file.id)
                                                showSelectionOverflow = true
                                            }
                                    )
                                }

                                null -> Unit
                            }
                        }
                    }
                        DesktopVerticalScrollbar(
                            state = mediaListState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }

                    else -> Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                        state = mediaGridState,
                        columns = GridCells.Fixed(mediaGridColumns),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(end = 10.dp)
                            .keyboardCollection(
                                gridKeyboard,
                                media.itemCount,
                                shortcuts,
                                label = stringResource(Res.string.nav_gallery)
                            )
                            .pinchZoom(
                                onZoomIn = viewModel::zoomIn,
                                onZoomOut = viewModel::zoomOut
                            )
                            .then(gridDragSelect),
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 12.dp,
                            bottom = 12.dp + padding.calculateBottomPadding() + LocalBottomBarInset.current
                        ),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(
                            count = media.itemCount,
                            key = media.itemKey { it.key },
                            span = { index ->
                                if (media.peek(index) is GalleryListItem.DayHeader) {
                                    GridItemSpan(maxLineSpan)
                                } else {
                                    GridItemSpan(1)
                                }
                            }
                        ) { index ->
                            when (val item = media[index]) {
                                is GalleryListItem.DayHeader -> DayHeaderRow(
                                    dayStartMillis = item.dayStartMillis,
                                    modifier = Modifier.animateItem()
                                )

                                is GalleryListItem.Media -> {
                                    val file = item.file
                                    FileGridItem(
                                        file = file,
                                        selected = file.id in state.selection,
                                        compact = true,
                                        onToggleSelection = { viewModel.toggleSelection(file.id) },
                                        onClick = {
                                            if (selectionMode) {
                                                viewModel.toggleSelection(file.id)
                                            } else {
                                                onOpenFile(file.id, previewSequence)
                                            }
                                        },
                                        onLongClick = { viewModel.toggleSelection(file.id) },
                                        modifier = Modifier
                                            .keyboardCollectionItem(gridKeyboard, index)
                                            .animateItem()
                                            .onDesktopContextClick {
                                                viewModel.selectFileForContext(file.id)
                                                showSelectionOverflow = true
                                            }
                                    )
                                }

                                null -> Unit
                            }
                        }
                    }
                        DesktopVerticalScrollbar(
                            state = mediaGridState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}

private const val TAB_SCROLL_THRESHOLD = 6f
