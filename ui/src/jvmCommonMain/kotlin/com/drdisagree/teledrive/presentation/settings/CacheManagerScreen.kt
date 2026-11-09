package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.repository.CacheEntryUsage
import com.drdisagree.teledrive.domain.repository.CacheFolderUsage
import com.drdisagree.teledrive.domain.repository.CacheManagerSummary
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.presentation.common.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject


private fun formatCacheQueueEta(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private const val CACHE_MANAGER_PAGE_SIZE = 60

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheManagerScreen(
    onBack: () -> Unit,
    initialFilter: String = "ALL",
    modifier: Modifier = Modifier
) {
    val repository = koinInject<CacheRepository>()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var summary by remember { mutableStateOf(CacheManagerSummary()) }
    var thumbnailQueueCount by remember { mutableStateOf(0) }
    var thumbnailQueueEstimate by remember { mutableStateOf("Estimating completion rate…") }
    var folderPath by remember { mutableStateOf(emptyList<CacheFolderUsage>()) }
    var folders by remember { mutableStateOf(emptyList<CacheFolderUsage>()) }
    var entries by remember { mutableStateOf(emptyList<CacheEntryUsage>()) }
    var selectedPaths by remember { mutableStateOf(emptySet<String>()) }
    var selectedFolderIds by remember { mutableStateOf(emptySet<String>()) }
    var searchInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var typeFilter by remember(initialFilter) { mutableStateOf(initialFilter) }
    var sortOrder by remember { mutableStateOf("LARGEST") }
    var pageOffset by remember { mutableStateOf(0) }
    var hasMore by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmUntracked by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableStateOf(0) }

    val folderId = folderPath.lastOrNull()?.id

    LaunchedEffect(folderId) {
        selectedPaths = emptySet()
        selectedFolderIds = emptySet()
    }

    LaunchedEffect(searchInput) {
        delay(300L)
        val normalized = searchInput.trim()
        if (normalized != searchQuery) {
            searchQuery = normalized
            pageOffset = 0
            selectedPaths = emptySet()
        }
    }

    LaunchedEffect(refreshKey) {
        summary = repository.cacheManagerSummary()
    }

    // Keep the queue estimate live on this page as well as on Thumbnail Manager
    // and View Queue. The rate is based on completed queue rows, not guessed network KB/s.
    LaunchedEffect(folderId) {
        var previousCount = -1
        var previousSampleAt = 0L
        var throughputFilesPerMinute = 0.0
        while (true) {
            val count = repository.thumbnailQueueCount()
            val paused = repository.thumbnailQueuePaused()
            val sampleAt = System.currentTimeMillis()
            if (paused) {
                throughputFilesPerMinute = 0.0
            } else if (previousCount >= 0 && previousSampleAt > 0L) {
                val elapsed = (sampleAt - previousSampleAt).coerceAtLeast(1L)
                val removed = previousCount - count
                if (removed > 0) {
                    val sampleRate = removed * 60_000.0 / elapsed
                    throughputFilesPerMinute = if (throughputFilesPerMinute <= 0.0) {
                        sampleRate
                    } else {
                        throughputFilesPerMinute * 0.65 + sampleRate * 0.35
                    }
                } else if (count > previousCount) {
                    throughputFilesPerMinute = 0.0
                }
            }
            thumbnailQueueCount = count
            thumbnailQueueEstimate = when {
                paused -> "ETA unavailable while paused"
                count == 0 -> "No thumbnail jobs pending"
                throughputFilesPerMinute > 0.0 -> {
                    val etaSeconds = (count * 60.0 / throughputFilesPerMinute)
                        .toLong().coerceAtLeast(1L)
                    "Recent queue reduction: ${throughputFilesPerMinute.toInt()} files/min · " +
                        "Estimated remaining: ${formatCacheQueueEta(etaSeconds)}"
                }
                else -> "Estimating completion rate…"
            }
            previousCount = count
            previousSampleAt = sampleAt
            delay(1_000L)
        }
    }

    LaunchedEffect(folderId, typeFilter, sortOrder, searchQuery, pageOffset, refreshKey) {
        loading = true
        folders = repository.cacheFolders(folderId, sortOrder)
        val page = repository.cacheEntries(
            folderId = folderId,
            offset = pageOffset,
            limit = CACHE_MANAGER_PAGE_SIZE + 1,
            typeFilter = typeFilter,
            searchQuery = searchQuery,
            sortOrder = sortOrder
        )
        if (page.isEmpty() && pageOffset > 0) {
            pageOffset = (pageOffset - CACHE_MANAGER_PAGE_SIZE).coerceAtLeast(0)
            loading = false
            return@LaunchedEffect
        }
        entries = if (pageOffset == 0) page.take(CACHE_MANAGER_PAGE_SIZE)
            else entries + page.take(CACHE_MANAGER_PAGE_SIZE)
        hasMore = page.size > CACHE_MANAGER_PAGE_SIZE
        loading = false
    }

    fun sortLabel(): String = when (sortOrder) {
        "SMALLEST" -> "Smallest first"
        "NEWEST" -> "Recently used"
        "OLDEST" -> "Least recently used"
        "NAME_ASC" -> "Name"
        else -> "Largest first"
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Cache manager") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        pageOffset = 0
                        entries = emptyList()
                        refreshKey++
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh cache inventory")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize()
                .padding(top = padding.calculateTopPadding())
                .verticalScroll(scrollState)
                .padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Card(modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Total cache footprint", style = MaterialTheme.typography.labelMedium)
                        Text(Formatters.bytes(summary.totalBytes), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Tracked entries plus temporary and Telegram-managed cache",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Card(modifier = Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Individually selectable", style = MaterialTheme.typography.labelMedium)
                        Text(Formatters.bytes(summary.previewBytes + summary.streamBytes + summary.tempBytes + summary.thumbnailBytes),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${summary.entryCount} entries · thumbnails, previews, streams and temporary files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Thumbnail preparation queue", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "$thumbnailQueueCount queued + running",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        thumbnailQueueEstimate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                "Thumbnail previews are included here for selective cleanup. Use Thumbnail Manager for generation policies and queue controls.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search cached files") }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ALL", "THUMBNAIL", "PREVIEW", "STREAM", "TEMP").forEach { filter ->
                    FilterChip(
                        selected = typeFilter == filter,
                        onClick = {
                            typeFilter = filter
                            pageOffset = 0
                            selectedPaths = emptySet()
                        },
                        label = { Text(if (filter == "ALL") "All types" else filter.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort: ${sortLabel()}", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    sortOrder = when (sortOrder) {
                        "LARGEST" -> "SMALLEST"
                        "SMALLEST" -> "NEWEST"
                        "NEWEST" -> "OLDEST"
                        "OLDEST" -> "NAME_ASC"
                        else -> "LARGEST"
                    }
                    pageOffset = 0
                }) { Text("Change") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (folderPath.isEmpty()) "All cache folders" else folderPath.joinToString("  ›  ") { it.name },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall
                )
                if (folderPath.isNotEmpty()) {
                    TextButton(onClick = { folderPath = folderPath.dropLast(1); pageOffset = 0 }) {
                        Text("Up")
                    }
                }
            }

            if (folders.isNotEmpty()) {
                Text("Folders", style = MaterialTheme.typography.titleMedium)
                folders.forEach { folder ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                folderPath = folderPath + folder
                                pageOffset = 0
                                selectedPaths = emptySet()
                            }.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = folder.id in selectedFolderIds,
                                onCheckedChange = { checked ->
                                    selectedFolderIds = if (checked) selectedFolderIds + folder.id
                                        else selectedFolderIds - folder.id
                                }
                            )
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(folder.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "${folder.entryCount} cached entries · ${Formatters.bytes(folder.totalBytes)} including subfolders",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                folderPath = folderPath + folder
                                pageOffset = 0
                                selectedPaths = emptySet()
                            }) { Text("Open") }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${selectedPaths.size} entries · ${selectedFolderIds.size} folders selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge
                )
                TextButton(
                    enabled = !loading && (entries.isNotEmpty() || folders.isNotEmpty()),
                    onClick = {
                        scope.launch {
                            busy = true
                            try {
                                val paths = repository.cacheEntryPathsMatching(
                                    folderId, typeFilter, searchQuery
                                ).toMutableSet()
                                if (folderId == null) {
                                    folders.forEach { folder ->
                                        paths += repository.cacheEntryPathsInFolderTree(folder.id, typeFilter, searchQuery)
                                    }
                                }
                                selectedPaths = paths
                                selectedFolderIds = emptySet()
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Select matching") }
                TextButton(
                    enabled = entries.isNotEmpty(),
                    onClick = { selectedPaths = selectedPaths + entries.map { it.path } }
                ) { Text("Select visible") }
                TextButton(
                    enabled = folders.isNotEmpty(),
                    onClick = { selectedFolderIds = selectedFolderIds + folders.map { it.id } }
                ) { Text("Select folders") }
                TextButton(
                    enabled = selectedPaths.isNotEmpty() || selectedFolderIds.isNotEmpty(),
                    onClick = {
                        selectedPaths = emptySet()
                        selectedFolderIds = emptySet()
                    }
                ) { Text("Clear") }
            }

            if (selectedPaths.isNotEmpty() || selectedFolderIds.isNotEmpty()) {
                OutlinedButton(
                    enabled = !busy,
                    onClick = { confirmRemove = true }
                ) {
                    Icon(Icons.Filled.DeleteOutline, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Remove selected cache")
                }
            }

            if (message.isNotBlank()) {
                Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Text("Cache entries", style = MaterialTheme.typography.titleMedium)
            if (loading) {
                CircularProgressIndicator()
            } else if (entries.isEmpty()) {
                Text("No indexed cache entries in this folder match the current filters.")
            } else {
                entries.forEach { entry ->
                    CacheEntryRow(
                        entry = entry,
                        selected = entry.path in selectedPaths,
                        onSelected = { checked ->
                            selectedPaths = if (checked) selectedPaths + entry.path
                                else selectedPaths - entry.path
                        }
                    )
                }
                if (hasMore) {
                    OutlinedButton(
                        enabled = !loading,
                        onClick = { pageOffset += CACHE_MANAGER_PAGE_SIZE }
                    ) { Text("Load more") }
                }
            }

            if (summary.untrackedBytes > 0L) {
                Spacer(Modifier.height(8.dp))
                Card {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Other app-managed cache", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${Formatters.bytes(summary.untrackedBytes)} · temporary working files and Telegram-managed downloads",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "These files are not individually indexed, so they cannot be selected one by one here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(onClick = { confirmUntracked = true }) {
                            Text("Clear other cache")
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove selected cache?") },
            text = {
                Text("Only the selected cache entries and the contents of selected folders will be removed. Original Telegram files are not deleted.")
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        confirmRemove = false
                        scope.launch {
                            busy = true
                            try {
                                val paths = selectedPaths.toMutableSet()
                                selectedFolderIds.forEach { id ->
                                    paths += repository.cacheEntryPathsInFolderTree(id, typeFilter, "")
                                }
                                val result = repository.removeCacheEntries(paths.toList())
                                selectedPaths = emptySet()
                                selectedFolderIds = emptySet()
                                message = "Removed ${result.removedEntries} entries · ${Formatters.bytes(result.removedBytes)}"
                                pageOffset = 0
                                refreshKey++
                            } finally {
                                busy = false
                            }
                        }
                    }
                ) { Text("Remove selected") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmUntracked) {
        AlertDialog(
            onDismissRequest = { confirmUntracked = false },
            title = { Text("Clear other app-managed cache?") },
            text = {
                Text("This removes temporary working files and Telegram-managed downloaded cache. Wait until active downloads and transfers finish first. Original files and thumbnail previews are not deleted.")
            },
            confirmButton = {
                Button(onClick = {
                    confirmUntracked = false
                    scope.launch {
                        busy = true
                        try {
                            repository.clearUntrackedCache()
                            summary = repository.cacheManagerSummary()
                            message = "Other app-managed cache cleared."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Clear other cache") }
            },
            dismissButton = {
                TextButton(onClick = { confirmUntracked = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun CacheEntryRow(
    entry: CacheEntryUsage,
    selected: Boolean,
    onSelected: (Boolean) -> Unit
) {
    Card {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = onSelected)
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${entry.type.lowercase().replaceFirstChar { it.uppercase() }} · ${Formatters.bytes(entry.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    entry.folderPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
