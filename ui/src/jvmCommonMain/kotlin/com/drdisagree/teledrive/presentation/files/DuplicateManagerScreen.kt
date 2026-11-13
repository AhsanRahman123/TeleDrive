package com.drdisagree.teledrive.presentation.files

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.domain.repository.DuplicateFileGroup
import com.drdisagree.teledrive.domain.repository.DuplicateFileSort
import com.drdisagree.teledrive.domain.repository.DuplicateGroupSort
import com.drdisagree.teledrive.domain.repository.DuplicateManagerRepository
import com.drdisagree.teledrive.domain.repository.DuplicateScanFile
import com.drdisagree.teledrive.domain.repository.DuplicateScanStatus
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.components.TooltipIconButton
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.DesktopScrollEdgeControls
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private enum class DuplicateTypeFilter(val label: String) {
    ALL("All file types"),
    IMAGE("Images"),
    VIDEO("Videos"),
    AUDIO("Audio"),
    DOCUMENT("Documents"),
    ARCHIVE("Archives"),
    OTHER("Other")
}

@Composable
fun DuplicateManagerScreen(
    folderId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = koinInject<DuplicateManagerRepository>()
    val trashRepository = koinInject<TrashRepository>()
    val clipboard = koinInject<FileClipboard>()
    val scope = rememberCoroutineScope()
    val state by repository.state.collectAsState()

    var showQueue by remember { mutableStateOf(false) }
    var selectedIds by remember(folderId) { mutableStateOf(emptySet<String>()) }
    var selectedQueueIds by remember(folderId) { mutableStateOf(emptySet<String>()) }
    var queueSearch by remember(folderId) { mutableStateOf("") }
    var queueSort by remember(folderId) { mutableStateOf(DuplicateFileSort.OLDEST_FIRST) }
    var showQueueSortMenu by remember { mutableStateOf(false) }
    var queueSelectionBase by remember(folderId) { mutableStateOf(emptySet<String>()) }
    val queueListState = rememberLazyListState()
    var search by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(DuplicateTypeFilter.ALL) }
    var groupSort by remember { mutableStateOf(DuplicateGroupSort.LARGEST_WASTED) }
    var fileSort by remember { mutableStateOf(DuplicateFileSort.NAME_ASC) }
    var showTypeMenu by remember { mutableStateOf(false) }
    var showGroupSortMenu by remember { mutableStateOf(false) }
    var showFileSortMenu by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var previousScanSample by remember(folderId) {
        mutableStateOf(state.scannedFiles to System.currentTimeMillis())
    }
    var scanFilesPerSecond by remember(folderId) { mutableStateOf(0.0) }

    LaunchedEffect(state.scannedFiles) {
        val now = System.currentTimeMillis()
        val (previousCount, previousAt) = previousScanSample
        val delta = state.scannedFiles - previousCount
        val elapsed = now - previousAt
        if (delta > 0 && elapsed > 0L) {
            scanFilesPerSecond = delta * 1_000.0 / elapsed.toDouble()
        }
        previousScanSample = state.scannedFiles to now
    }

    LaunchedEffect(folderId) {
        selectedIds = emptySet()
        showQueue = false
        repository.startScan(folderId)
    }
    fun matchesType(file: DuplicateScanFile): Boolean = when (typeFilter) {
        DuplicateTypeFilter.ALL -> true
        DuplicateTypeFilter.IMAGE -> file.mimeType.startsWith("image/")
        DuplicateTypeFilter.VIDEO -> file.mimeType.startsWith("video/")
        DuplicateTypeFilter.AUDIO -> file.mimeType.startsWith("audio/")
        DuplicateTypeFilter.DOCUMENT -> file.mimeType.startsWith("text/") ||
            file.mimeType.contains("pdf") || file.mimeType.contains("document") ||
            file.mimeType.contains("spreadsheet") || file.mimeType.contains("presentation")
        DuplicateTypeFilter.ARCHIVE -> file.mimeType.contains("zip") ||
            file.mimeType.contains("rar") || file.mimeType.contains("archive") ||
            file.mimeType.contains("compressed") || file.mimeType.contains("tar")
        DuplicateTypeFilter.OTHER -> !(
            file.mimeType.startsWith("image/") ||
                file.mimeType.startsWith("video/") ||
                file.mimeType.startsWith("audio/") ||
                file.mimeType.startsWith("text/") ||
                file.mimeType.contains("pdf") ||
                file.mimeType.contains("document") ||
                file.mimeType.contains("spreadsheet") ||
                file.mimeType.contains("presentation") ||
                file.mimeType.contains("zip") ||
                file.mimeType.contains("rar") ||
                file.mimeType.contains("archive") ||
                file.mimeType.contains("compressed") ||
                file.mimeType.contains("tar")
            )
    }

    val filteredGroups = remember(state.groups, search, typeFilter, groupSort) {
        val normalized = search.trim().lowercase()
        state.groups.filter { group ->
            group.files.any { file ->
                matchesType(file) &&
                    (normalized.isBlank() ||
                        file.name.lowercase().contains(normalized) ||
                        file.folderPath.lowercase().contains(normalized))
            }
        }.sortedWith(
            when (groupSort) {
                DuplicateGroupSort.MOST_COPIES ->
                    compareByDescending<DuplicateFileGroup> { it.files.size }
                        .thenByDescending { it.wastedBytes }
                DuplicateGroupSort.LARGEST_WASTED ->
                    compareByDescending<DuplicateFileGroup> { it.wastedBytes }
                        .thenByDescending { it.duplicateCount }
                DuplicateGroupSort.NAME_ASC ->
                    compareBy { it.files.firstOrNull()?.name.orEmpty().lowercase() }
            }
        )
    }

    fun orderedFiles(group: DuplicateFileGroup): List<DuplicateScanFile> =
        when (fileSort) {
            DuplicateFileSort.NAME_ASC -> group.files.sortedBy { it.name.lowercase() }
            DuplicateFileSort.NAME_DESC -> group.files.sortedByDescending { it.name.lowercase() }
            DuplicateFileSort.SIZE_LARGEST -> group.files.sortedByDescending { it.sizeBytes }
            DuplicateFileSort.SIZE_SMALLEST -> group.files.sortedBy { it.sizeBytes }
            DuplicateFileSort.OLDEST_FIRST -> group.files.sortedBy { it.addedAt }
            DuplicateFileSort.NEWEST_FIRST -> group.files.sortedByDescending { it.addedAt }
        }

    fun visibleFileIds(): Set<String> = filteredGroups.flatMap { group ->
        orderedFiles(group).map { it.id }
    }.toSet()

    val sortedQueue = remember(state.queue, queueSearch, queueSort) {
        val needle = queueSearch.trim().lowercase()
        val matching = state.queue.filter { item ->
            needle.isBlank() ||
                item.file.name.lowercase().contains(needle) ||
                item.file.folderPath.lowercase().contains(needle)
        }
        when (queueSort) {
            DuplicateFileSort.NAME_ASC -> matching.sortedBy { it.file.name.lowercase() }
            DuplicateFileSort.NAME_DESC -> matching.sortedByDescending { it.file.name.lowercase() }
            DuplicateFileSort.SIZE_LARGEST -> matching.sortedByDescending { it.file.sizeBytes }
            DuplicateFileSort.SIZE_SMALLEST -> matching.sortedBy { it.file.sizeBytes }
            DuplicateFileSort.OLDEST_FIRST -> matching.sortedBy { it.file.addedAt }
            DuplicateFileSort.NEWEST_FIRST -> matching.sortedByDescending { it.file.addedAt }
        }
    }
    val queueDragSelect = rememberDragSelect(
        listState = queueListState,
        onStart = { queueSelectionBase = selectedQueueIds },
        isSelected = { index ->
            sortedQueue.getOrNull(index)?.file?.id?.let(selectedQueueIds::contains) == true
        },
        onRange = { range, shouldSelect ->
            val ids = range.mapNotNull { sortedQueue.getOrNull(it)?.file?.id }.toSet()
            selectedQueueIds = if (shouldSelect) queueSelectionBase + ids
            else queueSelectionBase - ids
        },
        onEnd = { queueSelectionBase = emptySet() }
    )
    val activeQueueCount = state.waitingCount + state.generatingCount
    val individuallyPausedCount = state.queue.count { it.status == DuplicateScanStatus.PAUSED }
    val queueEta = when {
        state.isPaused -> "Estimated remaining: paused"
        activeQueueCount == 0 && individuallyPausedCount > 0 ->
            "${individuallyPausedCount} file(s) paused · resume them to continue"
        activeQueueCount == 0 && state.failedCount > 0 ->
            "${state.failedCount} file(s) failed · retry them to continue"
        activeQueueCount == 0 -> "Queue complete"
        scanFilesPerSecond > 0.0 -> {
            val seconds = (activeQueueCount / scanFilesPerSecond).toLong().coerceAtLeast(1L)
            val eta = Formatters.duration(seconds * 1_000L)
            if (individuallyPausedCount > 0) {
                "Estimated remaining: ${eta} · ${individuallyPausedCount} paused"
            } else {
                "Estimated remaining: ${eta}"
            }
        }
        else -> "Estimated remaining: calculating…"
    }

    fun selectAllMatches() {
        selectedIds = filteredGroups.flatMap { it.files }.map { it.id }.toSet()
    }

    fun autoSelectExtras() {
        selectedIds = filteredGroups.flatMap { group ->
            // Keep the oldest copy in each group; select every other identical copy.
            group.files.sortedBy { it.addedAt }.drop(1).map { it.id }
        }.toSet()
        message = "Selected duplicate copies while keeping the oldest copy in each group."
    }

    if (confirmTrash) {
        AlertDialog(
            onDismissRequest = { confirmTrash = false },
            title = { Text("Move selected duplicates to Trash?") },
            text = {
                Text(
                    "Move " + selectedIds.size + " selected file(s) to Trash. " +
                        "The oldest copy in each group is not selected automatically."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmTrash = false
                    val ids = selectedIds.toList()
                    scope.launch {
                        when (val result = trashRepository.moveFilesToTrash(ids)) {
                            is AppResult.Success -> {
                                selectedIds = emptySet()
                                message = "Moved " + ids.size + " file(s) to Trash."
                                repository.startScan(folderId)
                            }
                            is AppResult.Failure -> {
                                message = "Could not move files to Trash: " + result.error
                            }
                        }
                    }
                }) { Text("Move to Trash") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTrash = false }) { Text("Cancel") }
            }
        )
    }

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TooltipIconButton(tooltip = "Back to Files", onClick = {
                if (showQueue) showQueue = false else onBack()
            }) {
                androidx.compose.material3.Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back"
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Duplicate manager", style = MaterialTheme.typography.titleLarge)
                Text(
                    state.groups.size.toString() + " duplicate group(s) · " +
                        state.groups.sumOf { it.duplicateCount } + " extra copy/copies · " +
                        Formatters.bytes(state.groups.sumOf { it.wastedBytes }) + " reclaimable",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { showQueue = !showQueue }) {
                Text(if (showQueue) "View results" else "View queue (" + state.totalFiles + ")")
            }
            TooltipIconButton(tooltip = "Scan this folder again", onClick = {
                selectedIds = emptySet()
                scope.launch { repository.startScan(folderId, resetQueue = true) }
            }) {
                androidx.compose.material3.Icon(Icons.Filled.Refresh, contentDescription = "Rescan")
            }
        }

        if (state.totalFiles > 0) {
            val progress = state.scannedFiles.toFloat() / state.totalFiles.toFloat()
            LinearProgressIndicator(
                progress = progress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            )
            Text(
                state.scannedFiles.toString() + " of " + state.totalFiles + " files checked · " +
                    state.waitingCount + " waiting · " + state.generatingCount + " checking · " +
                    state.failedCount + " failed",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (message != null) {
            Text(
                message!!,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 6.dp)
            )
        }
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (showQueue) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    enabled = state.isRunning && !state.isPaused,
                    onClick = repository::pauseAll
                ) { Text("Pause all") }
                OutlinedButton(
                    enabled = state.isPaused,
                    onClick = repository::resumeAll
                ) { Text("Resume all") }
                OutlinedButton(
                    enabled = state.queue.any {
                        it.status == DuplicateScanStatus.WAITING ||
                            it.status == DuplicateScanStatus.GENERATING ||
                            it.status == DuplicateScanStatus.PAUSED
                    },
                    onClick = repository::cancelAll
                ) { Text("Cancel all") }
                OutlinedButton(
                    enabled = state.failedCount > 0 || state.cancelledCount > 0,
                    onClick = repository::retryFailed
                ) { Text("Retry failed/cancelled") }
            }

            Text(
                queueEta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = queueSearch,
                onValueChange = { queueSearch = it },
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                singleLine = true,
                label = { Text("Search queue by filename or folder") }
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(selectedQueueIds.size.toString() + " selected", modifier = Modifier.weight(1f))
                Box {
                    OutlinedButton(onClick = { showQueueSortMenu = true }) {
                        Text(
                            "Sort: " + when (queueSort) {
                                DuplicateFileSort.NAME_ASC -> "Name A–Z"
                                DuplicateFileSort.NAME_DESC -> "Name Z–A"
                                DuplicateFileSort.SIZE_LARGEST -> "Largest"
                                DuplicateFileSort.SIZE_SMALLEST -> "Smallest"
                                DuplicateFileSort.OLDEST_FIRST -> "Oldest"
                                DuplicateFileSort.NEWEST_FIRST -> "Newest"
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showQueueSortMenu,
                        onDismissRequest = { showQueueSortMenu = false }
                    ) {
                        DuplicateFileSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            DuplicateFileSort.NAME_ASC -> "Name A–Z"
                                            DuplicateFileSort.NAME_DESC -> "Name Z–A"
                                            DuplicateFileSort.SIZE_LARGEST -> "Largest file"
                                            DuplicateFileSort.SIZE_SMALLEST -> "Smallest file"
                                            DuplicateFileSort.OLDEST_FIRST -> "Oldest first"
                                            DuplicateFileSort.NEWEST_FIRST -> "Newest first"
                                        }
                                    )
                                },
                                onClick = { queueSort = option; showQueueSortMenu = false }
                            )
                        }
                    }
                }
                TextButton(
                    enabled = state.queue.isNotEmpty(),
                    onClick = { selectedQueueIds = state.queue.map { it.file.id }.toSet() }
                ) { Text("Select all") }
                TextButton(
                    enabled = sortedQueue.isNotEmpty(),
                    onClick = { selectedQueueIds = sortedQueue.map { it.file.id }.toSet() }
                ) { Text("Select visible") }
                TextButton(
                    enabled = selectedQueueIds.isNotEmpty(),
                    onClick = { selectedQueueIds = emptySet() }
                ) { Text("Clear") }
            }
            if (selectedQueueIds.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { repository.pauseFiles(selectedQueueIds) }) {
                        Text("Pause selected")
                    }
                    OutlinedButton(onClick = { repository.resumeFiles(selectedQueueIds) }) {
                        Text("Resume selected")
                    }
                    OutlinedButton(onClick = {
                        repository.cancelFiles(selectedQueueIds)
                        selectedQueueIds = emptySet()
                    }) { Text("Cancel selected") }
                    OutlinedButton(onClick = {
                        repository.retryFiles(selectedQueueIds)
                        selectedQueueIds = emptySet()
                    }) { Text("Retry selected") }
                }
            }

            if (state.queue.isEmpty()) {
                Text("There are no files in this folder tree.")
            } else if (sortedQueue.isEmpty()) {
                Text("No queue items match this search.")
            } else {
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = queueListState,
                        modifier = Modifier.fillMaxSize().then(queueDragSelect),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(sortedQueue, key = { it.file.id }) { item ->
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Row(
                                    modifier = Modifier.padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = item.file.id in selectedQueueIds,
                                        onCheckedChange = { checked ->
                                            selectedQueueIds = if (checked) {
                                                selectedQueueIds + item.file.id
                                            } else selectedQueueIds - item.file.id
                                        }
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(item.file.name, maxLines = 1)
                                        Text(
                                            Formatters.bytes(item.file.sizeBytes) + " · " + item.file.folderPath,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 2
                                        )
                                        Text(
                                            when (item.status) {
                                                DuplicateScanStatus.WAITING -> "Waiting"
                                                DuplicateScanStatus.GENERATING -> "Checking content"
                                                DuplicateScanStatus.SCANNED -> "Checked"
                                                DuplicateScanStatus.FAILED -> "Failed"
                                                DuplicateScanStatus.PAUSED -> "Paused"
                                                DuplicateScanStatus.CANCELLED -> "Cancelled"
                                            },
                                            style = MaterialTheme.typography.labelMedium,
                                            color = when (item.status) {
                                                DuplicateScanStatus.FAILED -> MaterialTheme.colorScheme.error
                                                DuplicateScanStatus.GENERATING -> MaterialTheme.colorScheme.primary
                                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                        item.error?.let {
                                            Text(
                                                it,
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    when (item.status) {
                                        DuplicateScanStatus.WAITING,
                                        DuplicateScanStatus.GENERATING -> {
                                            TextButton(onClick = {
                                                repository.pauseFiles(setOf(item.file.id))
                                            }) { Text("Pause") }
                                            TextButton(onClick = {
                                                repository.cancelFiles(setOf(item.file.id))
                                            }) { Text("Cancel") }
                                        }
                                        DuplicateScanStatus.PAUSED -> {
                                            TextButton(onClick = {
                                                repository.resumeFiles(setOf(item.file.id))
                                            }) { Text("Resume") }
                                            TextButton(onClick = {
                                                repository.cancelFiles(setOf(item.file.id))
                                            }) { Text("Cancel") }
                                        }
                                        DuplicateScanStatus.FAILED,
                                        DuplicateScanStatus.CANCELLED -> {
                                            TextButton(onClick = {
                                                repository.retryFiles(setOf(item.file.id))
                                            }) { Text("Retry") }
                                        }
                                        DuplicateScanStatus.SCANNED -> Unit
                                    }
                                }
                            }
                        }
                    }
                    DesktopVerticalScrollbar(
                        state = queueListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxSize()
                    )
                    DesktopScrollEdgeControls(
                        state = queueListState,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 8.dp)
                    )
                }
            }
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                singleLine = true,
                label = { Text("Search duplicate files") }
            )

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box {
                    OutlinedButton(onClick = { showTypeMenu = true }) { Text(typeFilter.label) }
                    DropdownMenu(expanded = showTypeMenu, onDismissRequest = { showTypeMenu = false }) {
                        DuplicateTypeFilter.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(option.label) },
                                onClick = { typeFilter = option; showTypeMenu = false }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { showGroupSortMenu = true }) {
                        Text(
                            when (groupSort) {
                                DuplicateGroupSort.MOST_COPIES -> "Sort: Most copies"
                                DuplicateGroupSort.LARGEST_WASTED -> "Sort: Most space"
                                DuplicateGroupSort.NAME_ASC -> "Sort: Name"
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showGroupSortMenu,
                        onDismissRequest = { showGroupSortMenu = false }
                    ) {
                        DuplicateGroupSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            DuplicateGroupSort.MOST_COPIES -> "Most copies"
                                            DuplicateGroupSort.LARGEST_WASTED -> "Largest wasted space"
                                            DuplicateGroupSort.NAME_ASC -> "Name A–Z"
                                        }
                                    )
                                },
                                onClick = { groupSort = option; showGroupSortMenu = false }
                            )
                        }
                    }
                }
                Box {
                    OutlinedButton(onClick = { showFileSortMenu = true }) {
                        Text(
                            when (fileSort) {
                                DuplicateFileSort.NAME_ASC -> "Files: Name A–Z"
                                DuplicateFileSort.NAME_DESC -> "Files: Name Z–A"
                                DuplicateFileSort.SIZE_LARGEST -> "Files: Largest"
                                DuplicateFileSort.SIZE_SMALLEST -> "Files: Smallest"
                                DuplicateFileSort.OLDEST_FIRST -> "Files: Oldest"
                                DuplicateFileSort.NEWEST_FIRST -> "Files: Newest"
                            }
                        )
                    }
                    DropdownMenu(
                        expanded = showFileSortMenu,
                        onDismissRequest = { showFileSortMenu = false }
                    ) {
                        DuplicateFileSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            DuplicateFileSort.NAME_ASC -> "Name A–Z"
                                            DuplicateFileSort.NAME_DESC -> "Name Z–A"
                                            DuplicateFileSort.SIZE_LARGEST -> "Largest first"
                                            DuplicateFileSort.SIZE_SMALLEST -> "Smallest first"
                                            DuplicateFileSort.OLDEST_FIRST -> "Oldest first"
                                            DuplicateFileSort.NEWEST_FIRST -> "Newest first"
                                        }
                                    )
                                },
                                onClick = { fileSort = option; showFileSortMenu = false }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    selectedIds.size.toString() + " selected · " + filteredGroups.size + " matching groups",
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { selectedIds = visibleFileIds() }) {
                    Text("Select visible")
                }
                TextButton(onClick = ::selectAllMatches) { Text("Select all") }
                TextButton(onClick = ::autoSelectExtras) { Text("Auto-select extras") }
                TextButton(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { selectedIds = emptySet() }
                ) { Text("Clear") }
            }

            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(onClick = {
                        clipboard.copy(CutItems(selectedIds.toList(), emptyList(), isCut = false))
                        message = "Copied " + selectedIds.size + " duplicate file(s). Navigate to a Files folder and press Ctrl+V."
                    }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        clipboard.cut(CutItems(selectedIds.toList(), emptyList(), isCut = true))
                        message = "Cut " + selectedIds.size + " duplicate file(s). Navigate to a Files folder and press Ctrl+V."
                    }) { Text("Cut") }
                    OutlinedButton(onClick = { confirmTrash = true }) { Text("Move to Trash") }
                }
            }

            if (filteredGroups.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            state.isRunning -> "Scanning files for identical content… no duplicates found yet."
                            state.failedCount > 0 ->
                                "No duplicate groups found yet. " + state.failedCount +
                                    " file(s) could not be checked; see View queue."
                            else -> "No duplicate files found in this folder tree."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredGroups, key = { it.contentHash }) { group ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceContainerLow
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    group.files.size.toString() + " identical files · " +
                                        Formatters.bytes(group.wastedBytes) + " extra space",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    group.files.firstOrNull()?.contentHash.orEmpty().take(16) + "…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                orderedFiles(group).forEach { file ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                selectedIds = if (file.id in selectedIds) {
                                                    selectedIds - file.id
                                                } else selectedIds + file.id
                                            }
                                            .padding(vertical = 5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Checkbox(
                                            checked = file.id in selectedIds,
                                            onCheckedChange = { checked ->
                                                selectedIds = if (checked) {
                                                    selectedIds + file.id
                                                } else selectedIds - file.id
                                            }
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(file.name, maxLines = 1)
                                            Text(
                                                Formatters.bytes(file.sizeBytes) + " · " +
                                                    file.mimeType + " · " + file.folderPath,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 2
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
