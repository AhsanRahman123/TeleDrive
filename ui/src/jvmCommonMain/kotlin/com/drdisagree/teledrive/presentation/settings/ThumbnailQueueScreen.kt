package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.drdisagree.teledrive.domain.repository.CacheRepository
import com.drdisagree.teledrive.domain.repository.ThumbnailQueueItem
import com.drdisagree.teledrive.domain.repository.ThumbnailQueueSort
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
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

private const val THUMBNAIL_QUEUE_PAGE_SIZE = 100
private const val MAX_THUMBNAIL_ATTEMPTS = 5

@Composable
fun ThumbnailQueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = koinInject<CacheRepository>()
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    val activeJobs by repository.observeThumbnailActiveJobs().collectAsState(initial = emptySet())
    var items by remember { mutableStateOf(emptyList<ThumbnailQueueItem>()) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var failedQueueIds by remember { mutableStateOf(emptySet<String>()) }
    var offset by remember { mutableStateOf(0) }
    var pendingCount by remember { mutableStateOf(0) }
    var queuePaused by remember { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    var selectingAll by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var sortOrder by remember { mutableStateOf(ThumbnailQueueSort.OLDEST_FIRST) }
    var throughputFilesPerMinute by remember { mutableStateOf(0.0) }

    LaunchedEffect(offset, sortOrder) {
        var previousCount = -1
        var previousFailedCount = 0
        var previousSampleAt = 0L
        throughputFilesPerMinute = 0.0
        while (true) {
            refreshing = true
            val page = repository.thumbnailQueue(offset, THUMBNAIL_QUEUE_PAGE_SIZE, sortOrder.name)
            pendingCount = repository.thumbnailQueueCount()
            failedQueueIds = repository.thumbnailQueueFailedFileIds().toSet()
            queuePaused = repository.thumbnailQueuePaused()
            val remainingWork = (pendingCount - failedQueueIds.size).coerceAtLeast(0)

            val sampleAt = System.currentTimeMillis()
            if (queuePaused) {
                throughputFilesPerMinute = 0.0
            } else if (previousCount >= 0 && previousSampleAt > 0L) {
                val elapsed = (sampleAt - previousSampleAt).coerceAtLeast(1L)
                val removed = previousCount - pendingCount
                if (removed > 0) {
                    val sampleRate = removed * 60_000.0 / elapsed
                    throughputFilesPerMinute = if (throughputFilesPerMinute <= 0.0) {
                        sampleRate
                    } else {
                        throughputFilesPerMinute * 0.65 + sampleRate * 0.35
                    }
                } else if (pendingCount > previousCount || failedQueueIds.size != previousFailedCount) {
                    throughputFilesPerMinute = 0.0
                }
            }
            previousCount = pendingCount
            previousFailedCount = failedQueueIds.size
            previousSampleAt = sampleAt

            if (page.isEmpty() && offset > 0) {
                // The live queue shrank while this page was open. Move to the last valid page,
                // including page zero when the queue has become empty.
                offset = if (pendingCount > 0) {
                    ((pendingCount - 1) / THUMBNAIL_QUEUE_PAGE_SIZE) *
                        THUMBNAIL_QUEUE_PAGE_SIZE
                } else {
                    0
                }
            } else {
                items = page
                if (pendingCount == 0) selectedIds = emptySet()
            }
            refreshing = false
            delay(1_000L)
        }
    }

    val failedSelected = selectedIds.intersect(failedQueueIds)

    if (confirmCancelAll) {
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = { Text("Cancel all thumbnail preparation?") },
            text = {
                Text(
                    "All queued and currently running thumbnail jobs will be removed. " +
                        "Existing thumbnails and original files will remain untouched."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelAll = false
                    scope.launch {
                        repository.cancelAllThumbnailJobs()
                        selectedIds = emptySet()
                        offset = 0
                        queuePaused = false
                    }
                }) { Text("Cancel all jobs") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelAll = false }) { Text("Keep jobs") }
            }
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Thumbnail queue", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "${(pendingCount - activeJobs.size).coerceAtLeast(0)} waiting · " +
                            activeJobs.size + " generating · " + pendingCount + " total" +
                            if (queuePaused) " · Paused" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val remainingWork = (pendingCount - failedQueueIds.size).coerceAtLeast(0)
                    val queueEstimate = when {
                        queuePaused -> "ETA unavailable while paused"
                        remainingWork == 0 && failedQueueIds.isNotEmpty() ->
                            "${failedQueueIds.size} failed jobs need retry · ETA unavailable"
                        remainingWork == 0 -> "All queued jobs completed"
                        throughputFilesPerMinute > 0.0 -> {
                            val etaSeconds = (remainingWork * 60.0 / throughputFilesPerMinute).toLong()
                                .coerceAtLeast(1L)
                            "Recent queue reduction: ${throughputFilesPerMinute.toInt()} files/min · " +
                                "Estimated remaining: ${formatThumbnailQueueEta(etaSeconds)}"
                        }
                        else -> "Estimating completion rate…"
                    }
                    Text(
                        queueEstimate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { scope.launch { scrollState.animateScrollTo(0) } }) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Scroll to top")
                }
                IconButton(onClick = {
                    scope.launch { scrollState.animateScrollTo(scrollState.maxValue) }
                }) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Scroll to bottom")
                }
                IconButton(onClick = {
                    scope.launch {
                        items = repository.thumbnailQueue(offset, THUMBNAIL_QUEUE_PAGE_SIZE, sortOrder.name)
                        pendingCount = repository.thumbnailQueueCount()
                        queuePaused = repository.thumbnailQueuePaused()
                    }
                }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh queue")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Queue sorting", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "Changes the list order only; generation continues in its existing priority order.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                ThumbnailQueueSortDropdown(
                    value = sortOrder,
                    onSelect = { sortOrder = it }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                if (queuePaused) {
                    Text(
                        "Queue paused. Jobs are safely retained and will continue when resumed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (activeJobs.isNotEmpty()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Working on " + activeJobs.size +
                            " item(s) in parallel. Jobs remain queued across app restarts.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (pendingCount > 0) {
                    Text(
                        "No job is currently running. Failed items stay visible here and can be retried.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(selectedIds.size.toString() + " selected", modifier = Modifier.weight(1f))
                    OutlinedButton(
                        enabled = !selectingAll && pendingCount > 0,
                        onClick = {
                            scope.launch {
                                selectingAll = true
                                try {
                                    selectedIds = repository.thumbnailQueueAllFileIds().toSet()
                                } finally {
                                    selectingAll = false
                                }
                            }
                        }
                    ) { Text(if (selectingAll) "Selecting…" else "Select all") }
                    TextButton(
                        enabled = items.isNotEmpty(),
                        onClick = {
                            val pageIds = items.map { it.fileId }.toSet()
                            selectedIds = selectedIds + pageIds
                        }
                    ) { Text("Select visible page") }
                    TextButton(
                        enabled = selectedIds.isNotEmpty(),
                        onClick = { selectedIds = emptySet() }
                    ) { Text("Clear selection") }
                }

                Text(
                    "Select visible page selects the rows shown here. Select all selects every job in the queue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        enabled = pendingCount > 0 || queuePaused,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                if (queuePaused) repository.resumeAllThumbnailJobs()
                                else repository.pauseAllThumbnailJobs()
                                queuePaused = repository.thumbnailQueuePaused()
                            }
                        }
                    ) { Text(if (queuePaused) "Resume all" else "Pause all") }
                    OutlinedButton(
                        enabled = pendingCount > 0,
                        modifier = Modifier.weight(1f),
                        onClick = { confirmCancelAll = true }
                    ) { Text("Cancel all jobs") }
                }

                Text(
                    "Pause keeps jobs for later. Cancel removes queue entries only; original files and cached previews are not deleted.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )

                if (selectedIds.isNotEmpty()) {
                    Text("Actions for all " + selectedIds.size + " selected jobs", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val ids = selectedIds.toList()
                            scope.launch { repository.pauseThumbnailJobs(ids) }
                        }) { Text("Pause selected") }
                        OutlinedButton(onClick = {
                            val ids = selectedIds.toList()
                            scope.launch { repository.resumeThumbnailJobs(ids) }
                        }) { Text("Resume selected") }
                        OutlinedButton(onClick = {
                            val ids = selectedIds.toList()
                            scope.launch {
                                repository.cancelThumbnailJobs(ids)
                                selectedIds = emptySet()
                            }
                        }) { Text("Cancel selected") }
                        if (failedSelected.isNotEmpty()) {
                            OutlinedButton(onClick = {
                                val ids = failedSelected.toList()
                                scope.launch {
                                    repository.retryThumbnailJobs(ids)
                                    selectedIds = selectedIds - ids.toSet()
                                }
                            }) { Text("Retry failed") }
                        }
                    }
                }

                if (items.isEmpty()) {
                    Text(
                        if (refreshing) "Loading queue…" else "The thumbnail queue is empty.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    items.forEach { item ->
                        ThumbnailQueueRow(
                            item = item,
                            selected = item.fileId in selectedIds,
                            active = item.fileId in activeJobs,
                            globallyPaused = queuePaused,
                            onSelect = { checked ->
                                selectedIds = if (checked) selectedIds + item.fileId
                                else selectedIds - item.fileId
                            },
                            onPause = {
                                scope.launch { repository.pauseThumbnailJobs(listOf(item.fileId)) }
                            },
                            onResume = {
                                scope.launch { repository.resumeThumbnailJobs(listOf(item.fileId)) }
                            },
                            onCancel = {
                                scope.launch {
                                    repository.cancelThumbnailJobs(listOf(item.fileId))
                                    selectedIds = selectedIds - item.fileId
                                }
                            },
                            onRetry = {
                                scope.launch { repository.retryThumbnailJobs(listOf(item.fileId)) }
                            }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Showing " + (if (items.isEmpty()) 0 else offset + 1) +
                            "–" + (offset + items.size)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            enabled = offset > 0,
                            onClick = { offset = (offset - THUMBNAIL_QUEUE_PAGE_SIZE).coerceAtLeast(0) }
                        ) { Text("Previous") }
                        OutlinedButton(
                            enabled = items.size == THUMBNAIL_QUEUE_PAGE_SIZE,
                            onClick = { offset += THUMBNAIL_QUEUE_PAGE_SIZE }
                        ) { Text("Next") }
                    }
                }
                }
                DesktopVerticalScrollbar(
                    state = scrollState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(end = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun ThumbnailQueueSortDropdown(
    value: ThumbnailQueueSort,
    onSelect: (ThumbnailQueueSort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (value) {
        ThumbnailQueueSort.OLDEST_FIRST -> "Oldest first"
        ThumbnailQueueSort.NEWEST_FIRST -> "Newest first"
        ThumbnailQueueSort.NAME_ASC -> "Name A–Z"
        ThumbnailQueueSort.NAME_DESC -> "Name Z–A"
        ThumbnailQueueSort.SOURCE_LARGEST -> "Largest file"
        ThumbnailQueueSort.SOURCE_SMALLEST -> "Smallest file"
        ThumbnailQueueSort.FAILED_FIRST -> "Failed first"
    }
    Box {
        androidx.compose.material3.OutlinedButton(onClick = { expanded = true }) {
            Text("Sort: $label")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ThumbnailQueueSort.entries.forEach { option ->
                val optionLabel = when (option) {
                    ThumbnailQueueSort.OLDEST_FIRST -> "Oldest first"
                    ThumbnailQueueSort.NEWEST_FIRST -> "Newest first"
                    ThumbnailQueueSort.NAME_ASC -> "Name A–Z"
                    ThumbnailQueueSort.NAME_DESC -> "Name Z–A"
                    ThumbnailQueueSort.SOURCE_LARGEST -> "Largest file"
                    ThumbnailQueueSort.SOURCE_SMALLEST -> "Smallest file"
                    ThumbnailQueueSort.FAILED_FIRST -> "Failed first"
                }
                DropdownMenuItem(
                    text = { Text(optionLabel) },
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
private fun ThumbnailQueueRow(
    item: ThumbnailQueueItem,
    selected: Boolean,
    active: Boolean,
    globallyPaused: Boolean,
    onSelect: (Boolean) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer
        else MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(checked = selected, onCheckedChange = onSelect)
            Column(modifier = Modifier.weight(1f)) {
                Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                Text(
                    Formatters.bytes(item.fileSizeBytes) + " · " +
                        item.mimeType.ifBlank { "Unknown type" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    item.folderPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
                Text(
                    when {
                        globallyPaused -> "Paused · queue paused"
                        item.isPaused -> "Paused"
                        active -> "Generating now"
                        item.attempts >= MAX_THUMBNAIL_ATTEMPTS ->
                            "Failed after " + item.attempts + " attempts"
                        item.attempts > 0 -> "Waiting to retry · attempt " + item.attempts
                        else -> "Waiting"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "Queued " + Formatters.dateTime(item.queuedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.attempts >= MAX_THUMBNAIL_ATTEMPTS) {
                TextButton(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onCancel) { Text("Remove") }
            } else if (item.isPaused && !globallyPaused) {
                TextButton(onClick = onResume) { Text("Resume") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else if (!globallyPaused) {
                TextButton(onClick = onPause) { Text("Pause") }
                TextButton(onClick = onCancel) { Text("Cancel") }
            } else {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
