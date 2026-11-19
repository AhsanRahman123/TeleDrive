package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File

/**
 * Built-in file picker for desktop uploads, modelled on the Android app's
 * upload picker: browse folders, tick any number of files, confirm. Shown
 * unless the user turns on "Use Windows native file picker" in Settings.
 */
@Composable
internal fun DesktopUploadPickerDialog(
    multiSelect: Boolean,
    onPicked: (List<File>) -> Unit,
    onCancel: () -> Unit
) {
    var currentDir by remember {
        mutableStateOf(File(System.getProperty("user.home") ?: "/"))
    }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val entries = remember(currentDir) {
        runCatching {
            currentDir.listFiles().orEmpty()
                .filter { it.isDirectory || it.isFile }
                .sortedWith(
                    compareByDescending<File> { it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
        }.getOrDefault(emptyList())
    }

    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (currentDir.parentFile != null) {
                    IconButton(onClick = {
                        currentDir = currentDir.parentFile
                        selected = emptySet()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
                    }
                }
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(entries, key = { it.absolutePath }) { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                if (entry.isDirectory) {
                                    currentDir = entry
                                    selected = emptySet()
                                } else if (multiSelect) {
                                    selected = if (entry.absolutePath in selected) {
                                        selected - entry.absolutePath
                                    } else {
                                        selected + entry.absolutePath
                                    }
                                } else {
                                    onPicked(listOf(entry))
                                }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (entry.isDirectory) {
                                Icons.Filled.Folder
                            } else {
                                Icons.Filled.InsertDriveFile
                            },
                            contentDescription = null
                        )
                        Text(
                            text = entry.name,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (multiSelect && entry.isFile) {
                            Checkbox(
                                checked = entry.absolutePath in selected,
                                onCheckedChange = { checked ->
                                    selected = if (checked) {
                                        selected + entry.absolutePath
                                    } else {
                                        selected - entry.absolutePath
                                    }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (multiSelect) {
                TextButton(
                    onClick = { onPicked(selected.sorted().map { File(it) }) },
                    enabled = selected.isNotEmpty()
                ) {
                    Text("Select (${selected.size})")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
