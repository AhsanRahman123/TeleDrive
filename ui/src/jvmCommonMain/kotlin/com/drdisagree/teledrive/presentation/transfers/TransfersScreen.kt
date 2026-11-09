package com.drdisagree.teledrive.presentation.transfers

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.components.DesktopScrollEdgeControls
import androidx.compose.foundation.focusable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import java.util.Date
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.drdisagree.teledrive.domain.model.TransferStage
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.presentation.common.add
import com.drdisagree.teledrive.presentation.components.CollectionShortcuts
import com.drdisagree.teledrive.presentation.components.ConfirmDialog
import com.drdisagree.teledrive.presentation.components.EmptyState
import com.drdisagree.teledrive.presentation.components.keyboardCollection
import com.drdisagree.teledrive.presentation.components.keyboardCollectionItem
import com.drdisagree.teledrive.presentation.components.liftedTopAppBarColors
import com.drdisagree.teledrive.presentation.components.rememberKeyboardCollection
import com.drdisagree.teledrive.presentation.components.rememberToolbarLift
import com.drdisagree.teledrive.presentation.platform.LocalFileRevealer
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_actions
import com.drdisagree.teledrive.resources.common_back
import com.drdisagree.teledrive.resources.common_cancel
import com.drdisagree.teledrive.resources.common_clear
import com.drdisagree.teledrive.resources.common_dismiss
import com.drdisagree.teledrive.resources.common_pause
import com.drdisagree.teledrive.resources.common_resume
import com.drdisagree.teledrive.resources.common_retry
import com.drdisagree.teledrive.resources.transfer_stage_joining
import com.drdisagree.teledrive.resources.transfer_stage_sealing
import com.drdisagree.teledrive.resources.transfer_stage_verifying
import com.drdisagree.teledrive.resources.transfer_type_backup
import com.drdisagree.teledrive.resources.transfer_type_download
import com.drdisagree.teledrive.resources.transfer_type_restore
import com.drdisagree.teledrive.resources.transfer_type_upload
import com.drdisagree.teledrive.resources.transfers
import com.drdisagree.teledrive.resources.transfers_cancel
import com.drdisagree.teledrive.resources.transfers_cancel_transfers
import com.drdisagree.teledrive.resources.transfers_clear_finished
import com.drdisagree.teledrive.resources.transfers_clear_finished_transfers
import com.drdisagree.teledrive.resources.transfers_completed_canceled_entries_removed
import com.drdisagree.teledrive.resources.transfers_failed
import com.drdisagree.teledrive.resources.transfers_failed_downloads
import com.drdisagree.teledrive.resources.transfers_failed_transfers
import com.drdisagree.teledrive.resources.transfers_failed_uploads
import com.drdisagree.teledrive.resources.transfers_more_queued
import com.drdisagree.teledrive.resources.transfers_no_transfers
import com.drdisagree.teledrive.resources.transfers_pause
import com.drdisagree.teledrive.resources.transfers_queued_running_paused_failed
import com.drdisagree.teledrive.resources.transfers_resume
import com.drdisagree.teledrive.resources.transfers_section_active
import com.drdisagree.teledrive.resources.transfers_section_finished
import com.drdisagree.teledrive.resources.transfers_section_paused
import com.drdisagree.teledrive.resources.transfers_uploads_downloads_appear_here
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransfersScreen(
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit = {},
    viewModel: TransfersViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val transferConcurrency by viewModel.transferConcurrency.collectAsStateWithLifecycle()
    val fileRevealer = LocalFileRevealer.current
    var concurrencyDraft by remember { mutableIntStateOf(transferConcurrency) }
    var confirmClearFinished by remember { mutableStateOf(false) }
    var confirmCancelAll by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showTransferSettings by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("ALL") }
    var sortOrder by remember { mutableStateOf("STATUS") }
    var showFilterMenu by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    var selectedTransferIds by remember { mutableStateOf(emptySet<String>()) }
    var detailsTransfer by remember { mutableStateOf<TransferTask?>(null) }

    androidx.compose.runtime.LaunchedEffect(transferConcurrency) {
        concurrencyDraft = transferConcurrency
    }

    if (showTransferSettings) {
        AlertDialog(
            onDismissRequest = { showTransferSettings = false },
            title = { Text("Transfer concurrency") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Maximum simultaneous Telegram transfer jobs: $concurrencyDraft of 6")
                    Slider(
                        value = concurrencyDraft.toFloat(),
                        onValueChange = { concurrencyDraft = it.toInt().coerceIn(1, 6) },
                        valueRange = 1f..6f,
                        steps = 4
                    )
                    Text(
                        "This controls how many queued transfers may run at once. Raising it can use more bandwidth and disk activity.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setTransferConcurrency(concurrencyDraft)
                    showTransferSettings = false
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = {
                    concurrencyDraft = transferConcurrency
                    showTransferSettings = false
                }) { Text("Cancel") }
            }
        )
    }

    if (confirmClearFinished) {
        ConfirmDialog(
            title = stringResource(Res.string.transfers_clear_finished_transfers),
            message = stringResource(Res.string.transfers_completed_canceled_entries_removed),
            confirmLabel = stringResource(Res.string.common_clear),
            onConfirm = {
                confirmClearFinished = false
                viewModel.clearFinished()
            },
            onDismiss = { confirmClearFinished = false }
        )
    }

    if (detailsTransfer != null) {
        TransferDetailsDialog(
            transfer = detailsTransfer!!,
            onDismiss = { detailsTransfer = null }
        )
    }

    if (confirmCancelAll) {
        ConfirmDialog(
            title = stringResource(Res.string.transfers_cancel_transfers),
            message = stringResource(Res.string.transfers_queued_running_paused_failed),
            confirmLabel = stringResource(Res.string.transfers_cancel),
            destructive = true,
            onConfirm = {
                confirmCancelAll = false
                viewModel.cancelAll()
            },
            onDismiss = { confirmCancelAll = false }
        )
    }

    val listState = rememberLazyListState()
    val lifted by rememberToolbarLift(listState)

    Scaffold(
        topBar = {
            TopAppBar(
                colors = liftedTopAppBarColors(lifted),
                title = { Text(stringResource(Res.string.transfers)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        concurrencyDraft = transferConcurrency
                        showTransferSettings = true
                    }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Transfer settings")
                    }
                    if (state.active.isNotEmpty()) {
                        IconButton(onClick = viewModel::pauseAll) {
                            Icon(
                                Icons.Filled.Pause,
                                contentDescription = stringResource(Res.string.transfers_pause)
                            )
                        }
                    } else if (state.paused.isNotEmpty()) {
                        IconButton(onClick = viewModel::resumeAll) {
                            Icon(
                                Icons.Filled.PlayArrow,
                                contentDescription = stringResource(Res.string.transfers_resume)
                            )
                        }
                    }
                    IconButton(onClick = { showMenu = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(Res.string.common_actions)
                        )
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.transfers_cancel)) },
                            enabled = state.active.isNotEmpty() ||
                                    state.paused.isNotEmpty() ||
                                    state.failed.isNotEmpty(),
                            onClick = {
                                showMenu = false
                                confirmCancelAll = true
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.transfers_clear_finished)) },
                            enabled = state.completed.isNotEmpty(),
                            onClick = {
                                showMenu = false
                                confirmClearFinished = true
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        val isEmpty = state.active.isEmpty() && state.paused.isEmpty() &&
                state.failed.isEmpty() && state.completed.isEmpty()
        if (isEmpty && !state.loading) {
            EmptyState(
                icon = Icons.Outlined.SwapVert,
                title = stringResource(Res.string.transfers_no_transfers),
                description = stringResource(Res.string.transfers_uploads_downloads_appear_here),
                modifier = Modifier.padding(padding)
            )
            return@Scaffold
        }
        fun filterAndSort(items: List<TransferTask>): List<TransferTask> =
            items.asSequence()
                .filter { transfer ->
                    val typeMatches = when (typeFilter) {
                        "DOWNLOADS" -> transfer.type == TransferType.DOWNLOAD || transfer.type == TransferType.RESTORE
                        "UPLOADS" -> transfer.type == TransferType.UPLOAD || transfer.type == TransferType.BACKUP
                        "FAILED" -> transfer.state == TransferState.FAILED
                        "COMPLETED" -> transfer.state == TransferState.COMPLETED
                        else -> true
                    }
                    typeMatches && (searchQuery.isBlank() ||
                        transfer.displayName.contains(searchQuery.trim(), ignoreCase = true))
                }
                .let { sequence ->
                    when (sortOrder) {
                        "PRIORITY" -> sequence.sortedWith(
                            compareByDescending<TransferTask> { it.priority }.thenBy { it.createdAt }
                        )
                        "LARGEST" -> sequence.sortedByDescending { it.sizeBytes }
                        "SMALLEST" -> sequence.sortedBy { it.sizeBytes }
                        "NEWEST" -> sequence.sortedByDescending { it.createdAt }
                        "OLDEST" -> sequence.sortedBy { it.createdAt }
                        "NAME" -> sequence.sortedBy { it.displayName.lowercase() }
                        else -> sequence.sortedWith(
                            compareByDescending<TransferTask> { it.progress }.thenBy { it.createdAt }
                        )
                    }
                }.toList()

        val visibleActive = filterAndSort(state.active)
        val visiblePaused = filterAndSort(state.paused)
        val visibleFailed = filterAndSort(state.failed)
        val visibleCompleted = filterAndSort(state.completed)
        val visibleTransfers = visibleActive + visiblePaused + visibleFailed + visibleCompleted
        val visibleIds = visibleTransfers.map { it.id }.toSet()
        val selectedTransfers = visibleTransfers.filter { it.id in selectedTransferIds }
        val allVisibleSelected = visibleIds.isNotEmpty() && visibleIds.all { it in selectedTransferIds }
        val activeTitle = stringResource(Res.string.transfers_section_active)
        val pausedTitle = stringResource(Res.string.transfers_section_paused)
        val finishedTitle = stringResource(Res.string.transfers_section_finished)
        val failedTitle = failedSectionTitle(visibleFailed)
        val activeTotal = if (typeFilter == "ALL" && searchQuery.isBlank()) state.activeTotal else visibleActive.size
        val pausedTotal = if (typeFilter == "ALL" && searchQuery.isBlank()) state.pausedTotal else visiblePaused.size
        val failedTotal = if (typeFilter == "ALL" && searchQuery.isBlank()) state.failedTotal else visibleFailed.size
        val completedTotal = if (typeFilter == "ALL" && searchQuery.isBlank()) state.completedTotal else visibleCompleted.size
        val rows = buildList {
            listOf(
                visibleActive to activeTotal,
                visiblePaused to pausedTotal,
                visibleFailed to failedTotal,
                visibleCompleted to completedTotal
            ).forEach { (transfers, total) ->
                if (transfers.isEmpty()) return@forEach
                add(null)
                addAll(transfers)
                if (total > transfers.size) add(null)
            }
        }
        val keyboard = rememberKeyboardCollection(listState) { rows.getOrNull(it) != null }
        val shortcuts = CollectionShortcuts(
            onDelete = { index ->
                rows.getOrNull(index)
                    ?.takeIf { it !in state.completed }
                    ?.let { viewModel.cancel(it.id) }
            },
            onBack = onBack
        )
        val itemModifier: (TransferTask) -> Modifier = { transfer ->
            Modifier.keyboardCollectionItem(keyboard, rows.indexOf(transfer))
        }
        val actionsFocusable: (TransferTask) -> Boolean = { transfer ->
            keyboard.current == rows.indexOf(transfer)
        }
        Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                        .padding(end = 10.dp)
                .keyboardCollection(keyboard, rows.count { it != null }, shortcuts),
            contentPadding = padding.add(horizontal = 16.dp, top = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
) {
            item(key = "transfer-toolbar") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("Search downloads and transfers") }
                        )
                        Box {
                            TextButton(onClick = { showFilterMenu = true }) {
                                Text(
                                    when (typeFilter) {
                                        "DOWNLOADS" -> "Downloads"
                                        "UPLOADS" -> "Uploads"
                                        "FAILED" -> "Failed"
                                        "COMPLETED" -> "Completed"
                                        else -> "All files"
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = showFilterMenu,
                                onDismissRequest = { showFilterMenu = false }
                            ) {
                                listOf("ALL" to "All files", "DOWNLOADS" to "Downloads",
                                    "UPLOADS" to "Uploads", "FAILED" to "Failed",
                                    "COMPLETED" to "Completed").forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            typeFilter = value
                                            showFilterMenu = false
                                        }
                                    )
                                }
                            }
                        }
                        Box {
                            TextButton(onClick = { showSortMenu = true }) {
                                Text(
                                    when (sortOrder) {
                                        "PRIORITY" -> "Priority"
                                        "LARGEST" -> "Largest"
                                        "SMALLEST" -> "Smallest"
                                        "NEWEST" -> "Newest"
                                        "OLDEST" -> "Oldest"
                                        "NAME" -> "Name A–Z"
                                        else -> "By status"
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                listOf("STATUS" to "By status", "PRIORITY" to "By priority",
                                    "LARGEST" to "Largest first", "SMALLEST" to "Smallest first",
                                    "NEWEST" to "Newest first", "OLDEST" to "Oldest first",
                                    "NAME" to "Name A–Z").forEach { (value, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            sortOrder = value
                                            showSortMenu = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = allVisibleSelected,
                            onCheckedChange = { checked ->
                                selectedTransferIds = if (checked) selectedTransferIds + visibleIds
                                    else selectedTransferIds - visibleIds
                            },
                            enabled = visibleIds.isNotEmpty()
                        )
                        Text("${visibleIds.size} shown · ${selectedTransferIds.intersect(visibleIds).size} selected",
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            selectedTransferIds = if (allVisibleSelected) {
                                selectedTransferIds - visibleIds
                            } else {
                                selectedTransferIds + visibleIds
                            }
                        }, enabled = visibleIds.isNotEmpty()) {
                            Text(if (allVisibleSelected) "Deselect visible" else "Select visible")
                        }
                    }
                    if (selectedTransfers.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (selectedTransfers.any { it.state == TransferState.QUEUED || it.state == TransferState.RUNNING }) {
                                TextButton(onClick = { viewModel.pauseSelected(selectedTransfers.map { it.id }) }) {
                                    Text("Pause")
                                }
                            }
                            if (selectedTransfers.any { it.state == TransferState.PAUSED }) {
                                TextButton(onClick = { viewModel.resumeSelected(selectedTransfers.map { it.id }) }) {
                                    Text("Resume")
                                }
                            }
                            if (selectedTransfers.any { it.state == TransferState.FAILED }) {
                                TextButton(onClick = { viewModel.retrySelected(selectedTransfers.filter { it.state == TransferState.FAILED }.map { it.id }) }) {
                                    Text("Retry failed")
                                }
                            }
                            TextButton(onClick = {
                                viewModel.cancelSelected(selectedTransfers.filter { !it.state.isTerminal }.map { it.id })
                                selectedTransferIds = selectedTransferIds - visibleIds
                            }, enabled = selectedTransfers.any { !it.state.isTerminal }) {
                                Text("Cancel")
                            }
                            TextButton(onClick = { selectedTransferIds = emptySet() }) { Text("Clear") }
                        }
                    }
                }
            }
            if (visibleTransfers.isEmpty()) {
                item(key = "transfer-empty-filter") {
                    Text(
                        "No transfers match the current search or filter.",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            section("active", activeTitle, visibleActive, activeTotal) { transfer ->
                TransferRow(
                    transfer = transfer,
                    primaryIcon = Icons.Filled.Pause,
                    primaryLabel = stringResource(Res.string.common_pause),
                    onPrimary = { viewModel.pause(transfer.id) },
                    onCancel = { viewModel.cancel(transfer.id) },
                    onPriorityChange = { viewModel.setPriority(transfer.id, it) },
                    selected = transfer.id in selectedTransferIds,
                    onSelect = { checked ->
                        selectedTransferIds = if (checked) selectedTransferIds + transfer.id
                            else selectedTransferIds - transfer.id
                    },
                    onDetails = { detailsTransfer = transfer },
                    actionsFocusable = actionsFocusable(transfer),
                    modifier = itemModifier(transfer)
                )
            }
            section("paused", pausedTitle, visiblePaused, pausedTotal) { transfer ->
                TransferRow(
                    transfer = transfer,
                    primaryIcon = Icons.Filled.PlayArrow,
                    primaryLabel = stringResource(Res.string.common_resume),
                    onPrimary = { viewModel.resume(transfer.id) },
                    onCancel = { viewModel.cancel(transfer.id) },
                    onPriorityChange = { viewModel.setPriority(transfer.id, it) },
                    selected = transfer.id in selectedTransferIds,
                    onSelect = { checked ->
                        selectedTransferIds = if (checked) selectedTransferIds + transfer.id
                            else selectedTransferIds - transfer.id
                    },
                    onDetails = { detailsTransfer = transfer },
                    actionsFocusable = actionsFocusable(transfer),
                    modifier = itemModifier(transfer)
                )
            }
            section("failed", failedTitle, visibleFailed, failedTotal) { transfer ->
                TransferRow(
                    transfer = transfer,
                    primaryIcon = Icons.Filled.Refresh,
                    primaryLabel = stringResource(Res.string.common_retry),
                    onPrimary = { viewModel.retry(transfer.id) },
                    onCancel = { viewModel.cancel(transfer.id) },
                    onPriorityChange = { viewModel.setPriority(transfer.id, it) },
                    selected = transfer.id in selectedTransferIds,
                    onSelect = { checked ->
                        selectedTransferIds = if (checked) selectedTransferIds + transfer.id
                            else selectedTransferIds - transfer.id
                    },
                    onDetails = { detailsTransfer = transfer },
                    actionsFocusable = actionsFocusable(transfer),
                    modifier = itemModifier(transfer)
                )
            }
            section("finished", finishedTitle, visibleCompleted, completedTotal) { transfer ->
                TransferRow(
                    transfer = transfer,
                    onOpenInApp = transfer.fileId?.let { id -> { onOpenFile(id) } },
                    onShowInFolder = transfer.localPath?.takeIf { fileRevealer != null }?.let { path ->
                        { fileRevealer?.reveal(path) }
                    },
                    selected = transfer.id in selectedTransferIds,
                    onSelect = { checked ->
                        selectedTransferIds = if (checked) selectedTransferIds + transfer.id
                            else selectedTransferIds - transfer.id
                    },
                    onDetails = { detailsTransfer = transfer },
                    modifier = itemModifier(transfer)
                )
            }
        }
                DesktopScrollEdgeControls(
                    state = listState,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 14.dp, top = 8.dp)
                    )
                DesktopVerticalScrollbar(
                    state = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
    }
}

