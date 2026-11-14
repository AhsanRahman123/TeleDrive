package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
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
import com.drdisagree.teledrive.presentation.components.TooltipIconButton
import com.drdisagree.teledrive.presentation.components.collectionCommandShortcuts
import com.drdisagree.teledrive.presentation.components.rememberCollectionCommandFocusRequester
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.drdisagree.teledrive.presentation.components.LocalAndroidLayout
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.teledrive.domain.repository.CacheEntryUsage
import com.drdisagree.teledrive.domain.repository.CacheFolderUsage
import com.drdisagree.teledrive.domain.repository.CacheManagerSummary
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.AppBackHandler
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.DesktopScrollEdgeControls
import com.drdisagree.teledrive.presentation.common.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import org.koin.compose.viewmodel.koinViewModel


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
    val androidLayout = LocalAndroidLayout.current
    val repository = koinInject<CacheRepository>()
    val settingsViewModel = koinViewModel<SettingsViewModel>()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val commandFocusRequester = rememberCollectionCommandFocusRequester()
    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTool by remember { mutableStateOf("CACHE") }
    var thumbnailManagerHasInternalBack by remember { mutableStateOf(false) }
    var thumbnailManagerBackAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmClearAllCache by remember { mutableStateOf(false) }
    var confirmClearThumbnailCache by remember { mutableStateOf(false) }

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
    var rangeSelectionBase by remember { mutableStateOf(emptySet<String>()) }
    var folderRangeSelectionBase by remember { mutableStateOf(emptySet<String>()) }
    val cacheEntryListState = rememberLazyListState()
    val folderListState = rememberLazyListState()

    CollectSnackbarMessages(
        messages = settingsViewModel.messages,
        hostState = snackbarHostState
    )

    val folderId = folderPath.lastOrNull()?.id

    fun navigateBackOneStep() {
        when {
            selectedTool == "QUEUE" -> selectedTool = "THUMBNAILS"
            selectedTool == "THUMBNAILS" && thumbnailManagerHasInternalBack -> {
                thumbnailManagerBackAction?.invoke()
            }
            selectedTool != "CACHE" -> selectedTool = "CACHE"
            folderPath.isNotEmpty() -> {
                folderPath = folderPath.dropLast(1)
                pageOffset = 0
                selectedPaths = emptySet()
                selectedFolderIds = emptySet()
            }
            else -> onBack()
        }
    }

    LaunchedEffect(selectedTool) {
        if (selectedTool != "THUMBNAILS") {
            thumbnailManagerHasInternalBack = false
            thumbnailManagerBackAction = null
        }
        if (selectedTool == "CACHE") commandFocusRequester.requestFocus()
    }
    AppBackHandler { navigateBackOneStep() }
    val folderDragSelect = rememberDragSelect(
        listState = folderListState,
        onStart = { folderRangeSelectionBase = selectedFolderIds },
        isSelected = { index ->
            folders.getOrNull(index)?.id?.let(selectedFolderIds::contains) == true
        },
        onRange = { range, shouldSelect ->
            val ids = range.mapNotNull { folders.getOrNull(it)?.id }.toSet()
            selectedFolderIds = if (shouldSelect) folderRangeSelectionBase + ids
                else folderRangeSelectionBase - ids
        },
        onEnd = { folderRangeSelectionBase = emptySet() }
    )
    val cacheEntryDragSelect = rememberDragSelect(
        listState = cacheEntryListState,
        onStart = { rangeSelectionBase = selectedPaths },
        isSelected = { index -> entries.getOrNull(index)?.path?.let(selectedPaths::contains) == true },
        onRange = { range, shouldSelect ->
            val paths = range.mapNotNull { entries.getOrNull(it)?.path }.toSet()
            selectedPaths = if (shouldSelect) rangeSelectionBase + paths
                else rangeSelectionBase - paths
        },
        onEnd = { rangeSelectionBase = emptySet() }
    )

    LaunchedEffect(folderId) {
        folderListState.scrollToItem(0)
        cacheEntryListState.scrollToItem(0)
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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Free up space") },
                navigationIcon = {
                    TooltipIconButton(tooltip = "Back", onClick = ::navigateBackOneStep) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (selectedTool == "CACHE") {
                        TooltipIconButton(tooltip = "Refresh cache inventory", onClick = {
                            pageOffset = 0
                            entries = emptyList()
                            refreshKey++
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "Refresh cache inventory")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())
        ) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .then(if (androidLayout) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedTool == "CACHE",
                    onClick = { selectedTool = "CACHE" },
                    label = { Text("Cache cleanup", maxLines = 1, softWrap = false) }
                )
                FilterChip(
                    selected = selectedTool == "THUMBNAILS",
                    onClick = { selectedTool = "THUMBNAILS" },
                    label = { Text("Thumbnail manager", maxLines = 1, softWrap = false) }
                )
                FilterChip(
                    selected = selectedTool == "QUEUE",
                    onClick = { selectedTool = "QUEUE" },
                    label = { Text("View queue", maxLines = 1, softWrap = false) }
                )
                FilterChip(
                    selected = selectedTool == "LOCAL_COPIES",
                    onClick = { selectedTool = "LOCAL_COPIES" },
                    label = { Text("Local copies", maxLines = 1, softWrap = false) }
                )
            }
            when (selectedTool) {
                "LOCAL_COPIES" -> LocalCopyInventoryScreen(
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                "THUMBNAILS" -> ThumbnailManagerScreen(
                    onBack = { selectedTool = "CACHE" },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onInternalBackStateChange = { canGoBack, action ->
                        thumbnailManagerHasInternalBack = canGoBack
                        thumbnailManagerBackAction = action.takeIf { canGoBack }
                    }
                )
                "QUEUE" -> ThumbnailQueueScreen(
                    onBack = { selectedTool = "THUMBNAILS" },
                    modifier = Modifier.weight(1f).fillMaxWidth()
                )
                else -> Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(horizontal = 18.dp, vertical = 12.dp)
                            .collectionCommandShortcuts(
                                focusRequester = commandFocusRequester,
                                onSelectAll = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val paths = repository.cacheEntryPathsMatching(
                                                folderId, typeFilter, searchQuery
                                            ).toMutableSet()
                                            if (folderId == null) {
                                                folders.forEach { folder ->
                                                    paths += repository.cacheEntryPathsInFolderTree(
                                                        folder.id, typeFilter, searchQuery
                                                    )
                                                }
                                            }
                                            selectedPaths = paths
                                            selectedFolderIds = folders.map { it.id }.toSet()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                    true
                                }
                            ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
            Row(
                modifier = Modifier.fillMaxWidth().then(
                    if (androidLayout) Modifier.horizontalScroll(rememberScrollState()) else Modifier
                ),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Card(modifier = if (androidLayout) Modifier.width(220.dp) else Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Total cache footprint", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Formatters.bytes(summary.totalBytes), style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "Tracked entries plus temporary and Telegram-managed cache",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (androidLayout) 2 else Int.MAX_VALUE,
                            overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip
                        )
                    }
                }
                Card(modifier = if (androidLayout) Modifier.width(220.dp) else Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Individually selectable", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(Formatters.bytes(summary.previewBytes + summary.streamBytes + summary.tempBytes + summary.thumbnailBytes),
                            style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "${summary.entryCount} entries · thumbnails, previews, streams and temporary files",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = if (androidLayout) 2 else Int.MAX_VALUE,
                            overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip
                        )
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Thumbnail preparation queue", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "$thumbnailQueueCount queued + running",
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        thumbnailQueueEstimate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Text(
                "Thumbnail previews are included here for selective cleanup. Use the tabs above for thumbnail generation rules, sorting, bulk actions and every queue control.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (androidLayout) 2 else Int.MAX_VALUE,
                overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip
            )
            Row(
                modifier = Modifier.then(if (androidLayout) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = { confirmClearAllCache = true }) {
                    Text("Clear all app cache", maxLines = 1, softWrap = false)
                }
                OutlinedButton(onClick = { confirmClearThumbnailCache = true }) {
                    Text("Clear thumbnail cache", maxLines = 1, softWrap = false)
                }
            }

            OutlinedTextField(
                value = searchInput,
                onValueChange = { searchInput = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search cached files") }
            )

            Row(
                modifier = Modifier.then(if (androidLayout) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf("ALL", "THUMBNAIL", "PREVIEW", "STREAM", "TEMP").forEach { filter ->
                    FilterChip(
                        selected = typeFilter == filter,
                        onClick = {
                            typeFilter = filter
                            pageOffset = 0
                            selectedPaths = emptySet()
                        },
                        label = { Text(if (filter == "ALL") "All types" else filter.lowercase().replaceFirstChar { it.uppercase() }, maxLines = if (androidLayout) 1 else Int.MAX_VALUE, softWrap = !androidLayout) }
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Sort: ${sortLabel()}", modifier = Modifier.weight(1f), maxLines = if (androidLayout) 1 else Int.MAX_VALUE, overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip)
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
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = if (androidLayout) 1 else Int.MAX_VALUE,
                    overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip
                )
                if (folderPath.isNotEmpty()) {
                    TextButton(onClick = { folderPath = folderPath.dropLast(1); pageOffset = 0 }) {
                        Text("Up")
                    }
                }
            }

            if (folders.isNotEmpty()) {
                Text("Folders", style = MaterialTheme.typography.titleMedium)
                Box(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                    LazyColumn(
                        state = folderListState,
                        modifier = Modifier.fillMaxSize().then(folderDragSelect),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(folders, key = { _, folder -> folder.id }) { _, folder ->
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
                                        Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = if (androidLayout) 1 else Int.MAX_VALUE, overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip)
                                        Text(
                                            "${folder.entryCount} cached entries · ${Formatters.bytes(folder.totalBytes)} including subfolders",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = if (androidLayout) 2 else Int.MAX_VALUE,
                                            overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip
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
                    DesktopScrollEdgeControls(
                        state = folderListState,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 2.dp)
                    )
                    DesktopVerticalScrollbar(
                        state = folderListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${selectedPaths.size} entries · ${selectedFolderIds.size} folders selected",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = if (androidLayout) 1 else Int.MAX_VALUE,
                    overflow = if (androidLayout) TextOverflow.Ellipsis else TextOverflow.Clip
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
                Box(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)) {
                    LazyColumn(
                        state = cacheEntryListState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
                            .then(cacheEntryDragSelect),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        itemsIndexed(entries, key = { _, entry -> entry.path }) { _, entry ->
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
                            item {
                                OutlinedButton(
                                    enabled = !loading,
                                    onClick = { pageOffset += CACHE_MANAGER_PAGE_SIZE }
                                ) { Text("Load more") }
                            }
                        }
                    }
                    DesktopScrollEdgeControls(
                        state = cacheEntryListState,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 2.dp)
                    )
                    DesktopVerticalScrollbar(
                        state = cacheEntryListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
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
                    DesktopScrollEdgeControls(
                        state = scrollState,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp)
                    )
                    DesktopVerticalScrollbar(
                        state = scrollState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }

    if (confirmClearAllCache) {
        AlertDialog(
            onDismissRequest = { confirmClearAllCache = false },
            title = { Text("Clear all app cache?") },
            text = {
                Text("This clears the app-managed preview, stream and temporary cache. It does not delete original Telegram files or thumbnail queue jobs.")
            },
            confirmButton = {
                Button(onClick = {
                    confirmClearAllCache = false
                    settingsViewModel.clearCache { refreshKey++ }
                }) { Text("Clear all cache") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAllCache = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearThumbnailCache) {
        AlertDialog(
            onDismissRequest = { confirmClearThumbnailCache = false },
            title = { Text("Clear thumbnail cache?") },
            text = {
                Text("This removes generated thumbnail previews. It does not delete the original media or cancel queued thumbnail jobs.")
            },
            confirmButton = {
                Button(onClick = {
                    confirmClearThumbnailCache = false
                    settingsViewModel.clearThumbnails { refreshKey++ }
                }) { Text("Clear thumbnails") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearThumbnailCache = false }) { Text("Cancel") }
            }
        )
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
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = selected,
                    role = Role.Checkbox,
                    onValueChange = onSelected
                )
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = null)
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
