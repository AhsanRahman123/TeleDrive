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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import com.drdisagree.teledrive.domain.repository.ThumbnailTargetType
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.koinInject

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
    var folderPath by remember { mutableStateOf(emptyList<ThumbnailFolderUsage>()) }
    var folders by remember { mutableStateOf(emptyList<ThumbnailFolderUsage>()) }
    var files by remember { mutableStateOf(emptyList<ThumbnailFileUsage>()) }
    var filePolicy by remember { mutableStateOf(ThumbnailPolicy.INHERIT) }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var loading by remember { mutableStateOf(true) }
    var hasMore by remember { mutableStateOf(false) }
    var pageOffset by remember { mutableStateOf(0) }
    var refreshKey by remember { mutableStateOf(0) }
    var confirmClearFolderId by remember { mutableStateOf<String?>(null) }
    var confirmClearOrphans by remember { mutableStateOf(false) }
    var confirmClearAll by remember { mutableStateOf(false) }
    var confirmRemoveSelected by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    var rangeSelectionBase by remember { mutableStateOf(emptySet<String>()) }
    val fileListState = rememberLazyListState()

    val folderId = folderPath.lastOrNull()?.id
    val fileDragSelect = rememberDragSelect(
        listState = fileListState,
        onStart = { rangeSelectionBase = selectedIds },
        onRange = { range ->
            selectedIds = rangeSelectionBase + range.mapNotNull { files.getOrNull(it)?.fileId }
        },
        onEnd = { rangeSelectionBase = emptySet() }
    )
    LaunchedEffect(folderId) { selectedIds = emptySet() }
    LaunchedEffect(folderId, refreshKey, pageOffset) {
        loading = true
        summary = cacheRepository.thumbnailManagerSummary()
        folders = cacheRepository.thumbnailFolders(folderId)
        val page = cacheRepository.thumbnailFiles(
            folderId,
            pageOffset,
            THUMBNAIL_PAGE_SIZE + 1
        )
        files = page.take(THUMBNAIL_PAGE_SIZE)
        hasMore = page.size > THUMBNAIL_PAGE_SIZE
        filePolicy = if (folderId == null) {
            ThumbnailPolicy.INHERIT
        } else {
            cacheRepository.thumbnailPolicy(ThumbnailTargetType.FOLDER, folderId)
        }
        loading = false
    }

    // Update totals and folder aggregates without resetting the current file page or selection.
    LaunchedEffect(folderId) {
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
                folders = cacheRepository.thumbnailFolders(folderId)
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
                    "Cached previews in this folder and its subfolders will be deleted. " +
                        "Original files will not be touched, and the folder will switch to Manual only."
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
                    "Every thumbnail preview and queued preparation will be removed from this device. " +
                        "Your original files and Telegram backups will remain untouched."
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
            title = { Text("Delete selected thumbnails?") },
            text = { Text("The selected cached previews and any matching queued jobs will be removed. Original files will not be affected.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveSelected = false
                    val ids = selectedIds.toList()
                    scope.launch {
                        ids.forEach { cacheRepository.removeThumbnail(it) }
                        selectedIds = emptySet()
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 10.dp)
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        if (folderPath.isEmpty()) onBack()
                        else {
                            folderPath = folderPath.dropLast(1)
                            pageOffset = 0
                        }
                    }
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Thumbnail manager", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (folderPath.isEmpty()) "Storage overview"
                        else folderPath.joinToString(" / ") { it.name },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                OutlinedButton(onClick = { showQueue = true }) { Text("View queue") }
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                }
            }

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
                    subtitle = "pending preparations",
                    modifier = Modifier.weight(1f)
                )
            }

            Text(
                "Existing library files are Manual only by default. New successful uploads are " +
                    "automatically prepared. Choose individual files or folders to prepare older previews.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

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
                            policyLabel(filePolicy),
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
                                cacheRepository.generateFolderThumbnails(folderId)
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
                                cacheRepository.generateFolderThumbnails(null)
                                summary = cacheRepository.thumbnailManagerSummary()
                            }
                        }
                    ) { Text("Prepare unfiled") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                cacheRepository.generateAllThumbnails()
                                summary = cacheRepository.thumbnailManagerSummary()
                            }
                        }
                    ) { Text("Generate all missing") }
                    OutlinedButton(onClick = { confirmClearAll = true }) { Text("Delete all") }
                }
            }

            if (folders.isNotEmpty()) {
                Text("Folders", style = MaterialTheme.typography.titleMedium)
                folders.forEach { folder ->
                    ThumbnailFolderRow(
                        folder = folder,
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
                                cacheRepository.generateFolderThumbnails(folder.id)
                                summary = cacheRepository.thumbnailManagerSummary()
                            }
                        },
                        onRemove = { confirmClearFolderId = folder.id }
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
                        "Page ${pageOffset / THUMBNAIL_PAGE_SIZE + 1} · ${files.size} files · largest cached previews first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (files.isNotEmpty()) {
                    TextButton(onClick = {
                        val pageIds = files.map { it.fileId }.toSet()
                        selectedIds = if (pageIds.all { it in selectedIds }) selectedIds - pageIds
                        else selectedIds + pageIds
                    }) {
                        Text(if (files.isNotEmpty() && files.all { it.fileId in selectedIds }) "Deselect page" else "Select page")
                    }
                }
            }

            if (selectedIds.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${selectedIds.size} selected",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                cacheRepository.generateThumbnails(selectedIds.toList())
                                summary = cacheRepository.thumbnailManagerSummary()
                            }
                        }
                    ) { Text("Generate") }
                    OutlinedButton(onClick = { confirmRemoveSelected = true }) { Text("Delete selected") }
                    TextButton(onClick = { selectedIds = emptySet() }) { Text("Deselect") }
                }
            }

            if (loading) {
                Text("Loading thumbnail inventory…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (files.isEmpty()) {
                Text(
                    "No eligible files in this folder.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(
                    state = fileListState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(440.dp)
                        .then(fileDragSelect),
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
                                    cacheRepository.generateThumbnail(file.fileId)
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
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                "${Formatters.bytes(folder.totalBytes)} · ${folder.cachedFiles} cached · direct ${Formatters.bytes(folder.directBytes)} · ${policyLabel(currentPolicy)}" +
                    if (folder.isTrashed) " · In Trash" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
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
                    "${Formatters.bytes(file.thumbnailSizeBytes ?: 0L)} thumbnail · ${Formatters.bytes(file.fileSizeBytes)} file"
                } else {
                    "Not cached · ${Formatters.bytes(file.fileSizeBytes)} file"
                }) + if (file.isTrashed) " · In Trash" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            Text(
                policyLabel(file.policy),
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
