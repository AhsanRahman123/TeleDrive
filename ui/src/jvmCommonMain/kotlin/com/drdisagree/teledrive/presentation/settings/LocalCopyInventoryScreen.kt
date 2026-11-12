package com.drdisagree.teledrive.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.components.FileThumbnail
import com.drdisagree.teledrive.presentation.platform.LocalDeleteConsentLauncher
import com.drdisagree.teledrive.presentation.platform.LocalFileRevealer
import kotlinx.coroutines.launch
import org.koin.compose.koinInject
import java.io.File
import java.nio.file.Paths

private data class LocalCopyRow(
    val file: DriveFile,
    val path: String,
    val exists: Boolean,
    val managedByTeleDrive: Boolean
)

@Composable
fun LocalCopyInventoryScreen(modifier: Modifier = Modifier) {
    val fileRepository = koinInject<FileRepository>()
    val settingsRepository = koinInject<SettingsRepository>()
    val storagePaths = koinInject<AppStoragePaths>()
    val scope = rememberCoroutineScope()
    val deleteConsentLauncher = LocalDeleteConsentLauncher.current
    val fileRevealer = LocalFileRevealer.current

    val preferences by settingsRepository.preferences.collectAsStateWithLifecycle(
        initialValue = UserPreferences()
    )
    val backedUpFiles by remember {
        fileRepository.observeFiles(
            FileQuerySpec(
                backedUpOnly = true,
                showHidden = true,
                showArchived = true,
                sortField = FileSortField.SIZE,
                sortDirection = SortDirection.DESCENDING
            )
        )
    }.collectAsStateWithLifecycle(initialValue = emptyList())

    var search by remember { mutableStateOf("") }
    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var confirmRemove by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var loadingConsent by remember { mutableStateOf(false) }
    var pendingIds by remember { mutableStateOf<List<String>>(emptyList()) }

    val downloadBase = preferences.downloadDirectory
        ?.takeIf { it.isNotBlank() }
        ?.let(::File)
        ?: File(System.getProperty("user.home").orEmpty(), "Downloads")
    val managedRoots = listOf(
        storagePaths.filesDir,
        File(downloadBase, "TeleDrive")
    ).map { it.absoluteFile.toPath().normalize() }

    fun isManaged(path: String): Boolean = runCatching {
        val candidate = File(path).absoluteFile.toPath().normalize()
        managedRoots.any { root -> candidate.startsWith(root) }
    }.getOrDefault(false)

    val rows = backedUpFiles.mapNotNull { file ->
        val path = file.localPath ?: return@mapNotNull null
        LocalCopyRow(
            file = file,
            path = path,
            exists = runCatching { File(path).isFile }.getOrDefault(false),
            managedByTeleDrive = isManaged(path)
        )
    }.filter { row ->
        search.isBlank() ||
            row.file.name.contains(search, ignoreCase = true) ||
            row.path.contains(search, ignoreCase = true)
    }

    val selectedRows = rows.filter { it.file.id in selectedIds }
    val visibleBytes = rows.filter { it.exists }.sumOf { it.file.sizeBytes.coerceAtLeast(0L) }
    val removableSelection = selectedRows.any { it.managedByTeleDrive && it.exists }

    fun removeLocalCopies(ids: List<String>) {
        if (ids.isEmpty()) return
        scope.launch {
            busy = true
            try {
                when (val result = fileRepository.deleteLocalCopy(ids)) {
                    is AppResult.Success -> {
                        val consent = result.value.consentRequest
                        if (consent != null) {
                            pendingIds = ids
                            loadingConsent = true
                            deleteConsentLauncher.launch(consent) { granted ->
                                loadingConsent = false
                                if (granted) removeLocalCopies(pendingIds)
                                pendingIds = emptyList()
                            }
                        } else {
                            val cleanup = result.value
                            message = buildString {
                                append("Removed ${cleanup.deletedCount} TeleDrive-managed local copies.")
                                if (cleanup.keptOffline > 0) append(" Kept ${cleanup.keptOffline} Available Offline files.")
                                if (cleanup.keptUnmanaged > 0) append(" Preserved ${cleanup.keptUnmanaged} external/original files.")
                                if (cleanup.unverifiedCount > 0) append(" Left ${cleanup.unverifiedCount} unverified files untouched.")
                                if (cleanup.failedCount > 0) append(" ${cleanup.failedCount} items need another review.")
                            }
                            selectedIds = emptySet()
                        }
                    }
                    is AppResult.Failure -> message = result.error.toString()
                }
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text(
            "Backed-up local copies",
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            "These are library files with a verified Telegram backup and a saved local path. " +
                "The full path and ownership are shown so you can decide what to keep.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Visible local copies", style = MaterialTheme.typography.labelMedium)
                    Text(rows.size.toString(), style = MaterialTheme.typography.headlineSmall)
                }
            }
            Card(modifier = Modifier.weight(1f)) {
                Column(Modifier.padding(12.dp)) {
                    Text("Existing file size", style = MaterialTheme.typography.labelMedium)
                    Text(Formatters.bytes(visibleBytes), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        Spacer(Modifier.size(10.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            label = { Text("Search file name or full path") }
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${selectedIds.size} selected", modifier = Modifier.weight(1f))
            TextButton(
                enabled = rows.isNotEmpty(),
                onClick = {
                    selectedIds = if (selectedIds.size == rows.size) emptySet()
                    else rows.map { it.file.id }.toSet()
                }
            ) { Text(if (selectedIds.size == rows.size && rows.isNotEmpty()) "Clear" else "Select all") }
            Button(
                enabled = !busy && !loadingConsent && removableSelection,
                onClick = { confirmRemove = true }
            ) { Text("Free up selected") }
        }
        if (message.isNotBlank()) {
            Text(message, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) {
            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        if (rows.isEmpty()) {
            Text(
                "No backed-up library files with a saved local path match this view.",
                modifier = Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(rows, key = { it.file.id }) { row ->
                    Card {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = row.file.id in selectedIds,
                                onCheckedChange = { checked ->
                                    selectedIds = if (checked) selectedIds + row.file.id
                                    else selectedIds - row.file.id
                                }
                            )
                            FileThumbnail(
                                file = row.file,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    row.file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${Formatters.bytes(row.file.sizeBytes)} · " +
                                        if (!row.exists) "Missing at saved path"
                                        else if (row.file.isAvailableOffline) "Available Offline"
                                        else if (row.managedByTeleDrive) "TeleDrive-managed local copy"
                                        else "External/original file (protected)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    row.path,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (fileRevealer != null && row.exists) {
                                TextButton(onClick = { fileRevealer.reveal(row.path) }) {
                                    Text("Reveal")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { if (!busy) confirmRemove = false },
            title = { Text("Free up selected local copies?") },
            text = {
                Text(
                    "TeleDrive removes only its managed local copies after checking the Telegram backup. " +
                        "External/original files and Available Offline copies are preserved. " +
                        "Your Telegram cloud backups are not deleted."
                )
            },
            confirmButton = {
                Button(
                    enabled = !busy,
                    onClick = {
                        confirmRemove = false
                        removeLocalCopies(selectedRows.map { it.file.id })
                    }
                ) { Text("Free up space") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text("Keep files") }
            }
        )
    }
}
