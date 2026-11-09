package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.collect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.drdisagree.teledrive.core.media.ThumbnailModel
import com.drdisagree.teledrive.core.media.ThumbnailChangeNotifier
import com.drdisagree.teledrive.domain.model.ThumbnailPolicy
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.ThumbnailFileUsage
import com.drdisagree.teledrive.domain.repository.ThumbnailFolderUsage
import com.drdisagree.teledrive.domain.repository.ThumbnailManagerSummary
import com.drdisagree.teledrive.domain.repository.ThumbnailCacheFilter
import com.drdisagree.teledrive.domain.repository.ThumbnailPolicyFilter
import com.drdisagree.teledrive.domain.repository.ThumbnailEffectivePolicyFilter
import com.drdisagree.teledrive.domain.repository.ThumbnailFileSort
import com.drdisagree.teledrive.domain.repository.ThumbnailFolderSort
import com.drdisagree.teledrive.domain.repository.ThumbnailTargetType
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.DesktopScrollEdgeControls
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private fun formatThumbnailQueueEta(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600L
    val minutes = (totalSeconds % 3_600L) / 60L
    val seconds = totalSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

private const val THUMBNAIL_PAGE_SIZE = 60

@Composable
fun ThumbnailManagerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val cacheRepository = koinInject<CacheRepository>()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var summary by remember { mutableStateOf(ThumbnailManagerSummary()) }
    var queueEstimate by remember { mutableStateOf("Estimating completion rate…") }
    var folderPath by remember { mutableStateOf(emptyList<ThumbnailFolderUsage>()) }
    var folders by remember { mutableStateOf(emptyList<ThumbnailFolderUsage>()) }
    var files by remember { mutableStateOf(emptyList<ThumbnailFileUsage>()) }
    var filePolicy by remember { mutableStateOf(ThumbnailPolicy.INHERIT) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var selectedFolderIds by remember { mutableStateOf(emptySet<String>()) }
    var selectionBusy by remember { mutableStateOf(false) }
    var generationMessage by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var pageOffset by remember { mutableStateOf(0) }
    var refreshKey by remember { mutableStateOf(0) }
    var confirmClearFolderId by remember { mutableStateOf<String?>(null) }
    var confirmClearOrphans by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmRemoveSelected by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var selectingAll by remember { mutableStateOf(false) }
    var showAdvancedFilters by remember { mutableStateOf(false) }
    var cacheFilter by remember { mutableStateOf(ThumbnailCacheFilter.ALL) }
    var policyFilter by remember { mutableStateOf(ThumbnailPolicyFilter.ALL) }
    var effectivePolicyFilter by remember { mutableStateOf(ThumbnailEffectivePolicyFilter.ALL) }
    var sortOrder by remember { mutableStateOf(ThumbnailFileSort.THUMBNAIL_LARGEST) }
    var folderSortOrder by remember { mutableStateOf(ThumbnailFolderSort.MOST_MISSING) }
    var searchInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var rangeSelectionBase by remember { mutableStateOf(emptySet<String>()) }
    var folderRangeSelectionBase by remember { mutableStateOf(emptySet<String>()) }
    val fileListState = rememberLazyListState()
    val folderListState = rememberLazyListState()

    val folderId = folderPath.lastOrNull()?.id
    val folderDragSelect = rememberDragSelect(
        listState = folderListState,
        onStart = { folderRangeSelectionBase = selectedFolderIds },
        isSelected = { index ->
            folders.getOrNull(index)?.id?.let(selectedFolderIds::contains) == true
        },
        onRange = { range, shouldSelect ->
            val rangeIds = range.mapNotNull { folders.getOrNull(it)?.id }.toSet()
            selectedFolderIds = if (shouldSelect) {
                folderRangeSelectionBase + rangeIds
            } else {
                folderRangeSelectionBase - rangeIds
            }
        },
        onEnd = { folderRangeSelectionBase = emptySet() }
    )
    val fileDragSelect = rememberDragSelect(
        listState = fileListState,
        onStart = { rangeSelectionBase = selectedIds },
        isSelected = { index -> files.getOrNull(index)?.fileId?.let(selectedIds::contains) == true },
        onRange = { range, shouldSelect ->
            val rangeIds = range.mapNotNull { files.getOrNull(it)?.fileId }.toSet()
            selectedIds = if (shouldSelect) {
                rangeSelectionBase + rangeIds
            } else {
                rangeSelectionBase - rangeIds
            }
        },
        onEnd = { rangeSelectionBase = emptySet() }
    )
    LaunchedEffect(folderId) {
        selectedIds = emptySet()
        selectedFolderIds = emptySet()
        generationMessage = ""
    }
    LaunchedEffect(searchInput) {
        delay(300L)
        val normalized = searchInput.trim()
        if (searchQuery != normalized) {
            searchQuery = normalized
            pageOffset = 0
            selectedIds = emptySet()
        }
    }

    LaunchedEffect(cacheFilter, policyFilter, effectivePolicyFilter, sortOrder, searchQuery) {
        pageOffset = 0
        selectedIds = emptySet()
    }

    LaunchedEffect(
        folderId, refreshKey, pageOffset, cacheFilter, policyFilter, effectivePolicyFilter, sortOrder, folderSortOrder, searchQuery
    ) {
        loading = true
        summary = cacheRepository.thumbnailManagerSummary()
        folders = cacheRepository.thumbnailFolders(folderId, folderSortOrder.name)
        val page = cacheRepository.thumbnailFiles(
            folderId = folderId,
            offset = pageOffset,
            limit = THUMBNAIL_PAGE_SIZE + 1,
            cacheFilter = cacheFilter.name,
            policyFilter = policyFilter.name,
            effectivePolicyFilter = effectivePolicyFilter.name,
            searchQuery = searchQuery,
            sortOrder = sortOrder.name
        )
        if (page.isEmpty() && pageOffset > 0) {
            pageOffset = (pageOffset - THUMBNAIL_PAGE_SIZE).coerceAtLeast(0)
            loading = false
            return@LaunchedEffect
        }
        files = page.take(THUMBNAIL_PAGE_SIZE)
        hasMore = page.size > THUMBNAIL_PAGE_SIZE
        filePolicy = if (folderId == null) {
            ThumbnailPolicy.INHERIT
        } else {
            cacheRepository.thumbnailPolicy(ThumbnailTargetType.FOLDER, folderId)
        }
        loading = false
    }

    // Keep the manager and queue page independently live, using the same
    // one-second queue-reduction estimate on both screens.
    LaunchedEffect(folderId) {
        var previousCount = -1
        var previousFailedCount = 0
        var previousSampleAt = 0L
        var throughputFilesPerMinute = 0.0
        while (true) {
            val liveQueueCount = cacheRepository.thumbnailQueueCount()
            val failedCount = cacheRepository.thumbnailQueueFailedFileIds().size
            val remainingWork = (liveQueueCount - failedCount).coerceAtLeast(0)
            val paused = cacheRepository.thumbnailQueuePaused()
            val sampleAt = System.currentTimeMillis()
            if (paused) {
                throughputFilesPerMinute = 0.0
            } else if (previousCount >= 0 && previousSampleAt > 0L) {
                val elapsed = (sampleAt - previousSampleAt).coerceAtLeast(1L)
                val removed = previousCount - liveQueueCount
                if (removed > 0) {
                    val sampleRate = removed * 60_000.0 / elapsed
                    throughputFilesPerMinute = if (throughputFilesPerMinute <= 0.0) {
                        sampleRate
                    } else {
                        throughputFilesPerMinute * 0.65 + sampleRate * 0.35
                    }
                } else if (liveQueueCount > previousCount || failedCount != previousFailedCount) {
                    throughputFilesPerMinute = 0.0
                }
            }
            previousCount = liveQueueCount
            previousFailedCount = failedCount
            previousSampleAt = sampleAt
            if (summary.pendingJobs != liveQueueCount) {
                summary = summary.copy(pendingJobs = liveQueueCount)
            }
            queueEstimate = when {
                paused -> "ETA unavailable while paused"
                remainingWork == 0 && failedCount > 0 ->
                    "$failedCount failed jobs need retry · ETA unavailable"
                remainingWork == 0 -> "All queued jobs completed"
                throughputFilesPerMinute > 0.0 -> {
                    val etaSeconds = (remainingWork * 60.0 / throughputFilesPerMinute)
                        .toLong().coerceAtLeast(1L)
                    "Recent queue reduction: ${throughputFilesPerMinute.toInt()} files/min · " +
                        "Estimated remaining: ${formatThumbnailQueueEta(etaSeconds)}"
                }
                else -> "Estimating completion rate…"
            }
            delay(1_000L)
        }
    }

    // Update totals and folder aggregates without resetting the current file page or selection.
    LaunchedEffect(folderId, folderSortOrder) {
        while (true) {
            val latest = cacheRepository.thumbnailManagerSummary()
            val cacheChanged = latest.totalBytes != summary.totalBytes ||
                latest.cachedFiles != summary.cachedFiles ||
                latest.unfiledBytes != summary.unfiledBytes ||
                latest.unfiledCachedFiles != summary.unfiledCachedFiles ||
                latest.orphanedBytes != summary.orphanedBytes ||
                latest.orphanedCachedFiles != summary.orphanedCachedFiles
            summary = latest
            if (cacheChanged) {
                folders = cacheRepository.thumbnailFolders(folderId, folderSortOrder.name)
            }
            delay(5_000L)
        }
    }

    if (showQueue) {
        ThumbnailQueueScreen(
            onBack = { showQueue = false },
            modifier = modifier
        )
        return
    }

    // Update visible file rows as their persistent cache entries are written.
    LaunchedEffect(folderId) {
        ThumbnailChangeNotifier.events.collect { change ->
            val existing = files.firstOrNull { it.fileId == change.fileId }
            // Only update the visible page incrementally. Off-page events are reflected by the
            // lightweight five-second summary refresh, avoiding an unbounded 100k-ID set.
            if (existing != null) {
                val wasCached = existing.isCached
                val delta = if (wasCached) {
                    change.sizeBytes - (existing.thumbnailSizeBytes ?: 0L)
                } else {
                    change.sizeBytes
                }
                summary = summary.copy(
                    totalBytes = (summary.totalBytes + delta).coerceAtLeast(0L),
                    cachedFiles = if (wasCached) summary.cachedFiles
                    else summary.cachedFiles + 1
                )
                files = files.map { file ->
                    if (file.fileId == change.fileId) {
                        file.copy(
                            thumbnailSizeBytes = change.sizeBytes,
                            generatedAt = change.generatedAt,
                            lastAccessAt = change.generatedAt,
                            encrypted = change.encrypted
                        )
                    } else file
                }
            }
            // Refresh the filtered/sorted page even when the changed file is off-page:
            // it may enter/leave the current cache-status filter or change sort position.
            refreshKey++
        }
    }

    if (confirmClearOrphans) {
        AlertDialog(
            onDismissRequest = { confirmClearOrphans = false },
            title = { Text("Remove unlinked thumbnails?") },
            text = {
                Text(
                    "These preview files no longer have a matching TeleDrive file record. " +
                        "Only the orphaned thumbnail cache files and metadata will be removed."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmClearOrphans = false
                        scope.launch {
                            cacheRepository.clearOrphanedThumbnails()
                            refreshKey++
                        }
                    }
                ) { Text("Remove unlinked cache") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearOrphans = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearFolderId != null) {
        AlertDialog(
            onDismissRequest = { confirmClearFolderId = null },
            title = { Text("Clear this folder's thumbnails?") },
            text = {
                Text(
                    "Only cached previews in this folder and its subfolders will be removed. " +
                        "Queued jobs and Automatic/Manual policies will remain unchanged, so pending work may create previews again."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val targetFolderId = confirmClearFolderId
                        confirmClearFolderId = null
                        if (targetFolderId != null) scope.launch {
                            cacheRepository.removeFolderThumbnails(targetFolderId)
                            refreshKey++
                        }
                    }
                ) { Text("Clear thumbnails") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearFolderId = null }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearAll) {
        AlertDialog(
            onDismissRequest = { confirmClearAll = false },
            title = { Text("Delete all cached thumbnails?") },
            text = {
                Text(
                    "Every cached thumbnail preview will be removed. Queue entries and Automatic/Manual policies will remain unchanged, so pending work may create previews again. Original files and Telegram backups will remain untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearAll = false
                    scope.launch {
                        cacheRepository.clearThumbnails()
                        selectedIds = emptySet()
                        refreshKey++
                    }
                }) { Text("Delete all thumbnails") }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearAll = false }) { Text("Cancel") }
            }
        )
    }

    if (confirmRemoveSelected) {
        AlertDialog(
            onDismissRequest = { confirmRemoveSelected = false },
            title = { Text("Remove selected cached thumbnails?") },
            text = {
                Text(
                    "This removes cached previews for ${selectedIds.size} selected files and " +
                        "${selectedFolderIds.size} selected folders (including their subfolders). " +
                        "Original files and generation policies remain unchanged."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveSelected = false
                    val ids = selectedIds.toList()
                    val folderIds = selectedFolderIds.toList()
                    scope.launch {
                        if (ids.isNotEmpty()) cacheRepository.removeThumbnails(ids)
                        folderIds.forEach { cacheRepository.removeFolderThumbnails(it) }
                        selectedIds = emptySet()
                        selectedFolderIds = emptySet()
                        refreshKey++
                    }
                }) { Text("Delete selected") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveSelected = false }) { Text("Cancel") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // SettingsSectionScreen already supplies the main page title and back button.
            // Keep only folder navigation here, avoiding a duplicate fixed title bar.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (folderPath.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            folderPath = folderPath.dropLast(1)
                            pageOffset = 0
                        }
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to parent folder")
                    }
                }
                Text(
                    if (folderPath.isEmpty()) "Storage overview"
                    else folderPath.joinToString(" / ") { it.name },
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
                OutlinedButton(
                    onClick = { showQueue = true },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(38.dp)
                ) { Text("View queue") }
                IconButton(onClick = {
                    scope.launch { scrollState.animateScrollTo(0) }
                }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Scroll to top")
                }
                IconButton(onClick = {
                    scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }
            // Compact sticky toolbar: only the controls needed for frequent navigation stay pinned.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = { searchInput = it },
                    modifier = Modifier.weight(1f).height(48.dp),
                    singleLine = true,
                    placeholder = { Text("Search files in this folder") }
                )
                ThumbnailCacheStatusButton(
                    label = "All",
                    selected = cacheFilter == ThumbnailCacheFilter.ALL,
                    onClick = { cacheFilter = ThumbnailCacheFilter.ALL },
                    compact = true
                )
                ThumbnailCacheStatusButton(
                    label = "Generated",
                    selected = cacheFilter == ThumbnailCacheFilter.GENERATED,
                    onClick = { cacheFilter = ThumbnailCacheFilter.GENERATED },
                    compact = true
                )
                ThumbnailCacheStatusButton(
                    label = "Not generated",
                    selected = cacheFilter == ThumbnailCacheFilter.MISSING,
                    onClick = { cacheFilter = ThumbnailCacheFilter.MISSING },
                    compact = true
                )
                ThumbnailQuickSortDropdown(
                    value = sortOrder,
                    onSelect = { sortOrder = it }
                )
                OutlinedButton(
                    onClick = { showAdvancedFilters = !showAdvancedFilters },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(if (showAdvancedFilters) "Hide filters" else "More filters")
                }
            }
            if (showAdvancedFilters) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThumbnailInventoryDropdown(
                        title = "Policy",
                        description = "Direct rule on each file",
                        value = policyFilter.name,
                        options = ThumbnailPolicyFilter.entries.map { it.name to policyFilterLabel(it) },
                        onSelect = { policyFilter = ThumbnailPolicyFilter.valueOf(it) }
                    )
                    ThumbnailInventoryDropdown(
                        title = "Behavior",
                        description = "Resolved automatic/manual behavior",
                        value = effectivePolicyFilter.name,
                        options = ThumbnailEffectivePolicyFilter.entries.map {
                            it.name to effectivePolicyFilterLabel(it)
                        },
                        onSelect = { effectivePolicyFilter = ThumbnailEffectivePolicyFilter.valueOf(it) }
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(end = 10.dp)
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SummaryTile(
                    title = "Thumbnail cache",
                    value = Formatters.bytes(summary.totalBytes),
                    subtitle = "${summary.cachedFiles} cached files",
                    modifier = Modifier.weight(1f)
                )
                SummaryTile(
                    title = "Background queue",
                    value = summary.pendingJobs.toString(),
                    subtitle = "queued + running",
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                queueEstimate,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            Text(
                "Existing library files are Manual only by default. New successful uploads are " +
                    "automatically prepared. Choose individual files or folders to prepare older previews.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedIds.size} files · ${selectedFolderIds.size} folders selected",
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(
                        enabled = !selectingAll && files.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                selectingAll = true
                                try {
                                    selectedIds = cacheRepository.thumbnailFileIdsMatching(
                                        folderId = folderId,
                                        cacheFilter = cacheFilter.name,
                                        policyFilter = policyFilter.name,
                                        effectivePolicyFilter = effectivePolicyFilter.name,
                                        searchQuery = searchQuery
                                    ).toSet()
                                } finally {
                                    selectingAll = false
                                }
                            }
                        }
                    ) {
                        Text(
                            if (selectingAll) "Selecting…"
                            else if (folderId == null) "Select matching unfiled files"
                            else "Select matching files"
                        )
                    }
                    if (folderId == null) {
                        TextButton(
                            enabled = !selectingAll,
                            onClick = {
                                scope.launch {
                                    selectingAll = true
                                    try {
                                        selectedIds = cacheRepository.thumbnailAllMissingFileIds().toSet()
                                        selectedFolderIds = emptySet()
                                        generationMessage = "Selected ${selectedIds.size} missing files across the drive."
                                    } finally {
                                        selectingAll = false
                                    }
                                }
                            }
                        ) { Text(if (selectingAll) "Selecting…" else "Select all missing (drive)") }
                    }
                    TextButton(
                        enabled = !selectingAll && files.isNotEmpty(),
                        onClick = { selectedIds = selectedIds + files.map { it.fileId } }
                    ) { Text("Select visible") }
                    TextButton(
                        enabled = folders.isNotEmpty(),
                        onClick = { selectedFolderIds = selectedFolderIds + folders.map { it.id } }
                    ) { Text("Select folders") }
                    TextButton(
                        enabled = selectedIds.isNotEmpty() || selectedFolderIds.isNotEmpty(),
                        onClick = {
                            selectedIds = emptySet()
                            selectedFolderIds = emptySet()
                        }
                    ) { Text("Clear") }
                }
                Text(
                    "Select visible files on this page, matching files across the filter, or tick folders below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                if (selectedIds.isNotEmpty() || selectedFolderIds.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            enabled = !selectionBusy,
                            onClick = {
                                scope.launch {
                                    selectionBusy = true
                                    try {
                                        var newlyQueued = 0
                                        var alreadyQueued = 0
                                        if (selectedIds.isNotEmpty()) {
                                            val result = cacheRepository.generateThumbnails(selectedIds.toList())
                                            newlyQueued += result.newlyQueued
                                            alreadyQueued += result.alreadyQueued
                                        }
                                        selectedFolderIds.forEach { id ->
                                            val result = cacheRepository.generateFolderThumbnails(id)
                                            newlyQueued += result.newlyQueued
                                            alreadyQueued += result.alreadyQueued
                                        }
                                        generationMessage = enqueueResultMessage(newlyQueued, alreadyQueued)
                                        selectedIds = emptySet()
                                        selectedFolderIds = emptySet()
                                        summary = cacheRepository.thumbnailManagerSummary()
                                    } finally {
                                        selectionBusy = false
                                    }
                                }
                            }
                        ) { Text(if (selectionBusy) "Adding…" else "Generate missing") }
                        ThumbnailBulkPolicyMenu(
                            onSelect = { policy ->
                                scope.launch {
                                    selectionBusy = true
                                    try {
                                        if (selectedIds.isNotEmpty()) {
                                            cacheRepository.setThumbnailPolicies(selectedIds.toList(), policy)
                                        }
                                        selectedFolderIds.forEach { id ->
                                            cacheRepository.setThumbnailPolicy(
                                                ThumbnailTargetType.FOLDER, id, policy
                                            )
                                        }
                                        selectedIds = emptySet()
                                        selectedFolderIds = emptySet()
                                        refreshKey++
                                    } finally {
                                        selectionBusy = false
                                    }
                                }
                            }
                        )
                        OutlinedButton(
                            enabled = !selectionBusy,
                            onClick = { confirmRemoveSelected = true }
                        ) { Text("Remove previews") }
                    }
                }
                if (generationMessage.isNotBlank()) {
                    Text(
                        generationMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (summary.orphanedCachedFiles > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unlinked cache", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${summary.orphanedCachedFiles} entries · ${Formatters.bytes(summary.orphanedBytes)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(onClick = { confirmClearOrphans = true }) {
                        Text("Clean up")
                    }
                }
            }

            if (folderId != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Folder policy", style = MaterialTheme.typography.labelLarge)
                        Text(
                            if (filePolicy == ThumbnailPolicy.INHERIT) {
                                "Rule: Inherit · Effective: " + policyLabel(
                                    folderPath.lastOrNull()?.effectivePolicy
                                        ?: ThumbnailPolicy.MANUAL_ONLY
                                )
                            } else {
                                "Rule: " + policyLabel(filePolicy)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ThumbnailPolicyMenu(
                        policy = filePolicy,
                        allowInherit = true,
                        onSelect = { policy ->
                            scope.launch {
                                cacheRepository.setThumbnailPolicy(
                                    ThumbnailTargetType.FOLDER,
                                    folderId,
                                    policy
                                )
                                refreshKey++
                            }
                        }
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = cacheRepository.generateFolderThumbnails(folderId)
                                generationMessage = enqueueResultMessage(
                                    result.newlyQueued,
                                    result.alreadyQueued
                                )
                                summary = cacheRepository.thumbnailManagerSummary()
                            }
                        }
                    ) { Text("Generate all missing") }
                    IconButton(onClick = { confirmClearFolderId = folderId }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear folder thumbnails")
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Unfiled thumbnails", style = MaterialTheme.typography.labelLarge)
                        Text(
                            "${Formatters.bytes(summary.unfiledBytes)} · ${summary.unfiledCachedFiles} cached",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = cacheRepository.generateFolderThumbnails(null)
                                generationMessage = enqueueResultMessage(
                                    result.newlyQueued,
                                    result.alreadyQueued
                                )
                                summary = cacheRepository.thumbnailManagerSummary()
                            }
                        }
                    ) { Text("Prepare unfiled") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = cacheRepository.generateAllThumbnails()
                                generationMessage = enqueueResultMessage(
                                    result.newlyQueued,
                                    result.alreadyQueued
                                )
                                summary = cacheRepository.thumbnailManagerSummary()
                            }
                        }
                    ) { Text("Generate all missing") }
                    OutlinedButton(onClick = { confirmClearAll = true }) { Text("Delete all") }
                }
            }

            if (folders.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Folders", style = MaterialTheme.typography.titleMedium)
                    ThumbnailQuickFolderSortDropdown(
                        value = folderSortOrder,
                        onSelect = { folderSortOrder = it }
                    )
                }
                Text(
                    "Folder sorting uses the generated/missing counts shown on each row.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Tick a folder to include it and all its subfolders in bulk actions. Use the folder icon to open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Box(
                    modifier = Modifier.fillMaxWidth().height(280.dp)
                ) {
                    LazyColumn(
                        state = folderListState,
                        modifier = Modifier.fillMaxSize().then(folderDragSelect),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(folders, key = { _, folder -> folder.id }) { _, folder ->
                            ThumbnailFolderRow(
                                folder = folder,
                                selected = folder.id in selectedFolderIds,
                                onSelectionChange = { checked ->
                                    selectedFolderIds = if (checked) selectedFolderIds + folder.id
                                    else selectedFolderIds - folder.id
                                },
                                onOpen = {
                                    folderPath = folderPath + folder
                                    pageOffset = 0
                                },
                                onPolicy = { policy ->
                                    scope.launch {
                                        cacheRepository.setThumbnailPolicy(
                                            ThumbnailTargetType.FOLDER,
                                            folder.id,
                                            policy
                                        )
                                        refreshKey++
                                    }
                                },
                                currentPolicy = folder.policy,
                                onGenerate = {
                                    scope.launch {
                                        val result = cacheRepository.generateFolderThumbnails(folder.id)
                                        generationMessage = enqueueResultMessage(
                                            result.newlyQueued,
                                            result.alreadyQueued
                                        )
                                        summary = cacheRepository.thumbnailManagerSummary()
                                    }
                                },
                                onRemove = { confirmClearFolderId = folder.id }
                            )
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (folderId == null) "Unfiled files" else "Files in this folder",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Page ${pageOffset / THUMBNAIL_PAGE_SIZE + 1} · ${files.size} shown",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Text(
                "Each file checkbox selects that item. The Generated / Not generated filter can be changed above without changing any files.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary
            )

            if (loading) {
                Text("Loading thumbnail inventory…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (files.isEmpty()) {
                Text(
                    "No eligible files in this folder.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                ) {
                    LazyColumn(
                        state = fileListState,
                        modifier = Modifier.fillMaxSize().then(fileDragSelect),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemsIndexed(files, key = { _, file -> file.fileId }) { _, file ->
                            ThumbnailFileRow(
                                file = file,
                                selected = file.fileId in selectedIds,
                                onSelectionChange = { checked ->
                                    selectedIds = if (checked) selectedIds + file.fileId
                                    else selectedIds - file.fileId
                                },
                                onPolicy = { policy ->
                                    scope.launch {
                                        cacheRepository.setThumbnailPolicy(
                                            ThumbnailTargetType.FILE,
                                            file.fileId,
                                            policy
                                        )
                                        refreshKey++
                                    }
                                },
                                onGenerate = {
                                    scope.launch {
                                        val result = cacheRepository.generateThumbnails(listOf(file.fileId))
                                        generationMessage = enqueueResultMessage(
                                            result.newlyQueued,
                                            result.alreadyQueued
                                        )
                                        summary = cacheRepository.thumbnailManagerSummary()
                                    }
                                },
                                onRemove = {
                                    scope.launch {
                                        cacheRepository.removeThumbnail(file.fileId)
                                        selectedIds = selectedIds - file.fileId
                                        refreshKey++
                                    }
                                }
                            )
                        }
                    }
                    DesktopScrollEdgeControls(
                        state = fileListState,
                        modifier = Modifier.align(Alignment.TopEnd).padding(end = 8.dp, top = 2.dp)
                    )
                    DesktopVerticalScrollbar(
                        state = fileListState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = {
                            pageOffset = (pageOffset - THUMBNAIL_PAGE_SIZE).coerceAtLeast(0)
                        },
                        enabled = pageOffset > 0
                    ) { Text("Previous") }
                    Text(
                        "Page ${pageOffset / THUMBNAIL_PAGE_SIZE + 1}",
                        style = MaterialTheme.typography.labelMedium
                    )
                    OutlinedButton(
                        onClick = { pageOffset += THUMBNAIL_PAGE_SIZE },
                        enabled = hasMore
                    ) { Text("Next") }
                }
            }
                }
                DesktopVerticalScrollbar(
                    state = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun SummaryTile(
    title: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThumbnailFolderRow(
    folder: ThumbnailFolderUsage,
    selected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    currentPolicy: ThumbnailPolicy,
    onOpen: () -> Unit,
    onPolicy: (ThumbnailPolicy) -> Unit,
    onGenerate: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = selected, onCheckedChange = onSelectionChange)
        IconButton(onClick = onOpen) {
            Icon(Icons.Filled.Folder, contentDescription = "Open folder", tint = MaterialTheme.colorScheme.primary)
        }
        Column(modifier = Modifier.weight(1f).clickable(onClick = onOpen)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${folder.eligibleFiles} image/video files · ${Formatters.bytes(folder.totalBytes)} cached",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "${folder.generatedFiles} generated",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "${folder.missingFiles} missing",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    "Rule: ${policyLabel(currentPolicy)} · Effective: ${policyLabel(folder.effectivePolicy)}" +
                        if (folder.isTrashed) " · In Trash" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        IconButton(onClick = onGenerate) {
            Icon(Icons.Filled.AutoAwesome, contentDescription = "Generate folder thumbnails")
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete folder thumbnails")
        }
        ThumbnailPolicyMenu(currentPolicy, allowInherit = true, onSelect = onPolicy)
    }
}

@Composable
private fun ThumbnailFileRow(
    file: ThumbnailFileUsage,
    selected: Boolean,
    onSelectionChange: (Boolean) -> Unit,
    onPolicy: (ThumbnailPolicy) -> Unit,
    onGenerate: () -> Unit,
    onRemove: () -> Unit
) {
    var thumbnailRevision by remember(file.fileId) { mutableStateOf(0L) }
    LaunchedEffect(file.fileId) {
        ThumbnailChangeNotifier.events.collect { change ->
            if (change.fileId == file.fileId) thumbnailRevision++
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceContainerLow
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Checkbox(checked = selected, onCheckedChange = onSelectionChange)
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            if (file.isCached) {
                AsyncImage(
                    model = ThumbnailModel(file.fileId, thumbnailRevision),
                    contentDescription = file.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = if (file.category == "VIDEO") Icons.Filled.PlayArrow else Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                (if (file.isCached) {
                    "Generated · ${Formatters.bytes(file.thumbnailSizeBytes ?: 0L)} thumbnail · ${Formatters.bytes(file.fileSizeBytes)} file"
                } else {
                    "Not generated · ${Formatters.bytes(file.fileSizeBytes)} file"
                }) + if (file.isTrashed) " · In Trash" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                "Rule: ${policyLabel(file.policy)} · Effective: ${policyLabel(file.effectivePolicy)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            file.generatedAt?.let { generated ->
                Text(
                    "Generated " + Formatters.dateTime(generated) +
                        (file.lastAccessAt?.let { " · Last viewed " + Formatters.dateTime(it) } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
        ThumbnailPolicyMenu(file.policy, allowInherit = true, onSelect = onPolicy)
        if (file.isCached) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, contentDescription = "Remove thumbnail")
            }
        } else {
            IconButton(onClick = onGenerate) {
                Icon(Icons.Filled.AutoAwesome, contentDescription = "Generate thumbnail")
            }
        }
    }
}

@Composable
private fun ThumbnailQuickSortDropdown(
    value: ThumbnailFileSort,
    onSelect: (ThumbnailFileSort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(40.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text("Sort: ${fileSortLabel(value)}", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThumbnailFileSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(fileSortLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun ThumbnailQuickFolderSortDropdown(
    value: ThumbnailFolderSort,
    onSelect: (ThumbnailFolderSort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(38.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text("Sort folders: ${folderSortLabel(value)}", maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThumbnailFolderSort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(folderSortLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

@Composable
private fun ThumbnailInventoryDropdown(
    title: String,
    description: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == value }?.second ?: value
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedLabel, maxLines = 1)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (option, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        }
                    )
                }
            }
        }
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
    }
}

@Composable
private fun ThumbnailCacheStatusButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    compact: Boolean = false
) {
    OutlinedButton(
        onClick = onClick,
        modifier = if (compact) Modifier.height(40.dp) else Modifier,
        contentPadding = if (compact) {
            PaddingValues(horizontal = 9.dp, vertical = 0.dp)
        } else {
            PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        },
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            contentColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Text(if (selected) "✓ $label" else label, maxLines = 1)
    }
}

@Composable
private fun ThumbnailBulkPolicyMenu(onSelect: (ThumbnailPolicy) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text("Set policy") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(
                ThumbnailPolicy.AUTOMATIC,
                ThumbnailPolicy.MANUAL_ONLY,
                ThumbnailPolicy.INHERIT
            ).forEach { policy ->
                DropdownMenuItem(
                    text = { Text(policyLabel(policy)) },
                    onClick = {
                        expanded = false
                        onSelect(policy)
                    }
                )
            }
        }
    }
}

private fun enqueueResultMessage(newlyQueued: Int, alreadyQueued: Int): String = when {
    newlyQueued == 0 && alreadyQueued == 0 ->
        "No missing eligible thumbnails were found in this selection."
    newlyQueued > 0 && alreadyQueued > 0 ->
        "Added $newlyQueued new jobs and refreshed $alreadyQueued jobs already in the queue."
    newlyQueued > 0 ->
        "Added $newlyQueued missing thumbnail jobs to the queue."
    else ->
        "Refreshed $alreadyQueued jobs already in the queue for manual generation."
}

private fun folderSortLabel(sort: ThumbnailFolderSort): String = when (sort) {
    ThumbnailFolderSort.MOST_MISSING -> "Most missing"
    ThumbnailFolderSort.MOST_GENERATED -> "Most generated"
    ThumbnailFolderSort.MISSING_FIRST -> "Folders with missing"
    ThumbnailFolderSort.GENERATED_FIRST -> "Fully generated first"
    ThumbnailFolderSort.CACHE_LARGEST -> "Largest cache"
    ThumbnailFolderSort.CACHE_SMALLEST -> "Smallest cache"
    ThumbnailFolderSort.NAME_ASC -> "Name A–Z"
    ThumbnailFolderSort.NAME_DESC -> "Name Z–A"
}

private fun cacheFilterLabel(filter: ThumbnailCacheFilter): String = when (filter) {
    ThumbnailCacheFilter.ALL -> "All files"
    ThumbnailCacheFilter.GENERATED -> "Generated"
    ThumbnailCacheFilter.MISSING -> "Not generated"
}

private fun policyFilterLabel(filter: ThumbnailPolicyFilter): String = when (filter) {
    ThumbnailPolicyFilter.ALL -> "All rules"
    ThumbnailPolicyFilter.INHERITED -> "Inherited"
    ThumbnailPolicyFilter.EXPLICIT_AUTOMATIC -> "Explicit Automatic"
    ThumbnailPolicyFilter.EXPLICIT_MANUAL_ONLY -> "Explicit Manual only"
}

private fun effectivePolicyFilterLabel(filter: ThumbnailEffectivePolicyFilter): String = when (filter) {
    ThumbnailEffectivePolicyFilter.ALL -> "All behaviors"
    ThumbnailEffectivePolicyFilter.AUTOMATIC -> "Automatic"
    ThumbnailEffectivePolicyFilter.MANUAL_ONLY -> "Manual only"
}

private fun fileSortLabel(sort: ThumbnailFileSort): String = when (sort) {
    ThumbnailFileSort.THUMBNAIL_LARGEST -> "Largest thumbnail"
    ThumbnailFileSort.THUMBNAIL_SMALLEST -> "Smallest thumbnail"
    ThumbnailFileSort.SOURCE_LARGEST -> "Largest original file"
    ThumbnailFileSort.SOURCE_SMALLEST -> "Smallest original file"
    ThumbnailFileSort.GENERATED_NEWEST -> "Recently generated"
    ThumbnailFileSort.LAST_VIEWED_OLDEST -> "Least recently viewed"
    ThumbnailFileSort.NAME_ASC -> "Name A–Z"
    ThumbnailFileSort.NAME_DESC -> "Name Z–A"
    ThumbnailFileSort.GENERATED_FIRST -> "Generated first"
    ThumbnailFileSort.MISSING_FIRST -> "Not generated first"
}

@Composable
private fun ThumbnailPolicyMenu(
    policy: ThumbnailPolicy,
    allowInherit: Boolean,
    onSelect: (ThumbnailPolicy) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Tune, contentDescription = "Thumbnail policy")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowInherit) {
                DropdownMenuItem(
                    text = { Text("Inherit") },
                    trailingIcon = {
                        if (policy == ThumbnailPolicy.INHERIT) {
                            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelect(ThumbnailPolicy.INHERIT)
                    }
                )
            }
            DropdownMenuItem(
                text = { Text("Automatic") },
                trailingIcon = {
                    if (policy == ThumbnailPolicy.AUTOMATIC) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(ThumbnailPolicy.AUTOMATIC)
                }
            )
            DropdownMenuItem(
                text = { Text("Manual only") },
                trailingIcon = {
                    if (policy == ThumbnailPolicy.MANUAL_ONLY) {
                        Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    }
                },
                onClick = {
                    expanded = false
                    onSelect(ThumbnailPolicy.MANUAL_ONLY)
                }
            )
        }
    }
}

private fun policyLabel(policy: ThumbnailPolicy): String = when (policy) {
    ThumbnailPolicy.INHERIT -> "Inherit from folder"
    ThumbnailPolicy.AUTOMATIC -> "Automatic"
    ThumbnailPolicy.MANUAL_ONLY -> "Manual only"
}
