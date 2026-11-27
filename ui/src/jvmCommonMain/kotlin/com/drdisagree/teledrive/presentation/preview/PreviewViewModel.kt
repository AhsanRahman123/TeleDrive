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
import com.drdisagree.teledrive.resources.preview_archive_entries_downloading
import com.drdisagree.teledrive.resources.preview_archive_entries_saved
import com.drdisagree.teledrive.resources.preview_archive_entry_downloading
import com.drdisagree.teledrive.resources.preview_archive_entry_failed
import com.drdisagree.teledrive.resources.preview_archive_entry_saved
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
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.archive.ArchiveDownloadReporter
import com.drdisagree.teledrive.core.archive.ArchiveException
import com.drdisagree.teledrive.core.archive.LocalArchiveFormat
import com.drdisagree.teledrive.core.archive.LocalArchiveService
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.files.formatPreviewText
import com.drdisagree.teledrive.core.archive.archiveFailureDetail
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.domain.model.ArchiveThumbnailSize
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
import kotlinx.coroutines.sync.Mutex

@OptIn(ExperimentalCoroutinesApi::class)
class PreviewViewModel(
    savedStateHandle: SavedStateHandle,
    private val fileRepository: FileRepository,
    private val trashRepository: TrashRepository,
    private val transferRepository: TransferRepository,
    private val settingsRepository: SettingsRepository,
    private val contentResolver: PreviewContentResolver,
    private val remoteArchiveService: RemoteArchiveService,
    private val archiveDownloadReporter: ArchiveDownloadReporter,
    // Local 7z/TAR extraction (1.6.25 item 5). Nullable with a default ONLY so
    // the existing desktop unit tests that construct this ViewModel by hand
    // keep compiling unchanged; production DI (SharedUiModule) always
    // provides the real service, and a null service keeps the pre-1.6.25
    // local-only behaviour for local archives.
    private val localArchiveService: LocalArchiveService? = null
) : ViewModel() {

    /** A local 7z/TAR copy a [LocalArchiveService] extraction can read, per file id. */
    private data class LocalArchiveSource(val path: String, val format: LocalArchiveFormat)

    private val localArchives = mutableMapOf<String, LocalArchiveSource>()

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
                    // A local 7z/TAR listing is tappable too (Android vc181 B10,
                    // ported with 1.6.25 item 5): its entries extract from the
                    // local copy via LocalArchiveService, so the listing is
                    // stored and the local source remembered. Local ZIP/CBZ
                    // listings are NOT stored here — they keep the existing
                    // local-only behaviour unless re-marked remote, exactly as
                    // on Android.
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
     * must stay serialised even though starting them is no longer blocked (1.6.24
     * note 2 — the old code early-returned while any download ran, which made the
     * pane's Download selected button dead for the whole duration of a download).
     */
    private sealed interface ArchiveDownloadRequest {
        val file: DriveFile

        /** A single entry, saved flat like a tap-download. */
        data class Single(
            override val file: DriveFile,
            val step: ArchiveDownloadStep,
            val displayName: String,
            val sizeBytes: Long,
            /** Set when the entry extracts from a LOCAL 7z/TAR copy instead of the remote ZIP. */
            val localSource: LocalArchiveSource? = null
        ) : ArchiveDownloadRequest

        /** A Download-selected batch: [plan] steps over the listing snapshot [listing]. */
        data class Batch(
            override val file: DriveFile,
            val listing: List<ArchiveEntry>,
            val plan: List<ArchiveDownloadStep>,
            /** Set when the steps extract from a LOCAL 7z/TAR copy instead of the remote ZIP. */
            val localSource: LocalArchiveSource? = null
        ) : ArchiveDownloadRequest
    }

    private val archiveDownloadQueue = ArrayDeque<ArchiveDownloadRequest>()
    private var archiveDownloadProcessor: Job? = null

    /**
     * The single serialisation gate for EVERY archive extraction of this
     * viewer — queued downloads AND previews (MF-1, report 54 second pass).
     * [RemoteArchiveService] has no internal lock and all extractions of one
     * archive share the one TDLib window for its fileId, so two extractions
     * must never run at once on either axis:
     *  - the queue processor [Mutex.lock]s the gate before running a request,
     *    so a download waits while a preview extraction is in flight;
     *  - a preview [Mutex.tryLock]s the gate synchronously before launching,
     *    so a preview tap while a download (or another preview) holds the
     *    gate is dropped, exactly as the old busy-index gate dropped it —
     *    and a preview stays immediate when the gate is free.
     * Each holder passes a unique owner token, and the busy index is only
     * ever cleared by its owner ([clearArchiveBusy]), so one extraction's
     * teardown can no longer wipe the other's busy mark.
     */
    private val archiveExtractionMutex = Mutex()

    /**
     * Monotonic token for in-zip preview loads (PC 1.6.25 item 7): every new
     * load/step/dismiss bumps it, and a load publishes ONLY if its token is
     * still current at publish time — a superseded step publishes nothing and
     * its temp file is deleted instead.
     */
    @Volatile
    private var archivePreviewGeneration = 0
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
     * Accepts a download request: it is queued behind any running request and its
     * start message is emitted immediately (1.6.24 note 5 — the message carries
     * the View -> Transfers action, so it must be on screen from the moment the
     * download is accepted, not only when it completes). The Transfers record is
     * deliberately NOT created here: records are created when a request actually
     * starts running, so a queued request has no record to cancel yet and the
     * history never shows work that has not begun.
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

    /**
     * Drops every queued archive download request and stops the processor
     * (1.6.25 item 1, bulk cancel): queued requests have no Transfers record
     * yet by design, so dropping them here IS their cancellation — they never
     * start and never create a record. The running request's record was
     * already written CANCELLED by the repository's bulk cancel.
     */
    private fun dropArchiveDownloadQueue() {
        synchronized(archiveDownloadQueue) {
            archiveDownloadQueue.clear()
            archiveDownloadProcessor = null
        }
        publishArchiveQueueDepth()
    }

    /** Runs queued requests one at a time until the queue is empty. */
    private suspend fun drainArchiveDownloadQueue() {
        while (true) {
            // Bulk controls (1.6.25 item 1): Transfers Pause all / Cancel all
            // act on the shared registry, not on this queue directly, because
            // queued requests have no Transfers record or registered job for
            // the repository to reach. The epoch is captured BEFORE waiting
            // out a bulk pause: a Cancel all that lands while this processor
            // is parked releases the hold, and the epoch change below is what
            // stops the parked processor from simply starting the next queued
            // request afterwards.
            val bulkEpoch = archiveDownloadReporter.bulkCancelEpoch()
            archiveDownloadReporter.awaitArchiveQueueReleased()
            if (archiveDownloadReporter.bulkCancelEpoch() != bulkEpoch) {
                dropArchiveDownloadQueue()
                return
            }
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
                        is ArchiveDownloadRequest.Batch ->
                            runArchiveBatchDownload(request, owner, bulkEpoch)
                    }
                } finally {
                    archiveExtractionMutex.unlock(owner)
                }
            } catch (failure: CancellationException) {
                if (!currentCoroutineContext().isActive) throw failure
                _messages.tryEmit(
                    UiText.Plain("Could not open archive: ${archiveFailureDetail(failure)}")
                )
            } catch (failure: Exception) {
                _messages.tryEmit(
                    UiText.Plain("Could not open archive: ${archiveFailureDetail(failure)}")
                )
            }
            // A Cancel all that landed while this request ran (its running
            // step was stopped through the registry and its record already
            // written CANCELLED by the repository) drops everything still
            // queued instead of starting the next request.
            if (archiveDownloadReporter.bulkCancelEpoch() != bulkEpoch) {
                dropArchiveDownloadQueue()
                return
            }
        }
    }

    /**
     * Downloads just the entry at [entryIndex] in the stored listing out of a remotely listed
     * ZIP, saved to Downloads like a normal download. Identity is the listing index, never the
     * entry value, so duplicate names each download their own bytes. A listing that came from a
     * local-only copy has no remote source to extract from, so it gets a clear message instead
     * of a failure. While another download is running this one queues behind it instead of
     * being dropped (1.6.24 note 2).
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
                sizeBytes = entry.sizeBytes,
                localSource = localArchives[file.id]
            ),
            startedMessage = UiText.Resource(
                Res.string.preview_archive_entry_downloading, displayName
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
            // the batch path uses per step.
            val savedPath = coroutineScope {
                val stepJob = async {
                    val local = request.localSource
                    if (local != null) {
                        val service = localArchiveService ?: error(
                            "Local archive service unavailable"
                        )
                        service.extractEntry(
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
            val detail = archiveFailureDetail(failure)
            archiveDownloadReporter.fail(recordId, detail)
            _messages.tryEmit(
                UiText.Plain("Could not open archive: $detail")
            )
        } finally {
            clearArchiveBusy(owner)
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
     * The ZIP contents list's thumbnail size (PC 1.6.26 item P7), persisted
     * as `UserPreferences.archiveThumbnailSize` (DataStore key
     * `archive_thumbnail_size`) so the Big/Medium/Small switcher choice
     * survives restarts and is shared by every archive pane.
     */
    val archiveThumbnailSize: StateFlow<ArchiveThumbnailSize> =
        settingsRepository.preferences
            .map { it.archiveThumbnailSize }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                ArchiveThumbnailSize.MEDIUM
            )

    fun setArchiveThumbnailSize(size: ArchiveThumbnailSize) {
        viewModelScope.launch {
            settingsRepository.update { it.copy(archiveThumbnailSize = size) }
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
        val occurrence = archiveEntryOccurrence(listing, entryIndex)
        if (occurrence < 0) return
        val displayName = archiveEntryDisplayName(entry)
        // MF-1: claim the extraction gate SYNCHRONOUSLY, before launching.
        // While a queued download holds the gate this tap is dropped, exactly
        // as the busy-index gate dropped it; while a download is merely
        // queued behind this preview, the processor's lock() waits for the
        // unlock in the finally below. Claiming here (not inside the launch)
        // closes the window where a just-tapped preview had not yet marked
        // anything and a download could start underneath it.
        val owner = Any()
        if (!archiveExtractionMutex.tryLock(owner)) return
        markArchiveBusy(owner, entryIndex)
        val generation = ++archivePreviewGeneration
        viewModelScope.launch {
            try {
                val local = localArchives[file.id]
                val temp = if (local != null) {
                    val service = localArchiveService ?: error(
                        "Local archive service unavailable"
                    )
                    service.extractEntryToTempFile(
                        local.path, local.format, entryIndex, entry.name
                    )
                } else {
                    remoteArchiveService.extractEntryToTempFile(file, entry.name, occurrence)
                }
                val preview = when (action) {
                    ArchiveEntryTapAction.PREVIEW_IMAGE ->
                        ArchiveEntryPreview.Image(displayName, temp.absolutePath, entryIndex)
                    ArchiveEntryTapAction.PLAY_AUDIO ->
                        ArchiveEntryPreview.Audio(displayName, temp.absolutePath, entryIndex)
                    ArchiveEntryTapAction.PREVIEW_PDF ->
                        // The extracted temp file is already a real, seekable .pdf —
                        // exactly what the PDF viewer needs, same as a normal local PDF.
                        ArchiveEntryPreview.Pdf(displayName, temp.absolutePath, entryIndex)
                    ArchiveEntryTapAction.PREVIEW_DOCUMENT -> {
                        // EPUB Tier 1 / OOXML text extraction runs on the extracted
                        // temp file, exactly as for a normal local document.
                        val extraction = try {
                            extractDocumentText(temp)
                        } finally {
                            temp.delete()
                        }
                        when (extraction) {
                            is DocumentExtraction.Text -> ArchiveEntryPreview.Text(
                                displayName,
                                extraction.text,
                                entryIndex = entryIndex,
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

                    ArchiveEntryTapAction.PREVIEW_TEXT -> {
                        // String decoding with the UTF-8 charset replaces malformed input
                        // instead of failing, so a non-UTF-8 text file still shows sanely.
                        val entryMime = MimeTypes.fromFileName(entry.name)
                        val text = formatPreviewText(
                            temp.readBytes().toString(Charsets.UTF_8),
                            entryMime
                        )
                        temp.delete()
                        ArchiveEntryPreview.Text(
                            displayName,
                            text,
                            entryIndex,
                            isMarkdown = MimeTypes.isMarkdown(entryMime),
                            isHtml = MimeTypes.isHtml(entryMime)
                        )

                    }
                    ArchiveEntryTapAction.DOWNLOAD -> error("unreachable")
                }
                if (generation != archivePreviewGeneration) {
                    // Superseded while extracting: publish nothing.
                    deleteArchivePreviewTemp(preview)
                    return@launch
                }
                publishArchivePreview(preview, file.id)
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: DocumentPreviewException) {
                _messages.tryEmit(UiText.Plain(failure.message ?: "Could not read this file."))
            } catch (failure: ArchiveException) {
                _messages.tryEmit(UiText.Plain(failure.message ?: "Could not extract this file."))
            } catch (failure: Exception) {
                _messages.tryEmit(
                    UiText.Plain("Could not open archive: ${archiveFailureDetail(failure)}")
                )
            } finally {
                clearArchiveBusy(owner)
                archiveExtractionMutex.unlock(owner)
            }
        }
    }

    /**
     * Publishes [preview] as the current in-archive preview, deleting the temp
     * file of the preview it replaces. Switching photos in the in-zip pager goes
     * through here, so a switch never leaks the previous photo's temp file; a
     * failed extraction never reaches this point and the current preview (and
     * its temp) stays exactly as it was.
     */
    private fun publishArchivePreview(preview: ArchiveEntryPreview, fileId: String? = null) {
        val replaced = _uiState.value.archivePreview
        _uiState.update {
            it.copy(
                archivePreview = preview,
                archivePreviewFileId = fileId ?: it.archivePreviewFileId
            )
        }
        if (replaced != null && replaced != preview) deleteArchivePreviewTemp(replaced)
    }

    /** Deletes the extracted temp file behind a preview, if it holds one (text holds none). */
    private fun deleteArchivePreviewTemp(preview: ArchiveEntryPreview?) {
        val path = when (preview) {
            is ArchiveEntryPreview.Image -> preview.path
            is ArchiveEntryPreview.Audio -> preview.path
            is ArchiveEntryPreview.Video -> preview.path
            is ArchiveEntryPreview.Pdf -> preview.path
            is ArchiveEntryPreview.Text -> null
            null -> null
        }
        // Best-effort, exactly as the audio branch has always behaved: when the
        // desktop player has parked the file for background playback, the OS may
        // refuse the delete while the player holds it open — playback is never
        // interrupted to force it (known edge, shared with audio).
        if (path != null) runCatching { java.io.File(path).delete() }
    }

    /** Closes the in-archive preview and deletes its temp file (text previews hold no file). */
    fun dismissArchivePreview() {
        archivePreviewGeneration++
        val preview = _uiState.value.archivePreview
        _uiState.update { it.copy(archivePreview = null, archivePreviewFileId = null) }
        deleteArchivePreviewTemp(preview)
    }

    /**
     * Download-while-viewing for the in-zip viewer (PC 1.6.25 item 8): resolves
     * the open preview's owning archive file and entry index, then delegates to
     * the EXISTING list-row path [downloadArchiveEntry] — same report-only
     * Transfers record and instant View snackbar, no new download logic.
     */
    fun downloadCurrentArchiveEntry() {
        val state = _uiState.value
        val fileId = state.archivePreviewFileId ?: return
        val entryIndex = state.archivePreview?.entryIndex ?: return
        if (entryIndex < 0) return
        val file = state.files.firstOrNull { it.id == fileId } ?: return
        downloadArchiveEntry(file, entryIndex)
    }

    /**
     * One step in the UNIFIED in-zip preview sequence (PC 1.6.25 item 7):
     * [sequence] is the displayed previewable listing indices across ALL types
     * (built by the pane via archiveUnifiedPreviewIndices). Stepping from any
     * in-zip preview — photo, video, audio or text — moves to the neighbouring
     * previewable entry in display order and opens it through the same load
     * path as an individual tap, so the correct surface per type is chosen by
     * the entry itself, switch failures report exactly like an individual open
     * (no false corruption verdicts), and a failed/refused step never moves
     * the published preview. Video targets run the free-space preflight inside
     * the load path before any extraction. No neighbour pre-extraction, ever.
     * Returns false when there is no neighbour (either end / no preview open).
     */
    fun stepArchivePreview(file: DriveFile, delta: Int, sequence: List<Int>): Boolean {
        val current = _uiState.value.archivePreview ?: return false
        val target = archiveUnifiedPreviewNeighbour(sequence, current.entryIndex, delta)
            ?: return false
        val entry = _uiState.value.archiveListings[file.id]?.getOrNull(target) ?: return false
        if (MimeTypes.isVideo(MimeTypes.fromFileName(entry.name))) {
            previewArchiveVideoEntry(file, target)
        } else {
            previewArchiveEntry(file, target)
        }
        return true
    }

    /**
     * Desktop-routed in-zip video playback (1.6.24 note 9): extracts the video
     * entry at [entryIndex] to a verified temp file — the same extraction a
     * download performs — and publishes it as an [ArchiveEntryPreview.Video]
     * for the desktop player, exactly the pattern the audio branch uses. This
     * is deliberately NOT part of the shared tap routing: [archiveEntryTapAction]
     * keeps returning DOWNLOAD for video, so Android's tap behaviour is
     * unchanged; only the desktop pane calls this. The busy gate serialises it
     * with every other extraction (the ranged reads share one window), and a
     * failure reports the same detailed message as the other preview paths and
     * leaves any current preview untouched.
     */
    fun previewArchiveVideoEntry(file: DriveFile, entryIndex: Int) {
        if (_uiState.value.archiveEntryBusyIndex != null) return
        val listing = _uiState.value.archiveListings[file.id]
        if (listing == null) {
            _messages.tryEmit(UiText.Resource(Res.string.preview_archive_local_only))
            return
        }
        val entry = listing.getOrNull(entryIndex) ?: return
        if (entry.isDirectory) return
        if (!MimeTypes.isVideo(MimeTypes.fromFileName(entry.name))) return
        val occurrence = archiveEntryOccurrence(listing, entryIndex)
        if (occurrence < 0) return
        val displayName = archiveEntryDisplayName(entry)
        // MF-1: same synchronous gate claim as previewArchiveEntry.
        // Free-space preflight (Android vc181/vc182 semantics): the entry
        // extracts WHOLE to a temp file, so refuse up front when the temp dir
        // cannot hold it — a disk-full mid-extraction must not read as
        // archive corruption. The current preview stays untouched on refusal.
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir") ?: ".")
        val freeBytes = tempDir.usableSpace
        if (!archiveVideoFitsInFreeSpace(entry.sizeBytes, freeBytes)) {
            _messages.tryEmit(
                UiText.Plain(
                    "There is not enough free space to play this video here. " +
                        "It needs about " +
                        com.drdisagree.teledrive.presentation.common.Formatters.bytes(entry.sizeBytes) +
                        " but only " +
                        com.drdisagree.teledrive.presentation.common.Formatters.bytes(freeBytes) +
                        " is free. Download it instead."
                )
            )
            return
        }
        val owner = Any()
        if (!archiveExtractionMutex.tryLock(owner)) return
        markArchiveBusy(owner, entryIndex)
        val generation = ++archivePreviewGeneration
        viewModelScope.launch {
            try {
                val local = localArchives[file.id]
                val temp = if (local != null) {
                    val service = localArchiveService ?: error(
                        "Local archive service unavailable"
                    )
                    service.extractEntryToTempFile(
                        local.path, local.format, entryIndex, entry.name
                    )
                } else {
                    remoteArchiveService.extractEntryToTempFile(file, entry.name, occurrence)
                }
                val videoPreview = ArchiveEntryPreview.Video(displayName, temp.absolutePath, entryIndex)
                if (generation != archivePreviewGeneration) {
                    deleteArchivePreviewTemp(videoPreview)
                    return@launch
                }
                publishArchivePreview(videoPreview, file.id)

            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: ArchiveException) {
                _messages.tryEmit(UiText.Plain(failure.message ?: "Could not extract this file."))
            } catch (failure: Exception) {
                _messages.tryEmit(
                    UiText.Plain("Could not open archive: ${archiveFailureDetail(failure)}")
                )
            } finally {
                clearArchiveBusy(owner)
                archiveExtractionMutex.unlock(owner)
            }
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
        // being dropped (1.6.24 note 2); its start message fires now (note 5).
        enqueueArchiveDownload(
            request = ArchiveDownloadRequest.Batch(
                file, listing, plan, localSource = localArchives[file.id]
            ),
            startedMessage = if (plan.size == 1) {
                UiText.Resource(
                    Res.string.preview_archive_entry_downloading,
                    archiveEntryDisplayName(listing[plan.first().entryIndex])
                )
            } else {
                UiText.Resource(Res.string.preview_archive_entries_downloading, plan.size)
            }
        )
    }

    /** Runs one queued Download-selected batch; see [downloadArchiveSelection]. */
    private suspend fun runArchiveBatchDownload(
        request: ArchiveDownloadRequest.Batch,
        owner: Any,
        bulkEpoch: Long
    ) {
        val file = request.file
        val listing = request.listing
        val plan = request.plan
        var saved = 0
        // Set when a Transfers Cancel all lands mid-batch (1.6.25 item 1):
        // the remaining steps never start (they have no records yet, so not
        // starting IS their cancellation) and no completion message is
        // emitted — Transfers already shows the running record CANCELLED.
        var bulkAborted = false
        _uiState.update { it.copy(archiveBatchProgress = ArchiveBatchProgress(0, plan.size)) }
        try {
            for (step in plan) {
                // Bulk controls per step (1.6.25 item 1): a per-record stop
                // from Transfers still only skips its own step (the catches
                // below), but a bulk CANCEL aborts the whole batch, and a
                // bulk PAUSE parks the batch here — the next step does not
                // start while the queue is held. The epoch is re-checked
                // after the wait because Cancel all releases the hold.
                if (archiveDownloadReporter.bulkCancelEpoch() != bulkEpoch) {
                    bulkAborted = true
                    break
                }
                archiveDownloadReporter.awaitArchiveQueueReleased()
                if (archiveDownloadReporter.bulkCancelEpoch() != bulkEpoch) {
                    bulkAborted = true
                    break
                }
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
                            val local = request.localSource
                            if (local != null) {
                                val service = localArchiveService ?: error(
                                    "Local archive service unavailable"
                                )
                                service.extractEntry(
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
                            failure.message ?: archiveFailureDetail(failure)
                        )
                    )
                } catch (failure: Exception) {
                    val detail = archiveFailureDetail(failure)
                    archiveDownloadReporter.fail(recordId, detail)
                    _messages.tryEmit(
                        UiText.Resource(
                            Res.string.preview_archive_entry_failed,
                            archiveEntryDisplayName(listing[step.entryIndex]),
                            detail
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
        // A bulk-cancelled batch emits no completion message (see bulkAborted).
        if (bulkAborted) return
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
