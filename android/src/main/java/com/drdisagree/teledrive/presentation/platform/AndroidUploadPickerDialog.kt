package com.drdisagree.teledrive.presentation.platform

import android.content.Context
import android.os.Build
import android.os.Environment
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.view.View
import android.view.ViewParent
import android.view.Window
import android.os.storage.StorageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.picker_folder_mode_title
import com.drdisagree.teledrive.resources.picker_files_mode_title
import com.drdisagree.teledrive.resources.picker_storage_internal
import com.drdisagree.teledrive.resources.picker_search_files
import com.drdisagree.teledrive.resources.picker_show_hidden
import com.drdisagree.teledrive.resources.picker_select_all
import com.drdisagree.teledrive.resources.common_clear_selection
import com.drdisagree.teledrive.resources.picker_sort
import com.drdisagree.teledrive.resources.picker_sort_name
import com.drdisagree.teledrive.resources.picker_sort_size
import com.drdisagree.teledrive.resources.picker_sort_modified
import com.drdisagree.teledrive.resources.picker_sort_type
import com.drdisagree.teledrive.resources.picker_filter_all
import com.drdisagree.teledrive.resources.picker_filter_files
import com.drdisagree.teledrive.resources.picker_filter_folders
import com.drdisagree.teledrive.resources.picker_filter_images
import com.drdisagree.teledrive.resources.picker_filter_videos
import com.drdisagree.teledrive.resources.picker_filter_audio
import com.drdisagree.teledrive.resources.picker_filter_documents
import com.drdisagree.teledrive.resources.picker_filter_archives
import com.drdisagree.teledrive.resources.picker_filter_other
import com.drdisagree.teledrive.resources.picker_selected_count
import com.drdisagree.teledrive.resources.picker_choose_files
import com.drdisagree.teledrive.resources.picker_select_current_folder
import com.drdisagree.teledrive.resources.picker_no_items
import com.drdisagree.teledrive.resources.picker_access_required
import com.drdisagree.teledrive.resources.picker_access_summary
import com.drdisagree.teledrive.resources.picker_grant_access
import com.drdisagree.teledrive.resources.picker_use_android_picker
import com.drdisagree.teledrive.resources.common_cancel
import org.jetbrains.compose.resources.stringResource
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class UploadStorageRoot(
    val name: String,
    val path: String,
    val removable: Boolean
)

private enum class UploadPickerSort {
    NAME, SIZE, MODIFIED, TYPE
}

private enum class UploadPickerFilter {
    ALL, FILES, FOLDERS, IMAGES, VIDEOS, AUDIO, DOCUMENTS, ARCHIVES, OTHER
}

private data class UploadPickerEntry(
    val file: File,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long
)