@Composable
private fun TransferDetailsDialog(
    transfer: TransferTask,
    onDismiss: () -> Unit
) {
    var tab by remember(transfer.id) { mutableStateOf("GENERAL") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(transfer.displayName, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { tab = "GENERAL" }) {
                        Text(if (tab == "GENERAL") "● General" else "General")
                    }
                    TextButton(onClick = { tab = "PROGRESS" }) {
                        Text(if (tab == "PROGRESS") "● Progress" else "Progress")
                    }
                }
                if (tab == "GENERAL") {
                    TransferDetailLine("Type", transfer.type.name.lowercase().replaceFirstChar { it.uppercase() })
                    TransferDetailLine("Status", transfer.state.name.lowercase().replaceFirstChar { it.uppercase() })
                    TransferDetailLine("Size", Formatters.bytes(transfer.sizeBytes))
                    TransferDetailLine("Local path", transfer.localPath ?: "Not downloaded to this device")
                    TransferDetailLine("Added", Date(transfer.createdAt).toString())
                    TransferDetailLine("Priority", transfer.priority.toString())
                } else {
                    TransferDetailLine("Transferred", "${Formatters.bytes(transfer.transferredBytes)} / ${Formatters.bytes(transfer.sizeBytes)}")
                    TransferDetailLine("Progress", Formatters.percent(transfer.progress))
                    TransferDetailLine("Speed", if (transfer.speedBytesPerSecond > 0) Formatters.speed(transfer.speedBytesPerSecond) else "Not available")
                    TransferDetailLine("Time remaining", transfer.etaSeconds?.let { Formatters.eta(it) } ?: "Not available")
                    TransferDetailLine("Stage", transfer.stage?.name?.lowercase()?.replaceFirstChar { it.uppercase() } ?: "—")
                    transfer.errorMessage?.let { TransferDetailLine("Last error", it) }
                    transfer.completedAt?.let { TransferDetailLine("Completed", Date(it).toString()) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun TransferDetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun LazyListScope.section(
    sectionKey: String,
    title: String,
    transfers: List<TransferTask>,
    total: Int,
    content: @Composable (TransferTask) -> Unit
) {
    if (transfers.isEmpty()) return
    item(key = "header-$sectionKey") {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier
                .padding(top = 8.dp, bottom = 2.dp)
                .semantics { heading() }
        )
    }
    items(transfers, key = { "$sectionKey-${it.id}" }) { transfer ->
        content(transfer)
    }
    val hidden = total - transfers.size
    if (hidden > 0) {
        item(key = "more-$sectionKey") {
            Text(
                text = pluralStringResource(Res.plurals.transfers_more_queued, hidden, hidden),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp, bottom = 4.dp)
            )
        }
    }
}

@Composable
private fun failedSectionTitle(failed: List<TransferTask>): String {
    val uploads = failed.count {
        it.type == TransferType.UPLOAD || it.type == TransferType.BACKUP
    }
    return when {
        failed.isEmpty() -> stringResource(Res.string.transfers_failed)
        uploads == failed.size -> stringResource(Res.string.transfers_failed_uploads)
        uploads == 0 -> stringResource(Res.string.transfers_failed_downloads)
        else -> stringResource(Res.string.transfers_failed_transfers)
    }
}

@Composable
private fun transferRate(transfer: TransferTask): String {
    val stage = transfer.stage
    if (stage != null) {
        return stringResource(
            when (stage) {
                TransferStage.SEALING -> Res.string.transfer_stage_sealing
                TransferStage.JOINING -> Res.string.transfer_stage_joining
                TransferStage.VERIFYING -> Res.string.transfer_stage_verifying
            }
        )
    }
    if (transfer.state != TransferState.RUNNING || transfer.speedBytesPerSecond <= 0) return ""
    val speed = Formatters.speed(transfer.speedBytesPerSecond)
    val eta = transfer.etaSeconds?.let { Formatters.eta(it) } ?: return speed
    return "$speed · $eta"
}

@Composable
private fun TransferRow(
    transfer: TransferTask,
    primaryIcon: ImageVector? = null,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null,
    onPriorityChange: ((Int) -> Unit)? = null,
    onOpenInApp: (() -> Unit)? = null,
    onShowInFolder: (() -> Unit)? = null,
    selected: Boolean = false,
    onSelect: ((Boolean) -> Unit)? = null,
    onDetails: (() -> Unit)? = null,
    actionsFocusable: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val actions = Modifier.focusProperties { canFocus = actionsFocusable }
    var showRowMenu by remember { mutableStateOf(false) }
    Card(
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .semantics(mergeDescendants = true) {}
            .clip(MaterialTheme.shapes.large)
            .indication(interactionSource, ripple())
            .focusable(interactionSource = interactionSource)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onSelect != null) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = onSelect,
                        modifier = actions
                    )
                }
                TransferThumbnail(transfer = transfer)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = transfer.displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(
                                when (transfer.type) {
                                    TransferType.UPLOAD -> stringResource(Res.string.transfer_type_upload)
                                    TransferType.DOWNLOAD -> stringResource(Res.string.transfer_type_download)
                                    TransferType.BACKUP -> stringResource(Res.string.transfer_type_backup)
                                    TransferType.RESTORE -> stringResource(Res.string.transfer_type_restore)
                                }
                            )
                            append(" · ")
                            append(Formatters.bytes(transfer.sizeBytes))
                            transfer.errorMessage?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (transfer.state == TransferState.FAILED) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (primaryIcon != null && onPrimary != null) {
                    IconButton(onClick = onPrimary, modifier = actions) {
                        Icon(primaryIcon, contentDescription = primaryLabel)
                    }
                }
                if (onCancel != null || onPriorityChange != null ||
                    onOpenInApp != null || onShowInFolder != null || onDetails != null
                ) {
                    Box {
                        IconButton(onClick = { showRowMenu = true }, modifier = actions) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "Transfer actions")
                        }
                        DropdownMenu(
                            expanded = showRowMenu,
                            onDismissRequest = { showRowMenu = false }
                        ) {
                            onOpenInApp?.let { open ->
                                DropdownMenuItem(
                                    text = { Text("Open in TeleDrive") },
                                    leadingIcon = { Icon(Icons.Filled.OpenInNew, contentDescription = null) },
                                    onClick = {
                                        showRowMenu = false
                                        open()
                                    }
                                )
                            }
                            onShowInFolder?.let { reveal ->
                                DropdownMenuItem(
                                    text = { Text("Show in folder") },
                                    leadingIcon = { Icon(Icons.Filled.FolderOpen, contentDescription = null) },
                                    onClick = {
                                        showRowMenu = false
                                        reveal()
                                    }
                                )
                            }
                            onDetails?.let { details ->
                                DropdownMenuItem(
                                    text = { Text("Details") },
                                    onClick = {
                                        showRowMenu = false
                                        details()
                                    }
                                )
                            }
                            if (onPriorityChange != null) {
                                DropdownMenuItem(
                                    text = { Text("Increase priority") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, contentDescription = null) },
                                    enabled = transfer.priority < 10,
                                    onClick = {
                                        showRowMenu = false
                                        onPriorityChange((transfer.priority + 1).coerceAtMost(10))
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Lower priority") },
                                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                                    enabled = transfer.priority > -10,
                                    onClick = {
                                        showRowMenu = false
                                        onPriorityChange((transfer.priority - 1).coerceAtLeast(-10))
                                    }
                                )
                            }
                        }
                    }
                }
            }
            if (transfer.state == TransferState.RUNNING ||
                transfer.state == TransferState.PAUSED
            ) {
                Spacer(Modifier.height(10.dp))
                LinearWavyProgressIndicator(
                    progress = { transfer.progress },
                    amplitude = { if (transfer.state == TransferState.RUNNING) 1f else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = Formatters.percent(transfer.progress),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = transferRate(transfer),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
