package com.drdisagree.teledrive.presentation.gallery

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.files.DeleteConsentRequest
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.toUiText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.drdisagree.teledrive.domain.model.DriveFolder
import com.drdisagree.teledrive.core.common.AppResult
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.files_cut_count
import com.drdisagree.teledrive.resources.files_queued_for_download
import com.drdisagree.teledrive.resources.files_copied_all
import com.drdisagree.teledrive.resources.files_copied_partial
import com.drdisagree.teledrive.resources.files_share_needs_local
import com.drdisagree.teledrive.resources.files_kept_offline_copies
import com.drdisagree.teledrive.resources.files_removed_local_copies
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.MediaAlbum
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.files.CutItems
import com.drdisagree.teledrive.presentation.files.FileClipboard
import com.drdisagree.teledrive.presentation.files.ShareRequest
import com.drdisagree.teledrive.domain.usecase.MakeAvailableOfflineUseCase
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.common.ListPosition
import com.drdisagree.teledrive.presentation.components.GridZoomLevel
import com.drdisagree.teledrive.presentation.components.MAX_GRID_COLUMNS
import com.drdisagree.teledrive.presentation.components.MIN_GRID_COLUMNS
import com.drdisagree.teledrive.presentation.components.SelectionCapabilities
import com.drdisagree.teledrive.presentation.components.zoomedIn
import com.drdisagree.teledrive.presentation.components.zoomedOut
import com.drdisagree.teledrive.presentation.navigation.Route
import com.drdisagree.teledrive.presentation.preview.PreviewSequence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

private data class GalleryBrowseState(
    val tab: GalleryTab,
    val sortField: FileSortField,
    val sortDirection: SortDirection,
    val filters: GalleryFilters
)

