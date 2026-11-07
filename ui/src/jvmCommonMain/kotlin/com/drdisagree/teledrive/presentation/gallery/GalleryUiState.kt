package com.drdisagree.teledrive.presentation.gallery

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.MediaAlbum
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.model.ViewMode
import com.drdisagree.teledrive.presentation.components.SelectionCapabilities

data class GalleryFilters(
    val searchQuery: String = "",
    val favoritesOnly: Boolean = false,
    val availableOfflineOnly: Boolean = false,
    val backedUpOnly: Boolean = false,
    val notBackedUpOnly: Boolean = false
)

data class GalleryUiState(
    val tab: GalleryTab = GalleryTab.ALL,
    val albums: List<MediaAlbum> = emptyList(),
    val albumTitle: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val isAlbumView: Boolean = false,
    val gridSize: Int = 3,
    val albumGridSize: Int = 3,
    val loaded: Boolean = false,
    val capabilities: SelectionCapabilities = SelectionCapabilities(),
    val sortField: FileSortField = FileSortField.DATE_MODIFIED,
    val sortDirection: SortDirection = SortDirection.DESCENDING,
    val filters: GalleryFilters = GalleryFilters(),
    val selection: Set<String> = emptySet()
) {
    val selectionMode: Boolean get() = selection.isNotEmpty()
    val allSelectedFavorite: Boolean get() = selectionMode && !capabilities.anyUnfavorited
    val allSelectedAvailableOffline: Boolean get() = selectionMode && !capabilities.anyNotAvailableOffline
    val hasActiveFilters: Boolean get() =
        filters.searchQuery.isNotBlank() ||
                filters.favoritesOnly ||
                filters.availableOfflineOnly ||
                filters.backedUpOnly ||
                filters.notBackedUpOnly
}