@Composable
fun AndroidUploadPickerDialog(
    context: Context,
    folderMode: Boolean,
    preferredRefreshRate: Float?,
    showHiddenFiles: Boolean,
    onShowHiddenFilesChange: (Boolean) -> Unit,
    accessRefreshToken: Int,
    onRequestStorageAccess: () -> Unit,
    onUseAndroidPicker: () -> Unit,
    onFilesSelected: (List<String>) -> Unit,
    onFolderSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshToken by remember { mutableIntStateOf(0) }
    var currentPath by remember { mutableStateOf<String?>(null) }
    var entries by remember { mutableStateOf(emptyList<UploadPickerEntry>()) }
    var loading by remember { mutableStateOf(false) }
    var unreadable by remember { mutableStateOf(false) }
    var search by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(UploadPickerSort.NAME) }
    var descending by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(UploadPickerFilter.ALL) }
    var sortExpanded by remember { mutableStateOf(false) }
    var filterExpanded by remember { mutableStateOf(false) }
    var gridView by remember { mutableStateOf(false) }
    var selectedPaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    val roots = remember(context, refreshToken, accessRefreshToken) {
        discoverUploadStorageRoots(context)
    }
    val currentRoot = roots.firstOrNull { root ->
        currentPath == root.path || currentPath?.startsWith(root.path.trimEnd('/') + "/") == true
    }
    val hasStorageAccess = remember(context, refreshToken, accessRefreshToken) {
        canBrowseAllFiles(context)
    }
    val parentPath = remember(currentPath, currentRoot) {
        val path = currentPath ?: return@remember null
        val root = currentRoot?.path ?: return@remember null
        if (path == root) null else File(path).parentFile?.absolutePath?.takeIf {
            it == root || it.startsWith(root.trimEnd('/') + "/")
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshToken++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(roots, hasStorageAccess) {
        val path = currentPath
        val stillOnMountedVolume = path != null && roots.any { root ->
            path == root.path || path.startsWith(root.path.trimEnd('/') + "/")
        }
        if (!stillOnMountedVolume) {
            currentPath = roots.firstOrNull()?.path
            selectedPaths = emptySet()
            search = ""
        }
    }

    LaunchedEffect(currentPath, showHiddenFiles, search, sort, descending, filter, refreshToken, hasStorageAccess) {
        val path = currentPath ?: run {
            entries = emptyList()
            unreadable = false
            loading = false
            return@LaunchedEffect
        }
        if (!hasStorageAccess) {
            entries = emptyList()
            unreadable = false
            loading = false
            return@LaunchedEffect
        }
        loading = true
        unreadable = false
        val loadResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val directory = File(path)
                require(directory.isDirectory && directory.canRead()) { "This folder cannot be read." }
                directory.listFiles().orEmpty()
                    .asSequence()
                    .filter { file -> showHiddenFiles || !(file.name.startsWith(".") || file.isHidden) }
                    .filter { file ->
                        search.isBlank() || file.name.contains(search.trim(), ignoreCase = true)
                    }
                    .map { file ->
                        UploadPickerEntry(
                            file = file,
                            isDirectory = file.isDirectory,
                            size = if (file.isFile) file.length().coerceAtLeast(0L) else 0L,
                            modified = file.lastModified()
                        )
                    }
                    .filter { entry -> matchesUploadPickerFilter(entry, filter) }
                    .toList()
                    .sortedWith(uploadPickerComparator(sort, descending))
            }.fold(
                onSuccess = { files -> files to false },
                onFailure = { emptyList<UploadPickerEntry>() to true }
            )
        }
        entries = loadResult.first
        unreadable = loadResult.second
        loading = false
    }

    val allVisibleSelected = entries.isNotEmpty() && entries.all { it.file.absolutePath in selectedPaths }

    fun toggleEntry(entry: UploadPickerEntry) {
        val path = entry.file.absolutePath
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    fun navigateBack() {
        if (parentPath != null) {
            currentPath = parentPath
            selectedPaths = emptySet()
            search = ""
        } else {
            onDismiss()
        }
    }

    BackHandler(enabled = true) { navigateBack() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        ApplyPreferredRefreshRateToDialogWindow(preferredRefreshRate)
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { navigateBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = if (parentPath == null) "Close" else "Up"
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(if (folderMode) Res.string.picker_folder_mode_title else Res.string.picker_files_mode_title),
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            currentPath ?: stringResource(Res.string.picker_storage_internal),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }

                if (roots.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        roots.forEach { root ->
                            FilterChip(
                                selected = currentRoot?.path == root.path,
                                onClick = {
                                    currentPath = root.path
                                    selectedPaths = emptySet()
                                    search = ""
                                },
                                label = { Text(root.name, maxLines = 1) },
                                leadingIcon = {
                                    Icon(
                                        if (root.removable) Icons.Filled.Storage else Icons.Filled.Folder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }

                if (!hasStorageAccess) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                stringResource(Res.string.picker_access_required),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                stringResource(Res.string.picker_access_summary),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onRequestStorageAccess, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.picker_grant_access))
                            }
                            OutlinedButton(onClick = onUseAndroidPicker, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.picker_use_android_picker))
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                        singleLine = true,
                        placeholder = { Text(stringResource(Res.string.picker_search_files)) },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = showHiddenFiles,
                            onClick = { onShowHiddenFilesChange(!showHiddenFiles) },
                            label = { Text(stringResource(Res.string.picker_show_hidden)) }
                        )
                        Box {
                            TextButton(onClick = { filterExpanded = true }) {
                                Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(filterLabel(filter))
                            }
                            DropdownMenu(
                                expanded = filterExpanded,
                                onDismissRequest = { filterExpanded = false }
                            ) {
                                UploadPickerFilter.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(filterLabel(option)) },
                                        onClick = { filter = option; filterExpanded = false }
                                    )
                                }
                            }
                        }
                        Box {
                            TextButton(onClick = { sortExpanded = true }) {
                                Text(stringResource(Res.string.picker_sort) + ": " + sortLabel(sort))
                            }
                            DropdownMenu(
                                expanded = sortExpanded,
                                onDismissRequest = { sortExpanded = false }
                            ) {
                                UploadPickerSort.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(sortLabel(option)) },
                                        onClick = { sort = option; sortExpanded = false }
                                    )
                                }
                            }
                        }
                        TextButton(onClick = { descending = !descending }) {
                            Text(if (descending) "↓" else "↑")
                        }
                        IconButton(onClick = { gridView = !gridView }) {
                            Icon(
                                if (gridView) Icons.Filled.List else Icons.Filled.GridView,
                                contentDescription = if (gridView) "List view" else "Grid view"
                            )
                        }
                    }

                    if (!folderMode) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                stringResource(Res.string.picker_selected_count, selectedPaths.size),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = {
                                selectedPaths = if (allVisibleSelected) {
                                    selectedPaths - entries.map { it.file.absolutePath }.toSet()
                                } else {
                                    selectedPaths + entries.map { it.file.absolutePath }
                                }
                            }) {
                                Icon(Icons.Filled.Checklist, contentDescription = null, modifier = Modifier.size(17.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (allVisibleSelected) stringResource(Res.string.common_clear_selection) else stringResource(Res.string.picker_select_all))
                            }
                        }
                    }

                    when {
                        loading -> Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) { CircularProgressIndicator() }
                        unreadable -> Box(
                            modifier = Modifier.fillMaxWidth().weight(1f).padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("This location cannot be read. Try another storage location or use the Android picker.")
                        }
                        entries.isEmpty() -> Box(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            contentAlignment = Alignment.Center
                        ) { Text(stringResource(Res.string.picker_no_items), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        gridView -> LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 156.dp),
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(entries, key = { it.file.absolutePath }) { entry ->
                                UploadPickerGridItem(
                                    entry = entry,
                                    selected = entry.file.absolutePath in selectedPaths,
                                    showSelection = !folderMode,
                                    onClick = {
                                        if (entry.isDirectory) {
                                            currentPath = entry.file.absolutePath
                                            selectedPaths = emptySet()
                                            search = ""
                                        } else if (!folderMode) toggleEntry(entry)
                                    },
                                    onToggle = { toggleEntry(entry) }
                                )
                            }
                        }
                        else -> LazyColumn(
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            items(entries, key = { it.file.absolutePath }) { entry ->
                                UploadPickerListItem(
                                    entry = entry,
                                    selected = entry.file.absolutePath in selectedPaths,
                                    showSelection = !folderMode,
                                    onClick = {
                                        if (entry.isDirectory) {
                                            currentPath = entry.file.absolutePath
                                            selectedPaths = emptySet()
                                            search = ""
                                        } else if (!folderMode) toggleEntry(entry)
                                    },
                                    onToggle = { toggleEntry(entry) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onUseAndroidPicker,
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(Res.string.picker_use_android_picker), maxLines = 1) }

                        Button(
                            onClick = {
                                if (folderMode) {
                                    currentPath?.let(onFolderSelected)
                                } else {
                                    val selected = selectedPaths.toList().sortedBy { it.length }
                                    val minimal = mutableListOf<String>()
                                    selected.forEach { path ->
                                        if (minimal.none { parent ->
                                                path == parent || path.startsWith(parent.trimEnd('/') + "/")
                                            }
                                        ) minimal += path
                                    }
                                    if (minimal.isNotEmpty()) onFilesSelected(minimal)
                                }
                            },
                            enabled = if (folderMode) currentPath != null else selectedPaths.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                stringResource(
                                    if (folderMode) Res.string.picker_select_current_folder
                                    else Res.string.picker_choose_files
                                ),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ApplyPreferredRefreshRateToDialogWindow(refreshRate: Float?) {
    val view = LocalView.current
    val dialogWindow = remember(view) {
        var parent: ViewParent? = view.parent
        var foundWindow: Window? = null
        while (parent != null && foundWindow == null) {
            if (parent is DialogWindowProvider) {
                foundWindow = parent.window
            }
            parent = (parent as? View)?.parent
        }
        foundWindow
    }

    SideEffect {
        if (refreshRate != null && dialogWindow != null) {
            val attributes = dialogWindow.attributes
            if (kotlin.math.abs(attributes.preferredRefreshRate - refreshRate) > 0.01f) {
                attributes.preferredRefreshRate = refreshRate
                dialogWindow.attributes = attributes
            }
        }
    }
}

@Composable
private fun UploadPickerListItem(
    entry: UploadPickerEntry,
    selected: Boolean,
    showSelection: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showSelection) {
            Checkbox(checked = selected, onCheckedChange = { onToggle() })
        }
        UploadPickerPreview(
            entry = entry,
            modifier = Modifier.size(54.dp).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                entry.file.name.ifBlank { entry.file.absolutePath },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    if (entry.isDirectory) append("Folder")
                    else append(formatUploadBytes(entry.size))
                    if (entry.modified > 0L) append(" · ").append(formatUploadDate(entry.modified))
                    if (!entry.isDirectory) append(" · ").append(entry.file.extension.ifBlank { "file" }.uppercase())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (entry.isDirectory) {
            TextButton(onClick = onClick) { Text("Open") }
        }
    }
}

@Composable
private fun UploadPickerGridItem(
    entry: UploadPickerEntry,
    selected: Boolean,
    showSelection: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().height(132.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                UploadPickerPreview(entry = entry, modifier = Modifier.fillMaxSize())
                if (showSelection) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { onToggle() },
                        modifier = Modifier.align(Alignment.TopEnd).padding(2.dp)
                    )
                }
            }
            Text(
                entry.file.name.ifBlank { entry.file.absolutePath },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                if (entry.isDirectory) "Folder" else formatUploadBytes(entry.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun UploadPickerPreview(
    entry: UploadPickerEntry,
    modifier: Modifier = Modifier
) {
    val extension = entry.file.extension.lowercase()
    val video = extension in uploadPickerVideoExtensions
    val isPreviewable = extension in uploadPickerImageExtensions || video
    val thumbnail by produceState<Bitmap?>(
        initialValue = null,
        key1 = entry.file.absolutePath,
        key2 = entry.modified,
        key3 = entry.size
    ) {
        value = if (isPreviewable && !entry.isDirectory) {
            withContext(Dispatchers.IO) { createUploadPickerThumbnail(entry.file) }
        } else {
            null
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        if (thumbnail != null) {
            Image(
                bitmap = thumbnail!!.asImageBitmap(),
                contentDescription = entry.file.name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            Icon(
                imageVector = if (entry.isDirectory) Icons.Filled.Folder else Icons.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
        }
        if (video) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = "Video",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp).size(22.dp)
            )
        }
    }
}

private val uploadPickerImageExtensions = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff", "avif"
)

private val uploadPickerVideoExtensions = setOf(
    "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "mpeg", "mpg", "ts", "m2ts"
)

private fun createUploadPickerThumbnail(file: File): Bitmap? = runCatching {
    when (file.extension.lowercase()) {
        in uploadPickerVideoExtensions -> {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                fun frameAt(timeUs: Long): Bitmap? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                        retriever.getScaledFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                            480,
                            480
                        )
                    } else {
                        retriever.getFrameAtTime(
                            timeUs,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }
                val frame = frameAt(1_000_000L) ?: frameAt(0L)
                frame?.let(::scaleUploadPickerBitmap)
            } finally {
                runCatching { retriever.release() }
            }
        }
        in uploadPickerImageExtensions -> decodeUploadPickerImage(file)
        else -> null
    }
}.getOrNull()

