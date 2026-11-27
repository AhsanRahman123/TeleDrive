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
import com.drdisagree.teledrive.resources.files_queued_for_upload
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
import com.drdisagree.teledrive.domain.repository.CacheRepository
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

private const val BYTES_PER_MEGABYTE = 1024L * 1024L

private fun String.toSizeBytes(): Long? = trim().toLongOrNull()
    ?.takeIf { it >= 0L }
    ?.coerceAtMost(Long.MAX_VALUE / BYTES_PER_MEGABYTE)
    ?.times(BYTES_PER_MEGABYTE)

private fun String.toStartOfDayMillis(): Long? = trim().takeIf { it.isNotEmpty() }
    ?.let { value ->
        runCatching {
            java.time.LocalDate.parse(value)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }

private fun String.toEndOfDayMillis(): Long? = trim().takeIf { it.isNotEmpty() }
    ?.let { value ->
        runCatching {
            java.time.LocalDate.parse(value).plusDays(1)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli() - 1L
        }.getOrNull()
    }

private data class GalleryBrowseState(
    val tab: GalleryTab,
    val sortField: FileSortField,
    val sortDirection: SortDirection,
    val filters: GalleryFilters
)

/**
 * The categories a gallery listing may show: the tab's category set intersected
 * with the album media-type filter's set. With the filter at
 * [GalleryMediaType.ALL] this is exactly the tab behaviour that predates the
 * filter, so the root Gallery is unchanged; inside an album (tab stuck at
 * [GalleryTab.ALL]) PHOTOS narrows to images and VIDEOS to videos.
 * (Ported from Android vc182, report 55 §4.)
 */
fun mediaCategories(tab: GalleryTab, mediaType: GalleryMediaType): List<FileCategory> {
    val tabCategories = when (tab) {
        GalleryTab.PHOTOS -> listOf(FileCategory.IMAGE)
        GalleryTab.VIDEOS -> listOf(FileCategory.VIDEO)
        else -> listOf(FileCategory.IMAGE, FileCategory.VIDEO)
    }
    val mediaTypeCategories = when (mediaType) {
        GalleryMediaType.PHOTOS -> listOf(FileCategory.IMAGE)
        GalleryMediaType.VIDEOS -> listOf(FileCategory.VIDEO)
        GalleryMediaType.ALL -> listOf(FileCategory.IMAGE, FileCategory.VIDEO)
    }
    return tabCategories.filter { it in mediaTypeCategories }
}

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
    private val cacheRepository: CacheRepository,
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
    private val _infoFolderPath = MutableStateFlow<String?>(null)
    val infoFolderPath: StateFlow<String?> = _infoFolderPath.asStateFlow()
    // Location labels are cached per folder id, but a cached path goes stale the moment any
    // folder on the path is renamed or moved (C4). The cache is therefore cleared whenever
    // the folder structure (id -> name/parent) changes — see the collector in init — and is
    // concurrent + size-capped so it can neither corrupt nor grow without bound.
    private val folderPathCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun cacheFolderPath(folderId: String, path: String) {
        if (folderPathCache.size >= MAX_FOLDER_PATH_CACHE_ENTRIES) folderPathCache.clear()
        folderPathCache[folderId] = path
    }

    suspend fun folderPathLabel(folderId: String?): String {
        if (folderId == null) return "Drive root"
        folderPathCache[folderId]?.let { return it }
        return folderPathFor(folderId).orEmpty().ifBlank { "Unknown folder" }
            .also { cacheFolderPath(folderId, it) }
    }

    private suspend fun folderPathFor(folderId: String?): String? {
        if (folderId == null) return "Drive root"
        folderPathCache[folderId]?.let { return it }
        var currentId = folderId
        val visited = mutableSetOf<String>()
        val segments = mutableListOf<String>()
        while (currentId != null && visited.add(currentId)) {
            val folder = fileRepository.getFolder(currentId) ?: break
            segments += folder.name
            currentId = folder.parentId
        }
        return segments.asReversed().joinToString(" / ").takeIf { it.isNotBlank() }
            ?.also { cacheFolderPath(folderId, it) }
    }

    /**
     * Albums are one row per folder, shown flat with no parent context, so two
     * different folders that share a name (an "Instagram" under two different
     * parents, say) are indistinguishable in the list. When a display name
     * repeats, attach the parent folder's path so they can be told apart.
     * Nothing is merged here: same-named folders under different parents are
     * legitimate, and only the sync-time merger converges same-parent
     * duplicates, where a merge is provably safe.
     */
    private suspend fun labelDuplicateAlbums(albums: List<MediaAlbum>): List<MediaAlbum> {
        val counts = albums.groupingBy { duplicateAlbumKey(it.name) }.eachCount()
        if (counts.values.none { it > 1 }) return albums
        return albums.map { album ->
            if ((counts[duplicateAlbumKey(album.name)] ?: 0) < 2) return@map album
            val parentId = album.folderId?.let { fileRepository.getFolder(it)?.parentId }
            album.copy(locationLabel = folderPathLabel(parentId))
        }
    }

    private fun duplicateAlbumKey(name: String?): String =
        name?.trim()?.lowercase().orEmpty()

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

    // Pinch zoom is a temporary, in-session adjustment only. The persisted Settings values
    // (galleryGridSize / albumGridSize) stay the single source of truth for the column counts:
    // a pinch records an override here instead of rewriting the setting, and any later
    // Settings change clears the override so the new value applies immediately.
    private val mediaZoomOverride = MutableStateFlow<Int?>(null)
    private val albumZoomOverride = MutableStateFlow<Int?>(null)

    init {
        viewModelScope.launch {
            settingsRepository.preferences
                .map { it.galleryGridSize }
                .distinctUntilChanged()
                .collect { mediaZoomOverride.value = null }
        }
        viewModelScope.launch {
            settingsRepository.preferences
                .map { it.albumGridSize }
                .distinctUntilChanged()
                .collect { albumZoomOverride.value = null }
        }
        // C4: folder location labels must follow renames/moves immediately, not at the
        // next process restart. Any change to the folder structure invalidates the
        // cached paths; unchanged emissions are filtered out by the signature.
        viewModelScope.launch {
            fileRepository.observeAllFolders()
                .map { folders ->
                    folders.mapTo(HashSet()) { Triple(it.id, it.name, it.parentId) }
                }
                .distinctUntilChanged()
                .collect { folderPathCache.clear() }
        }
    }

    private val effectiveGridSizes: Flow<Pair<Int, Int>> = combine(
        settingsRepository.preferences
            .map { it.galleryGridSize to it.albumGridSize }
            .distinctUntilChanged(),
        mediaZoomOverride,
        albumZoomOverride
    ) { persisted, mediaOverride, albumOverride ->
        (mediaOverride ?: persisted.first) to (albumOverride ?: persisted.second)
    }
    private val debouncedQuery = filters
        .map { it.searchQuery }
        .debounce(250.milliseconds)
        .distinctUntilChanged()
    private val filterOptions = filters
        .map { it.copy(searchQuery = "") }
        .distinctUntilChanged()
        .debounce(250.milliseconds)

    val listPosition = ListPosition(savedStateHandle)

    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val albumSelection = MutableStateFlow<Set<String>>(emptySet())
    private val _selectionModeEnabled = MutableStateFlow(false)
    val selectionModeEnabled: StateFlow<Boolean> = _selectionModeEnabled.asStateFlow()
    private var rangeBase: Set<String>? = null
    private var albumRangeBase: Set<String>? = null
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
            categories = mediaCategories(currentTab, currentFilters.mediaType),
            nameQuery = query.takeIf { it.isNotBlank() },
            favoritesOnly = currentFilters.favoritesOnly,
            availableOfflineOnly = currentFilters.availableOfflineOnly,
            backedUpOnly = currentFilters.backedUpOnly,
            notBackedUpOnly = currentFilters.notBackedUpOnly,
            missingThumbnailOnly = currentFilters.missingThumbnailOnly,
            withThumbnailOnly = currentFilters.withThumbnailOnly,
            extension = currentFilters.extensionQuery.trim().removePrefix(".")
                .takeIf { it.isNotBlank() },
            minSizeBytes = currentFilters.minSizeMb.toSizeBytes(),
            maxSizeBytes = currentFilters.maxSizeMb.toSizeBytes(),
            modifiedAfter = currentFilters.modifiedAfterDate.toStartOfDayMillis(),
            modifiedBefore = currentFilters.modifiedBeforeDate.toEndOfDayMillis(),
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
                val filtered = rows.asSequence()
                    .filter { album ->
                        query.isBlank() || album.name?.contains(query, ignoreCase = true) == true
                    }
                    .sortedWith(albumComparator(sortValue.first, sortValue.second))
                    .toList()
                labelDuplicateAlbums(filtered)
            }
        }
    }

    val previewSequence: StateFlow<PreviewSequence> = combine(tab, sort, filters) {
            current, (field, dir), currentFilters ->
        PreviewSequence(
            folderId = albumFolderId,
            filterByFolder = isAlbumView,
            nameQuery = currentFilters.searchQuery.takeIf { it.isNotBlank() },
            categories = mediaCategories(current, currentFilters.mediaType),
            favoritesOnly = currentFilters.favoritesOnly,
            availableOfflineOnly = currentFilters.availableOfflineOnly,
            backedUpOnly = currentFilters.backedUpOnly,
            notBackedUpOnly = currentFilters.notBackedUpOnly,
            missingThumbnailOnly = currentFilters.missingThumbnailOnly,
            withThumbnailOnly = currentFilters.withThumbnailOnly,
            extension = currentFilters.extensionQuery.trim().removePrefix(".")
                .takeIf { it.isNotBlank() },
            minSizeBytes = currentFilters.minSizeMb.toSizeBytes(),
            maxSizeBytes = currentFilters.maxSizeMb.toSizeBytes(),
            modifiedAfter = currentFilters.modifiedAfterDate.toStartOfDayMillis(),
            modifiedBefore = currentFilters.modifiedBeforeDate.toEndOfDayMillis(),
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
        albumSelection,
        effectiveGridSizes,
        combine(
            albums,
            settingsRepository.preferences.map { it.galleryViewMode }.distinctUntilChanged()
        ) { albumList, mode -> albumList to mode }
    ) { browse, selectionState, selectedAlbums, sizes, extras ->
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
            albumSelection = selectedAlbums,
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

    fun setMediaTypeFilter(value: GalleryMediaType) {
        clearSelection()
        filters.update { it.copy(mediaType = value) }
    }

    fun setFavoritesFilter(enabled: Boolean) {
        clearSelection()
        filters.update { it.copy(favoritesOnly = enabled) }
    }

    fun setMissingThumbnailFilter(enabled: Boolean) {
        clearSelection()
        filters.update {
            it.copy(
                missingThumbnailOnly = enabled,
                withThumbnailOnly = if (enabled) false else it.withThumbnailOnly
            )
        }
    }

    fun setWithThumbnailFilter(enabled: Boolean) {
        clearSelection()
        filters.update {
            it.copy(
                withThumbnailOnly = enabled,
                missingThumbnailOnly = if (enabled) false else it.missingThumbnailOnly
            )
        }
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

    fun setAdvancedFilters(
        extension: String = filters.value.extensionQuery,
        minSizeMb: String = filters.value.minSizeMb,
        maxSizeMb: String = filters.value.maxSizeMb,
        modifiedAfterDate: String = filters.value.modifiedAfterDate,
        modifiedBeforeDate: String = filters.value.modifiedBeforeDate
    ) {
        clearSelection()
        filters.update {
            it.copy(
                extensionQuery = extension,
                minSizeMb = minSizeMb,
                maxSizeMb = maxSizeMb,
                modifiedAfterDate = modifiedAfterDate,
                modifiedBeforeDate = modifiedBeforeDate
            )
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
            val persisted = settingsRepository.preferences.first().albumGridSize
            albumZoomOverride.value = ((albumZoomOverride.value ?: persisted) + step)
                .coerceIn(MIN_GRID_COLUMNS, MAX_GRID_COLUMNS)
        }
    }

    private fun applyZoom(transform: (GridZoomLevel) -> GridZoomLevel) {
        viewModelScope.launch {
            val prefs = settingsRepository.preferences.first()
            val next = transform(
                GridZoomLevel(
                    prefs.galleryViewMode,
                    mediaZoomOverride.value ?: prefs.galleryGridSize
                )
            )
            // Only the grid/list mode switch stays persisted (it is the view-mode setting);
            // the column count itself becomes a session-local override so a stray pinch can
            // never silently rewrite the Settings value again.
            mediaZoomOverride.value = next.gridSize
            if (next.viewMode != prefs.galleryViewMode) {
                settingsRepository.update { it.copy(galleryViewMode = next.viewMode) }
            }
        }
    }

    fun startSelectionMode() {
        _selectionModeEnabled.update { true }
    }

    fun selectFileForContext(id: String) {
        albumSelection.update { emptySet() }
        _selectionModeEnabled.update { true }
        _allSelected.update { false }
        if (id !in selection.value) selection.update { setOf(id) }
    }

    fun toggleSelection(id: String) {
        albumSelection.update { emptySet() }
        _selectionModeEnabled.update { true }
        _allSelected.update { false }
        selection.update { if (id in it) it - id else it + id }
    }

    fun toggleAlbumSelection(folderId: String) {
        selection.update { emptySet() }
        _allSelected.update { false }
        albumSelection.update { current ->
            if (folderId in current) current - folderId else current + folderId
        }
        _selectionModeEnabled.update { albumSelection.value.isNotEmpty() }
    }

    fun startAlbumRangeSelection() {
        albumRangeBase = albumSelection.value
        selection.update { emptySet() }
        _selectionModeEnabled.update { true }
        _allSelected.update { false }
    }

    fun extendAlbumRangeSelection(folderIds: List<String>, shouldSelect: Boolean) {
        val base = albumRangeBase ?: return
        albumSelection.update {
            if (shouldSelect) base + folderIds else base - folderIds.toSet()
        }
        _selectionModeEnabled.update { albumSelection.value.isNotEmpty() }
    }

    fun endAlbumRangeSelection() {
        albumRangeBase = null
    }

    fun clearSelection() {
        _selectionModeEnabled.update { false }
        _allSelected.update { false }
        selection.update { emptySet() }
        albumSelection.update { emptySet() }
    }

    fun hasSelection(): Boolean = selection.value.isNotEmpty() || albumSelection.value.isNotEmpty()

    fun hasClipboardItems(): Boolean = clipboard.peek() != null

    private fun takeSelection(): Pair<List<String>, List<String>> {
        val fileIds = selection.value.toList()
        val folderIds = albumSelection.value.toList()
        clearSelection()
        return fileIds to folderIds
    }

    fun generateThumbnailsSelected() {
        val (ids, folders) = takeSelection()
        if (ids.isEmpty() && folders.isEmpty()) return
        viewModelScope.launch {
            var newlyQueued = 0
            var alreadyQueued = 0
            if (ids.isNotEmpty()) {
                val result = cacheRepository.generateThumbnails(ids)
                newlyQueued += result.newlyQueued
                alreadyQueued += result.alreadyQueued
            }
            folders.forEach { folderId ->
                val result = cacheRepository.generateFolderThumbnails(folderId)
                newlyQueued += result.newlyQueued
                alreadyQueued += result.alreadyQueued
            }
            val submitted = newlyQueued + alreadyQueued
            _messages.tryEmit(
                UiText.Plain(
                    if (submitted > 0) {
                        "Added $submitted missing thumbnails to the priority queue."
                    } else {
                        "No eligible missing thumbnails were found in the selection."
                    }
                )
            )
        }
    }

    fun selectAll() {
        viewModelScope.launch {
            if (tab.value == GalleryTab.ALBUMS && !isAlbumView) {
                val folders = fileRepository.observeAlbums(
                    showHidden = false,
                    showArchived = false
                ).first().mapNotNull { it.folderId }.toSet()
                selection.update { emptySet() }
                albumSelection.value = folders
                _selectionModeEnabled.value = folders.isNotEmpty()
                _allSelected.update { folders.isNotEmpty() }
            } else {
                albumSelection.update { emptySet() }
                selection.update { fileRepository.fileIds(spec.first()).toSet() }
                _selectionModeEnabled.update { true }
                _allSelected.update { true }
            }
        }
    }

    /** Ctrl+A toggle (PC-29b): everything selected → clear, else select all. */
    fun toggleSelectAll() {
        if (_allSelected.value) {
            clearSelection()
        } else {
            // Optimistic flip: selectAll completes asynchronously, and
            // a rapid second press must already read "all selected".
            _allSelected.update { true }
            selectAll()
        }
    }

    fun startRangeSelection() {
        rangeBase = selection.value
    }

    fun extendRangeSelection(ids: List<String>, shouldSelect: Boolean) {
        val base = rangeBase ?: return
        _allSelected.update { false }
        selection.update { if (shouldSelect) base + ids else base - ids.toSet() }
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

    fun createAlbum(name: String) {
        viewModelScope.launch {
            createFolderIn(parentId = null, name = name)
        }
    }

    fun moveSelected(targetFolderId: String?) {
        val (ids, folders) = takeSelection()
        if (ids.isEmpty() && folders.isEmpty()) return
        viewModelScope.launch {
            if (ids.isNotEmpty()) fileRepository.moveFiles(ids, targetFolderId).reportFailure()
            folders.forEach { fileRepository.moveFolder(it, targetFolderId).reportFailure() }
        }
    }

    fun cutSelected() {
        val (ids, folders) = takeSelection()
        val total = ids.size + folders.size
        if (total == 0) return
        clipboard.cut(CutItems(ids, folders))
        _messages.tryEmit(UiText.Resource(Res.string.files_cut_count, total))
    }

    fun trashSelected() {
        val (ids, folders) = takeSelection()
        if (ids.isEmpty() && folders.isEmpty()) return
        viewModelScope.launch {
            if (ids.isNotEmpty()) trashRepository.moveFilesToTrash(ids).reportFailure()
            folders.forEach { trashRepository.moveFolderToTrash(it).reportFailure() }
        }
    }

    fun copyToClipboardSelected() {
        val (ids, folders) = takeSelection()
        val total = ids.size + folders.size
        if (total == 0) return
        clipboard.copy(CutItems(ids, folders, isCut = false))
        _messages.tryEmit(UiText.Plain("Copied $total item(s). Navigate to a Gallery album and press Ctrl+V."))
    }

    fun paste() {
        val items = clipboard.peek() ?: return
        if (items.isCut) {
            viewModelScope.launch {
                var succeeded = true
                if (items.fileIds.isNotEmpty()) {
                    when (val result = fileRepository.moveFiles(items.fileIds, albumFolderId)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> {
                            succeeded = false
                            report(result.error)
                        }
                    }
                }
                items.folderIds.forEach { folderId ->
                    when (val result = fileRepository.moveFolder(folderId, albumFolderId)) {
                        is AppResult.Success -> Unit
                        is AppResult.Failure -> {
                            succeeded = false
                            report(result.error)
                        }
                    }
                }
                if (succeeded) clipboard.clearIfMatches(items)
            }
        } else {
            copyItems(items.fileIds, items.folderIds, albumFolderId)
        }
    }

    fun copySelected(targetFolderId: String?) {
        val (ids, folders) = takeSelection()
        copyItems(ids, folders, targetFolderId)
    }

    private fun copyItems(ids: List<String>, folders: List<String>, targetFolderId: String?) {
        if (ids.isEmpty() && folders.isEmpty()) return
        viewModelScope.launch {
            if (ids.isNotEmpty()) {
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
            folders.forEach { folderId ->
                when (val result = fileRepository.copyFolder(folderId, targetFolderId)) {
                    is AppResult.Success -> Unit
                    is AppResult.Failure -> report(result.error)
                }
            }
        }
    }

    fun shareSelected() {
        val (fileIds, folderIds) = takeSelection()
        if (fileIds.isEmpty() && folderIds.isEmpty()) return
        viewModelScope.launch {
            val ids = (fileIds + folderIds.flatMap { fileRepository.fileIdsInTree(it) }).distinct()
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
        val (fileIds, folderIds) = takeSelection()
        if (fileIds.isEmpty() && folderIds.isEmpty()) return
        viewModelScope.launch {
            val ids = (fileIds + folderIds.flatMap { fileRepository.fileIdsInTree(it) }).distinct()
            removeLocalCopies(ids)
        }
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
            val file = fileRepository.getFile(fileId)
            if (file != null) {
                _infoTarget.value = file
                _infoFolderPath.value = folderPathFor(file.folderId)
            }
            clearSelection()
        }
    }

    fun dismissInfo() {
        _infoTarget.value = null
        _infoFolderPath.value = null
    }

    fun downloadSelected() {
        val (fileIds, folderIds) = takeSelection()
        viewModelScope.launch {
            val ids = (fileIds + folderIds.flatMap { fileRepository.fileIdsInTree(it) }).distinct()
            var queued = 0
            ids.forEach { id ->
                when (val result = transferRepository.enqueueDownload(id)) {
                    is AppResult.Success -> queued++
                    is AppResult.Failure -> report(result.error)
                }
            }
            if (queued > 0) _messages.tryEmit(UiText.Resource(Res.string.files_queued_for_download, queued))
        }
    }

    fun uploadSelected() {
        val (fileIds, folderIds) = takeSelection()
        viewModelScope.launch {
            val ids = (fileIds + folderIds.flatMap { fileRepository.fileIdsInTree(it) }).distinct()
            var queued = 0
            ids.forEach { id ->
                when (val result = transferRepository.enqueueUpload(id)) {
                    is AppResult.Success -> queued++
                    is AppResult.Failure -> report(result.error)
                }
            }
            if (queued > 0) _messages.tryEmit(UiText.Resource(Res.string.files_queued_for_upload, queued))
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
        val (ids, folders) = takeSelection()
        viewModelScope.launch {
            if (ids.isNotEmpty()) fileRepository.setFilesFavorite(ids, favorite)
            folders.forEach { fileRepository.setFolderFavorite(it, favorite) }
        }
    }

    fun setSelectedAvailableOffline(available: Boolean) {
        val (ids, folders) = takeSelection()
        viewModelScope.launch {
            val queued = makeAvailableOffline(ids, folders, available)
            if (queued > 0) _messages.tryEmit(UiText.Resource(Res.string.files_queued_for_download, queued))
        }
    }

    fun hideSelected(hidden: Boolean) {
        val (ids, folders) = takeSelection()
        viewModelScope.launch {
            if (ids.isNotEmpty()) fileRepository.setFilesHidden(ids, hidden)
            folders.forEach { fileRepository.setFolderHidden(it, hidden) }
        }
    }

    fun archiveSelected(archived: Boolean) {
        val (ids, folders) = takeSelection()
        viewModelScope.launch {
            if (ids.isNotEmpty()) fileRepository.setFilesArchived(ids, archived)
            folders.forEach { fileRepository.setFolderArchived(it, archived) }
        }
    }

    companion object {
        private const val MIN_REFRESH_VISIBLE_MS = 700L
        private const val MAX_FOLDER_PATH_CACHE_ENTRIES = 512
    }
}
