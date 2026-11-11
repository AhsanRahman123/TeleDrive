package com.drdisagree.teledrive.presentation.files

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.presentation.common.Formatters

@Composable
fun StorageAuditDialog(
    snapshot: StorageAuditSnapshot,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit,
    canReveal: Boolean,
    onReveal: (String) -> Boolean,
    onDeleteManagedCopies: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var confirmRemoval by remember(snapshot.lastCheckedAt) { mutableStateOf(false) }
    val deletableCount = snapshot.files.count { file ->
        file.localPath != null &&
                file.localExists &&
                file.localIsTeleDriveManaged &&
                file.telegramRemoteFileId != null &&
                file.tdlibFileId != null
    }

    if (confirmRemoval) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Remove $deletableCount TeleDrive-managed copies?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("TeleDrive-managed database copies and TDLib cache files inside managed folders will be removed. External TDLib paths, original files outside TeleDrive-managed folders, and Telegram cloud backups will be preserved.")
                    snapshot.files.filter { file ->
                        file.localPath != null && file.localExists &&
                                file.localIsTeleDriveManaged && file.telegramRemoteFileId != null &&
                                file.tdlibFileId != null
                    }.forEach { file ->
                        Text("• ${file.name}", style = MaterialTheme.typography.bodySmall)
                        file.localPath?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                        }
                    }
                    Text("Original files outside TeleDrive-managed folders and Telegram cloud backups will be preserved.")
                }
            },
            confirmButton = {
                Button(onClick = {
                    confirmRemoval = false
                    onDeleteManagedCopies()
                }) { Text("Remove local copies") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoval = false }) { Text("Keep files") }
            }
        )
    } else AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Storage locations")
                Text(
                    text = if (snapshot.loading) "Refreshing actual filesystem and TDLib paths…"
                    else "Last checked: " + Formatters.dateTime(snapshot.lastCheckedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .widthIn(max = 760.dp)
                    .heightIn(max = 620.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "ACTIVE APP INSTANCE",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    "Every source-code checkout that uses this same profile path shares its database, settings and TDLib session. Compare this path with the other TeleDrive windows you have launched.",
                    style = MaterialTheme.typography.bodySmall
                )
                AuditPath(
                    label = "App data / profile root",
                    path = snapshot.instanceRoot,
                    exists = snapshot.roots.firstOrNull { it.label == "This app instance / profile" }?.exists == true,
                    sizeBytes = null,
                    onReveal = onReveal,
                    canReveal = canReveal,
                    onCopy = { clipboard.setText(AnnotatedString(snapshot.instanceRoot)) }
                )

                HorizontalDivider()
                Text(
                    "APP STORAGE ROOTS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                snapshot.roots.drop(1).forEach { root ->
                    AuditPath(
                        label = root.label,
                        path = root.path,
                        exists = root.exists,
                        sizeBytes = root.sizeBytes,
                        onReveal = onReveal,
                        canReveal = canReveal,
                        onCopy = { clipboard.setText(AnnotatedString(root.path)) }
                    )
                }

                HorizontalDivider()
                Text(
                    "SELECTED FILES",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                snapshot.files.forEach { file ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(file.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Expected size: ${Formatters.bytes(file.expectedSizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            HorizontalDivider()

                            Text("DATABASE LOCAL COPY", style = MaterialTheme.typography.labelMedium)
                            Text(
                                when {
                                    file.localPath == null -> "No local path recorded"
                                    !file.localExists -> "Recorded path is missing"
                                    file.localIsTeleDriveManaged -> "TeleDrive-managed copy exists · ${file.localSizeBytes?.let { Formatters.bytes(it) }.orEmpty()}"
                                    else -> "External/original file exists · ${file.localSizeBytes?.let { Formatters.bytes(it) }.orEmpty()} · protected from Free up space"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (file.localIsTeleDriveManaged) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            file.localPath?.let { path ->
                                PathValue(
                                    path = path,
                                    exists = file.localExists,
                                    onReveal = onReveal,
                                    canReveal = canReveal,
                                    onCopy = { clipboard.setText(AnnotatedString(path)) }
                                )
                            }

                            HorizontalDivider()
                            Text("TDLIB REPORTED LOCAL PATH", style = MaterialTheme.typography.labelMedium)
                            Text(
                                when {
                                    file.tdlibError != null -> "TDLib lookup failed: ${file.tdlibError}"
                                    file.tdlibPath == null -> "TDLib reports no local path"
                                    file.tdlibPathExists -> "File exists · ${file.tdlibSizeBytes?.let { Formatters.bytes(it) }.orEmpty()} · completed=${file.tdlibDownloadComplete}"
                                    else -> "TDLib reports a path, but the file is not present"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            file.tdlibPath?.let { path ->
                                PathValue(
                                    path = path,
                                    exists = file.tdlibPathExists,
                                    onReveal = onReveal,
                                    canReveal = canReveal,
                                    onCopy = { clipboard.setText(AnnotatedString(path)) }
                                )
                                Text(
                                    if (file.tdlibPathIsTeleDriveManaged) {
                                        "Ownership: inside a TeleDrive-managed storage folder"
                                    } else {
                                        "Ownership: outside TeleDrive-managed folders — protected"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (file.tdlibPathIsTeleDriveManaged) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error
                                )
                            }

                            HorizontalDivider()
                            Text("TELEGRAM CLOUD BACKUP", style = MaterialTheme.typography.labelMedium)
                            Text(
                                if (file.telegramChatId != null && file.telegramMessageId != null) {
                                    "Storage chat: ${file.telegramChatId}  ·  Message: ${file.telegramMessageId}"
                                } else {
                                    "No confirmed Telegram chat/message mapping"
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            file.telegramRemoteFileId?.let {
                                Text("Remote file ID: $it", style = MaterialTheme.typography.bodySmall)
                            }
                            file.tdlibFileId?.let {
                                Text("TDLib file ID: $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }

                if (snapshot.error != null) {
                    Text(snapshot.error, color = MaterialTheme.colorScheme.error)
                }
                Text(
                    "Safety rule: Free up space only removes paths inside TeleDrive-managed folders. Original files selected from other drives or folders are preserved.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onRefresh, enabled = !snapshot.loading) {
                    Text("Refresh")
                }
                Button(
                    onClick = { confirmRemoval = true },
                    enabled = !snapshot.loading && deletableCount > 0
                ) {
                    Text("Remove $deletableCount managed copies")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AuditPath(
    label: String,
    path: String,
    exists: Boolean,
    sizeBytes: Long?,
    onReveal: (String) -> Boolean,
    canReveal: Boolean,
    onCopy: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text(
            if (exists) "Present" else "Not created yet",
            style = MaterialTheme.typography.labelSmall,
            color = if (exists) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        PathValue(
            path = path,
            exists = exists,
            onReveal = onReveal,
            canReveal = canReveal,
            onCopy = onCopy
        )
        sizeBytes?.takeIf { it > 0L }?.let {
            Text(Formatters.bytes(it), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PathValue(
    path: String,
    exists: Boolean,
    onReveal: (String) -> Boolean,
    canReveal: Boolean,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        var revealFailed by remember(path) { mutableStateOf(false) }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = path,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
            )
            if (revealFailed) {
                Text("Could not open this location on the current platform.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            }
        }
        Column {
            TextButton(onClick = onCopy) { Text("Copy") }
            if (canReveal) {
                TextButton(
                    onClick = { revealFailed = !onReveal(path) },
                    enabled = exists
                ) { Text("Open") }
            }
        }
    }
}