private fun decodeUploadPickerImage(file: File): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    var sampleSize = 1
    while (bounds.outWidth / sampleSize > 720 || bounds.outHeight / sampleSize > 720) {
        sampleSize *= 2
    }
    val options = BitmapFactory.Options().apply {
        inSampleSize = sampleSize
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    val decoded = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null

    val orientation = runCatching {
        ExifInterface(file.absolutePath).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                postRotate(90f)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                postRotate(270f)
                postScale(-1f, 1f)
            }
        }
    }
    val oriented = if (matrix.isIdentity) decoded else {
        Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            .also { if (it !== decoded) decoded.recycle() }
    }
    return scaleUploadPickerBitmap(oriented)
}

private fun scaleUploadPickerBitmap(bitmap: Bitmap): Bitmap {
    val maxDimension = 480
    val largestSide = maxOf(bitmap.width, bitmap.height)
    if (largestSide <= maxDimension) return bitmap
    val ratio = maxDimension.toFloat() / largestSide
    val scaled = Bitmap.createScaledBitmap(
        bitmap,
        (bitmap.width * ratio).toInt().coerceAtLeast(1),
        (bitmap.height * ratio).toInt().coerceAtLeast(1),
        true
    )
    if (scaled !== bitmap) bitmap.recycle()
    return scaled
}

