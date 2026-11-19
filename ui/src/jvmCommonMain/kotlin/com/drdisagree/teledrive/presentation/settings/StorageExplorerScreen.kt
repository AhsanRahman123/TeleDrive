package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.repository.StorageExplorerEntry
import com.drdisagree.teledrive.domain.repository.StorageExplorerRepository
import com.drdisagree.teledrive.domain.repository.StorageExplorerRoot
import com.drdisagree.teledrive.domain.repository.StorageExplorerSnapshot
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import com.drdisagree.teledrive.presentation.platform.LocalFileSharer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import java.io.File

private enum class StorageExplorerFilter(val label: String) {
    ALL("All"),
    CACHE("Cache"),
    DOWNLOADS("Downloads"),
    TELEGRAM("Telegram"),
    APP_DATA("App data")
}

private enum class StorageExplorerSort(val label: String) {
    LARGEST("Largest first"),
    SMALLEST("Smallest first"),
    NEWEST("Newest first"),
    OLDEST("Oldest first"),
    NAME("Name A–Z"),
    TYPE("File type")
}

@Composable
fun StorageExplorerScreen(modifier: Modifier = Modifier) {
    val repository = koinInject<StorageExplorerRepository>()
    val clipboard = LocalClipboardManager.current
    val fileSharer = LocalFileSharer.current
    val scope = rememberCoroutineScope()

    var snapshot by remember { mutableStateOf(StorageExplorerSnapshot()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var currentRootId by remember { mutableStateOf<String?>(null) }
    var currentPath by remember { mutableStateOf("") }
    var showAllFiles by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf(StorageExplorerFilter.ALL) }
    var sort by remember { mutableStateOf(StorageExplorerSort.LARGEST) }
    var sorting by remember { mutableStateOf(false) }
    var filteredEntries by remember { mutableStateOf(emptyList<StorageExplorerEntry>()) }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
    val entryListState = rememberLazyListState()
    var dragBase by remember { mutableStateOf<Set<String>?>(null) }

    // The same long-press drag-painting Files uses (#64), shared via rememberDragSelect:
    // long-press anchors on a row, dragging paints the range (from a selected row it
    // erases), with edge auto-scroll. Nothing is consumed before the long-press lands, so
    // taps, checkboxes and scrolling are unchanged; painting is snapshot-based.
    val entryDragSelect = rememberDragSelect(
        listState = entryListState,
        onStart = { dragBase = selectedPaths },
        isSelected = { index ->
            filteredEntries.getOrNull(index)?.let { it.path in selectedPaths } == true
        },
        onRange = { range, shouldSelect ->
            val base = dragBase ?: selectedPaths
            val paths = range.mapNotNull { filteredEntries.getOrNull(it)?.path }
            selectedPaths = if (shouldSelect) base + paths else base - paths.toSet()
        },
        onEnd = { dragBase = null },
        resolveNearestVisibleItem = true,
        processReleasePosition = true
    )
    var selectedEntry by remember { mutableStateOf<StorageExplorerEntry?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmTelegramClear by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            loading = true
            message = ""
            try {
                val result = repository.scan()
                snapshot = result
                val root = currentRootId?.let { id -> result.roots.firstOrNull { it.id == id } }
                if (currentRootId != null && root == null) {
                    currentRootId = null
                    currentPath = ""
                }
                selectedPaths = emptySet()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "Storage scan failed: " + (error.message ?: "unknown error")
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    val currentRoot = snapshot.roots.firstOrNull { it.id == currentRootId }

    // Large inventories can contain many thousands of paths. Filter and sort off the UI thread.
    LaunchedEffect(snapshot, search, currentRootId, currentPath, filter, sort, showAllFiles) {
        sorting = true
        try {
            delay(140)
            filteredEntries = withContext(Dispatchers.Default) {
                val source = if (search.isNotBlank()) {
                    snapshot.entries.asSequence().filter { entry ->
                        entry.name.contains(search, ignoreCase = true) ||
                            entry.path.contains(search, ignoreCase = true)
                    }
                } else if (currentRootId != null) {
                    snapshot.entries.asSequence().filter { entry ->
                        entry.rootId == currentRootId && entry.parentPath == currentPath
                    }
                } else if (showAllFiles) {
                    snapshot.entries.asSequence()
                } else {
                    emptySequence()
                }

                source.filter { entry -> matchesFilter(entry.category, filter) }
                    .let { sequence ->
                        when (sort) {
                            StorageExplorerSort.LARGEST -> sequence.sortedWith(
                                compareByDescending<StorageExplorerEntry> { it.sizeBytes }
                                    .thenBy { it.name.lowercase() }
                            )
                            StorageExplorerSort.SMALLEST -> sequence.sortedWith(
                                compareBy<StorageExplorerEntry> { it.sizeBytes }
                                    .thenBy { it.name.lowercase() }
                            )
                            StorageExplorerSort.NEWEST -> sequence.sortedByDescending { it.lastModifiedAt }
                            StorageExplorerSort.OLDEST -> sequence.sortedBy { it.lastModifiedAt }
                            StorageExplorerSort.NAME -> sequence.sortedBy { it.name.lowercase() }
                            StorageExplorerSort.TYPE -> sequence.sortedWith(
                                compareBy<StorageExplorerEntry> {
                                    if (it.isDirectory) "" else it.name.substringAfterLast('.', "").lowercase()
                                }.thenBy { it.name.lowercase() }
                            )
                        }
                    }.toList()
            }
        } finally {
            sorting = false
        }
    }

    val selectedEntries = snapshot.entries.filter { it.path in selectedPaths }
    val deletableSelectionCount = selectedEntries.count { it.canDelete }
    val protectedSelectionCount = selectedEntries.size - deletableSelectionCount
    val telegramDownloadBytes = snapshot.entries
        .filter { it.category == CATEGORY_TELEGRAM_DOWNLOADS && !it.isDirectory }
        .sumOf { it.sizeBytes }

    fun openFolder(entry: StorageExplorerEntry) {
        currentRootId = entry.rootId
        currentPath = entry.path
        search = ""
        selectedPaths = emptySet()
    }

    fun navigateUp() {
        val root = currentRoot ?: return
        if (currentPath == root.path) {
            currentRootId = null
            currentPath = ""
        } else {
            currentPath = File(currentPath).parentFile?.path ?: root.path
        }
        selectedPaths = emptySet()
    }

    fun deleteSelected() {
        scope.launch {
            busy = true
            try {
                val result = repository.delete(selectedPaths.toList())
                message = "Removed " + result.deletedFiles + " files · " +
                    Formatters.bytes(result.deletedBytes) + " freed"
                if (result.skippedItems > 0) {
                    message += " · " + result.skippedItems + " protected or overlapping items skipped"
                }
                if (result.failedPaths.isNotEmpty()) {
                    message += " · " + result.failedPaths.size + " could not be removed"
                }
                selectedPaths = emptySet()
                loading = true
                snapshot = repository.scan()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                message = "Could not remove selected items: " + (error.message ?: "unknown error")
            } finally {
                loading = false
                busy = false
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Physical storage found", style = MaterialTheme.typography.labelLarge)
                Text(
                    Formatters.bytes(snapshot.totalBytes),
                    style = MaterialTheme.typography.headlineMedium
                )
                Text(
                    snapshot.fileCount.toString() + " files · " +
                        snapshot.directoryCount + " folders across " + snapshot.roots.size + " locations",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (snapshot.scannedAt > 0L) {
                        "Scanned " + Formatters.dateTime(snapshot.scannedAt)
                    } else {
                        "Scan to inspect files stored by Pranto Drive on this device."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Overlapping locations are counted once; folder sizes include their contents.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            StorageExplorerFilter.entries.forEach { option ->
                FilterChip(
                    selected = filter == option,
                    onClick = { filter = option },
                    label = { Text(option.label, maxLines = 1, softWrap = false) }
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("Search name or full path") }
            )
            TextButton(onClick = ::refresh, enabled = !loading && !busy) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }

        if (currentRoot != null && search.isBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 5.dp, bottom = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = ::navigateUp) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    Text("Up")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(currentRoot.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        currentPath,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (currentRoot != null || search.isNotBlank() || showAllFiles) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when {
                        search.isNotBlank() -> "Search results"
                        currentRoot != null -> "Folder contents"
                        else -> "All storage items"
                    },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (currentRoot == null && search.isBlank()) {
                    TextButton(onClick = {
                        showAllFiles = false
                        selectedPaths = emptySet()
                    }) { Text("Locations") }
                }
                TextButton(
                    enabled = filteredEntries.isNotEmpty(),
                    onClick = {
                        val visible = filteredEntries.map { it.path }.toSet()
                        selectedPaths = if (visible.all { it in selectedPaths }) {
                            selectedPaths - visible
                        } else {
                            selectedPaths + visible
                        }
                    }
                ) {
                    Text(
                        if (filteredEntries.isNotEmpty() &&
                            filteredEntries.all { it.path in selectedPaths }
                        ) "Clear all" else "Select all"
                    )
                }
                Box {
                    TextButton(onClick = { sortExpanded = true }) {
                        Text("Sort: " + sort.label, maxLines = 1)
                    }
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        StorageExplorerSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = {
                                    sort = option
                                    sortExpanded = false
                                }
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Storage locations",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                TextButton(onClick = {
                    showAllFiles = true
                    selectedPaths = emptySet()
                }) { Text("All files") }
            }
        }

        if (selectedPaths.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    selectedPaths.size.toString() + " selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge
                )
                TextButton(
                    onClick = { selectedPaths = emptySet() },
                    enabled = !busy
                ) { Text("Clear") }
                Button(
                    onClick = { confirmDelete = true },
                    enabled = !busy && deletableSelectionCount > 0
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    Text("Delete " + deletableSelectionCount)
                }
            }
        }

        if (telegramDownloadBytes > 0L && search.isBlank() && currentRootId == null) {
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy && !loading,
                onClick = { confirmTelegramClear = true }
            ) {
                Text("Clear Telegram downloaded cache · " + Formatters.bytes(telegramDownloadBytes))
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        if (snapshot.warnings.isNotEmpty() && search.isBlank() && currentRootId == null) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Some locations could not be fully inspected", style = MaterialTheme.typography.labelLarge)
                    snapshot.warnings.forEach { warning ->
                        Text(
                            warning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        when {
            loading || sorting -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    val progress by repository.scanProgress.collectAsState()
                    progress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }

            search.isBlank() && currentRootId == null && !showAllFiles -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(snapshot.roots, key = { it.id }) { root ->
                        StorageRootRow(root = root, onClick = {
                            if (root.isDirectory) {
                                currentRootId = root.id
                                currentPath = root.path
                                selectedPaths = emptySet()
                            } else {
                                selectedEntry = snapshot.entries.firstOrNull { it.path == root.path }
                            }
                        })
                    }
                }
            }

            filteredEntries.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (search.isNotBlank()) "No files or folders match this search."
                    else "This location is empty or no items match the selected filter.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            else -> LazyColumn(
                state = entryListState,
                modifier = Modifier.weight(1f).fillMaxWidth().then(entryDragSelect),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredEntries, key = { it.path }) { entry ->
                    StorageEntryRow(
                        entry = entry,
                        selected = entry.path in selectedPaths,
                        onSelect = { checked ->
                            selectedPaths = if (checked) selectedPaths + entry.path
                            else selectedPaths - entry.path
                        },
                        // While a selection is active, tapping the entry toggles it
                        // (the same rule as the upload picker) instead of opening it;
                        // the row's Open/Info button always opens.
                        onTap = {
                            if (selectedPaths.isNotEmpty()) {
                                selectedPaths = if (entry.path in selectedPaths) {
                                    selectedPaths - entry.path
                                } else {
                                    selectedPaths + entry.path
                                }
                            } else if (entry.isDirectory) {
                                openFolder(entry)
                            } else {
                                selectedEntry = entry
                            }
                        },
                        onOpen = {
                            if (entry.isDirectory) {
                                openFolder(entry)
                            } else {
                                selectedEntry = entry
                            }
                        }
                    )
                }
            }
        }
    }

    if (selectedEntry != null) {
        val entry = selectedEntry!!
        AlertDialog(
            onDismissRequest = { selectedEntry = null },
            title = { Text(entry.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(categoryLabel(entry.category), color = MaterialTheme.colorScheme.primary)
                    Text("Size: " + Formatters.bytes(entry.sizeBytes))
                    if (entry.lastModifiedAt > 0L) {
                        Text("Modified: " + Formatters.dateTime(entry.lastModifiedAt))
                    }
                    HorizontalDivider()
                    Text(
                        entry.path,
                        style = MaterialTheme.typography.bodySmall
                    )
                    entry.protectionReason?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (entry.canDelete) {
                        Text(
                            "This item is inside a Pranto Drive-managed cache or download folder and can be removed here.",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (canOpenWith(entry.category) && !entry.isDirectory) {
                        TextButton(onClick = {
                            selectedEntry = null
                            fileSharer.share(listOf(entry.path), "*/*", "Open with")
                        }) { Text("Open / share") }
                    }
                    TextButton(onClick = {
                        clipboard.setText(AnnotatedString(entry.path))
                    }) { Text("Copy path") }
                    TextButton(onClick = { selectedEntry = null }) { Text("Done") }
                }
            },
            dismissButton = {
                if (entry.canDelete) {
                    TextButton(onClick = {
                        selectedEntry = null
                        selectedPaths = setOf(entry.path)
                        confirmDelete = true
                    }) { Text("Delete") }
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete selected storage items?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        deletableSelectionCount.toString() + " selected item(s) are inside approved cache or Pranto Drive download folders."
                    )
                    if (protectedSelectionCount > 0) {
                        Text(
                            protectedSelectionCount.toString() +
                                " protected item(s) will be skipped. Telegram databases, local copies, settings and installed app files are never deleted here."
                        )
                    }
                    Text("Files deleted from cache may be downloaded or generated again when needed.")
                }
            },
            confirmButton = {
                Button(
                    enabled = !busy && deletableSelectionCount > 0,
                    onClick = {
                        confirmDelete = false
                        deleteSelected()
                    }
                ) { Text("Delete selected") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmTelegramClear) {
        AlertDialog(
            onDismissRequest = { confirmTelegramClear = false },
            title = { Text("Clear Telegram downloaded cache?") },
            text = {
                Text(
                    "Pranto Drive will ask TDLib to clear its downloaded local files. This does not delete Telegram cloud originals, but items marked Available Offline may need to be downloaded again."
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        confirmTelegramClear = false
                        scope.launch {
                            busy = true
                            try {
                                val cleared = repository.clearTelegramDownloads()
                                message = if (cleared) {
                                    "Telegram downloaded cache cleared."
                                } else {
                                    "Telegram cache could not be cleared. Check the connection and try again."
                                }
                                loading = true
                                snapshot = repository.scan()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                message = "Telegram cache cleanup failed: " + (error.message ?: "unknown error")
                            } finally {
                                loading = false
                                busy = false
                            }
                        }
                    }
                ) { Text("Clear downloaded cache") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTelegramClear = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun StorageRootRow(
    root: StorageExplorerRoot,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (root.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(root.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    if (root.exists) {
                        root.path
                    } else {
                        "Not created yet · " + root.path
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    root.fileCount.toString() + " files · " +
                        root.directoryCount + " folders",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                Formatters.bytes(root.sizeBytes),
                style = MaterialTheme.typography.titleSmall
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StorageEntryRow(
    entry: StorageExplorerEntry,
    selected: Boolean,
    onSelect: (Boolean) -> Unit,
    onTap: () -> Unit,
    onOpen: () -> Unit
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = selected,
                onCheckedChange = onSelect
            )
            Icon(
                if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.canDelete) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(9.dp))
            Column(
                // combinedClickable with a no-op long-click: the list's drag-select
                // gesture owns long-presses, and this keeps a long-press release from
                // also firing Open (the same arrangement as Files rows). The tap goes
                // through onTap, which toggles instead of opening while a selection
                // is active; the Open/Info button keeps the unconditional onOpen.
                modifier = Modifier.weight(1f)
                    .combinedClickable(onClick = onTap, onLongClick = {})
                    .padding(vertical = 5.dp)
            ) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    categoryLabel(entry.category) + " · " + Formatters.bytes(entry.sizeBytes) +
                        if (entry.isDirectory) " · " + entry.fileCount + " files" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!entry.canDelete && entry.protectionReason != null) {
                    Text(
                        "Protected",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            TextButton(onClick = onOpen) {
                Text(if (entry.isDirectory) "Open" else "Info")
            }
        }
    }
}

private fun canOpenWith(category: String): Boolean = category in setOf(
    CATEGORY_CACHE,
    CATEGORY_DOWNLOADS,
    CATEGORY_TELEGRAM_DOWNLOADS,
    CATEGORY_LOCAL_COPIES,
    CATEGORY_EXTERNAL_FILES
)

private fun matchesFilter(category: String, filter: StorageExplorerFilter): Boolean = when (filter) {
    StorageExplorerFilter.ALL -> true
    StorageExplorerFilter.CACHE -> category == CATEGORY_CACHE
    StorageExplorerFilter.DOWNLOADS -> category == CATEGORY_DOWNLOADS
    StorageExplorerFilter.TELEGRAM ->
        category == CATEGORY_TELEGRAM_DATABASE ||
            category == CATEGORY_TELEGRAM_DOWNLOADS ||
            category == CATEGORY_LOCAL_COPIES
    StorageExplorerFilter.APP_DATA ->
        category == CATEGORY_APP_DATA ||
            category == CATEGORY_EXTERNAL_FILES ||
            category == CATEGORY_DATABASE ||
            category == CATEGORY_SETTINGS ||
            category == CATEGORY_APP_PACKAGE
}

private fun categoryLabel(category: String): String = when (category) {
    CATEGORY_APP_DATA -> "App data"
    CATEGORY_CACHE -> "Cache"
    CATEGORY_TELEGRAM_DATABASE -> "Telegram database"
    CATEGORY_TELEGRAM_DOWNLOADS -> "Telegram downloads"
    CATEGORY_LOCAL_COPIES -> "Local copies"
    CATEGORY_DOWNLOADS -> "Pranto Drive downloads"
    CATEGORY_EXTERNAL_FILES -> "External app files"
    CATEGORY_DATABASE -> "App database"
    CATEGORY_SETTINGS -> "App settings"
    CATEGORY_APP_PACKAGE -> "Installed app package"
    else -> category.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
}

private const val CATEGORY_APP_DATA = "APP_DATA"
private const val CATEGORY_CACHE = "CACHE"
private const val CATEGORY_TELEGRAM_DATABASE = "TELEGRAM_DATABASE"
private const val CATEGORY_TELEGRAM_DOWNLOADS = "TELEGRAM_DOWNLOADS"
private const val CATEGORY_LOCAL_COPIES = "LOCAL_COPIES"
private const val CATEGORY_DOWNLOADS = "DOWNLOADS"
private const val CATEGORY_EXTERNAL_FILES = "EXTERNAL_FILES"
private const val CATEGORY_DATABASE = "DATABASE"
private const val CATEGORY_SETTINGS = "SETTINGS"
private const val CATEGORY_APP_PACKAGE = "APP_PACKAGE"
