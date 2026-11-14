package com.drdisagree.teledrive.presentation.files

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.DriveFolder
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.repository.FileRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import org.koin.compose.koinInject

private data class EmptyItemEntry(
    val id: String,
    val name: String,
    val path: String,
    val isFolder: Boolean
) {
    val key: String get() = (if (isFolder) "folder:" else "file:") + id
}

private data class PendingEmptyScanFolder(
    val id: String?,
    val path: String,
    val folder: DriveFolder?
)

@Composable
fun EmptyItemsScreen(
    folderId: String?,
    onBack: () -> Unit,
    onMoveSelected: (List<String>, List<String>) -> Unit,
    onCopySelected: (List<String>, List<String>) -> Unit,
    onDeleteSelected: (List<String>, List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val repository = koinInject<FileRepository>()
    var includeSubfolders by remember(folderId) { mutableStateOf(true) }
    var refreshToken by remember(folderId) { mutableIntStateOf(0) }
    var results by remember(folderId) { mutableStateOf(emptyList<EmptyItemEntry>()) }
    var selectedKeys by remember(folderId) { mutableStateOf(emptySet<String>()) }
    var search by remember(folderId) { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scannedFolders by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var scopeName by remember(folderId) { mutableStateOf("Files") }

    LaunchedEffect(folderId, includeSubfolders, refreshToken) {
        scanning = true
        scannedFolders = 0
        error = null
        selectedKeys = emptySet()
        try {
            scopeName = folderId?.let { repository.getFolder(it)?.name } ?: "Files"
            results = scanEmptyItems(
                repository = repository,
                rootFolderId = folderId,
                includeSubfolders = includeSubfolders,
                onProgress = { scannedFolders = it }
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            error = failure.message?.takeIf(String::isNotBlank)
                ?: "The empty-item scan could not be completed."
            results = emptyList()
        } finally {
            scanning = false
        }
    }

    val matchingResults = remember(results, search) {
        val needle = search.trim().lowercase()
        if (needle.isBlank()) results
        else results.filter {
            it.name.lowercase().contains(needle) || it.path.lowercase().contains(needle)
        }
    }
    val selectedItems = remember(results, selectedKeys) {
        results.filter { it.key in selectedKeys }
    }
    val selectedFileIds = selectedItems.filterNot { it.isFolder }.map { it.id }
    val selectedFolderIds = selectedItems.filter { it.isFolder }.map { it.id }
    val emptyFileCount = results.count { !it.isFolder }
    val emptyFolderCount = results.count { it.isFolder }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("Empty items", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Searching in " + scopeName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { refreshToken++ }, enabled = !scanning) {
                Icon(Icons.Filled.Refresh, contentDescription = "Scan again")
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    "Find zero-byte files and folders with no files or subfolders.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Hidden and archived items are included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = includeSubfolders,
                        onCheckedChange = { includeSubfolders = it },
                        enabled = !scanning
                    )
                    Text("Include all subfolders")
                }
            }
        }

        if (scanning) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                "Scanning folders… " + scannedFolders + " checked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (error != null) {
            Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
        } else if (!scanning) {
            Text(
                emptyFileCount.toString() + " empty files · " +
                    emptyFolderCount + " empty folders · " +
                    selectedItems.size + " selected",
                style = MaterialTheme.typography.titleSmall
            )
        }

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search empty items") }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                enabled = matchingResults.isNotEmpty() && !scanning,
                onClick = { selectedKeys = matchingResults.map { it.key }.toSet() }
            ) {
                Icon(Icons.Filled.SelectAll, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Select all")
            }
            TextButton(
                enabled = selectedKeys.isNotEmpty(),
                onClick = { selectedKeys = emptySet() }
            ) { Text("Clear") }
            Spacer(modifier = Modifier.weight(1f))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                enabled = selectedItems.isNotEmpty(),
                onClick = { onMoveSelected(selectedFileIds, selectedFolderIds) }
            ) { Text("Move") }
            OutlinedButton(
                enabled = selectedItems.isNotEmpty(),
                onClick = { onCopySelected(selectedFileIds, selectedFolderIds) }
            ) { Text("Copy") }
            Button(
                enabled = selectedItems.isNotEmpty(),
                onClick = { onDeleteSelected(selectedFileIds, selectedFolderIds) }
            ) {
                Icon(Icons.Filled.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Move to Trash")
            }
        }

        when {
            scanning && results.isEmpty() -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            !scanning && matchingResults.isEmpty() -> {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            error != null -> "Scan failed."
                            results.isEmpty() -> "No empty files or folders were found."
                            else -> "No empty items match this search."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(matchingResults, key = { it.key }) { item ->
                        val selected = item.key in selectedKeys
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                                )
                                .clickable {
                                    selectedKeys = if (selected) selectedKeys - item.key
                                    else selectedKeys + item.key
                                }
                                .padding(horizontal = 10.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    selectedKeys = if (checked) selectedKeys + item.key
                                    else selectedKeys - item.key
                                }
                            )
                            Icon(
                                imageVector = if (item.isFolder) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp).size(22.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    (if (item.isFolder) "Empty folder" else "Zero-byte file") +
                                        " · " + item.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private suspend fun scanEmptyItems(
    repository: FileRepository,
    rootFolderId: String?,
    includeSubfolders: Boolean,
    onProgress: (Int) -> Unit
): List<EmptyItemEntry> {
    suspend fun filesIn(folderId: String?): List<DriveFile> =
        repository.observeFiles(
            FileQuerySpec(
                folderId = folderId,
                filterByFolder = true,
                showHidden = true,
                showArchived = true
            )
        ).first()

    suspend fun foldersIn(folderId: String?): List<DriveFolder> =
        repository.observeFolders(
            parentId = folderId,
            showHidden = true,
            showArchived = true
        ).first()

    val rootName = rootFolderId?.let { repository.getFolder(it)?.name } ?: "Files"
    val results = mutableListOf<EmptyItemEntry>()

    if (includeSubfolders) {
        val pending = mutableListOf(
            PendingEmptyScanFolder(id = rootFolderId, path = rootName, folder = null)
        )
        val visited = mutableSetOf<String>()
        var cursor = 0
        var scanned = 0

        while (cursor < pending.size) {
            val current = pending[cursor++]
            if (current.id != null && !visited.add(current.id)) continue

            val files = filesIn(current.id)
            val childFolders = foldersIn(current.id)

            files.asSequence().filter { it.sizeBytes == 0L }.forEach { file ->
                results += EmptyItemEntry(
                    id = file.id,
                    name = file.name,
                    path = current.path,
                    isFolder = false
                )
            }

            val currentFolder = current.folder
            if (currentFolder != null && files.isEmpty() && childFolders.isEmpty()) {
                results += EmptyItemEntry(
                    id = currentFolder.id,
                    name = currentFolder.name,
                    path = current.path,
                    isFolder = true
                )
            }

            scanned++
            onProgress(scanned)
            childFolders.forEach { child ->
                pending += PendingEmptyScanFolder(
                    id = child.id,
                    path = current.path + " / " + child.name,
                    folder = child
                )
            }
        }
    } else {
        val files = filesIn(rootFolderId)
        files.filter { it.sizeBytes == 0L }.forEach { file ->
            results += EmptyItemEntry(
                id = file.id,
                name = file.name,
                path = rootName,
                isFolder = false
            )
        }

        val childFolders = foldersIn(rootFolderId)
        childFolders.forEach { child ->
            val childFiles = filesIn(child.id)
            val nestedFolders = foldersIn(child.id)
            if (childFiles.isEmpty() && nestedFolders.isEmpty()) {
                results += EmptyItemEntry(
                    id = child.id,
                    name = child.name,
                    path = rootName + " / " + child.name,
                    isFolder = true
                )
            }
        }
        onProgress(1 + childFolders.size)
    }

    return results.sortedWith(
        compareBy<EmptyItemEntry>(
            { if (it.isFolder) 0 else 1 },
            { it.path.lowercase() },
            { it.name.lowercase() }
        )
    )
}