private fun albumComparator(
    field: FileSortField,
    direction: SortDirection
): Comparator<MediaAlbum> {
    val ascending = when (field) {
        FileSortField.SIZE -> compareBy<MediaAlbum> { it.itemCount }
        FileSortField.DATE_MODIFIED, FileSortField.DATE_ADDED ->
            compareBy<MediaAlbum> { it.latestAt }
        else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.orEmpty() }
    }
    return if (direction == SortDirection.ASCENDING) {
        ascending.thenBy { it.folderId.orEmpty() }
    } else {
        ascending.reversed().thenBy { it.folderId.orEmpty() }
    }
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class GalleryViewModel(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository,
    private val trashRepository: TrashRepository,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val makeAvailableOffline: MakeAvailableOfflineUseCase,
    private val clipboard: FileClipboard
) : ViewModel() {

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _shareRequests = MutableSharedFlow<ShareRequest>(extraBufferCapacity = 2)
    val shareRequests = _shareRequests.asSharedFlow()

    private val _deleteConsentRequests = MutableSharedFlow<DeleteConsentRequest>(extraBufferCapacity = 1)
    val deleteConsentRequests = _deleteConsentRequests.asSharedFlow()
    private var pendingLocalCopyIds: List<String> = emptyList()

    private val _infoTarget = MutableStateFlow<DriveFile?>(null)
    val infoTarget: StateFlow<DriveFile?> = _infoTarget.asStateFlow()

    private fun report(error: AppError) {
        _messages.tryEmit(error.toUiText())
    }

    private fun AppResult<*>.reportFailure() {
        if (this is AppResult.Failure) report(error)
    }

    private val route: Route.GalleryAlbum? = runCatching {
        savedStateHandle.toRoute<Route.GalleryAlbum>()
    }.getOrNull()

    private val albumFolderId: String? = route?.folderId
    private val isAlbumView: Boolean = route != null

    private val tab = MutableStateFlow(GalleryTab.ALL)
    private val sort = settingsRepository.preferences
        .map { it.gallerySortField to it.gallerySortDirection }
        .distinctUntilChanged()
    private val filters = MutableStateFlow(GalleryFilters())
    private val debouncedQuery = filters
        .map { it.searchQuery }
        .debounce(250.milliseconds)
        .distinctUntilChanged()
    private val filterOptions = filters
        .map { it.copy(searchQuery = "") }
        .distinctUntilChanged()

    val listPosition = ListPosition(savedStateHandle)

    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val _selectionModeEnabled = MutableStateFlow(false)
    val selectionModeEnabled: StateFlow<Boolean> = _selectionModeEnabled.asStateFlow()
    private var rangeBase: Set<String>? = null
    private val _allSelected = MutableStateFlow(false)
    val allSelected: StateFlow<Boolean> = _allSelected.asStateFlow()

    private val spec: Flow<FileQuerySpec> = combine(
        tab,
        sort,
        debouncedQuery,
        filterOptions
    ) { currentTab, (field, direction), query, currentFilters ->
        FileQuerySpec(
            folderId = albumFolderId,
            filterByFolder = isAlbumView,
            categories = when (currentTab) {
                GalleryTab.PHOTOS -> listOf(FileCategory.IMAGE)
                GalleryTab.VIDEOS -> listOf(FileCategory.VIDEO)
                else -> listOf(FileCategory.IMAGE, FileCategory.VIDEO)
            },
            nameQuery = query.takeIf { it.isNotBlank() },
            favoritesOnly = currentFilters.favoritesOnly,
            availableOfflineOnly = currentFilters.availableOfflineOnly,
            backedUpOnly = currentFilters.backedUpOnly,
            notBackedUpOnly = currentFilters.notBackedUpOnly,
            showHidden = false,
            showArchived = false,
            sortField = field,
            sortDirection = direction
        )
    }.distinctUntilChanged()

    private val albums: Flow<List<MediaAlbum>> = combine(debouncedQuery, sort) { query, sortValue ->
        query to sortValue
    }.flatMapLatest { (query, sortValue) ->
        if (isAlbumView) {
            flowOf(emptyList())
        } else {
            fileRepository.observeAlbums(showHidden = false, showArchived = false).map { rows ->
                rows.asSequence()
                    .filter { album ->
                        query.isBlank() || album.name?.contains(query, ignoreCase = true) == true
                    }
                    .sortedWith(albumComparator(sortValue.first, sortValue.second))
                    .toList()
            }
        }
    }

    val previewSequence: StateFlow<PreviewSequence> = combine(tab, sort, filters) {
            current, (field, dir), currentFilters ->
        PreviewSequence(
            folderId = albumFolderId,
            filterByFolder = isAlbumView,
            nameQuery = currentFilters.searchQuery.takeIf { it.isNotBlank() },
            categories = when (current) {
                GalleryTab.PHOTOS -> listOf(FileCategory.IMAGE)
                GalleryTab.VIDEOS -> listOf(FileCategory.VIDEO)
                else -> listOf(FileCategory.IMAGE, FileCategory.VIDEO)
            },
            favoritesOnly = currentFilters.favoritesOnly,
            availableOfflineOnly = currentFilters.availableOfflineOnly,
            backedUpOnly = currentFilters.backedUpOnly,
            notBackedUpOnly = currentFilters.notBackedUpOnly,
            sortField = field,
            sortDirection = dir
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PreviewSequence())

    val pagedMedia: Flow<PagingData<GalleryListItem>> = spec
        .flatMapLatest { current ->
            fileRepository.pagedFiles(current).map { data -> withDayHeaders(data, current) }
        }
        .cachedIn(viewModelScope)

    private val selectionCapabilities: Flow<Pair<Set<String>, SelectionCapabilities>> = selection
        .flatMapLatest { ids ->
            flow {
                emit(ids to SelectionCapabilities.of(fileRepository.getFiles(ids.toList())))
            }
        }

    private val browseState = combine(tab, sort, filters) { currentTab, sortValue, currentFilters ->
        GalleryBrowseState(
            tab = currentTab,
            sortField = sortValue.first,
            sortDirection = sortValue.second,
            filters = currentFilters
        )
    }

    val uiState: StateFlow<GalleryUiState> = combine(
        browseState,
        selectionCapabilities,
        settingsRepository.preferences
            .map { it.galleryGridSize to it.albumGridSize }
            .distinctUntilChanged(),
        combine(
            albums,
            settingsRepository.preferences.map { it.galleryViewMode }.distinctUntilChanged()
        ) { albumList, mode -> albumList to mode }
    ) { browse, selectionState, sizes, extras ->
        val (selected, capabilities) = selectionState
        val (albumList, mode) = extras
        val (gridSize, albumGridSize) = sizes
        GalleryUiState(
            tab = browse.tab,
            albums = albumList,
            albumTitle = route?.title,
            viewMode = mode,
            isAlbumView = isAlbumView,
            gridSize = gridSize,
            albumGridSize = albumGridSize,
            sortField = browse.sortField,
            sortDirection = browse.sortDirection,
            filters = browse.filters,
            selection = selected,
            capabilities = capabilities,
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GalleryUiState())

    private fun withDayHeaders(
        data: PagingData<DriveFile>,
        spec: FileQuerySpec
    ): PagingData<GalleryListItem> {
        val scope = spec.categories.joinToString("+") { it.name }
        val mapped = data.map<DriveFile, GalleryListItem> { GalleryListItem.Media(it, scope) }
        if (spec.sortField != FileSortField.DATE_MODIFIED &&
            spec.sortField != FileSortField.DATE_ADDED
        ) {
            return mapped
        }
        return mapped.insertSeparators { before, after ->
            val next = (after as? GalleryListItem.Media)?.file ?: return@insertSeparators null
            val nextDay = Formatters.dayStart(timestampFor(next, spec.sortField))
            val previous = (before as? GalleryListItem.Media)?.file
            val previousDay = previous
                ?.let { Formatters.dayStart(timestampFor(it, spec.sortField)) }
            if (previousDay == nextDay) null else GalleryListItem.DayHeader(nextDay, scope)
        }
    }

    private fun timestampFor(file: DriveFile, sortField: FileSortField): Long =
        if (sortField == FileSortField.DATE_ADDED) file.addedAt else file.modifiedAt

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun refresh() {
        if (_refreshing.value) return
        _refreshing.update { true }
        viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            syncRepository.fullResync()
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < MIN_REFRESH_VISIBLE_MS) delay((MIN_REFRESH_VISIBLE_MS - elapsed).milliseconds)
            _refreshing.update { false }
        }
    }

    fun setTab(value: GalleryTab) {
        clearSelection()
        tab.update { value }
    }

    fun setSort(field: FileSortField, direction: SortDirection) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(gallerySortField = field, gallerySortDirection = direction)
            }
        }
    }

    fun setSearchQuery(value: String) {
        if (filters.value.searchQuery != value) clearSelection()
        filters.update { it.copy(searchQuery = value) }
    }

    fun setFavoritesFilter(enabled: Boolean) {
        clearSelection()
        filters.update { it.copy(favoritesOnly = enabled) }
    }

    fun setOfflineFilter(enabled: Boolean) {
        clearSelection()
        filters.update { it.copy(availableOfflineOnly = enabled) }
    }

    fun setBackedUpFilter(enabled: Boolean) {
        clearSelection()
        filters.update {
            it.copy(backedUpOnly = enabled, notBackedUpOnly = if (enabled) false else it.notBackedUpOnly)
        }
    }

    fun setNotBackedUpFilter(enabled: Boolean) {
        clearSelection()
        filters.update {
            it.copy(notBackedUpOnly = enabled, backedUpOnly = if (enabled) false else it.backedUpOnly)
        }
    }

    fun clearFilters() {
        clearSelection()
        filters.update { GalleryFilters() }
    }

    fun setViewMode(mode: com.drdisagree.teledrive.domain.model.ViewMode) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(galleryViewMode = mode) }
        }
    }

    fun zoomIn() = applyZoom(GridZoomLevel::zoomedIn)

    fun zoomOut() = applyZoom(GridZoomLevel::zoomedOut)

    fun zoomAlbumsIn() = stepAlbumColumns(-1)

    fun zoomAlbumsOut() = stepAlbumColumns(1)

    private fun stepAlbumColumns(step: Int) {
        viewModelScope.launch {
            settingsRepository.update { prefs ->
                prefs.copy(
                    albumGridSize = (prefs.albumGridSize + step)
                        .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
                )
            }
        }
    }

    private fun applyZoom(transform: (GridZoomLevel) -> GridZoomLevel) {
        viewModelScope.launch {
            settingsRepository.update { prefs ->
                val next = transform(GridZoomLevel(prefs.galleryViewMode, prefs.galleryGridSize))
                prefs.copy(galleryViewMode = next.viewMode, galleryGridSize = next.gridSize)
            }
        }
    }

    fun startSelectionMode() {
        _selectionModeEnabled.update { true }
    }

    fun selectFileForContext(id: String) {
        _selectionModeEnabled.update { true }
        _allSelected.update { false }
        if (id !in selection.value) selection.update { setOf(id) }
    }

    fun toggleSelection(id: String) {
        _allSelected.update { false }
        selection.update { if (id in it) it - id else it + id }
    }

    fun clearSelection() {
        _selectionModeEnabled.update { false }
        _allSelected.update { false }
        selection.update { emptySet() }
    }

    fun hasSelection(): Boolean = selection.value.isNotEmpty()

    fun selectAll() {
        viewModelScope.launch {
            selection.update { fileRepository.fileIds(spec.first()).toSet() }
            _allSelected.update { true }
        }
    }

    fun startRangeSelection() {
        rangeBase = selection.value
    }

    fun extendRangeSelection(ids: List<String>) {
        val base = rangeBase ?: return
        _allSelected.update { false }
        selection.update { base + ids }
    }

    fun endRangeSelection() {
        rangeBase = null
    }

    suspend fun childFolders(parentId: String?): List<DriveFolder> =
        fileRepository.observeFolders(parentId, showHidden = true, showArchived = true).first()

    suspend fun createFolderIn(parentId: String?, name: String): Boolean =
        when (val result = fileRepository.createFolder(parentId, name)) {
            is AppResult.Success -> true
            is AppResult.Failure -> {
                report(result.error)
                false
            }
        }

    fun moveSelected(targetFolderId: String?) {
        val ids = selection.value.toList()
        clearSelection()
        if (ids.isEmpty()) return
        viewModelScope.launch { fileRepository.moveFiles(ids, targetFolderId).reportFailure() }
    }

    fun cutSelected() {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        clearSelection()
        clipboard.cut(CutItems(ids, emptyList()))
        _messages.tryEmit(UiText.Resource(Res.string.files_cut_count, ids.size))
    }

    fun trashSelected() {
        val ids = selection.value.toList()
        clearSelection()
        viewModelScope.launch { trashRepository.moveFilesToTrash(ids).reportFailure() }
    }

    fun copySelected(targetFolderId: String?) {
        val ids = selection.value.toList()
        clearSelection()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            when (val result = fileRepository.copyFiles(ids, targetFolderId)) {
                is AppResult.Success -> _messages.tryEmit(
                    if (result.value == ids.size) {
                        UiText.Resource(Res.string.files_copied_all, result.value)
                    } else {
                        UiText.Resource(Res.string.files_copied_partial, result.value, ids.size)
                    }
                )
                is AppResult.Failure -> report(result.error)
            }
        }
    }

    fun shareSelected() {
        val ids = selection.value.toList()
        if (ids.isEmpty()) return
        clearSelection()
        viewModelScope.launch {
            val files = fileRepository.getFiles(ids)
            val paths = files.mapNotNull { it.localPath }
            if (files.size != ids.size || paths.size != files.size) {
                _messages.tryEmit(UiText.Resource(Res.string.files_share_needs_local))
                return@launch
            }
            val mimeType = files.map { it.mimeType }.distinct().singleOrNull() ?: "*/*"
            _shareRequests.tryEmit(ShareRequest(paths, mimeType))
        }
    }

    fun deleteLocalCopies() {
        val ids = selection.value.toList()
        clearSelection()
        removeLocalCopies(ids)
    }

    fun retryLocalCopyRemoval() {
        removeLocalCopies(pendingLocalCopyIds)
    }

    private fun removeLocalCopies(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            when (val result = fileRepository.deleteLocalCopy(ids)) {
                is AppResult.Success -> {
                    val consent = result.value.consentRequest
                    if (consent != null) {
                        pendingLocalCopyIds = ids
                        _deleteConsentRequests.tryEmit(consent)
                    } else {
                        pendingLocalCopyIds = emptyList()
                        val kept = result.value.keptOffline
                        _messages.tryEmit(
                            if (kept > 0 && result.value.deletedCount == 0) {
                                UiText.Resource(Res.string.files_kept_offline_copies, kept)
                            } else {
                                UiText.Resource(
                                    Res.string.files_removed_local_copies,
                                    result.value.deletedCount
                                )
                            }
                        )
                    }
                }
                is AppResult.Failure -> report(result.error)
            }
        }
    }

    fun showInfoForSelection() {
        val fileId = selection.value.singleOrNull() ?: return
        viewModelScope.launch {
            fileRepository.getFile(fileId)?.let { _infoTarget.value = it }
            clearSelection()
        }
    }

    fun dismissInfo() {
        _infoTarget.value = null
    }

    fun downloadSelected() {
        val ids = selection.value.toList()
        clearSelection()
        viewModelScope.launch {
            var queued = 0
            ids.forEach { id ->
                when (val result = transferRepository.enqueueDownload(id)) {
                    is AppResult.Success -> queued++
                    is AppResult.Failure -> report(result.error)
                }
            }
            if (queued > 0) {
                _messages.tryEmit(UiText.Resource(Res.string.files_queued_for_download, queued))
            }
        }
    }

    fun uploadSelected() {
        val ids = selection.value.toList()
        clearSelection()
        viewModelScope.launch {
            ids.forEach { transferRepository.enqueueUpload(it).reportFailure() }
        }
    }

    private val _renameTarget = MutableStateFlow<DriveFile?>(null)
    val renameTarget: StateFlow<DriveFile?> = _renameTarget.asStateFlow()

    fun requestRenameSelected() {
        requestRename(selection.value.singleOrNull() ?: return)
    }

    fun requestRename(id: String) {
        viewModelScope.launch { _renameTarget.value = fileRepository.getFile(id) }
    }

    fun dismissRename() {
        _renameTarget.value = null
    }

    fun confirmRename(newName: String) {
        val target = _renameTarget.value ?: return
        _renameTarget.value = null
        clearSelection()
        viewModelScope.launch { fileRepository.renameFile(target.id, newName).reportFailure() }
    }

    fun favoriteSelected(favorite: Boolean) {
        val ids = selection.value.toList()
        clearSelection()
        viewModelScope.launch { fileRepository.setFilesFavorite(ids, favorite) }
    }

    fun setSelectedAvailableOffline(available: Boolean) {
        val ids = selection.value.toList()
        clearSelection()
        viewModelScope.launch {
            val queued = makeAvailableOffline(ids, available = available)
            if (queued > 0) {
                _messages.tryEmit(UiText.Resource(Res.string.files_queued_for_download, queued))
            }
        }
    }

    fun hideSelected(hidden: Boolean) {
        val ids = selection.value.toList()
        clearSelection()
        viewModelScope.launch { fileRepository.setFilesHidden(ids, hidden) }
    }

    fun archiveSelected(archived: Boolean) {
        val ids = selection.value.toList()
        clearSelection()
        viewModelScope.launch { fileRepository.setFilesArchived(ids, archived) }
    }

    companion object {
        private const val MIN_REFRESH_VISIBLE_MS = 700L
    }
}
