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
import com.drdisagree.teledrive.resources.preview_archive_downloading_selected
import com.drdisagree.teledrive.resources.preview_archive_entries_saved
import com.drdisagree.teledrive.resources.preview_archive_entry_failed
import com.drdisagree.teledrive.resources.preview_archive_entry_saved
import com.drdisagree.teledrive.resources.preview_archive_entry_saving
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
import com.drdisagree.teledrive.core.archive.ArchiveErrorKind
import com.drdisagree.teledrive.core.archive.ArchiveException
import com.drdisagree.teledrive.core.archive.LocalArchiveFormat
import com.drdisagree.teledrive.core.archive.LocalArchiveService
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
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
    private val localArchiveService: LocalArchiveService,
    private val archiveDownloadReporter: ArchiveDownloadReporter
) : ViewModel() {

    private val route: Route.Preview = savedStateHandle.toRoute()

    private val _uiState = MutableStateFlow(PreviewUiState())
    val uiState: StateFlow<PreviewUiState> = _uiState.asStateFlow()

    private val _messages = MutableSharedFlow<UiText>(extraBufferCapacity = 8)
    val messages = _messages.asSharedFlow()

    private val _infoTarget = MutableStateFlow<DriveFile?>(null)
    val infoTarget: StateFlow<DriveFile?> = _infoTarget.asStateFlow()

    private val _archiveInfoTarget = MutableStateFlow<ArchiveEntryInfo?>(null)
    val archiveInfoTarget: StateFlow<ArchiveEntryInfo?> = _archiveInfoTarget.asStateFlow()

    private val contentCache = mutableMapOf<String, StateFlow<PreviewContent>>()

    /** The in-flight in-zip preview extraction (initial open or a pager step), if any. */
    private var archivePreviewJob: Job? = null

    /** Archive format label ("ZIP", "CBZ", "7Z", …) per file id, as last resolved. */
    private val archiveFormats = mutableMapOf<String, String>()

    /** A local 7z/TAR copy a [LocalArchiveService] extraction can read, per file id. */
    private data class LocalArchiveSource(val path: String, val format: LocalArchiveFormat)

    private val localArchives = mutableMapOf<String, LocalArchiveSource>()

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
                    if (content is PreviewContent.Archive) {
                        archiveFormats[file.id] = content.format
                    }
                    if (content is PreviewContent.Archive && content.remote) {
                        _uiState.update {
                            it.copy(archiveListings = it.archiveListings + (file.id to content.entries))
                        }
                    }
                    // A local 7z/TAR listing is tappable too (vc181 B10): its
                    // entries extract from the local copy via LocalArchiveService,
                    // so the listing is stored and the local source remembered.
                    // Local ZIP/CBZ listings are NOT stored here — they keep the
                    // existing local-only behaviour unless re-marked remote.
                    if (content is PreviewContent.Archive && !content.remote) {
                        val format = LocalArchiveFormat.fromArchiveLabel(content.format)
                        val localPath = file.localPath
                            ?.takeIf { java.io.File(it).exists() }
                        if (format != null && localPath != null) {
                            localArchives[file.id] = LocalArchiveSource(localPath, format)
                            _uiState.update {
                                it.copy(
                                    archiveListings = it.archiveListings +
                                        (file.id to content.entries)
                                )
                            }
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
     * One accepted in-ZIP download request. Requests run strictly one at a time, in
     * acceptance order, through [drainArchiveDownloadQueue]: ranged reads on one
     * archive fileId share one Telegram window, so extractions of the same archive
     * must stay serialised even though starting them is no longer blocked (ported
     * from PC 1.6.24 note 2 — the old code early-returned while any download ran,
     * which silently dropped the tap for the whole duration of a download).
     */
    private sealed interface ArchiveDownloadRequest {
        val file: DriveFile

        /** A single entry, saved flat like a tap-download. */
        data class Single(
            override val file: DriveFile,
            val step: ArchiveDownloadStep,
            val displayName: String,
            val sizeBytes: Long
        ) : ArchiveDownloadRequest

        /** A Download-selected batch: [plan] steps over the listing snapshot [listing]. */
        data class Batch(
            override val file: DriveFile,
            val listing: List<ArchiveEntry>,
            val plan: List<ArchiveDownloadStep>
        ) : ArchiveDownloadRequest
    }

    private val archiveDownloadQueue = ArrayDeque<ArchiveDownloadRequest>()
    private var archiveDownloadProcessor: Job? = null

    /**
     * The single serialisation gate for EVERY archive extraction of this
     * viewer — queued downloads AND previews (MF-1, report 54 second pass,
     * ported with the fix, not the pre-fix design). [RemoteArchiveService]
     * has no internal lock and all extractions of one archive share the one
     * TDLib window for its fileId, so two extractions must never run at once
     * on either axis:
     *  - the queue processor [Mutex.lock]s the gate before running a request,
     *    so a download waits while a preview extraction is in flight;
     *  - a preview load [Mutex.lock]s the gate as the first step of its job
     *    (see loadArchivePreview), so it can never run concurrently with a
     *    download either; the entry points keep their existing busy-index
     *    refusals, so a preview tap while a download runs is still dropped
     *    exactly as before — the gate closes the race where a just-tapped
     *    preview had launched but not yet marked anything.
     * Each download holder passes a unique owner token, preview loads share
     * [archivePreviewBusyOwner], and the busy index is only ever cleared by
     * its owner ([clearArchiveBusy]), so one extraction's teardown can no
     * longer wipe the other's busy mark. Preview loads claim their busy mark
     * at registration time — before their job starts, hence before/independently
     * of gate acquisition (MF-1) — so a load waiting on the gate behind a
     * download is visible (busy/loading) instead of silently popping in when
     * the download finishes, and it never claims a download-owned mark.
     */
    private val archiveExtractionMutex = Mutex()
    private var archiveBusyOwner: Any? = null
    private val archiveBusyLock = Any()

    /** Marks [entryIndex] busy on behalf of [owner] (the gate holder). */
    private fun markArchiveBusy(owner: Any, entryIndex: Int) {
        synchronized(archiveBusyLock) { archiveBusyOwner = owner }
        _uiState.update { it.copy(archiveEntryBusyIndex = entryIndex) }
    }

    /** Clears the busy index only if [owner] is the one who set it. */
    private fun clearArchiveBusy(owner: Any) {
        val owns = synchronized(archiveBusyLock) {
            if (archiveBusyOwner === owner) {
                archiveBusyOwner = null
                true
            } else {
                false
            }
        }
        if (owns) _uiState.update { it.copy(archiveEntryBusyIndex = null) }
    }

    /**
     * The busy-owner token for preview loads (second-pass MF-1). Downloads
     * each own a unique token; preview loads share this one, because a
     * superseding preview load must be able to take over the previous preview
     * load's busy claim (the superseded load's teardown is identity-guarded
     * and never clears it), while NO preview-side teardown — dismiss,
     * pager settle-back, video placeholder — may ever clear a busy mark that
     * belongs to a download, and vice versa. All preview-side clears go
     * through [clearArchiveBusy] with this token.
     */
    private val archivePreviewBusyOwner = Any()

    /**
     * Claims the busy index for a preview load on behalf of
     * [archivePreviewBusyOwner], unless a download currently owns it (a load
     * waiting on the extraction gate behind that download must not steal or
     * later clear the download's mark). Returns true when the preview side
     * owns the busy index after the call.
     */
    private fun claimArchivePreviewBusy(entryIndex: Int): Boolean {
        val owns = synchronized(archiveBusyLock) {
            if (archiveBusyOwner == null || archiveBusyOwner === archivePreviewBusyOwner) {
                archiveBusyOwner = archivePreviewBusyOwner
                true
            } else {
                false
            }
        }
        if (owns) _uiState.update { it.copy(archiveEntryBusyIndex = entryIndex) }
        return owns
    }

    /**
     * Accepts a download request: it is queued behind any running request and its
     * start message is emitted immediately (the message carries the View ->
     * Transfers action, so it must be on screen from the moment the download is
     * accepted, not only when it completes — the vc180 instant-View behaviour,
     * extended from the running case to the queued case). The Transfers record
     * is deliberately NOT created here: records are created when a request
     * actually starts running, so a queued request has no record to cancel yet
     * and the history never shows work that has not begun.
     */
    private fun enqueueArchiveDownload(request: ArchiveDownloadRequest, startedMessage: UiText) {
        // The add and the processor liveness check are one locked step with the
        // drain loop's exit decision, so a request can never land in the gap
        // between the processor seeing an empty queue and it finishing.
        synchronized(archiveDownloadQueue) {
            archiveDownloadQueue.addLast(request)
            if (archiveDownloadProcessor?.isActive != true) {
                archiveDownloadProcessor = viewModelScope.launch { drainArchiveDownloadQueue() }
            }
        }
        publishArchiveQueueDepth()
        _messages.tryEmit(startedMessage)
    }

    private fun publishArchiveQueueDepth() {
        val depth = synchronized(archiveDownloadQueue) { archiveDownloadQueue.size }
        _uiState.update { it.copy(archiveQueuedDownloads = depth) }
    }

    /** Runs queued requests one at a time until the queue is empty. */
    private suspend fun drainArchiveDownloadQueue() {
        while (true) {
            val request = synchronized(archiveDownloadQueue) {
                val next = archiveDownloadQueue.removeFirstOrNull()
                if (next == null) archiveDownloadProcessor = null
                next
            } ?: return
            publishArchiveQueueDepth()
            // Per-request isolation (MF-2): one request failing outside its
            // own inner catches must never kill the processor and strand the
            // requests still queued behind it. The failure is surfaced and
            // the loop CONTINUES with the next request; only a cancellation
            // of the processor's own context propagates.
            val owner = Any()
            try {
                // MF-1: hold the extraction gate for the whole request, so a
                // download never runs concurrently with a preview extraction
                // (it waits here until the preview finishes).
                archiveExtractionMutex.lock(owner)
                try {
                    when (request) {
                        is ArchiveDownloadRequest.Single -> runArchiveSingleDownload(request, owner)
                        is ArchiveDownloadRequest.Batch -> runArchiveBatchDownload(request, owner)
                    }
                } finally {
                    archiveExtractionMutex.unlock(owner)
                }
            } catch (failure: CancellationException) {
                if (!currentCoroutineContext().isActive) throw failure
                _messages.tryEmit(UiText.Resource(Res.string.preview_archive_failed))
            } catch (failure: Exception) {
                _messages.tryEmit(UiText.Resource(Res.string.preview_archive_failed))
            }
        }
    }

    /**
     * Downloads just the entry at [entryIndex] in the stored listing out of a remotely listed
     * ZIP, saved to Downloads like a normal download. Identity is the listing index, never the
     * entry value, so duplicate names each download their own bytes. A listing that came from a
     * local-only copy has no remote source to extract from, so it gets a clear message instead
     * of a failure. While another download is running this one queues behind it instead of
     * being dropped (ported from PC 1.6.24 note 2).
     */
    fun downloadArchiveEntry(file: DriveFile, entryIndex: Int) {
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
        val displayName = archiveEntryDisplayName(entry)
        enqueueArchiveDownload(
            request = ArchiveDownloadRequest.Single(
                file = file,
                step = ArchiveDownloadStep(entryIndex, entry.name, occurrence),
                displayName = displayName,
                sizeBytes = entry.sizeBytes
            ),
            startedMessage = UiText.Resource(
                Res.string.preview_archive_entry_saving, displayName
            )
        )
    }

    /** Runs one queued single-entry download; see [downloadArchiveEntry]. */
    private suspend fun runArchiveSingleDownload(
        request: ArchiveDownloadRequest.Single,
        owner: Any
    ) {
        val file = request.file
        val step = request.step
        markArchiveBusy(owner, step.entryIndex)
        // Report-only Transfers history record: written before extraction starts,
        // completed/failed/stopped alongside the extraction's own outcome. The
        // begin() call lives INSIDE the try (MF-2): it is a suspending Room
        // insert, and a throw from it must land in the catches below (recordId
        // still null, which every reporter call tolerates) instead of escaping
        // and killing the queue processor.
        var recordId: String? = null
        try {
            recordId = archiveDownloadReporter.begin(request.displayName, request.sizeBytes)
            // The extraction runs as a CHILD job registered under this record, so a
            // stop from Transfers cancels only this extraction while the queue
            // processor stays alive to run the next request — the same discipline
            // the batch path uses per step. (Pre-queue this path attached the
            // whole launch job; with a queue that would have killed the processor.)
            val savedPath = coroutineScope {
                val stepJob = async {
                    val local = localArchives[file.id]
                    if (local != null) {
                        localArchiveService.extractEntry(
                            file = file,
                            localPath = local.path,
                            format = local.format,
                            entryIndex = step.entryIndex,
                            entryName = step.entryName,
                            onProgress = { done ->
                                archiveDownloadReporter.progress(recordId, done)
                            }
                        )
                    } else {
                        remoteArchiveService.extractEntry(
                            file,
                            step.entryName,
                            step.occurrence,
                            onProgress = { done ->
                                archiveDownloadReporter.progress(recordId, done)
                            }
                        )
                    }
                }
                archiveDownloadReporter.attach(recordId, stepJob)
                stepJob.await()
            }
            archiveDownloadReporter.complete(recordId, savedPath)
            _messages.tryEmit(
                UiText.Resource(Res.string.preview_archive_entry_saved, request.displayName)
            )
        } catch (failure: CancellationException) {
            // vc183 ITEM 1: pass the cause through — a registry stop lands
            // CANCELLED, an internal byte-source timeout lands FAILED.
            archiveDownloadReporter.ownerStopped(recordId, failure)
            if (!currentCoroutineContext().isActive) {
                // Real teardown of the queue processor itself: keep propagating.
                // (A per-record stop from Transfers leaves this context active and
                // falls through, so the queue continues with the next request.)
                throw failure
            }
        } catch (failure: ArchiveException) {
            archiveDownloadReporter.fail(recordId, failure.message)
            _messages.tryEmit(UiText.Plain(failure.message ?: "Could not extract this file."))
        } catch (failure: Exception) {
            archiveDownloadReporter.fail(recordId, null)
            _messages.tryEmit(UiText.Resource(Res.string.preview_archive_failed))
        } finally {
            clearArchiveBusy(owner)
        }
    }

    /**
     * Downloads the entry currently open in the in-zip viewer (vc182), through
     * the exact same [downloadArchiveEntry] path as a list-row download — so
     * the instant "saving" message and the Transfers record behave identically.
     * The download runs in viewModelScope, not in the preview load job, and
     * [dismissArchivePreview] only releases the busy slot for a preview load,
     * so dismissing the overlay mid-download lets the download complete.
     */
    fun downloadCurrentArchiveEntry() {
        val state = _uiState.value
        val fileId = state.archivePreviewFileId ?: return
        val entryIndex = state.archivePreview?.entryIndex ?: return
        val file = state.files.firstOrNull { it.id == fileId } ?: return
        downloadArchiveEntry(file, entryIndex)
    }

    /** Opens the Info sheet for the entry currently open in the in-zip viewer (vc182). */
    fun showArchiveEntryInfo() {
        val state = _uiState.value
        val fileId = state.archivePreviewFileId ?: return
        val entryIndex = state.archivePreview?.entryIndex ?: return
        val file = state.files.firstOrNull { it.id == fileId } ?: return
        val entry = state.archiveListings[fileId]?.getOrNull(entryIndex) ?: return
        _archiveInfoTarget.update {
            ArchiveEntryInfo(
                entry = entry,
                archiveName = file.name,
                archiveFormat = archiveFormats[fileId].orEmpty()
            )
        }
    }

    fun dismissArchiveEntryInfo() = _archiveInfoTarget.update { null }

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
     * Routes a tap on an archive file row: images open in the image viewer, audio
     * and video play, text reads in place — each after the same verified
     * extraction a download performs — and everything else downloads. In
     * selection mode the screen toggles selection instead and never calls this.
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
            ArchiveEntryTapAction.PLAY_VIDEO,
            ArchiveEntryTapAction.PREVIEW_TEXT,
            ArchiveEntryTapAction.PREVIEW_PDF,
            ArchiveEntryTapAction.PREVIEW_DOCUMENT -> previewArchiveEntry(file, entryIndex)
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
        if (action == ArchiveEntryTapAction.PREVIEW_DOCUMENT &&
            !archiveEntryDocumentPreviewable(entry)
        ) {
            _messages.tryEmit(
                UiText.Plain(
                    "\"${archiveEntryDisplayName(entry)}\" is too large to preview here " +
                        "(${com.drdisagree.teledrive.presentation.common.Formatters.bytes(entry.sizeBytes)}). " +
                        "Download it instead."
                )
            )
            return
        }
        if (action == ArchiveEntryTapAction.PREVIEW_PDF && !archiveEntryPdfPreviewable(entry)) {
            _messages.tryEmit(
                UiText.Plain(
                    "\"${archiveEntryDisplayName(entry)}\" is too large to preview here " +
                        "(${com.drdisagree.teledrive.presentation.common.Formatters.bytes(entry.sizeBytes)}). " +
                        "Download it instead."
                )
            )
            return
        }
        // vc183: ONE unified sequence for every previewable type (video
        // included), in display order — a direct list tap on a video is a
        // deliberate open, so it extracts and plays immediately (with the
        // vc181 preflight inside loadArchivePreview), but it publishes its
        // position in the unified sequence so swipe and the player's
        // prev/next can cross to neighbouring images/text/etc. and back.
        // A CBZ reads as a comic: image pages only, in natural path order —
        // not the archive list's sort. Any other tap inside a CBZ (a text file,
        // say) keeps the plain ZIP pager behaviour.
        val pagerIndices = if (archiveFormats[file.id] == "CBZ" &&
            action == ArchiveEntryTapAction.PREVIEW_IMAGE
        ) {
            comicPageSequence(listing)
        } else {
            archiveUnifiedPreviewIndices(
                listing,
                archiveSort.value.first,
                archiveSort.value.second
            )
        }
        loadArchivePreview(
            file = file,
            entryIndex = entryIndex,
            pagerIndices = pagerIndices,
            pagerPosition = archivePreviewPagerPosition(pagerIndices, entryIndex)
        )
    }

    /**
     * Publishes a video placeholder page at [pagerPosition] in [pagerIndices]
     * (vc183): no extraction, no preflight, no temp file. Used when a swipe or
     * a prev/next step settles on a video — the deliberate Play tap on the
     * placeholder ([playCurrentArchiveVideo]) is what extracts it. Any
     * in-flight preview load is superseded (cancelled); the previous preview's
     * temp is deleted only after the placeholder is published.
     */
    private fun publishArchiveVideoPlaceholder(
        file: DriveFile,
        entryIndex: Int,
        pagerIndices: List<Int>,
        pagerPosition: Int
    ) {
        val listing = _uiState.value.archiveListings[file.id] ?: return
        val entry = listing.getOrNull(entryIndex) ?: return
        archivePreviewJob?.cancel()
        archivePreviewJob = null
        val previous = _uiState.value.archivePreview
        _uiState.update {
            it.copy(
                archivePreview = ArchiveEntryPreview.VideoPlaceholder(
                    name = archiveEntryDisplayName(entry),
                    entryIndex = entryIndex,
                    sizeBytes = entry.sizeBytes
                ),
                archivePreviewEntries = pagerIndices,
                archivePreviewPosition = pagerPosition,
                archivePreviewFileId = file.id,
                archiveVideoQueue = pagerIndices,
                archiveVideoQueuePosition = pagerPosition,
                archivePreviewLoading = false
            )
        }
        // MF-1: release only the preview side's own busy claim — a running
        // download's busy mark (its own owner token) survives publishing a
        // placeholder over it.
        clearArchiveBusy(archivePreviewBusyOwner)
        if (previous != null && previous.entryIndex != entryIndex) {
            deleteArchivePreviewTemp(previous)
        }
    }

    /** Deliberate Play on a video placeholder page: extract + play that entry. */
    fun playCurrentArchiveVideo() {
        val state = _uiState.value
        val placeholder = state.archivePreview as? ArchiveEntryPreview.VideoPlaceholder ?: return
        if (archivePreviewJob?.isActive != true && state.archiveEntryBusyIndex != null) return
        val fileId = state.archivePreviewFileId ?: return
        val file = state.files.firstOrNull { it.id == fileId } ?: return
        loadArchivePreview(
            file = file,
            entryIndex = placeholder.entryIndex,
            pagerIndices = state.archivePreviewEntries,
            pagerPosition = state.archivePreviewPosition
        )
    }

    /**
     * Extracts [entryIndex] to a verified temp file and publishes it as the in-archive
     * preview at [pagerPosition] in [pagerIndices]. Shared by the initial tap-open and
     * by pager steps: a step keeps the current preview (and position) published until
     * the new entry has landed, then swaps and deletes the previous temp file. A new
     * load supersedes an in-flight preview load — fast swipes cancel the extraction
     * they leave behind (the archive service deletes its partial temp on failure,
     * cancellation included) and only the last page swiped to is published.
     *
     * vc183 superseded-load discipline: the job is created LAZY, registered as
     * [archivePreviewJob] BEFORE it starts, and every publish in it — success,
     * step-failure resync and error message alike — is guarded by
     * [archivePreviewLoadMayPublish] on job identity. A superseded load
     * publishes nothing (see PreviewUiState for the false-corruption story).
     */
    private fun loadArchivePreview(
        file: DriveFile,
        entryIndex: Int,
        pagerIndices: List<Int>,
        pagerPosition: Int,
        videoQueuePosition: Int? = null
    ) {
        val listing = _uiState.value.archiveListings[file.id] ?: return
        val entry = listing.getOrNull(entryIndex) ?: return
        val action = archiveEntryTapAction(entry)
        if (action == ArchiveEntryTapAction.DOWNLOAD) return
        val occurrence = archiveEntryOccurrence(listing, entryIndex)
        if (occurrence < 0) return
        val displayName = archiveEntryDisplayName(entry)
        archivePreviewJob?.cancel()
        val previous = _uiState.value.archivePreview
        // MF-1 gate owner for this load: the job below holds the extraction
        // gate for its whole extraction, so a queued download can never run
        // concurrently with it (the queue processor locks the same mutex).
        // A superseding load cancels this job first; its finally releases
        // the gate and the replacement's lock() then proceeds — preview vs
        // preview serialisation stays the existing job-cancellation one.
        val gateOwner = Any()
        // MF-1 (second pass): register this load's busy/loading state NOW,
        // before the job starts — not inside the job after lock acquisition.
        // A load that must wait on the gate behind a running download is
        // therefore visible while it waits (archivePreviewLoading, and the
        // busy index whenever the preview side owns it) instead of sitting
        // invisible and popping in unprompted when the download finishes.
        // The claim goes through the owner discipline: if a download owns
        // the busy index, the claim leaves it untouched (the load still gets
        // its loading state), and this load's teardown will only ever clear
        // a preview-owned mark.
        claimArchivePreviewBusy(entryIndex)
        _uiState.update { it.copy(archivePreviewLoading = previous != null) }
        val job = viewModelScope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            // If this job is cancelled while waiting for the gate, lock()
            // throws before the try is entered and nothing is held. The gate
            // is the job's FIRST step; the vc183 publish guard below is
            // complementary — the gate serialises against downloads, the
            // guard serialises publishes against superseding preview loads.
            archiveExtractionMutex.lock(gateOwner)
            try {
            val myJob = currentCoroutineContext()[Job]
            val isCurrentLoad = {
                archivePreviewLoadMayPublish(archivePreviewJob === myJob)
            }
            // Re-claim the busy mark now that the gate is held: while this
            // load waited, a download may have owned and then released the
            // index, so the extraction that is actually starting re-marks
            // its own entry (a no-op if the registration claim still holds).
            if (isCurrentLoad()) claimArchivePreviewBusy(entryIndex)
            try {
                if (action == ArchiveEntryTapAction.PLAY_VIDEO) {
                    val tempDir = java.io.File(
                        System.getProperty("java.io.tmpdir") ?: "."
                    )
                    val freeBytes = tempDir.usableSpace
                    if (!archiveVideoFitsInFreeSpace(entry.sizeBytes, freeBytes)) {
                        throw ArchiveException(
                            ArchiveErrorKind.INSUFFICIENT_SPACE,
                            "There is not enough free space to play this video here. " +
                                "It needs about " +
                                com.drdisagree.teledrive.presentation.common.Formatters
                                    .bytes(entry.sizeBytes) +
                                " but only " +
                                com.drdisagree.teledrive.presentation.common.Formatters
                                    .bytes(freeBytes) +
                                " is free. Download it instead."
                        )
                    }
                }
                val local = localArchives[file.id]
                val temp = if (local != null) {
                    localArchiveService.extractEntryToTempFile(
                        local.path,
                        local.format,
                        entryIndex,
                        entry.name
                    )
                } else {
                    remoteArchiveService.extractEntryToTempFile(file, entry.name, occurrence)
                }
                // A superseded load that finished anyway (a non-suspending
                // local extraction can outrun cancellation) publishes
                // nothing and deletes its own temp.
                if (!isCurrentLoad()) {
                    temp.delete()
                    return@launch
                }
                val preview = when (action) {
                    ArchiveEntryTapAction.PREVIEW_IMAGE ->
                        ArchiveEntryPreview.Image(displayName, temp.absolutePath, entryIndex)
                    ArchiveEntryTapAction.PLAY_AUDIO ->
                        ArchiveEntryPreview.Audio(displayName, temp.absolutePath, entryIndex)
                    ArchiveEntryTapAction.PREVIEW_PDF ->
                        ArchiveEntryPreview.Pdf(displayName, temp.absolutePath, entryIndex)
                    ArchiveEntryTapAction.PREVIEW_DOCUMENT -> {
                        val extraction = try {
                            extractDocumentText(temp)
                        } finally {
                            temp.delete()
                        }
                        when (extraction) {
                            is DocumentExtraction.Text -> ArchiveEntryPreview.Text(
                                displayName,
                                extraction.text,
                                entryIndex,
                                truncated = extraction.truncated
                            )
                            DocumentExtraction.DrmProtected -> throw DocumentPreviewException(
                                "\"$displayName\" is DRM-protected and cannot be opened here."
                            )
                            DocumentExtraction.Failed -> throw DocumentPreviewException(
                                "Could not read \"$displayName\"."
                            )
                        }
                    }
                    ArchiveEntryTapAction.PLAY_VIDEO ->
                        ArchiveEntryPreview.Video(displayName, temp.absolutePath, entryIndex)
                    ArchiveEntryTapAction.PREVIEW_TEXT -> {
                        val entryMime =
                            com.drdisagree.teledrive.core.files.MimeTypes.fromFileName(entry.name)
                        val text = com.drdisagree.teledrive.core.files.formatPreviewText(
                            temp.readBytes().toString(Charsets.UTF_8),
                            entryMime
                        )
                        temp.delete()
                        ArchiveEntryPreview.Text(
                            displayName,
                            text,
                            entryIndex,
                            isMarkdown = com.drdisagree.teledrive.core.files.MimeTypes
                                .isMarkdown(entryMime),
                            isHtml = com.drdisagree.teledrive.core.files.MimeTypes
                                .isHtml(entryMime)
                        )
                    }
                    ArchiveEntryTapAction.DOWNLOAD -> error("unreachable")
                }
                if (!isCurrentLoad()) {
                    if (preview is ArchiveEntryPreview.Image) java.io.File(preview.path).delete()
                    if (preview is ArchiveEntryPreview.Audio) java.io.File(preview.path).delete()
                    if (preview is ArchiveEntryPreview.Pdf) java.io.File(preview.path).delete()
                    if (preview is ArchiveEntryPreview.Video) java.io.File(preview.path).delete()
                    return@launch
                }
                _uiState.update {
                    it.copy(
                        archivePreview = preview,
                        archivePreviewEntries = pagerIndices,
                        archivePreviewPosition = pagerPosition,
                        archivePreviewFileId = file.id,
                        archiveVideoQueue = pagerIndices,
                        archiveVideoQueuePosition = pagerPosition
                    )
                }
                if (previous != null && previous.entryIndex != entryIndex) {
                    deleteArchivePreviewTemp(previous)
                }
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: DocumentPreviewException) {
                if (isCurrentLoad()) {
                    _uiState.update { it.withArchivePreviewStepFailure() }
                    _messages.tryEmit(UiText.Plain(failure.message ?: "Could not read this file."))
                }
            } catch (failure: ArchiveException) {
                // Same error semantics as individual open, and only from the
                // current load: a superseded load's failure (e.g. a transient
                // read failure racing its cancellation) publishes nothing.
                if (isCurrentLoad()) {
                    _uiState.update { it.withArchivePreviewStepFailure() }
                    _messages.tryEmit(UiText.Plain(failure.message ?: "Could not extract this file."))
                }
            } catch (failure: Exception) {
                if (isCurrentLoad()) {
                    _uiState.update { it.withArchivePreviewStepFailure() }
                    _messages.tryEmit(UiText.Resource(Res.string.preview_archive_failed))
                }
            } finally {
                if (archivePreviewJob === currentCoroutineContext()[Job]) {
                    // MF-1: clear the busy index only through the
                    // owner-checked path (this load's claim is the preview
                    // token; a download's mark is never cleared here), and
                    // always drop the preview loading state.
                    clearArchiveBusy(archivePreviewBusyOwner)
                    _uiState.update { it.copy(archivePreviewLoading = false) }
                }
            }
            } finally {
                archiveExtractionMutex.unlock(gateOwner)
            }
        }
        archivePreviewJob = job
        job.start()
    }

    /**
     * The in-zip preview pager settled on [position]: steps the preview to that page,
     * extracting its entry on demand. Settling back on the page already shown while a
     * step is still extracting abandons that step, so its result cannot yank the pager
     * away afterwards. Positions are pager positions, not listing indices.
     */
    fun onArchivePreviewPageSelected(position: Int) {
        val state = _uiState.value
        if (state.archivePreview == null) return
        if (position == state.archivePreviewPosition) {
            if (archivePreviewJob?.isActive == true) {
                archivePreviewJob?.cancel()
                archivePreviewJob = null
                // MF-1: settling back abandons the in-flight preview load,
                // so its preview-owned busy claim is released — but a busy
                // mark owned by a running download is left untouched.
                clearArchiveBusy(archivePreviewBusyOwner)
                _uiState.update { it.copy(archivePreviewLoading = false) }
            }
            return
        }
        stepArchivePreview(position - state.archivePreviewPosition)
    }

    /**
     * Steps the in-zip preview by [delta] pages (negative = previous), clamped to the
     * pager sequence with no wrap-around. A step is refused while a list-side archive
     * extraction (download / batch) owns the busy slot; an in-flight preview load is
     * superseded instead, so rapid swiping lands on the last page swiped to.
     */
    fun stepArchivePreview(delta: Int) {
        val state = _uiState.value
        if (state.archivePreview == null || delta == 0) return
        val target = archivePreviewPagerTarget(
            state.archivePreviewEntries.size,
            state.archivePreviewPosition,
            delta
        ) ?: return
        if (archivePreviewJob?.isActive != true && state.archiveEntryBusyIndex != null) {
            // Refused: a list-side extraction owns the busy slot. Signal the refusal
            // so the pager snaps back to the published page instead of staying
            // settled on the unpublished one, showing loading forever.
            _uiState.update { it.withArchivePreviewStepFailure() }
            return
        }
        val fileId = state.archivePreviewFileId ?: return
        val file = state.files.firstOrNull { it.id == fileId } ?: return
        // vc183: settling/stepping onto a video page publishes only its
        // placeholder — a swipe must never start a whole-file extraction.
        // The extraction runs on the placeholder's deliberate Play tap, or
        // when the entry is tapped directly in the list.
        val listing = state.archiveListings[fileId]
        if (listing != null &&
            archivePagerPageIsVideo(listing, state.archivePreviewEntries, target)
        ) {
            publishArchiveVideoPlaceholder(
                file = file,
                entryIndex = state.archivePreviewEntries[target],
                pagerIndices = state.archivePreviewEntries,
                pagerPosition = target
            )
        } else {
            loadArchivePreview(
                file = file,
                entryIndex = state.archivePreviewEntries[target],
                pagerIndices = state.archivePreviewEntries,
                pagerPosition = target
            )
        }
    }

    /**
     * Steps the open in-zip video (or video placeholder) by [delta] pages in
     * the UNIFIED sequence (vc183) — the player's SkipPrevious/SkipNext
     * buttons now follow the same sequence as the swipe pager, so they can
     * step onto an image, text, PDF or another video (a video target lands
     * on its placeholder, never on an extraction). Before vc183 this stepped
     * a video-only queue, which is why video->image switching failed in the
     * field. Playback finishing does NOT auto-advance.
     */
    fun stepArchiveVideo(delta: Int) {
        val preview = _uiState.value.archivePreview
        if (preview !is ArchiveEntryPreview.Video &&
            preview !is ArchiveEntryPreview.VideoPlaceholder
        ) return
        stepArchivePreview(delta)
    }

    /** Deletes an in-archive preview's temp file (text previews hold no file). */
    private fun deleteArchivePreviewTemp(preview: ArchiveEntryPreview) {
        when (preview) {
            is ArchiveEntryPreview.Image -> java.io.File(preview.path).delete()
            is ArchiveEntryPreview.Audio -> java.io.File(preview.path).delete()
            is ArchiveEntryPreview.Pdf -> java.io.File(preview.path).delete()
            is ArchiveEntryPreview.Video -> java.io.File(preview.path).delete()
            is ArchiveEntryPreview.VideoPlaceholder -> Unit
            is ArchiveEntryPreview.Text -> Unit
        }
    }

    /** Closes the in-archive preview and deletes its temp file (text previews hold no file). */
    fun dismissArchivePreview() {
        val preview = _uiState.value.archivePreview
        archivePreviewJob?.cancel()
        archivePreviewJob = null
        _uiState.update {
            it.copy(
                archivePreview = null,
                archivePreviewEntries = emptyList(),
                archivePreviewPosition = -1,
                archivePreviewFileId = null,
                archiveVideoQueue = emptyList(),
                archiveVideoQueuePosition = -1,
                archivePreviewLoading = false
            )
        }
        // MF-1: only the preview side's own busy claim is released here, via
        // the owner-checked path. A list-side download running behind the
        // overlay keeps its busy state — including when a preview load was
        // in flight but merely WAITING on the extraction gate behind that
        // download (the old raw `if (previewLoadInFlight) null` write wiped
        // the download's mark in exactly that window).
        clearArchiveBusy(archivePreviewBusyOwner)
        preview?.let(::deleteArchivePreviewTemp)
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
        // While another download is running this batch queues behind it instead of
        // being dropped (ported from PC 1.6.24 note 2); its start message fires
        // now, at acceptance (note 5). A 1-entry batch uses the single-entry
        // message, matching its completion-message behaviour.
        enqueueArchiveDownload(
            request = ArchiveDownloadRequest.Batch(file, listing, plan),
            startedMessage = if (plan.size == 1) {
                UiText.Resource(
                    Res.string.preview_archive_entry_saving,
                    archiveEntryDisplayName(listing[plan.first().entryIndex])
                )
            } else {
                UiText.Resource(Res.string.preview_archive_downloading_selected, plan.size)
            }
        )
    }

    /** Runs one queued Download-selected batch; see [downloadArchiveSelection]. */
    private suspend fun runArchiveBatchDownload(
        request: ArchiveDownloadRequest.Batch,
        owner: Any
    ) {
        val file = request.file
        val listing = request.listing
        val plan = request.plan
        var saved = 0
        _uiState.update { it.copy(archiveBatchProgress = ArchiveBatchProgress(0, plan.size)) }
        try {
            for (step in plan) {
                markArchiveBusy(owner, step.entryIndex)
                val stepEntry = listing[step.entryIndex]
                // MF-2: begin() inside the per-step try, like the single path —
                // a throwing Room insert fails THIS step through the catches
                // below (recordId still null, tolerated by every reporter
                // call) and the batch/queue continues, instead of escaping.
                var recordId: String? = null
                try {
                    recordId = archiveDownloadReporter.begin(
                        archiveEntryDisplayName(stepEntry),
                        stepEntry.sizeBytes
                    )
                    // The extraction runs as a CHILD job registered under this step's
                    // record, so a stop from Transfers cancels only this step's job
                    // while this batch coroutine stays alive to continue with the rest.
                    val savedPath = coroutineScope {
                        val stepJob = async {
                            val local = localArchives[file.id]
                            if (local != null) {
                                localArchiveService.extractEntry(
                                    file = file,
                                    localPath = local.path,
                                    format = local.format,
                                    entryIndex = step.entryIndex,
                                    entryName = step.entryName,
                                    subFolderPath = step.subFolderPath.ifBlank { null },
                                    onProgress = { done ->
                                        archiveDownloadReporter.progress(recordId, done)
                                    }
                                )
                            } else {
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
                        }
                        archiveDownloadReporter.attach(recordId, stepJob)
                        stepJob.await()
                    }
                    archiveDownloadReporter.complete(recordId, savedPath)
                    saved++
                } catch (failure: CancellationException) {
                    // vc183 ITEM 1: pass the cause through — a registry stop
                    // lands CANCELLED, an internal byte-source timeout lands
                    // FAILED. The batch loop's per-record discrimination
                    // depends on the reader's cancellation surfacing here.
                    archiveDownloadReporter.ownerStopped(recordId, failure)
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
            clearArchiveBusy(owner)
            _uiState.update { it.copy(archiveBatchProgress = null) }
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
