package com.drdisagree.teledrive.presentation.files

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.DriveFolderUpload
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarOutline
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButtonMenu
import androidx.compose.material3.FloatingActionButtonMenuItem
import androidx.compose.material3.Icon
import com.drdisagree.teledrive.presentation.components.TooltipIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleFloatingActionButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.DriveFolder
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.model.ViewMode
import com.drdisagree.teledrive.presentation.common.AppBackHandler
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.add
import com.drdisagree.teledrive.presentation.common.isInitialLoad
import com.drdisagree.teledrive.presentation.common.resolve
import com.drdisagree.teledrive.presentation.components.ActionMenu
import com.drdisagree.teledrive.presentation.components.BlockingProgressDialog
import com.drdisagree.teledrive.presentation.components.BottomBarSnackbarHost
import com.drdisagree.teledrive.presentation.components.CollectionShortcuts
import com.drdisagree.teledrive.presentation.components.ConfirmDialog
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.FileGridItem
import com.drdisagree.teledrive.presentation.components.onDesktopContextClick
import com.drdisagree.teledrive.presentation.components.onDesktopEmptySpaceContextClick
import com.drdisagree.teledrive.presentation.components.SortMenu
import com.drdisagree.teledrive.presentation.components.FileInfoSheet
import com.drdisagree.teledrive.presentation.components.FileListItem
import com.drdisagree.teledrive.presentation.components.FileDetailsItem
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.DesktopScrollEdgeControls
import com.drdisagree.teledrive.presentation.components.FolderGridItem
import com.drdisagree.teledrive.presentation.components.FolderInfoSheet
import com.drdisagree.teledrive.presentation.components.FolderPickerDialog
import com.drdisagree.teledrive.presentation.components.FolderRow
import com.drdisagree.teledrive.presentation.components.LoadingState
import com.drdisagree.teledrive.presentation.components.MenuAction
import com.drdisagree.teledrive.presentation.components.RefreshAction
import com.drdisagree.teledrive.presentation.components.RefreshableContent
import com.drdisagree.teledrive.presentation.components.RenameDialog
import com.drdisagree.teledrive.presentation.components.keyboardCollection
import com.drdisagree.teledrive.presentation.components.keyboardCollectionItem
import com.drdisagree.teledrive.presentation.components.liftedTopAppBarColors
import com.drdisagree.teledrive.presentation.components.pinchZoom
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import com.drdisagree.teledrive.presentation.components.rememberKeyboardCollection
import com.drdisagree.teledrive.presentation.components.rememberToolbarLift
import com.drdisagree.teledrive.presentation.navigation.FabBottomBarInset
import com.drdisagree.teledrive.presentation.navigation.LocalBottomBarInset
import com.drdisagree.teledrive.presentation.platform.FileDropArea
import com.drdisagree.teledrive.presentation.platform.LocalDeleteConsentLauncher
import com.drdisagree.teledrive.presentation.platform.LocalFileRevealer
import com.drdisagree.teledrive.presentation.platform.LocalFileSharer
import com.drdisagree.teledrive.presentation.platform.LocalFolderPicker
import com.drdisagree.teledrive.presentation.platform.LocalMultiFilePicker
import com.drdisagree.teledrive.presentation.platform.PickResult
import com.drdisagree.teledrive.presentation.preview.PreviewSequence
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_actions
import com.drdisagree.teledrive.resources.common_add
import com.drdisagree.teledrive.resources.common_add_favorites
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.common_cancel
import com.drdisagree.teledrive.resources.common_clear_selection
import com.drdisagree.teledrive.resources.common_confirm_trash_count_title
import com.drdisagree.teledrive.resources.common_create
import com.drdisagree.teledrive.resources.common_deselect_all
import com.drdisagree.teledrive.resources.common_download
import com.drdisagree.teledrive.resources.common_free_space
import com.drdisagree.teledrive.resources.common_make_available_offline
import com.drdisagree.teledrive.resources.common_move_trash
import com.drdisagree.teledrive.resources.common_remove_from_offline
import com.drdisagree.teledrive.resources.common_rename
import com.drdisagree.teledrive.resources.common_restore_trash_emptied
import com.drdisagree.teledrive.resources.common_select_all
import com.drdisagree.teledrive.resources.common_select
import com.drdisagree.teledrive.resources.common_selection_count
import com.drdisagree.teledrive.resources.common_share
import com.drdisagree.teledrive.resources.files
import com.drdisagree.teledrive.resources.files_view_details
import com.drdisagree.teledrive.resources.files_view_list
import com.drdisagree.teledrive.resources.files_view_grid
import com.drdisagree.teledrive.resources.files_change_view
import com.drdisagree.teledrive.resources.files_add_files
import com.drdisagree.teledrive.resources.files_add_folder
import com.drdisagree.teledrive.resources.files_archive
import com.drdisagree.teledrive.resources.files_back_import_appear
import com.drdisagree.teledrive.resources.files_copy
import com.drdisagree.teledrive.resources.files_copy_here
import com.drdisagree.teledrive.resources.files_copy_to
import com.drdisagree.teledrive.resources.files_delete_local
import com.drdisagree.teledrive.resources.files_delete_local_copies
import com.drdisagree.teledrive.resources.files_folder_exists_message
import com.drdisagree.teledrive.resources.files_folder_exists_title
import com.drdisagree.teledrive.resources.files_folder_keep_both
import com.drdisagree.teledrive.resources.files_folder_merge
import com.drdisagree.teledrive.resources.files_folders_exist_message
import com.drdisagree.teledrive.resources.files_folders_exist_title
import com.drdisagree.teledrive.resources.files_hide
import com.drdisagree.teledrive.resources.files_move
import com.drdisagree.teledrive.resources.files_move_here
import com.drdisagree.teledrive.resources.files_move_to
import com.drdisagree.teledrive.resources.files_new_folder
import com.drdisagree.teledrive.resources.files_nothing_here_yet
import com.drdisagree.teledrive.resources.files_only_files_verified_telegram
import com.drdisagree.teledrive.resources.files_queued_for_download
import com.drdisagree.teledrive.resources.files_rename_file
import com.drdisagree.teledrive.resources.files_rename_folder
import com.drdisagree.teledrive.resources.files_search
import com.drdisagree.teledrive.resources.files_share_destination_confirm
import com.drdisagree.teledrive.resources.files_share_destination_title
import com.drdisagree.teledrive.resources.snackbar_view_transfers
import com.drdisagree.teledrive.resources.files_sort
import com.drdisagree.teledrive.resources.info_details
import com.drdisagree.teledrive.resources.note_edit_action
import com.drdisagree.teledrive.resources.nav_files
import com.drdisagree.teledrive.resources.note_new
import com.drdisagree.teledrive.resources.preview_remove_favorites
import com.drdisagree.teledrive.resources.preview_share_chooser_title
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalFoundationApi::class,
    ExperimentalComposeUiApi::class
)
@Composable
fun FilesScreen(
    focusFileId: String? = null,
    onOpenFolder: (String) -> Unit,
    onOpenCrumb: (String?) -> Unit,
    onOpenFile: (String, PreviewSequence) -> Unit,
    onOpenSearch: () -> Unit,
    onOpenTransfers: () -> Unit,
    onViewThumbnailQueue: () -> Unit,
    onNewNote: (String?) -> Unit,
    onEditNote: (String, String) -> Unit,
    onBack: (() -> Unit)?,
    viewModel: FilesViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val selectionModeEnabled by viewModel.selectionModeEnabled.collectAsStateWithLifecycle()
    val selectionMode = state.selectionMode || selectionModeEnabled
    val renameTarget by viewModel.renameTarget.collectAsStateWithLifecycle()
    val files = viewModel.pagedFiles.collectAsLazyPagingItems()
    val infoTarget by viewModel.infoTarget.collectAsStateWithLifecycle()
    val storageAudit by viewModel.storageAudit.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val allSelected by viewModel.allSelected.collectAsStateWithLifecycle()
    val activeFolderId by viewModel.activeFolderId.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var tabs by rememberSaveable(
        stateSaver = listSaver<List<FilesTabState>, String>(
            save = { saved ->
                saved.flatMap { tab ->
                    listOf(tab.id.toString(), tab.folderId.orEmpty(), tab.label)
                }
            },
            restore = { encoded ->
                encoded.chunked(3).mapNotNull { fields ->
                    if (fields.size != 3) null
                    else FilesTabState(
                        id = fields[0].toIntOrNull() ?: return@mapNotNull null,
                        folderId = fields[1].ifEmpty { null },
                        label = fields[2]
                    )
                }.ifEmpty { listOf(FilesTabState(0, null, "Files")) }
            }
        )
    ) { mutableStateOf(listOf(FilesTabState(0, activeFolderId, "Files"))) }
    var selectedTabId by rememberSaveable { mutableIntStateOf(0) }
    var nextTabId by rememberSaveable { mutableIntStateOf(1) }

    var showCreateFolder by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showViewMenu by remember { mutableStateOf(false) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var showCopyPicker by remember { mutableStateOf(false) }
    var showSelectionOverflow by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }

    LaunchedEffect(selectionMode) {
        if (!selectionMode) showSelectionOverflow = false
    }
    val uploadPicker = LocalMultiFilePicker.current
    val onPickUploads = {
        uploadPicker.pick { references ->
            if (references.isNotEmpty()) viewModel.importAndUpload(references)
        }
    }
    val folderUploadPicker = LocalFolderPicker.current
    val onPickUploadFolder = {
        folderUploadPicker.pick { result ->
            if (result is PickResult.Picked) viewModel.importAndUpload(listOf(result.path))
        }
    }

    LaunchedEffect(selectionMode) {
        if (!selectionMode) showSelectionOverflow = false
    }

    val deleteConsentLauncher = LocalDeleteConsentLauncher.current

    val viewTransfersLabel = stringResource(Res.string.snackbar_view_transfers)
    val isThumbnailQueueMessage: (UiText) -> Boolean = { message ->
        message is UiText.Plain &&
            message.value.startsWith("Added ") &&
            message.value.contains("missing thumbnails to the priority queue")
    }
    CollectSnackbarMessages(
        messages = viewModel.messages,
        hostState = snackbarHostState,
        actionLabel = { message ->
            when {
                isThumbnailQueueMessage(message) -> "View queue"
                message is UiText.Resource &&
                    message.resource == Res.string.files_queued_for_download -> viewTransfersLabel
                else -> null
            }
        },
        onAction = { message ->
            if (isThumbnailQueueMessage(message)) onViewThumbnailQueue()
            else onOpenTransfers()
        }
    )
    LaunchedEffect(Unit) {
        viewModel.deleteConsentRequests.collect { request ->
            deleteConsentLauncher.launch(request) { granted ->
                if (granted) viewModel.retryLocalCopyRemoval()
            }
        }
    }

    val working by viewModel.working.collectAsStateWithLifecycle()
    working?.let { BlockingProgressDialog(message = it.resolve()) }

    AppBackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val gridState = rememberLazyGridState()
    val tabPositions by rememberSaveable(
        stateSaver = listSaver<MutableMap<Pair<Int, String?>, Pair<Int, Int>>, String>(
            save = { positions ->
                positions.flatMap { (key, value) ->
                    listOf(
                        key.first.toString(),
                        key.second.orEmpty(),
                        value.first.toString(),
                        value.second.toString()
                    )
                }
            },
            restore = { encoded ->
                encoded.chunked(4).mapNotNull { fields ->
                    if (fields.size != 4) null
                    else {
                        val tabId = fields[0].toIntOrNull() ?: return@mapNotNull null
                        val itemIndex = fields[2].toIntOrNull() ?: return@mapNotNull null
                        val scrollOffset = fields[3].toIntOrNull() ?: return@mapNotNull null
                        (tabId to fields[1].ifEmpty { null }) to (itemIndex to scrollOffset)
                    }
                }.toMap().toMutableMap()
            }
        )
    ) { mutableStateOf(mutableMapOf()) }
    fun saveCurrentTabPosition() {
        tabPositions[selectedTabId to state.folderId] =
            gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset
    }
    fun navigateToFolder(folderId: String?) {
        saveCurrentTabPosition()
        viewModel.setActiveFolder(folderId)
    }
    fun activateTab(tab: FilesTabState) {
        if (tab.id == selectedTabId) return
        saveCurrentTabPosition()
        selectedTabId = tab.id
        viewModel.setActiveFolder(tab.folderId)
    }
    fun createTab() {
        saveCurrentTabPosition()
        val id = nextTabId++
        tabs = tabs + FilesTabState(id, null, "Files")
        selectedTabId = id
        viewModel.setActiveFolder(null)
    }
    fun closeTab(tab: FilesTabState) {
        if (tabs.size <= 1) return
        val oldIndex = tabs.indexOfFirst { it.id == tab.id }.coerceAtLeast(0)
        tabPositions.keys.removeAll { it.first == tab.id }
        val nextTabs = tabs.filterNot { it.id == tab.id }
        tabs = nextTabs
        if (tab.id == selectedTabId) {
            val replacement = nextTabs[oldIndex.coerceAtMost(nextTabs.lastIndex)]
            selectedTabId = replacement.id
            viewModel.setActiveFolder(replacement.folderId)
        }
    }
    val activeFolderLabel = if (activeFolderId == null) {
        stringResource(Res.string.files)
    } else {
        state.breadcrumbs.lastOrNull()?.name?.resolve() ?: "Folder"
    }
    LaunchedEffect(activeFolderId, activeFolderLabel) {
        tabs = tabs.map { tab ->
            if (tab.id == selectedTabId) {
                tab.copy(folderId = activeFolderId, label = activeFolderLabel)
            } else {
                tab
            }
        }
    }
    LaunchedEffect(selectedTabId, state.folderId, files.itemCount, files.loadState.refresh) {
        val currentTab = tabs.firstOrNull { it.id == selectedTabId } ?: return@LaunchedEffect
        if (currentTab.folderId != state.folderId || files.loadState.refresh is LoadState.Loading) {
            return@LaunchedEffect
        }
        val target = tabPositions[selectedTabId to state.folderId] ?: (0 to 0)
        val count = state.folders.size + files.itemCount
        if (count > 0) gridState.scrollToItem(target.first.coerceIn(0, count - 1), target.second)
    }
    var fabVisible by remember { mutableStateOf(true) }
    val listScrolls by remember {
        derivedStateOf { gridState.canScrollForward || gridState.canScrollBackward }
    }
    LaunchedEffect(listScrolls) { if (!listScrolls) fabVisible = true }
    val fabScrollConnection = remember {
        object : NestedScrollConnection {
            @Suppress("SameReturnValue")
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!listScrolls) return Offset.Zero
                when {
                    available.y < -FAB_SCROLL_THRESHOLD -> fabVisible = false
                    available.y > FAB_SCROLL_THRESHOLD -> fabVisible = true
                }
                return Offset.Zero
            }
        }
    }
    val lifted by rememberToolbarLift(gridState)

    var highlightedFileId by remember(focusFileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(focusFileId) {
        val target = focusFileId ?: return@LaunchedEffect
        // The effect starts before paging has anything, so wait for the first
        // page rather than giving up on an empty list.
        snapshotFlow { files.itemCount to files.loadState.refresh }
            .first { (count, refresh) -> count > 0 && refresh !is LoadState.Loading }

        var rounds = 0
        while (rounds++ < FOCUS_PAGE_LIMIT) {
            val index = (0 until files.itemCount)
                .firstOrNull { files.peek(it)?.id == target }
            if (index != null) {
                gridState.animateScrollToItem(state.folders.size + index)
                highlightedFileId = target
                delay(FOCUS_HIGHLIGHT_MS.milliseconds)
                highlightedFileId = null
                return@LaunchedEffect
            }
            if (files.loadState.append.endOfPaginationReached) return@LaunchedEffect

            val loaded = files.itemCount
            files[loaded - 1]
            val grew = withTimeoutOrNull(FOCUS_PAGE_WAIT_MS.milliseconds) {
                snapshotFlow { files.itemCount to files.loadState.append.endOfPaginationReached }
                    .first { (count, ended) -> count > loaded || ended }
            }
            if (grew == null) return@LaunchedEffect
        }
    }

    val density = LocalDensity.current
    var snackbarHeight by remember { mutableStateOf(0.dp) }
    val fabLift by animateDpAsState(targetValue = snackbarHeight, label = "fabLift")

    FileDropArea(onDropped = { references -> viewModel.importAndUpload(references) }) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                snackbarHost = {},
                topBar = {
                    if (selectionMode) {
                        TopAppBar(
                            colors = liftedTopAppBarColors(lifted),
                            title = {
                                Text(
                                    text = stringResource(
                                        Res.string.common_selection_count,
                                        state.selectionCount
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                TooltipIconButton(tooltip = stringResource(Res.string.common_clear_selection), onClick = viewModel::clearSelection) {
                                    Icon(
                                        Icons.Filled.Close,
                                        contentDescription = stringResource(Res.string.common_clear_selection)
                                    )
                                }
                            },
                            actions = {
                                TooltipIconButton(
                                    tooltip = stringResource(if (allSelected) Res.string.common_deselect_all else Res.string.common_select_all),
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
                                if (state.selectionCount > 0) {
                                    TooltipIconButton(tooltip = stringResource(Res.string.files_move), onClick = { showMovePicker = true }) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.DriveFileMove,
                                            contentDescription = stringResource(Res.string.files_move)
                                        )
                                    }
                                    Box {
                                        TooltipIconButton(tooltip = stringResource(Res.string.common_actions), onClick = { showSelectionOverflow = true }) {
                                        Icon(
                                            Icons.Filled.MoreVert,
                                            contentDescription = stringResource(Res.string.common_actions)
                                        )
                                    }
                                    val editableNote = state.capabilities.canEditNote &&
                                            state.selectionCount == 1 &&
                                            state.folderSelection.isEmpty()
                                    val filesOnly = !state.folderInSelection

                                    val selectedItemCount = state.selectionCount
                                    val actions = buildList {
                                        if (filesOnly) {
                                            add(
                                                MenuAction(
                                                    label = stringResource(Res.string.common_share),
                                                    icon = Icons.Filled.Share
                                                ) { viewModel.shareSelected() }
                                            )
                                        }
                                        if (state.folderInSelection || state.capabilities.canDownload) {
                                            add(
                                                MenuAction(
                                                    label = stringResource(Res.string.common_download),
                                                    icon = Icons.Filled.Download
                                                ) { viewModel.downloadSelected() }
                                            )
                                        }
                                        if (selectedItemCount > 0) {
                                            add(
                                                MenuAction(
                                                    label = "Storage locations / Free up space",
                                                    icon = Icons.Filled.CleaningServices
                                                ) { viewModel.inspectSelectedStorage() }
                                            )
                                        }
                                        if (selectedItemCount == 1) {
                                            add(
                                                MenuAction(
                                                    label = stringResource(Res.string.common_rename),
                                                    icon = Icons.Filled.DriveFileRenameOutline
                                                ) { viewModel.requestRenameSelected() }
                                            )
                                        }
                                        add(
                                            MenuAction(
                                                label = "Generate thumbnails",
                                                icon = Icons.Filled.Image
                                            ) { viewModel.generateThumbnailsSelected() }
                                        )
                                        add(
                                            MenuAction(
                                                label = stringResource(Res.string.files_copy),
                                                icon = Icons.Filled.ContentCopy
                                            ) { showCopyPicker = true }
                                        )
                                        add(
                                            MenuAction(
                                                label = stringResource(Res.string.files_move_to),
                                                icon = Icons.AutoMirrored.Filled.DriveFileMove
                                            ) { showMovePicker = true }
                                        )
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
                                        if (editableNote) {
                                            add(
                                                MenuAction(
                                                    label = stringResource(Res.string.note_edit_action),
                                                    icon = Icons.Filled.EditNote
                                                ) { viewModel.editSelectedNote() }
                                            )
                                        }
                                        if (selectedItemCount == 1) {
                                            add(
                                                MenuAction(
                                                    label = stringResource(Res.string.info_details),
                                                    icon = Icons.Outlined.Info
                                                ) { viewModel.showInfoForSelection() }
                                            )
                                        }
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
                            title = { Text(stringResource(Res.string.files)) },
                            navigationIcon = {
                                onBack?.let {
                                    TooltipIconButton(tooltip = stringResource(Res.string.common_back), onClick = it) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowBack,
                                            contentDescription = stringResource(Res.string.common_back)
                                        )
                                    }
                                }
                            },
                            actions = {
                                TooltipIconButton(tooltip = stringResource(Res.string.common_select), onClick = viewModel::startSelectionMode) {
                                    Icon(
                                        Icons.Filled.CheckBoxOutlineBlank,
                                        contentDescription = stringResource(Res.string.common_select)
                                    )
                                }
                                TooltipIconButton(tooltip = "View thumbnail queue", onClick = onViewThumbnailQueue) {
                                    Icon(
                                        Icons.Filled.CloudQueue,
                                        contentDescription = "View thumbnail queue"
                                    )
                                }
                                RefreshAction(
                                    refreshing = refreshing,
                                    onRefresh = viewModel::refresh
                                )
                                TooltipIconButton(tooltip = stringResource(Res.string.files_search), onClick = onOpenSearch) {
                                    Icon(
                                        Icons.Filled.Search,
                                        contentDescription = stringResource(Res.string.files_search)
                                    )
                                }
                                TooltipIconButton(tooltip = stringResource(Res.string.files_new_folder), onClick = { showCreateFolder = true }) {
                                    Icon(
                                        Icons.Filled.CreateNewFolder,
                                        contentDescription = stringResource(Res.string.files_new_folder)
                                    )
                                }
                                Box {
                                    TooltipIconButton(tooltip = stringResource(Res.string.files_change_view), onClick = { showViewMenu = true }) {
                                        Icon(
                                            Icons.Filled.ViewList,
                                            contentDescription = stringResource(Res.string.files_change_view)
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = showViewMenu,
                                        onDismissRequest = { showViewMenu = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(Res.string.files_view_grid)) },
                                            onClick = {
                                                showViewMenu = false
                                                viewModel.setViewMode(ViewMode.GRID)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(Res.string.files_view_list)) },
                                            onClick = {
                                                showViewMenu = false
                                                viewModel.setViewMode(ViewMode.LIST)
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(Res.string.files_view_details)) },
                                            onClick = {
                                                showViewMenu = false
                                                viewModel.setViewMode(ViewMode.DETAILS)
                                            }
                                        )
                                    }
                                }
                                TooltipIconButton(tooltip = stringResource(Res.string.files_sort), onClick = { showSortMenu = true }) {
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
                                    }
                                )
                            }
                        )
                    }
                },
                floatingActionButton = {
                    AnimatedVisibility(
                        visible = !selectionMode && (fabVisible || showAddMenu),
                        enter = scaleIn() + fadeIn(),
                        exit = scaleOut() + fadeOut()
                    ) {
                        FloatingActionButtonMenu(
                            expanded = showAddMenu,
                            modifier = Modifier.padding(
                                bottom = fabLift + if (LocalBottomBarInset.current > 0.dp) {
                                    FabBottomBarInset
                                } else {
                                    0.dp
                                }
                            ),
                            button = {
                                ToggleFloatingActionButton(
                                    checked = showAddMenu,
                                    onCheckedChange = { showAddMenu = it }
                                ) {
                                    Icon(
                                        imageVector = if (showAddMenu) {
                                            Icons.Filled.Close
                                        } else {
                                            Icons.Filled.Add
                                        },
                                        contentDescription = stringResource(Res.string.common_add),
                                        tint = if (showAddMenu) {
                                            MaterialTheme.colorScheme.onPrimary
                                        } else {
                                            MaterialTheme.colorScheme.onPrimaryContainer
                                        }
                                    )
                                }
                            }
                        ) {
                            FloatingActionButtonMenuItem(
                                onClick = {
                                    showAddMenu = false
                                    onPickUploads()
                                },
                                icon = { Icon(Icons.Filled.Upload, contentDescription = null) },
                                text = { Text(stringResource(Res.string.files_add_files)) }
                            )
                            FloatingActionButtonMenuItem(
                                onClick = {
                                    showAddMenu = false
                                    onPickUploadFolder()
                                },
                                icon = {
                                    Icon(
                                        Icons.Filled.DriveFolderUpload,
                                        contentDescription = null
                                    )
                                },
                                text = { Text(stringResource(Res.string.files_add_folder)) }
                            )
                            FloatingActionButtonMenuItem(
                                onClick = {
                                    showAddMenu = false
                                    onNewNote(state.folderId)
                                },
                                icon = { Icon(Icons.Filled.EditNote, contentDescription = null) },
                                text = { Text(stringResource(Res.string.note_new)) }
                            )
                        }
                    }
                }
            ) { padding ->
                var everLoaded by remember(state.folderId) { mutableStateOf(false) }
                val loadingNow = !state.loaded || files.isInitialLoad
                LaunchedEffect(loadingNow) {
                    if (!loadingNow) everLoaded = true
                }
                val isLoading = loadingNow && !everLoaded
                val isEmpty = files.itemCount == 0 && state.folders.isEmpty() && !loadingNow
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = padding.calculateTopPadding())
                        .nestedScroll(fabScrollConnection)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            tabs.forEach { tab ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(
                                        onClick = { activateTab(tab) },
                                        colors = ButtonDefaults.textButtonColors(
                                            containerColor = if (tab.id == selectedTabId) {
                                                MaterialTheme.colorScheme.secondaryContainer
                                            } else {
                                                Color.Transparent
                                            },
                                            contentColor = if (tab.id == selectedTabId) {
                                                MaterialTheme.colorScheme.onSecondaryContainer
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        ),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) { Text(tab.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                    if (tabs.size > 1) {
                                        TooltipIconButton(tooltip = "Close ${tab.label} tab", onClick = { closeTab(tab) }, modifier = Modifier.size(28.dp)) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Close ${tab.label} tab",
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        TooltipIconButton(tooltip = "Open new Files tab", onClick = ::createTab) {
                            Icon(Icons.Filled.Add, contentDescription = "Open new Files tab")
                        }
                    }
                    if (state.breadcrumbs.size > 1) {
                        Breadcrumbs(crumbs = state.breadcrumbs, onOpen = ::navigateToFolder)
                    }
                    RefreshableContent(
                        isRefreshing = refreshing,
                        onRefresh = viewModel::refresh,
                        modifier = Modifier
                            .fillMaxSize()
                            .onDesktopEmptySpaceContextClick(
                                onContextClick = {
                                    if (!selectionMode) showAddMenu = true
                                },
                                onEmptySpaceClick = {
                                    if (showAddMenu) showAddMenu = false
                                }
                            )
                    ) {
                        if (isLoading) {
                            LoadingState()
                        } else if (isEmpty) {
                            EmptyState(
                                icon = Icons.Outlined.FolderOff,
                                title = stringResource(Res.string.files_nothing_here_yet),
                                description = stringResource(Res.string.files_back_import_appear)
                            )
                        } else {
                            FilesContent(
                                highlightedFileId = highlightedFileId,
                                gridState = gridState,
                                state = state,
                                selectionMode = selectionMode,
                                onShowContextMenu = { showSelectionOverflow = true },
                                files = files,
                                padding = PaddingValues(
                                    bottom = padding.calculateBottomPadding() +
                                            LocalBottomBarInset.current
                                ),
                                onOpenFolder = ::navigateToFolder,
                                onOpenFile = onOpenFile,
                                onRequestTrash = { confirmTrash = true },
                                onCreateFolder = { showCreateFolder = true },
                                onBack = {
                                    if (state.folderId != null) {
                                        navigateToFolder(state.breadcrumbs.dropLast(1).lastOrNull()?.id)
                                    } else {
                                        onBack?.invoke()
                                    }
                                },
                                viewModel = viewModel
                            )
                        }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            ) {
                BottomBarSnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.onSizeChanged { size ->
                        snackbarHeight = with(density) { size.height.toDp() }
                    }
                )
            }
        }
    }

    val folderConflict by viewModel.folderConflict.collectAsStateWithLifecycle()
    folderConflict?.let { conflict ->
        val single = conflict.names.singleOrNull()
        AlertDialog(
            onDismissRequest = viewModel::cancelFolderImport,
            title = {
                Text(
                    if (single != null) {
                        stringResource(Res.string.files_folder_exists_title, single)
                    } else {
                        stringResource(Res.string.files_folders_exist_title)
                    }
                )
            },
            text = {
                Text(
                    if (single != null) {
                        stringResource(
                            Res.string.files_folder_exists_message,
                            conflict.renamed.getValue(single)
                        )
                    } else {
                        stringResource(Res.string.files_folders_exist_message)
                    }
                )
            },
            confirmButton = {
                Button(
                    onClick = viewModel::mergeIntoExistingFolders,
                    shapes = ButtonDefaults.shapes()
                ) { Text(stringResource(Res.string.files_folder_merge)) }
            },
            dismissButton = {
                Row {
                    TextButton(
                        onClick = viewModel::cancelFolderImport,
                        shapes = ButtonDefaults.shapes()
                    ) { Text(stringResource(Res.string.common_cancel)) }
                    TextButton(
                        onClick = viewModel::keepBothFolders,
                        shapes = ButtonDefaults.shapes()
                    ) { Text(stringResource(Res.string.files_folder_keep_both)) }
                }
            }
        )
    }

    if (showMovePicker || showCopyPicker) {
        val moving = showMovePicker
        FolderPickerHost(
            title = stringResource(
                if (moving) Res.string.files_move_to else Res.string.files_copy_to
            ),
            confirmLabel = stringResource(
                if (moving) Res.string.files_move_here else Res.string.files_copy_here
            ),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            excludedFolderIds = state.folderSelection,
            onConfirm = { target ->
                showMovePicker = false
                showCopyPicker = false
                if (moving) viewModel.moveSelected(target) else viewModel.copySelected(target)
            },
            onDismiss = {
                showMovePicker = false
                showCopyPicker = false
            }
        )
    }
    if (showCreateFolder) {
        RenameDialog(
            title = stringResource(Res.string.files_new_folder),
            initialValue = "",
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = {
                showCreateFolder = false
                viewModel.createFolder(it)
            },
            onDismiss = { showCreateFolder = false }
        )
    }
    renameTarget?.let { target ->
        RenameDialog(
            title = if (target.isFolder) stringResource(Res.string.files_rename_folder) else stringResource(
                Res.string.files_rename_file
            ),
            initialValue = target.name,
            confirmLabel = stringResource(Res.string.common_rename),
            onConfirm = viewModel::confirmRename,
            onDismiss = viewModel::dismissRename
        )
    }
    if (confirmTrash) {
        ConfirmDialog(
            title = stringResource(
                Res.string.common_confirm_trash_count_title,
                state.selectionCount
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
    val fileRevealer = LocalFileRevealer.current
    storageAudit?.let { snapshot ->
        StorageAuditDialog(
            snapshot = snapshot,
            onRefresh = viewModel::refreshStorageAudit,
            onDismiss = viewModel::dismissStorageAudit,
            canReveal = fileRevealer != null,
            onReveal = { path -> fileRevealer?.reveal(path) ?: false },
            onDeleteManagedCopies = viewModel::deleteAuditedLocalCopies
        )
    }
    LaunchedEffect(Unit) {
        viewModel.editRequests.collect { request ->
            onEditNote(request.fileId, request.title)
        }
    }

    val fileSharer = LocalFileSharer.current
    val shareChooserTitle = stringResource(Res.string.preview_share_chooser_title)
    LaunchedEffect(Unit) {
        viewModel.shareRequests.collect { request ->
            fileSharer.share(request.paths, request.mimeType, shareChooserTitle)
        }
    }

    val sharedUris by viewModel.sharedUris.collectAsStateWithLifecycle()
    if (sharedUris.isNotEmpty()) {
        FolderPickerHost(
            title = stringResource(Res.string.files_share_destination_title),
            confirmLabel = stringResource(Res.string.files_share_destination_confirm),
            loadChildren = viewModel::childFolders,
            createFolder = viewModel::createFolderIn,
            onConfirm = { target -> viewModel.acceptShare(sharedUris, target) },
            onDismiss = viewModel::dismissShare
        )
    }

    val folderInfoTarget by viewModel.folderInfoTarget.collectAsStateWithLifecycle()
    folderInfoTarget?.let { folder ->
        FolderInfoSheet(folder = folder, onDismiss = viewModel::dismissFolderInfo)
    }

    infoTarget?.let { file ->
        FileInfoSheet(file = file, onDismiss = viewModel::dismissInfo)
    }
}

/** Deep trees keep only the last three folders inline, so the row never grows past one line. */
@Composable
private fun Breadcrumbs(
    crumbs: List<FolderCrumb>,
    onOpen: (String?) -> Unit
) {
    var showCollapsed by remember { mutableStateOf(false) }
    val inlineCount = INLINE_CRUMBS.coerceAtMost(crumbs.size)
    val collapsed = crumbs.dropLast(inlineCount)
    val inline = crumbs.takeLast(inlineCount)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (collapsed.isNotEmpty()) {
            Box {
                CrumbLabel(
                    text = "…",
                    current = false,
                    onClick = { showCollapsed = true }
                )
                DropdownMenu(
                    expanded = showCollapsed,
                    onDismissRequest = { showCollapsed = false }
                ) {
                    collapsed.forEach { crumb ->
                        DropdownMenuItem(
                            text = { Text(crumb.name.resolve()) },
                            onClick = {
                                showCollapsed = false
                                onOpen(crumb.id)
                            }
                        )
                    }
                }
            }
            CrumbSeparator()
        }
        inline.forEachIndexed { index, crumb ->
            if (index > 0) CrumbSeparator()
            CrumbLabel(
                text = crumb.name.resolve(),
                current = index == inline.lastIndex,
                onClick = { onOpen(crumb.id) },
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}

@Composable
private fun CrumbSeparator() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun CrumbLabel(
    text: String,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = if (current) {
            MaterialTheme.colorScheme.onSurface
        } else {
            MaterialTheme.colorScheme.primary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .then(
                if (current) Modifier else Modifier.clickable(role = Role.Button, onClick = onClick)
            )
            .padding(horizontal = 6.dp, vertical = 4.dp)
    )
}

private const val INLINE_CRUMBS = 3

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FilesContent(
    highlightedFileId: String?,
    gridState: LazyGridState,
    state: FilesUiState,
    selectionMode: Boolean,
    onShowContextMenu: () -> Unit,
    files: LazyPagingItems<DriveFile>,
    padding: PaddingValues,
    onOpenFolder: (String) -> Unit,
    onOpenFile: (String, PreviewSequence) -> Unit,
    onRequestTrash: () -> Unit,
    onCreateFolder: () -> Unit,
    onBack: (() -> Unit)?,
    viewModel: FilesViewModel
) {
    val columns = if (state.viewMode == ViewMode.GRID) state.gridSize else 1
    val keyboard = rememberKeyboardCollection(gridState, columns)
    val itemCount = state.folders.size + files.itemCount
    val toggleAt: (Int) -> Unit = { index ->
        if (index < state.folders.size) {
            viewModel.toggleFolderSelection(state.folders[index].id)
        } else {
            files.peek(index - state.folders.size)?.let { viewModel.toggleSelection(it.id) }
        }
    }
    val selectIfNone: (Int) -> Unit = { index -> if (!selectionMode) toggleAt(index) }
    val shortcuts = CollectionShortcuts(
        onToggleSelect = toggleAt,
        onSelectAll = viewModel::selectAll,
        onDelete = { index ->
            selectIfNone(index)
            onRequestTrash()
        },
        onRename = { index ->
            if (index < state.folders.size) {
                viewModel.requestRename(null, state.folders[index].id)
            } else {
                files.peek(index - state.folders.size)?.let { viewModel.requestRename(it.id, null) }
            }
        },
        onCopy = { index ->
            selectIfNone(index)
            viewModel.copyToClipboardSelected()
        },
        onCut = { index ->
            selectIfNone(index)
            viewModel.cutSelected()
        },
        onPaste = viewModel::paste,
        onNewFolder = onCreateFolder,
        onBack = onBack,
        onClear = if (selectionMode) viewModel::clearSelection else null
    )
    val dragSelect = rememberDragSelect(
        gridState = gridState,
        onStart = viewModel::startRangeSelection,
        isSelected = { index ->
            if (index < state.folders.size) {
                state.folderSelection.contains(state.folders[index].id)
            } else {
                files.peek(index - state.folders.size)?.id?.let(state.selection::contains) == true
            }
        },
        onRange = { range, shouldSelect ->
            val folderIds = mutableListOf<String>()
            val fileIds = mutableListOf<String>()
            for (index in range) {
                if (index < state.folders.size) {
                    folderIds += state.folders[index].id
                } else {
                    val fileIndex = index - state.folders.size
                    if (fileIndex < files.itemCount) {
                        files.peek(fileIndex)?.let { fileIds += it.id }
                    }
                }
            }
            viewModel.extendRangeSelection(fileIds, folderIds, shouldSelect)
        },
        onEnd = viewModel::endRangeSelection
    )
    Box(modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(columns),
        modifier = Modifier
            .fillMaxSize()
            .padding(end = 10.dp)
            .keyboardCollection(
                keyboard,
                itemCount,
                shortcuts,
                label = stringResource(Res.string.nav_files)
            )
            .pinchZoom(onZoomIn = viewModel::zoomIn, onZoomOut = viewModel::zoomOut)
            .then(dragSelect),
        contentPadding = padding.add(horizontal = 12.dp, top = 12.dp, bottom = 100.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            count = state.folders.size,
            key = { "folder-${state.folders[it].id}" },
            span = {
                if (state.viewMode == ViewMode.GRID) GridItemSpan(1)
                else GridItemSpan(maxLineSpan)
            }
        ) { index ->
            val folder = state.folders[index]
            val folderSelected = folder.id in state.folderSelection
            if (state.viewMode == ViewMode.GRID) {
                FolderGridItem(
                    folder = folder,
                    selected = folderSelected,
                    onClick = {
                        if (selectionMode) viewModel.toggleFolderSelection(folder.id)
                        else onOpenFolder(folder.id)
                    },
                    onLongClick = { viewModel.toggleFolderSelection(folder.id) },
                    onToggleSelection = { viewModel.toggleFolderSelection(folder.id) },
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, index)
                        .animateItem()
                        .onDesktopContextClick {
                            viewModel.selectFolderForContext(folder.id)
                            onShowContextMenu()
                        }
                )
            } else {
                FolderRow(
                    folder = folder,
                    selected = folderSelected,
                    onClick = {
                        if (selectionMode) viewModel.toggleFolderSelection(folder.id)
                        else onOpenFolder(folder.id)
                    },
                    onLongClick = { viewModel.toggleFolderSelection(folder.id) },
                    onToggleSelection = { viewModel.toggleFolderSelection(folder.id) },
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, index)
                        .animateItem()
                        .onDesktopContextClick {
                            viewModel.selectFolderForContext(folder.id)
                            onShowContextMenu()
                        }
                )
            }
        }

        items(
            count = files.itemCount,
            key = files.itemKey { "file-${it.id}" }
        ) { index ->
            val file = files[index] ?: return@items
            val selected = file.id in state.selection
            val highlighted = file.id == highlightedFileId
            if (state.viewMode == ViewMode.GRID) {
                FileGridItem(
                    file = file,
                    selected = selected,
                    onToggleSelection = { viewModel.toggleSelection(file.id) },
                    onClick = {
                        if (selectionMode) viewModel.toggleSelection(file.id)
                        else onOpenFile(
                            file.id,
                            PreviewSequence(
                                folderId = state.folderId,
                                filterByFolder = true,
                                sortField = state.sortField,
                                sortDirection = state.sortDirection
                            )
                        )
                    },
                    onLongClick = { viewModel.toggleSelection(file.id) },
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, state.folders.size + index)
                        .animateItem()
                        .focusHighlight(highlighted)
                        .onDesktopContextClick {
                            viewModel.selectFileForContext(file.id)
                            onShowContextMenu()
                        }
                )
            } else if (state.viewMode == ViewMode.LIST) {
                FileListItem(
                    file = file,
                    selected = selected,
                    selectionMode = selectionMode,
                    prominent = true,
                    onToggleSelection = { viewModel.toggleSelection(file.id) },
                    onClick = {
                        if (selectionMode) viewModel.toggleSelection(file.id)
                        else onOpenFile(
                            file.id,
                            PreviewSequence(
                                folderId = state.folderId,
                                filterByFolder = true,
                                sortField = state.sortField,
                                sortDirection = state.sortDirection
                            )
                        )
                    },
                    onLongClick = { viewModel.toggleSelection(file.id) },
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, state.folders.size + index)
                        .animateItem()
                        .focusHighlight(highlighted)
                        .onDesktopContextClick {
                            viewModel.selectFileForContext(file.id)
                            onShowContextMenu()
                        }
                )
            } else {
                FileDetailsItem(
                    file = file,
                    selected = selected,
                    selectionMode = selectionMode,
                    onToggleSelection = { viewModel.toggleSelection(file.id) },
                    onClick = {
                        if (selectionMode) viewModel.toggleSelection(file.id)
                        else onOpenFile(
                            file.id,
                            PreviewSequence(
                                folderId = state.folderId,
                                filterByFolder = true,
                                sortField = state.sortField,
                                sortDirection = state.sortDirection
                            )
                        )
                    },
                    onLongClick = { viewModel.toggleSelection(file.id) },
                    modifier = Modifier
                        .keyboardCollectionItem(keyboard, state.folders.size + index)
                        .animateItem()
                        .focusHighlight(highlighted)
                        .onDesktopContextClick {
                            viewModel.selectFileForContext(file.id)
                            onShowContextMenu()
                        }
                )
            }
        }
        }
        DesktopScrollEdgeControls(
            state = gridState,
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 8.dp)
        )
        DesktopVerticalScrollbar(
            state = gridState,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

@Composable
fun FolderPickerHost(
    title: String,
    confirmLabel: String,
    loadChildren: suspend (String?) -> List<DriveFolder>,
    createFolder: suspend (String?, String) -> Boolean,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
    excludedFolderIds: Set<String> = emptySet()
) {
    val childrenCache = remember { mutableStateMapOf<String, List<DriveFolder>>() }
    val namesCache = remember { mutableStateMapOf<String, String>() }
    val parentsCache = remember { mutableStateMapOf<String, String?>() }
    var requestedLevel by remember { mutableStateOf<String?>(ROOT_KEY) }
    var reloadToken by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(requestedLevel, reloadToken) {
        val level = requestedLevel ?: return@LaunchedEffect
        val parentId = level.takeIf { it != ROOT_KEY }
        val folders = loadChildren(parentId)
        childrenCache[level] = folders
        folders.forEach { folder ->
            namesCache[folder.id] = folder.name
            parentsCache[folder.id] = parentId
        }
    }

    FolderPickerDialog(
        title = title,
        confirmLabel = confirmLabel,
        childrenOf = { parentId ->
            val key = parentId ?: ROOT_KEY
            childrenCache[key] ?: run {
                requestedLevel = key
                emptyList()
            }
        },
        nameOf = { namesCache[it].orEmpty() },
        parentOf = { parentsCache[it] },
        excludedFolderIds = excludedFolderIds,
        onCreateFolder = { parentId, name ->
            scope.launch {
                if (createFolder(parentId, name)) {
                    childrenCache.remove(parentId ?: ROOT_KEY)
                    requestedLevel = parentId ?: ROOT_KEY
                    reloadToken++
                }
            }
        },
        onConfirm = onConfirm,
        onDismiss = onDismiss
    )
}

private data class FilesTabState(val id: Int, val folderId: String?, val label: String)

private const val ROOT_KEY = "__root__"

private const val FAB_SCROLL_THRESHOLD = 6f


/** Draws over the item, since both item shapes paint their own background. */
@Composable
private fun Modifier.focusHighlight(active: Boolean): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (active) FOCUS_TINT_ALPHA else 0f,
        label = "focusHighlight"
    )
    val tint = MaterialTheme.colorScheme.secondary
    return drawWithContent {
        drawContent()
        if (alpha <= 0f) return@drawWithContent
        val corners = CornerRadius(FOCUS_TINT_RADIUS.toPx())
        drawRoundRect(color = tint, alpha = alpha * FOCUS_FILL_SCALE, cornerRadius = corners)
        // A tint alone disappears against a bright thumbnail, so the outline
        // carries the signal and the fill only softens it.
        drawRoundRect(
            color = tint,
            alpha = alpha,
            cornerRadius = corners,
            style = Stroke(width = FOCUS_OUTLINE_WIDTH.toPx())
        )
    }
}

private const val FOCUS_PAGE_LIMIT = 10
private const val FOCUS_PAGE_WAIT_MS = 5_000L
private const val FOCUS_HIGHLIGHT_MS = 1_800L
private const val FOCUS_TINT_ALPHA = 0.9f
private const val FOCUS_FILL_SCALE = 0.25f
private val FOCUS_OUTLINE_WIDTH = 3.dp
private val FOCUS_TINT_RADIUS = 18.dp
