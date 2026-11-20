package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the built-in desktop picker is choosing. */
internal enum class DesktopPickerMode {
    /** Files: single-click (single) or checkbox multi-selection. */
    FILES,

    /** One folder: navigate the tree, then take a folder row or the open folder. */
    FOLDER
}

/** Sort orders offered by the picker — the same set as the Android picker. */
internal enum class DesktopPickerSort { NAME, SIZE, MODIFIED, TYPE }

/**
 * Built-in file/folder picker for desktop uploads. This is a full port of
 * the Android app's upload picker (the same component on the sister
 * platform), with the Files section's gesture and keyboard semantics
 * (PC-15, report 18):
 *
 * - browse folders, tick any number of files, confirm;
 * - **snapshot-based drag multi-select** in list and grid views — the
 *   gesture's starting selection is captured on drag start and every
 *   reported range is applied against that snapshot, exactly like Files
 *   (`startRangeSelection`/`extendRangeSelection`) and the Android picker
 *   (`dragBase`). Dragging back ("undrag") therefore un-paints rows that
 *   leave the range, and the release position completes the gesture;
 * - a search field and a sort menu (Name / Size / Modified / Type,
 *   folders first, direction toggle);
 * - **real thumbnails for image files** in list and grid views, decoded
 *   downscaled and only for composed (visible) items (PC-17, see
 *   [PickerEntryThumbnail]). Folder mode is this same composable listing
 *   directories only, so it shares every feature by construction; the
 *   only file-mode-only behaviours are selection (checkboxes, drag
 *   multi-select) and thumbnails, which have no meaning for a
 *   single-folder pick (PC-18);
 * - **Esc ladder, always consumed by the picker**: clear the search, then
 *   clear the selection, then step up one folder, and only at a filesystem
 *   root close the picker. The key can never fall through to the screen
 *   behind the dialog (one Esc used to close the picker AND navigate the
 *   app back). Backspace steps up one folder, as in Files.
 *
 * Every filesystem root on the machine (on Windows: every drive letter) is
 * offered in the drive row — the tree is never trapped on the drive it
 * started from. The dialog opens at [initialDirectory] (the remembered
 * position) and reports every navigation through [onDirectoryChanged] so
 * the caller can persist it for the next open.
 */
