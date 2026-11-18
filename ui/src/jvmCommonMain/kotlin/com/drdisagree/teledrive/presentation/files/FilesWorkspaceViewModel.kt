package com.drdisagree.teledrive.presentation.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.navigation.toRoute
import com.drdisagree.teledrive.presentation.navigation.Route
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class FilesTabState(
    val id: Int,
    val folderId: String?,
    val label: String
)

/**
 * The Files workspace tabs and the per-tab scroll positions.
 *
 * Folder screens are separate navigation destinations, one per folder, but the tabs belong to
 * the whole Files back stack rather than to any single folder screen. This holder is scoped
 * to the bottom-most Files entry (see AppNavHost), so every folder destination in the stack
 * shares the same tabs, and the tabs live and die with the stack — the same lifetime they had
 * when a single screen swapped folders in place. SavedStateHandle persistence keeps them
 * across process death, as before.
 */
class FilesWorkspaceViewModel(
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val initialFolderId: String? = savedStateHandle.toRoute<Route.Files>().folderId

    private fun restoreWorkspaceTabs(): List<FilesTabState> {
        val encoded = savedStateHandle.get<ArrayList<String>>(KEY_WORKSPACE_TABS).orEmpty()
        return encoded.chunked(3).mapNotNull { fields ->
            if (fields.size != 3) return@mapNotNull null
            val id = fields[0].toIntOrNull() ?: return@mapNotNull null
            FilesTabState(id, fields[1].ifEmpty { null }, fields[2])
        }.distinctBy { it.id }
    }

    private val _workspaceTabs = MutableStateFlow(
        restoreWorkspaceTabs().ifEmpty {
            listOf(FilesTabState(0, initialFolderId, "Files"))
        }
    )
    val workspaceTabs: StateFlow<List<FilesTabState>> = _workspaceTabs.asStateFlow()

    private val _selectedWorkspaceTabId = MutableStateFlow(
        savedStateHandle.get<Int>(KEY_SELECTED_WORKSPACE_TAB_ID)
            ?.takeIf { id -> _workspaceTabs.value.any { it.id == id } }
            ?: _workspaceTabs.value.first().id
    )
    val selectedWorkspaceTabId: StateFlow<Int> = _selectedWorkspaceTabId.asStateFlow()

    // Scroll positions belong to the workspace, keyed by tab and folder: a folder's entry is
    // destroyed when its destination is popped (for example on a tab switch), and this store
    // is what puts the recreated screen back at the place the user left.
    private fun restoreFileListPositions(): MutableMap<Pair<Int, String?>, Pair<Int, Int>> =
        savedStateHandle.get<ArrayList<String>>(KEY_FILE_LIST_POSITIONS).orEmpty()
            .chunked(4)
            .mapNotNull { fields ->
                if (fields.size != 4) return@mapNotNull null
                val tabId = fields[0].toIntOrNull() ?: return@mapNotNull null
                val itemIndex = fields[2].toIntOrNull() ?: return@mapNotNull null
                val scrollOffset = fields[3].toIntOrNull() ?: return@mapNotNull null
                (tabId to fields[1].ifEmpty { null }) to
                    (itemIndex.coerceAtLeast(0) to scrollOffset.coerceAtLeast(0))
            }
            .toMap()
            .toMutableMap()

    private val fileListPositions = restoreFileListPositions()

    private fun persistFileListPositions() {
        savedStateHandle[KEY_FILE_LIST_POSITIONS] = ArrayList(
            fileListPositions.flatMap { (key, position) ->
                listOf(
                    key.first.toString(),
                    key.second.orEmpty(),
                    position.first.toString(),
                    position.second.toString()
                )
            }
        )
    }

    fun fileListPosition(tabId: Int, folderId: String?): Pair<Int, Int>? =
        fileListPositions[tabId to folderId]

    fun saveFileListPosition(
        tabId: Int,
        folderId: String?,
        firstVisibleItemIndex: Int,
        firstVisibleItemScrollOffset: Int
    ) {
        fileListPositions[tabId to folderId] =
            firstVisibleItemIndex.coerceAtLeast(0) to firstVisibleItemScrollOffset.coerceAtLeast(0)
        persistFileListPositions()
    }

    private fun clearFileListPositionsForTab(tabId: Int) {
        if (fileListPositions.keys.removeAll { it.first == tabId }) {
            persistFileListPositions()
        }
    }

    private var nextWorkspaceTabId = maxOf(
        savedStateHandle.get<Int>(KEY_NEXT_WORKSPACE_TAB_ID) ?: 1,
        (_workspaceTabs.value.maxOfOrNull { it.id } ?: 0) + 1
    )

    private fun persistWorkspaceTabs() {
        savedStateHandle[KEY_WORKSPACE_TABS] = ArrayList(
            _workspaceTabs.value.flatMap { tab ->
                listOf(tab.id.toString(), tab.folderId.orEmpty(), tab.label)
            }
        )
        savedStateHandle[KEY_SELECTED_WORKSPACE_TAB_ID] = _selectedWorkspaceTabId.value
        savedStateHandle[KEY_NEXT_WORKSPACE_TAB_ID] = nextWorkspaceTabId
    }

    /** Records which tab is selected. The caller navigates to that tab's folder. */
    fun selectWorkspaceTab(tabId: Int) {
        if (_workspaceTabs.value.none { it.id == tabId }) return
        if (_selectedWorkspaceTabId.value == tabId) return
        _selectedWorkspaceTabId.value = tabId
        persistWorkspaceTabs()
    }

    /** Adds a fresh tab at the root and selects it. The caller navigates to the root. */
    fun createWorkspaceTab(): FilesTabState {
        val newTab = FilesTabState(nextWorkspaceTabId++, null, "Files")
        _workspaceTabs.value = _workspaceTabs.value + newTab
        _selectedWorkspaceTabId.value = newTab.id
        persistWorkspaceTabs()
        return newTab
    }

    /**
     * Closes a tab and drops its saved scroll positions. Returns the tab that became
     * selected when the closed tab was the selected one (the caller navigates to it),
     * or null when the selection did not change.
     */
    fun closeWorkspaceTab(tabId: Int): FilesTabState? {
        val current = _workspaceTabs.value
        if (current.size <= 1) return null
        val oldIndex = current.indexOfFirst { it.id == tabId }
        if (oldIndex < 0) return null
        val nextTabs = current.filterNot { it.id == tabId }
        _workspaceTabs.value = nextTabs
        clearFileListPositionsForTab(tabId)
        val replacement = if (_selectedWorkspaceTabId.value == tabId) {
            val next = nextTabs[oldIndex.coerceAtMost(nextTabs.lastIndex)]
            _selectedWorkspaceTabId.value = next.id
            next
        } else {
            null
        }
        persistWorkspaceTabs()
        return replacement
    }

    /** Close every open Files tab and leave one fresh root tab ready to use. */
    fun closeAllWorkspaceTabs(): FilesTabState {
        val freshTab = FilesTabState(nextWorkspaceTabId++, null, "Files")
        _workspaceTabs.value = listOf(freshTab)
        _selectedWorkspaceTabId.value = freshTab.id
        fileListPositions.clear()
        persistFileListPositions()
        persistWorkspaceTabs()
        return freshTab
    }

    /**
     * Points the selected tab at the folder the visible Files screen is showing. Called by
     * whichever folder screen is on top, so a tab always tracks where its browsing ended up.
     */
    fun updateSelectedWorkspaceTab(folderId: String?, label: String) {
        val selectedId = _selectedWorkspaceTabId.value
        val current = _workspaceTabs.value
        val updated = current.map { tab ->
            if (tab.id == selectedId) tab.copy(folderId = folderId, label = label) else tab
        }
        if (updated != current) {
            _workspaceTabs.value = updated
            persistWorkspaceTabs()
        }
    }

    private companion object {
        const val KEY_WORKSPACE_TABS = "files_workspace_tabs"
        const val KEY_SELECTED_WORKSPACE_TAB_ID = "files_workspace_selected_tab_id"
        const val KEY_NEXT_WORKSPACE_TAB_ID = "files_workspace_next_tab_id"
        const val KEY_FILE_LIST_POSITIONS = "files_list_scroll_positions"
    }
}
