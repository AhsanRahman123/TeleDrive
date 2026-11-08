package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import com.drdisagree.teledrive.presentation.common.Formatters
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

private const val THUMBNAIL_QUEUE_PAGE_SIZE = 100
private const val MAX_THUMBNAIL_ATTEMPTS = 3

@Composable
fun ThumbnailQueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = koinInject<CacheRepository>()
    val scope = rememberCoroutineScope()
    val activeJobs by repository.observeThumbnailActiveJobs().collectAsState(initial = emptySet())
    var items by remember { mutableStateOf(emptyList<ThumbnailQueueItem>()) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var offset by remember { mutableStateOf(0) }
    var pendingCount by remember { mutableStateOf(0) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }

    LaunchedEffect(offset) {
        while (true) {
            refreshing = true
            items = repository.thumbnailQueue(offset, THUMBNAIL_QUEUE_PAGE_SIZE)
            pendingCount = repository.thumbnailManagerSummary().pendingJobs
            selectedIds = selectedIds.intersect(items.map { it.fileId }.toSet())
            refreshing = false
            delay(1_000L)
        }
    }

    val failedSelected = items.filter {
        it.fileId in selectedIds && it.attempts >= MAX_THUMBNAIL_ATTEMPTS
    }.map { it.fileId }

    if (confirmCancelAll) {
        AlertDialog(
            onDismissRequest = { confirmCancelAll = false },
            title = { Text("Cancel all thumbnail preparation?") },
            text = { Text("All queued and currently running thumbnail jobs will be stopped. Existing thumbnails will remain untouched.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmCancelAll = false
                    scope.launch {
                        repository.cancelAllThumbnailJobs()
                        selectedIds = emptySet()
                        offset = 0
                    }
                }) { Text("Cancel all jobs") }
            },
            dismissButton = {
                TextButton(onClick = { confirmCancelAll = false }) { Text("Keep running") }
            }
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Thumbnail queue", style = MaterialTheme.typography.titleLarge)
                Text(
                    pendingCount.toString() + " queued · " + activeJobs.size + " generating",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = {
                scope.launch {
                    items = repository.thumbnailQueue(offset, THUMBNAIL_QUEUE_PAGE_SIZE)
                    pendingCount = repository.thumbnailManagerSummary().pendingJobs
                }
            }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh queue")
            }
        }

        if (activeJobs.isNotEmpty()) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Working on " + activeJobs.size + " item(s) in parallel. Jobs remain queued across app restarts.",
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
            TextButton(onClick = {
                selectedIds = if (selectedIds.size == items.size) emptySet()
                else items.map { it.fileId }.toSet()
            }) {
                Text(if (items.isNotEmpty() && selectedIds.size == items.size) "Deselect page" else "Select page")
            }
        }

        if (selectedIds.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val ids = selectedIds.toList()
                    scope.launch {
                        repository.cancelThumbnailJobs(ids)
                        selectedIds = emptySet()
                    }
                }) { Text("Cancel selected") }
                if (failedSelected.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        val ids = failedSelected
                        scope.launch {
                            repository.retryThumbnailJobs(ids)
                            selectedIds = emptySet()
                        }
                    }) { Text("Retry failed") }
                }
            }
        }

        if (pendingCount > 0) {
            OutlinedButton(
                onClick = { confirmCancelAll = true },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel all jobs") }
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
                    onSelect = { checked ->
                        selectedIds = if (checked) selectedIds + item.fileId
                        else selectedIds - item.fileId
                    },
                    onCancel = {
                        scope.launch { repository.cancelThumbnailJobs(listOf(item.fileId)) }
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
            Text("Showing " + (if (items.isEmpty()) 0 else offset + 1) + "–" + (offset + items.size))
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
}

@Composable
private fun ThumbnailQueueRow(
    item: ThumbnailQueueItem,
    selected: Boolean,
    active: Boolean,
    onSelect: (Boolean) -> Unit,
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
                    Formatters.bytes(item.fileSizeBytes) + " · " + item.mimeType.ifBlank { "Unknown type" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    when {
                        active -> "Generating now"
                        item.attempts >= MAX_THUMBNAIL_ATTEMPTS -> "Failed after " + item.attempts + " attempts"
                        item.attempts > 0 -> "Waiting to retry · attempt " + item.attempts
                        else -> "Waiting"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (item.attempts >= MAX_THUMBNAIL_ATTEMPTS) {
                TextButton(onClick = onRetry) { Text("Retry") }
            } else {
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}
