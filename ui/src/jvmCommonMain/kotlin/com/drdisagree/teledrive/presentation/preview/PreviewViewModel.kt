package com.drdisagree.teledrive.presentation.preview

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.message_download_queued
import com.drdisagree.teledrive.resources.files_copied_all
import com.drdisagree.teledrive.resources.message_moved_count
import com.drdisagree.teledrive.resources.message_moved_to_trash
import com.drdisagree.teledrive.resources.message_renamed
import com.drdisagree.teledrive.resources.message_upload_queued
import com.drdisagree.teledrive.resources.preview_added_favorites
import com.drdisagree.teledrive.resources.preview_archive_entries_saved
import com.drdisagree.teledrive.resources.preview_archive_entry_failed
import com.drdisagree.teledrive.resources.preview_archive_entry_saved
import com.drdisagree.teledrive.resources.preview_archive_failed
import com.drdisagree.teledrive.resources.preview_archive_folder_empty
import com.drdisagree.teledrive.resources.preview_archive_local_only
import com.drdisagree.teledrive.resources.preview_archive_none_saved
import com.drdisagree.teledrive.resources.preview_archived
import com.drdisagree.teledrive.resources.preview_hidden
import com.drdisagree.teledrive.resources.preview_local_copy_removed
import com.drdisagree.teledrive.resources.preview_nothing_to_remove
import com.drdisagree.teledrive.resources.preview_removed_favorites
import com.drdisagree.teledrive.resources.preview_unarchived
import com.drdisagree.teledrive.resources.preview_unhidden
import com.drdisagree.teledrive.core.files.DeleteConsentRequest
import com.drdisagree.teledrive.core.archive.ArchiveDownloadReporter
import com.drdisagree.teledrive.core.archive.ArchiveException
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.DriveFolder
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.LinkMetadata
import com.drdisagree.teledrive.domain.model.PreviewBackgroundMode
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.toUiText
import com.drdisagree.teledrive.presentation.navigation.Route
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository,
    private val trashRepository: TrashRepository,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    private val contentResolver: PreviewContentResolver,
    private val remoteArchiveService: RemoteArchiveService,
    private val archiveDownloadReporter: ArchiveDownloadReporter
) : ViewModel() {

    private val route: Route.Preview = savedStateHandle.toRoute()

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _infoTarget = MutableStateFlow<DriveFile?>(null)
    val infoTarget: StateFlow<DriveFile?> = _infoTarget.asStateFlow()

    private val contentCache = mutableMapOf<String, StateFlow<PreviewContent>>()

    init {
        viewModelScope.launch {
            val target = fileRepository.getFile(route.fileId)
            if (target == null) {
                _uiState.update { it.copy(closed = true) }
                return@launch
            }
            val prefs = settingsRepository.preferences.first()
            val categories = route.categories
                ?.split(CATEGORY_SEPARATOR)
                ?.mapNotNull { name -> runCatching { FileCategory.valueOf(name) }.getOrNull() }
                .orEmpty()
            val sortField = route.sortField
                ?.let { name -> runCatching { FileSortField.valueOf(name) }.getOrNull() }
            val spec = when {
                sortField != null -> FileQuerySpec(
                    folderId = route.folderId,
                    filterByFolder = route.filterByFolder,
                    categories = categories,
                    nameQuery = route.nameQuery,
                    favoritesOnly = route.favoritesOnly,
                    availableOfflineOnly = route.availableOfflineOnly,
                    backedUpOnly = route.backedUpOnly,
                    notBackedUpOnly = route.notBackedUpOnly,
                    missingThumbnailOnly = route.missingThumbnailOnly,
                    withThumbnailOnly = route.withThumbnailOnly,
                    extension = route.extension,
                    minSizeBytes = route.minSizeBytes,
                    maxSizeBytes = route.maxSizeBytes,
                    modifiedAfter = route.modifiedAfter,
                    modifiedBefore = route.modifiedBefore,
                    hiddenOnly = route.hiddenOnly,
                    archivedOnly = route.archivedOnly,
                    showHidden = route.hiddenOnly || target.isHidden,
                    showArchived = route.archivedOnly || target.isArchived,
                    sortField = sortField,
                    sortDirection = if (route.sortDescending) {
                        SortDirection.DESCENDING
                    } else {
                        SortDirection.ASCENDING
                    }
                )

                route.mediaOnly -> FileQuerySpec(
                    categories = listOf(FileCategory.IMAGE, FileCategory.VIDEO),
                    showHidden = target.isHidden,
                    showArchived = target.isArchived,
                    sortField = prefs.sortField,
                    sortDirection = prefs.sortDirection
                )

                else -> FileQuerySpec(
                    folderId = target.folderId,
                    filterByFolder = true,
                    showHidden = target.isHidden,
                    showArchived = target.isArchived,
                    sortField = prefs.sortField,
                    sortDirection = prefs.sortDirection
                )
            }
            val siblings = fileRepository.observeFiles(spec).first()
            val files = if (siblings.any { it.id == target.id }) siblings else listOf(target)
            _uiState.update {
                it.copy(
                    files = files,
                    initialIndex = files.indexOfFirst { file -> file.id == target.id }
                        .coerceAtLeast(0),
                    ready = true
                )
            }
        }
    }

    val backgroundPlayback: StateFlow<Boolean> = settingsRepository.preferences
        .map { it.backgroundPlayback }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val previewBackgroundMode: StateFlow<PreviewBackgroundMode> = settingsRepository.preferences
        .map { it.previewBackgroundMode }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            PreviewBackgroundMode.THEME
        )

    val preferredAudioLanguage: StateFlow<String> = settingsRepository.preferences
        .map { it.preferredAudioLanguage }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val preferredSubtitleLanguage: StateFlow<String> = settingsRepository.preferences
        .map { it.preferredSubtitleLanguage }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun setBackgroundPlayback(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(backgroundPlayback = enabled) }
        }
    }

    fun setPreferredAudioLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(preferredAudioLanguage = language) }
        }
    }

    fun setPreferredSubtitleLanguage(language: String) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(preferredSubtitleLanguage = language) }
        }
    }

    /**
     * Follows the row and transfer queue, so a started download shows progress and the viewer
     * switches when it lands.
     */
    fun contentFor(file: DriveFile): StateFlow<PreviewContent> =
        contentCache.getOrPut(file.id) {
            combine(
                fileRepository.observeFile(file.id),
                transferRepository.observeActiveForFile(file.id)
            ) { latest, transfer ->
                val incoming = transfer?.takeIf { it.type.isIncoming }
                (latest ?: file) to incoming
            }
                .distinctUntilChanged()
                .flatMapLatest { (latest, incoming) ->
                    if (incoming != null) {
                        flowOf(
                            PreviewContent.DownloadProgress(
                                transferred = incoming.transferredBytes,
                                total = incoming.sizeBytes
                            )
                        )
                    } else {
                        contentResolver.resolve(latest)
                    }
                }
                .onEach { content ->
                    if (content is PreviewContent.Archive && content.remote) {
                        _uiState.update {
                            it.copy(archiveListings = it.archiveListings + (file.id to content.entries))
                        }
                    }
                }
                .stateIn(
                    viewModelScope,
                    SharingStarted.WhileSubscribed(5_000),
                    PreviewContent.Loading
                )
        }

    fun observeFile(fileId: String): Flow<DriveFile?> = fileRepository.observeFile(fileId)

    /**
     * Returns the physical parent folder for the currently viewed item.
     * Items in the drive root use the Files label.
     */
    suspend fun parentFolderName(file: DriveFile): String =
        file.folderId?.let { fileRepository.getFolder(it)?.name } ?: "Files"

    fun download(file: DriveFile) {
        viewModelScope.launch {
            when (val result = transferRepository.enqueueDownload(file.id)) {
                is AppResult.Success -> _messages.tryEmit(UiText.Resource(Res.string.message_download_queued))
                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    /**
     * Downloads just the entry at [entryIndex] in the stored listing out of a remotely listed
     * ZIP, saved to Downloads like a normal download. Identity is the listing index, never the
     * entry value, so duplicate names each download their own bytes. A listing that came from a
     * local-only copy has no remote source to extract from, so it gets a clear message instead
     * of a failure.
     */
    fun downloadArchiveEntry(file: DriveFile, entryIndex: Int) {
        if (_uiState.value.archiveEntryBusyIndex != null) return
        val listing = _uiState.value.archiveListings[file.id]
        if (listing == null) {
            _messages.tryEmit(UiText.Resource(Res.string.preview_archive_local_only))
            return
        }
        val entry = listing.getOrNull(entryIndex) ?: return
        if (entry.isDirectory) return
        // Occurrence counts only non-directory entries with this name before this index -
        // the same rule RemoteArchiveService uses to pick the entry, so both sides agree.
        val occurrence = archiveEntryOccurrence(listing, entryIndex)
        if (occurrence < 0) return
        val displayName = entry.name.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { entry.name }
        viewModelScope.launch {
            _uiState.update { it.copy(archiveEntryBusyIndex = entryIndex) }
            // Report-only Transfers history record: written before extraction starts,
            // completed/failed/stopped alongside the extraction's own outcome.
            val recordId = archiveDownloadReporter.begin(displayName, entry.sizeBytes)
            currentCoroutineContext()[Job]?.let { job ->
                archiveDownloadReporter.attach(recordId, job)
            }
            try {
                val savedPath = remoteArchiveService.extractEntry(
                    file,
                    entry.name,
                    occurrence,
                    onProgress = { done -> archiveDownloadReporter.progress(recordId, done) }
                )
                archiveDownloadReporter.complete(recordId, savedPath)
                _messages.tryEmit(
                    UiText.Resource(Res.string.preview_archive_entry_saved, displayName)
                )
            } catch (failure: CancellationException) {
                // The finally block clears the busy state, so a cancelled download cannot wedge
                // later ones; cancellation itself must keep propagating.
                archiveDownloadReporter.ownerStopped(recordId)
                throw failure
            } catch (failure: ArchiveException) {
                archiveDownloadReporter.fail(recordId, failure.message)
                _messages.tryEmit(UiText.Plain(failure.message ?: "Could not extract this file."))
            } catch (failure: Exception) {
                archiveDownloadReporter.fail(recordId, null)
                _messages.tryEmit(UiText.Resource(Res.string.preview_archive_failed))
            } finally {
                _uiState.update { it.copy(archiveEntryBusyIndex = null) }
            }
        }
    }

    /** The ZIP viewer's remembered sort (its own pair in preferences, like the gallery's). */
    val archiveSort: StateFlow<Pair<FileSortField, SortDirection>> =
        settingsRepository.preferences
            .map { it.archiveSortField to it.archiveSortDirection }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                FileSortField.NAME to SortDirection.ASCENDING
            )

    fun setArchiveSort(field: FileSortField, direction: SortDirection) {
        viewModelScope.launch {
            settingsRepository.update {
                it.copy(archiveSortField = field, archiveSortDirection = direction)
            }
        }
    }

    /**
     * Routes a tap on an archive file row: images open in the image viewer, audio plays,
     * text reads in place — each after the same verified extraction a download performs —
     * and everything else (video included, always) downloads. In selection mode the screen
     * toggles selection instead and never calls this.
     */
    fun onArchiveEntryTap(file: DriveFile, entryIndex: Int) {
        val listing = _uiState.value.archiveListings[file.id]
        val entry = listing?.getOrNull(entryIndex)
        if (entry == null || entry.isDirectory) {
            downloadArchiveEntry(file, entryIndex)
            return
        }
        when (archiveEntryTapAction(entry)) {
            ArchiveEntryTapAction.PREVIEW_IMAGE,
            ArchiveEntryTapAction.PLAY_AUDIO,
            ArchiveEntryTapAction.PREVIEW_TEXT -> previewArchiveEntry(file, entryIndex)
            ArchiveEntryTapAction.DOWNLOAD -> downloadArchiveEntry(file, entryIndex)
        }
    }

    /**
     * Extracts the entry at [entryIndex] to a verified temp file (never Downloads) and
     * publishes it as the in-archive preview. Text entries over the preview cap are refused
     * before any extraction, with a message that points at the normal download instead.
     */
    fun previewArchiveEntry(file: DriveFile, entryIndex: Int) {
        if (_uiState.value.archiveEntryBusyIndex != null) return
        val listing = _uiState.value.archiveListings[file.id]
        if (listing == null) {
            _messages.tryEmit(UiText.Resource(Res.string.preview_archive_local_only))
            return
        }
        val entry = listing.getOrNull(entryIndex) ?: return
        if (entry.isDirectory) return
        val action = archiveEntryTapAction(entry)
        if (action == ArchiveEntryTapAction.DOWNLOAD) {
            downloadArchiveEntry(file, entryIndex)
            return
        }
        if (action == ArchiveEntryTapAction.PREVIEW_TEXT && !archiveEntryTextPreviewable(entry)) {
            _messages.tryEmit(
                UiText.Plain(
                    "\"${archiveEntryDisplayName(entry)}\" is too large to preview here " +
                        "(${com.drdisagree.teledrive.presentation.common.Formatters.bytes(entry.sizeBytes)}). " +
                        "Download it instead."
                )
            )
            return
        }
        val occurrence = archiveEntryOccurrence(listing, entryIndex)
        if (occurrence < 0) return
        val displayName = archiveEntryDisplayName(entry)
        viewModelScope.launch {
            _uiState.update { it.copy(archiveEntryBusyIndex = entryIndex) }
            try {
                val temp = remoteArchiveService.extractEntryToTempFile(file, entry.name, occurrence)
                val preview = when (action) {
                    ArchiveEntryTapAction.PREVIEW_IMAGE ->
                        ArchiveEntryPreview.Image(displayName, temp.absolutePath)
                    ArchiveEntryTapAction.PLAY_AUDIO ->
                        ArchiveEntryPreview.Audio(displayName, temp.absolutePath)
                    ArchiveEntryTapAction.PREVIEW_TEXT -> {
                        // String decoding with the UTF-8 charset replaces malformed input
                        // instead of failing, so a non-UTF-8 text file still shows sanely.
                        val text = temp.readBytes().toString(Charsets.UTF_8)
                        temp.delete()
                        ArchiveEntryPreview.Text(displayName, text)
                    }
                    ArchiveEntryTapAction.DOWNLOAD -> error("unreachable")
                }
                _uiState.update { it.copy(archivePreview = preview) }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ArchiveException) {
                _messages.tryEmit(UiText.Plain(failure.message ?: "Could not extract this file."))
            } catch (failure: Exception) {
                _messages.tryEmit(UiText.Resource(Res.string.preview_archive_failed))
            } finally {
                _uiState.update { it.copy(archiveEntryBusyIndex = null) }
            }
        }
    }

    /** Closes the in-archive preview and deletes its temp file (text previews hold no file). */
    fun dismissArchivePreview() {
        val preview = _uiState.value.archivePreview
        _uiState.update { it.copy(archivePreview = null) }
        when (preview) {
            is ArchiveEntryPreview.Image -> java.io.File(preview.path).delete()
            is ArchiveEntryPreview.Audio -> java.io.File(preview.path).delete()
            else -> Unit
        }
    }

    /**
     * Downloads every entry in a multi-selection — directly selected files plus the full
     * contents of selected folders — one at a time through the same verified extraction
     * path as a single download (sequential on purpose: the ranged reads share one
     * Telegram source discipline and must not be parallelized). A failure is reported
     * per entry and does NOT stop the rest of the selection; the completion message
     * reports how many files were saved.
     */
    fun downloadArchiveSelection(
        file: DriveFile,
        entryIndices: Set<Int>,
        folderPaths: Set<String>
    ) {
        if (_uiState.value.archiveEntryBusyIndex != null) return
        val listing = _uiState.value.archiveListings[file.id]
        if (listing == null) {
            _messages.tryEmit(UiText.Resource(Res.string.preview_archive_local_only))
            return
        }
        val plan = planArchiveSelectionDownloads(listing, entryIndices, folderPaths)
        if (plan.isEmpty()) {
            _messages.tryEmit(UiText.Resource(Res.string.preview_archive_folder_empty))
            return
        }
        viewModelScope.launch {
            var saved = 0
            _uiState.update { it.copy(archiveBatchProgress = ArchiveBatchProgress(0, plan.size)) }
            try {
                for (step in plan) {
                    _uiState.update { it.copy(archiveEntryBusyIndex = step.entryIndex) }
                    val stepEntry = listing[step.entryIndex]
                    val recordId = archiveDownloadReporter.begin(
                        archiveEntryDisplayName(stepEntry),
                        stepEntry.sizeBytes
                    )
                    try {
                        // The extraction runs as a CHILD job registered under this step's
                        // record, so a stop from Transfers cancels only this step's job
                        // while this batch coroutine stays alive to continue with the rest.
                        val stepJob = async {
                            remoteArchiveService.extractEntry(
                                file = file,
                                entryName = step.entryName,
                                occurrence = step.occurrence,
                                subFolderPath = step.subFolderPath.ifBlank { null },
                                onProgress = { done ->
                                    archiveDownloadReporter.progress(recordId, done)
                                }
                            )
                        }
                        archiveDownloadReporter.attach(recordId, stepJob)
                        val savedPath = stepJob.await()
                        archiveDownloadReporter.complete(recordId, savedPath)
                        saved++
                    } catch (failure: CancellationException) {
                        archiveDownloadReporter.ownerStopped(recordId)
                        if (currentCoroutineContext().isActive) {
                            // Per-record stop from Transfers: the repository already wrote
                            // CANCELLED or PAUSED for this record. No failure message, not
                            // counted as saved — the batch continues with the next step.
                        } else {
                            // Real teardown of the batch itself: keep propagating.
                            throw failure
                        }
                    } catch (failure: ArchiveException) {
                        archiveDownloadReporter.fail(recordId, failure.message)
                        _messages.tryEmit(
                            UiText.Resource(
                                Res.string.preview_archive_entry_failed,
                                archiveEntryDisplayName(listing[step.entryIndex]),
                                failure.message ?: "unknown error"
                            )
                        )
                    } catch (failure: Exception) {
                        archiveDownloadReporter.fail(recordId, null)
                        _messages.tryEmit(
                            UiText.Resource(
                                Res.string.preview_archive_entry_failed,
                                archiveEntryDisplayName(listing[step.entryIndex]),
                                "unknown error"
                            )
                        )
                    }
                    _uiState.update {
                        it.copy(
                            archiveBatchProgress = ArchiveBatchProgress(
                                (it.archiveBatchProgress?.done ?: 0) + 1,
                                plan.size
                            )
                        )
                    }
                }
            } finally {
                _uiState.update {
                    it.copy(archiveEntryBusyIndex = null, archiveBatchProgress = null)
                }
            }
            when {
                saved == 0 -> _messages.tryEmit(
                    UiText.Resource(Res.string.preview_archive_none_saved)
                )
                plan.size == 1 -> _messages.tryEmit(
                    UiText.Resource(
                        Res.string.preview_archive_entry_saved,
                        archiveEntryDisplayName(listing[plan.first().entryIndex])
                    )
                )
                else -> _messages.tryEmit(
                    UiText.Resource(Res.string.preview_archive_entries_saved, saved)
                )
            }
        }
    }

    fun upload(file: DriveFile) {
        viewModelScope.launch {
            when (val result = transferRepository.enqueueUpload(file.id)) {
                is AppResult.Success -> _messages.tryEmit(UiText.Resource(Res.string.message_upload_queued))
                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    suspend fun childFolders(parentId: String?): List<DriveFolder> =
        fileRepository.observeFolders(
            parentId = parentId,
            showHidden = true,
            showArchived = true
        ).first()

    suspend fun createFolderIn(parentId: String?, name: String): Boolean =
        fileRepository.createFolder(parentId, name) is AppResult.Success

    fun copyTo(file: DriveFile, targetFolderId: String?) {
        viewModelScope.launch {
            when (val result = fileRepository.copyFiles(listOf(file.id), targetFolderId)) {
                is AppResult.Success ->
                    _messages.tryEmit(UiText.Resource(Res.string.files_copied_all, result.value))
                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun moveTo(file: DriveFile, targetFolderId: String?, currentIndex: Int) {
        if (file.folderId == targetFolderId) return
        viewModelScope.launch {
            when (val result = fileRepository.moveFiles(listOf(file.id), targetFolderId)) {
                is AppResult.Success -> {
                    val moveResult = previewAfterMove(
                        currentFiles = _uiState.value.files,
                        movedFile = file,
                        currentIndex = currentIndex
                    )
                    if (moveResult.closeViewer) {
                        // No media remains in this viewer sequence; return to its caller.
                        _uiState.update {
                            it.copy(
                                files = emptyList(),
                                initialIndex = 0,
                                closed = true
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                files = moveResult.files,
                                initialIndex = moveResult.initialIndex,
                                ready = true,
                                closed = false
                            )
                        }
                    }
                    _messages.tryEmit(
                        UiText.Resource(Res.string.message_moved_count, result.value)
                    )
                }
                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    private val _deleteConsentRequests =
        MutableSharedFlow<DeleteConsentRequest>(extraBufferCapacity = 1)
    val deleteConsentRequests = _deleteConsentRequests.asSharedFlow()
    private var pendingLocalCopyId: String? = null

    fun freeUpSpace(file: DriveFile) {
        removeLocalCopy(file.id)
    }

    fun retryLocalCopyRemoval() {
        pendingLocalCopyId?.let(::removeLocalCopy)
    }

    private suspend fun refreshFile(fileId: String) {
        val updated = fileRepository.getFiles(listOf(fileId)).firstOrNull() ?: return
        _uiState.update { state ->
            state.copy(files = state.files.map { if (it.id == fileId) updated else it })
        }
    }

    private fun removeLocalCopy(fileId: String) {
        viewModelScope.launch {
            when (val result = fileRepository.deleteLocalCopy(listOf(fileId))) {
                is AppResult.Success -> {
                    val consent = result.value.consentRequest
                    if (consent != null) {
                        pendingLocalCopyId = fileId
                        _deleteConsentRequests.tryEmit(consent)
                    } else {
                        pendingLocalCopyId = null
                        _messages.tryEmit(
                            if (result.value.deletedCount > 0) {
                                UiText.Resource(Res.string.preview_local_copy_removed)
                            } else {
                                UiText.Resource(Res.string.preview_nothing_to_remove)
                            }
                        )
                    }
                    refreshFile(fileId)
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun setFavorite(file: DriveFile, favorite: Boolean) {
        viewModelScope.launch {
            fileRepository.setFilesFavorite(listOf(file.id), favorite)
            refreshFile(file.id)
            _messages.tryEmit(
                if (favorite) UiText.Resource(Res.string.preview_added_favorites) else UiText.Resource(
                    Res.string.preview_removed_favorites
                )
            )
        }
    }

    fun setHidden(file: DriveFile, hidden: Boolean) {
        viewModelScope.launch {
            fileRepository.setFilesHidden(listOf(file.id), hidden)
            refreshFile(file.id)
            _messages.tryEmit(
                if (hidden) UiText.Resource(Res.string.preview_hidden) else UiText.Resource(
                    Res.string.preview_unhidden
                )
            )
        }
    }

    fun setArchived(file: DriveFile, archived: Boolean) {
        viewModelScope.launch {
            fileRepository.setFilesArchived(listOf(file.id), archived)
            refreshFile(file.id)
            _messages.tryEmit(
                if (archived) UiText.Resource(Res.string.preview_archived) else UiText.Resource(
                    Res.string.preview_unarchived
                )
            )
        }
    }

    fun rename(file: DriveFile, newName: String) {
        viewModelScope.launch {
            when (val result = fileRepository.renameFile(file.id, newName)) {
                is AppResult.Success -> {
                    refreshFile(file.id)
                    _messages.tryEmit(UiText.Resource(Res.string.message_renamed))
                }

                is AppResult.Failure -> _messages.tryEmit(result.error.toUiText())
            }
        }
    }

    fun moveToTrash(file: DriveFile) {
        viewModelScope.launch {
            trashRepository.moveFilesToTrash(listOf(file.id))
            _messages.tryEmit(UiText.Resource(Res.string.message_moved_to_trash))
            val remaining = _uiState.value.files.filterNot { it.id == file.id }
            if (remaining.isEmpty()) {
                _uiState.update { it.copy(closed = true) }
            } else {
                _uiState.update { it.copy(files = remaining) }
            }
        }
    }

    val textScale: StateFlow<Float> = settingsRepository.preferences
        .map { it.textPreviewScale }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    fun setTextScale(scale: Float) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(textPreviewScale = scale) }
        }
    }

    private val linkCache = mutableMapOf<String, LinkMetadata?>()

    suspend fun linkPreview(url: String): LinkMetadata? =
        linkCache.getOrPut(url) { fileRepository.linkPreview(url) }

    fun showInfo(file: DriveFile) {
        viewModelScope.launch {
            _infoTarget.update { fileRepository.getFile(file.id) ?: file }
        }
    }

    fun dismissInfo() = _infoTarget.update { null }
}