@Composable
internal fun DesktopUploadPickerDialog(
    mode: DesktopPickerMode,
    multiSelect: Boolean,
    initialDirectory: File,
    onDirectoryChanged: (File) -> Unit,
    onPicked: (List<File>) -> Unit,
    onCancel: () -> Unit
) {
    var currentDir by remember { mutableStateOf(initialDirectory) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(DesktopPickerSort.NAME) }
    var sortExpanded by remember { mutableStateOf(false) }
    var descending by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val roots = remember { fileSystemRoots() }
    // Directory listing is disk I/O: it runs off the UI thread (PC-7).
    var entries by remember { mutableStateOf<List<File>>(emptyList()) }
    var entriesLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(currentDir, mode) {
        entriesLoaded = false
        entries = withContext(Dispatchers.IO) {
            runCatching {
                currentDir.listFiles().orEmpty()
                    .filter {
                        it.isDirectory || (mode == DesktopPickerMode.FILES && it.isFile)
                    }
            }.getOrDefault(emptyList())
        }
        entriesLoaded = true
    }
    val visibleEntries = remember(entries, search, sort, descending) {
        sortPickerEntries(filterPickerEntries(entries, search), sort, descending)
    }

    fun navigateTo(directory: File) {
        currentDir = directory
        selected = emptySet()
        search = ""
        onDirectoryChanged(directory)
    }

    fun stepUp() {
        currentDir.parentFile?.let { navigateTo(it) }
    }

    // Esc ladder (PC-15): the picker consumes Escape itself, in the preview
    // pass, so it can never also reach the screen behind the dialog.
    fun handleEscape(): Boolean {
        if (search.isNotBlank()) {
            search = ""
            return true
        }
        return when (pickerEscapeAction(selected.isNotEmpty(), currentDir.parentFile != null)) {
            PickerEscapeAction.CLEAR_SELECTION -> {
                selected = emptySet()
                true
            }
            PickerEscapeAction.NAVIGATE_UP -> {
                stepUp()
                true
            }
            PickerEscapeAction.CLOSE -> {
                onCancel()
                true
            }
        }
    }

    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Snapshot drag selection (PC-15): one controller shared by the list
    // and grid wirings, with the Android picker's exact callback shape.
    val dragSelection = remember { PickerDragSelection() }
    val listDragSelect = rememberDragSelect(
        listState = listState,
        onStart = { dragSelection.onDragStart(selected) },
        isSelected = { index ->
            visibleEntries.getOrNull(index)
                ?.let { it.isFile && it.absolutePath in selected } == true
        },
        onRange = { range, shouldSelect ->
            selected = dragSelection.onDragRange(visibleEntries, range, shouldSelect)
        },
        onEnd = { dragSelection.onDragEnd() },
        mouseDragAddsToSelection = false,
        resolveNearestVisibleItem = true,
        processReleasePosition = true
    )
    val gridDragSelect = rememberDragSelect(
        gridState = gridState,
        onStart = { dragSelection.onDragStart(selected) },
        isSelected = { index ->
            visibleEntries.getOrNull(index)
                ?.let { it.isFile && it.absolutePath in selected } == true
        },
        onRange = { range, shouldSelect ->
            selected = dragSelection.onDragRange(visibleEntries, range, shouldSelect)
        },
        onEnd = { dragSelection.onDragEnd() },
        mouseDragAddsToSelection = false,
        resolveNearestVisibleItem = true,
        processReleasePosition = true
    )

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }

    fun entryClick(entry: File) {
        when {
            entry.isDirectory -> navigateTo(entry)
            multiSelect -> {
                selected = if (entry.absolutePath in selected) {
                    selected - entry.absolutePath
                } else {
                    selected + entry.absolutePath
                }
            }
            else -> onPicked(listOf(entry))
        }
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentDir.parentFile != null) {
                    IconButton(onClick = { stepUp() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                    }
                }
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Escape -> handleEscape()
                            Key.Backspace -> {
                                // Files steps up on Backspace; while typing
                                // in the search field it must delete text.
                                if (!searchFocused && currentDir.parentFile != null) {
                                    stepUp()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
            ) {
                if (roots.size > 1) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        roots.forEach { root ->
                            FilterChip(
                                selected = isOnRoot(currentDir, root),
                                onClick = { if (currentDir != root) navigateTo(root) },
                                label = { Text(root.absolutePath) }
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    singleLine = true,
                    placeholder = { Text("Search files") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .onFocusChanged { searchFocused = it.isFocused }
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box {
                        TextButton(onClick = { sortExpanded = true }) {
                            Text("Sort: " + pickerSortLabel(sort))
                        }
                        DropdownMenu(
                            expanded = sortExpanded,
                            onDismissRequest = { sortExpanded = false }
                        ) {
                            DesktopPickerSort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(pickerSortLabel(option)) },
                                    onClick = {
                                        sort = option
                                        sortExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    IconButton(onClick = { descending = !descending }) {
                        Icon(
                            imageVector = if (descending) {
                                Icons.Filled.ArrowDownward
                            } else {
                                Icons.Filled.ArrowUpward
                            },
                            contentDescription = if (descending) "Descending" else "Ascending"
                        )
                    }
                    Box(modifier = Modifier.weight(1f))
                    IconButton(onClick = { gridView = !gridView }) {
                        Icon(
                            imageVector = if (gridView) {
                                Icons.AutoMirrored.Filled.List
                            } else {
                                Icons.Filled.GridView
                            },
                            contentDescription = if (gridView) "List view" else "Grid view"
                        )
                    }
                }
                if (!entriesLoaded) {
                    Text(
                        text = "Loading…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else if (visibleEntries.isEmpty()) {
                    Text(
                        text = if (mode == DesktopPickerMode.FOLDER) {
                            "No folders here"
                        } else {
                            "No files here"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                } else if (gridView) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 120.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .then(
                                if (mode == DesktopPickerMode.FILES && multiSelect) {
                                    gridDragSelect
                                } else {
                                    Modifier
                                }
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        gridItems(visibleEntries, key = { it.absolutePath }) { entry ->
                            Column(
                                modifier = Modifier
                                    .clickable { entryClick(entry) }
                                    .padding(vertical = 6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(contentAlignment = Alignment.TopEnd) {
                                    PickerEntryThumbnail(entry = entry, grid = true)
                                    if (mode == DesktopPickerMode.FILES &&
                                        multiSelect && entry.isFile
                                    ) {
                                        Checkbox(
                                            checked = entry.absolutePath in selected,
                                            onCheckedChange = { checked ->
                                                selected = if (checked) {
                                                    selected + entry.absolutePath
                                                } else {
                                                    selected - entry.absolutePath
                                                }
                                            }
                                        )
                                    }
                                    if (mode == DesktopPickerMode.FOLDER &&
                                        entry.isDirectory
                                    ) {
                                        IconButton(onClick = { onPicked(listOf(entry)) }) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = "Select this folder"
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = entry.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp)
                            .then(
                                if (mode == DesktopPickerMode.FILES && multiSelect) {
                                    listDragSelect
                                } else {
                                    Modifier
                                }
                            )
                    ) {
                        items(visibleEntries, key = { it.absolutePath }) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { entryClick(entry) }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                PickerEntryThumbnail(entry = entry, grid = false)
                                Text(
                                    text = entry.name,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (mode == DesktopPickerMode.FOLDER && entry.isDirectory) {
                                    IconButton(onClick = { onPicked(listOf(entry)) }) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = "Select this folder"
                                        )
                                    }
                                }
                                if (mode == DesktopPickerMode.FILES &&
                                    multiSelect && entry.isFile
                                ) {
                                    Checkbox(
                                        checked = entry.absolutePath in selected,
                                        onCheckedChange = { checked ->
                                            selected = if (checked) {
                                                selected + entry.absolutePath
                                            } else {
                                                selected - entry.absolutePath
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            when {
                mode == DesktopPickerMode.FOLDER -> {
                    TextButton(onClick = { onPicked(listOf(currentDir)) }) {
                        Text("Use this folder")
                    }
                }
                multiSelect -> {
                    TextButton(
                        onClick = { onPicked(selected.sorted().map { File(it) }) },
                        enabled = selected.isNotEmpty()
                    ) {
                        Text("Select (${selected.size})")
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

/**
 * Snapshot-based drag-selection state for the picker (PC-15).
 *
 * The gesture's starting selection is captured in [onDragStart]; every
 * range the shared drag-select machinery reports is applied **against that
 * snapshot**, never against the live selection. This is the semantics both
 * reference implementations use (Files' ViewModel range selection and the
 * Android picker's `dragBase`), and it is what makes dragging back over
 * already-painted rows un-paint them: the range is recomputed from the
 * anchor to the current row on every event, so rows that leave the range
 * return to their pre-drag state. The round-2 wiring applied each range
 * incrementally to the live set, so painted rows could never leave the
 * selection until a brand-new gesture started — the user's "undrag does
 * not work until I release, leave, and come back".
 */
internal class PickerDragSelection {
    private var dragBase: Set<String>? = null

    fun onDragStart(currentSelection: Set<String>) {
        dragBase = currentSelection
    }

    fun onDragRange(entries: List<File>, range: IntRange, shouldSelect: Boolean): Set<String> {
        val base = dragBase ?: emptySet()
        val paths = range.mapNotNull { index ->
            entries.getOrNull(index)?.takeIf { it.isFile }?.absolutePath
        }
        return if (shouldSelect) base + paths else base - paths.toSet()
    }

    fun onDragEnd() {
        dragBase = null
    }
}

/** What one Esc press does inside the picker (PC-15). */
internal enum class PickerEscapeAction { CLEAR_SELECTION, NAVIGATE_UP, CLOSE }

/**
 * The picker's Esc ladder, mirroring Files (`onClear` = clear the
 * selection when one is active, otherwise go back): clear the selection
 * first, then step up one folder, and only at a filesystem root (no
 * parent) close the picker.
 */
internal fun pickerEscapeAction(hasSelection: Boolean, hasParent: Boolean): PickerEscapeAction =
    when {
        hasSelection -> PickerEscapeAction.CLEAR_SELECTION
        hasParent -> PickerEscapeAction.NAVIGATE_UP
        else -> PickerEscapeAction.CLOSE
    }

/** Search filter for the picker listing: name contains the query, case-insensitive. */
internal fun filterPickerEntries(entries: List<File>, search: String): List<File> {
    val query = search.trim()
    if (query.isEmpty()) return entries
    return entries.filter { it.name.contains(query, ignoreCase = true) }
}

/**
 * Picker ordering: folders always first (navigation structure), then the
 * chosen key; [descending] reverses within each kind only, so folders stay
 * on top in both directions — the Android picker's comparator shape.
 */
internal fun sortPickerEntries(
    entries: List<File>,
    sort: DesktopPickerSort,
    descending: Boolean
): List<File> {
    val keyComparator: Comparator<File> = when (sort) {
        DesktopPickerSort.NAME ->
            Comparator { a, b -> a.name.compareTo(b.name, ignoreCase = true) }
        DesktopPickerSort.SIZE ->
            Comparator { a, b -> a.length().compareTo(b.length()) }
        DesktopPickerSort.MODIFIED ->
            Comparator { a, b -> a.lastModified().compareTo(b.lastModified()) }
        DesktopPickerSort.TYPE ->
            Comparator { a, b ->
                a.extension.compareTo(b.extension, ignoreCase = true)
                    .takeIf { it != 0 }
                    ?: a.name.compareTo(b.name, ignoreCase = true)
            }
    }
    val directed = if (descending) keyComparator.reversed() else keyComparator
    return entries.sortedWith(compareByDescending<File> { it.isDirectory }.then(directed))
}

internal fun pickerSortLabel(sort: DesktopPickerSort): String = when (sort) {
    DesktopPickerSort.NAME -> "Name"
    DesktopPickerSort.SIZE -> "Size"
    DesktopPickerSort.MODIFIED -> "Date modified"
    DesktopPickerSort.TYPE -> "Type"
}

/**
 * Applies a drag-selected index range to a selection set. Only file rows
 * are selectable — folders inside the range are skipped, because a folder
 * row's click navigates rather than selects. Indices outside the current
 * listing are ignored. (Kept for the range-mapping tests; the dialog
 * itself routes through [PickerDragSelection], which applies ranges
 * against the gesture snapshot.)
 */
internal fun pickerRangeSelection(
    entries: List<File>,
    selected: Set<String>,
    range: IntRange,
    shouldSelect: Boolean
): Set<String> {
    val paths = range.mapNotNull { index ->
        entries.getOrNull(index)?.takeIf { it.isFile }?.absolutePath
    }
    return if (shouldSelect) selected + paths else selected - paths.toSet()
}

/** Every filesystem root on this machine (on Windows: every drive letter). */
internal fun fileSystemRoots(): List<File> =
    File.listRoots()?.toList().orEmpty()

/** True when [directory] is [root] itself or anywhere underneath it. */
internal fun isOnRoot(directory: File, root: File): Boolean {
    var current: File? = directory.absoluteFile
    while (current != null) {
        if (current == root.absoluteFile) return true
        current = current.parentFile
    }
    return false
}