private fun discoverUploadStorageRoots(context: Context): List<UploadStorageRoot> {
    val roots = mutableListOf<UploadStorageRoot>()
    val internal = Environment.getExternalStorageDirectory().absolutePath
    roots += UploadStorageRoot("Internal storage", internal, removable = false)

    val manager = context.getSystemService(StorageManager::class.java)
    manager?.storageVolumes.orEmpty().forEach { volume ->
        if (!volume.isRemovable) return@forEach
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            volume.directory?.absolutePath
        } else {
            volume.uuid?.let { "/storage/$it" }
        } ?: return@forEach
        if (roots.none { it.path.equals(path, ignoreCase = true) } && File(path).isDirectory) {
            val label = runCatching { volume.getDescription(context) }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: "SD card"
            roots += UploadStorageRoot(label, path, removable = true)
        }
    }
    return roots
}

private fun canBrowseAllFiles(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

private fun matchesUploadPickerFilter(
    entry: UploadPickerEntry,
    filter: UploadPickerFilter
): Boolean {
    val extension = entry.file.extension.lowercase()
    return when (filter) {
        UploadPickerFilter.ALL -> true
        UploadPickerFilter.FILES -> !entry.isDirectory
        UploadPickerFilter.FOLDERS -> entry.isDirectory
        UploadPickerFilter.IMAGES -> extension in setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff", "avif", "svg")
        UploadPickerFilter.VIDEOS -> extension in setOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "mpeg", "mpg", "ts", "m2ts")
        UploadPickerFilter.AUDIO -> extension in setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff")
        UploadPickerFilter.DOCUMENTS -> extension in setOf("pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "csv", "epub")
        UploadPickerFilter.ARCHIVES -> extension in setOf("zip", "7z", "rar", "tar", "gz", "bz2", "xz", "iso")
        UploadPickerFilter.OTHER -> !entry.isDirectory && extension !in setOf(
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "tif", "tiff", "avif", "svg",
            "mp4", "mkv", "mov", "avi", "webm", "m4v", "3gp", "mpeg", "mpg", "ts", "m2ts",
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "aiff",
            "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "rtf", "odt", "csv", "epub",
            "zip", "7z", "rar", "tar", "gz", "bz2", "xz", "iso"
        )
    }
}

private fun uploadPickerComparator(sort: UploadPickerSort, descending: Boolean): Comparator<UploadPickerEntry> =
    Comparator { left, right ->
        val folderOrder = when {
            left.isDirectory && !right.isDirectory -> -1
            !left.isDirectory && right.isDirectory -> 1
            else -> 0
        }
        if (folderOrder != 0) return@Comparator folderOrder
        val result = when (sort) {
            UploadPickerSort.NAME -> left.file.name.compareTo(right.file.name, ignoreCase = true)
            UploadPickerSort.SIZE -> left.size.compareTo(right.size)
            UploadPickerSort.MODIFIED -> left.modified.compareTo(right.modified)
            UploadPickerSort.TYPE -> left.file.extension.compareTo(right.file.extension, ignoreCase = true)
        }
        if (descending) -result else result
    }

@Composable
private fun sortLabel(sort: UploadPickerSort): String = stringResource(
    when (sort) {
        UploadPickerSort.NAME -> Res.string.picker_sort_name
        UploadPickerSort.SIZE -> Res.string.picker_sort_size
        UploadPickerSort.MODIFIED -> Res.string.picker_sort_modified
        UploadPickerSort.TYPE -> Res.string.picker_sort_type
    }
)

@Composable
private fun filterLabel(filter: UploadPickerFilter): String = stringResource(
    when (filter) {
        UploadPickerFilter.ALL -> Res.string.picker_filter_all
        UploadPickerFilter.FILES -> Res.string.picker_filter_files
        UploadPickerFilter.FOLDERS -> Res.string.picker_filter_folders
        UploadPickerFilter.IMAGES -> Res.string.picker_filter_images
        UploadPickerFilter.VIDEOS -> Res.string.picker_filter_videos
        UploadPickerFilter.AUDIO -> Res.string.picker_filter_audio
        UploadPickerFilter.DOCUMENTS -> Res.string.picker_filter_documents
        UploadPickerFilter.ARCHIVES -> Res.string.picker_filter_archives
        UploadPickerFilter.OTHER -> Res.string.picker_filter_other
    }
)

private fun formatUploadBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    do {
        value /= 1024.0
        unit++
    } while (value >= 1024.0 && unit < units.lastIndex)
    return String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

private fun formatUploadDate(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
