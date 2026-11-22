package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.crypto.KeyBackupCodec
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.publish.PublishScheduler
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.transfer.FileParts
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.PendingDeleteDao
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.inImmediateTransaction
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.remote.telegram.ExtraFields
import com.drdisagree.teledrive.data.remote.telegram.IndexSnapshotFile
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.remote.telegram.RemoteFileManifest
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderState
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.SyncRepository
import com.drdisagree.teledrive.domain.repository.SyncStats
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The chat is the source of truth for remote-backed files; after a local wipe this rebuilds the
 * whole drive.
 */
class SyncRepositoryImpl(
    private val telegramClient: TelegramClient,
    private val fileDao: FileDao,
    private val manifestCodec: ManifestCodec,
    private val pendingDeleteDao: PendingDeleteDao,
    private val filePartDao: FilePartDao,
    private val folderDao: FolderDao,
    private val folderPathResolver: FolderPathResolver,
    private val activeChannel: ActiveChannel,
    private val channelOwnership: ChannelOwnership,
    private val folderStateSynchronizer: FolderStateSynchronizer,
    private val folderMerger: FolderMerger,
    private val indexSnapshot: IndexSnapshot,
    private val publishScheduler: PublishScheduler,
    private val settingsRepository: SettingsRepository,
    private val resolver: RemoteMessageResolver,
    private val database: TeleDriveDatabase
) : SyncRepository {

    private val _syncing = MutableStateFlow(false)
    override val syncing: Flow<Boolean> = _syncing

    private val _indexedSoFar = MutableStateFlow(0)
    override val indexedSoFar: Flow<Int> = _indexedSoFar

    private val _lockedFileCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    override val lockedFileCounts: Flow<Map<Long, Int>> = _lockedFileCounts
    private val syncMutex = Mutex()
    private val captionRepairsQueued = mutableSetOf<String>()

    // Remember how many pending rows this repository instance has already scheduled.
    // A new instance starts at zero so work left pending across an app restart is kicked once.
    private var lastScheduledPendingPublishCount = 0

    /**
     * Round 11: the full-sync timestamp now lives in the settings store
     * ([com.drdisagree.teledrive.domain.model.UserPreferences.lastFullSyncAt]),
     * not in this field. The in-memory copy was initialised to 0 on every
     * cold start, which defeated the [catchUpWithRemote] throttle and made
     * every launch page the entire channel history. This mirror is only a
     * fast path for the current process; the persisted value is authoritative
     * and is re-read whenever the mirror is empty.
     */
    @Volatile
    private var lastFullSyncAtMirror = 0L

    private suspend fun lastFullSyncAt(): Long {
        val mirror = lastFullSyncAtMirror
        if (mirror > 0L) return mirror
        val persisted = settingsRepository.preferences.first().lastFullSyncAt
        if (persisted > 0L) lastFullSyncAtMirror = persisted
        return persisted
    }

    private suspend fun recordFullSyncCompleted(now: Long) {
        lastFullSyncAtMirror = now
        settingsRepository.update { it.copy(lastFullSyncAt = now) }
    }

    override suspend fun fullResync(): AppResult<SyncStats> =
        runSync(incremental = false)

    override suspend fun incrementalSync(): AppResult<SyncStats> =
        runSync(incremental = true)

    override suspend fun syncOnStart(): AppResult<SyncStats> {
        fileDao.repairBackedUpStates()
        return runSync(incremental = fileDao.fileCount(activeChannel.id()) > 0)
    }

    override suspend fun catchUpWithRemote(): AppResult<SyncStats>? {
        if (syncMutex.isLocked) return null
        val since = System.currentTimeMillis() - lastFullSyncAt()
        if (since in 0 until CATCH_UP_INTERVAL_MS) return null
        return runSync(incremental = false, quiet = true)
    }

    private suspend fun runSync(
        incremental: Boolean,
        quiet: Boolean = false
    ): AppResult<SyncStats> =
        syncMutex.withLock {
            if (!quiet) {
                _syncing.value = true
                _indexedSoFar.value = 0
            }
            try {
                doSync(incremental, quiet).also { result ->
                    if (!incremental && result is AppResult.Success) {
                        recordFullSyncCompleted(System.currentTimeMillis())
                    }
                    val pendingNow = fileDao.pendingPublishCount(activeChannel.id())
                    // Kick once when pending work first appears in this instance, including
                    // work left over from a previous process. Do not re-kick unchanged rows
                    // on every full resync.
                    if (pendingNow > lastScheduledPendingPublishCount) {
                        publishScheduler.kick()
                    }
                    lastScheduledPendingPublishCount = pendingNow
                }
            } catch (e: TelegramException) {
                AppResult.Failure(
                    if (e.isRateLimit) AppError.RateLimited(e.retryAfterSeconds ?: 0)
                    else AppError.TelegramError(e.code, e.message)
                )
            } finally {
                if (!quiet) _syncing.value = false
            }
        }

    private suspend fun forgetChat(chatId: Long) {
        val removed = fileDao.deleteRemoteOnlyInChat(chatId)
        val detached = fileDao.detachChat(chatId)
        SafeLog.d(TAG, "Storage chat changed: dropped $removed, detached $detached")
    }

    private suspend fun doSync(
        incremental: Boolean,
        quiet: Boolean
    ): AppResult<SyncStats> {
        val prefs = settingsRepository.preferences.first()
        val chatId = telegramClient.ensureStorageChat(prefs.storageChatId)
        channelOwnership.claimUnowned(chatId)
        if (chatId != prefs.storageChatId) {
            prefs.storageChatId?.let { previous -> forgetChat(previous) }
            settingsRepository.update { it.copy(storageChatId = chatId) }
        }

        var fromSnapshot = 0
        if (!incremental) {
            if (folderDao.pendingPublishCount(chatId) > 0) publishScheduler.kick()
            runCatching { folderStateSynchronizer.pull() }
                .onFailure { SafeLog.w(TAG, "Folder state pull failed", it) }
            folderMerger.mergeDuplicates(chatId)
            runCatching { mergeDuplicateMessageRows(chatId) }
                .onFailure { SafeLog.w(TAG, "Message-row merge failed", it) }
            if (folderDao.pendingPublishCount(chatId) > 0) publishScheduler.kick()
            if (fileDao.fileCount(chatId) == 0) fromSnapshot = applySnapshot(chatId, quiet)
        }

        val pendingDeletes = pendingDeleteDao.messageIdsIn(chatId).toSet()
        val mappedBeforeScan = if (incremental) {
            emptyMap()
        } else {
            fileDao.filesWithRemote().associate { it.id to it.messageId }
        }
        val folderCache = mutableMapOf<String, String?>()
        SafeLog.d(TAG, "Sync start chat=$chatId incremental=$incremental")
        var inserted = fromSnapshot
        var updated = 0
        var locked = 0
        val seenMessageIds = mutableSetOf<Long>()
        val channelDocuments = mutableListOf<RemoteDocument>()
        val snapshots = mutableListOf<RemoteDocument>()
        var fromMessageId = 0L
        var pages = 0

        while (pages++ < MAX_PAGES) {
            val page = try {
                telegramClient.fetchDocuments(chatId, fromMessageId, PAGE_SIZE)
            } catch (e: TelegramException) {
                if (e.isRateLimit) {
                    delay((((e.retryAfterSeconds ?: 5) + 1) * 1000L).milliseconds)
                    continue
                }
                throw e
            }
            SafeLog.d(TAG, "Sync page ${page.documents.size} documents")
            if (page.documents.isEmpty() && page.nextFromMessageId == 0L) break

            var reachedKnown = false
            val known = existingForPage(page.documents)
            database.inImmediateTransaction {
                for (document in page.documents) {
                    seenMessageIds.add(document.messageId)
                    // Still in the chat, but a delete is owed for it.
                    if (document.messageId in pendingDeletes) continue
                    if (indexSnapshot.isSnapshot(document)) snapshots += document
                    if (isInternalDocument(document)) continue
                    channelDocuments += document
                    if (manifestCodec.isLocked(document.caption)) {
                        locked++
                        continue
                    }
                    if (incremental && !isNewToLocal(document, known)) {
                        reachedKnown = true
                        break
                    }
                    when (reconcile(document, folderCache, known)) {
                        ReconcileResult.INSERTED -> {
                            inserted++
                            if (!quiet) _indexedSoFar.value = inserted
                        }

                        ReconcileResult.UPDATED -> updated++
                        ReconcileResult.UNCHANGED -> Unit
                    }
                }
            }
            if (reachedKnown) {
                return finishSync(
                    chatId, seenMessageIds, mappedBeforeScan, inserted, updated, locked,
                    partial = true
                )
            }
            if (page.nextFromMessageId == 0L) break
            fromMessageId = page.nextFromMessageId
        }

        val result = finishSync(
            chatId, seenMessageIds, mappedBeforeScan, inserted, updated, locked,
            partial = incremental
        )
        if (!incremental) {
            runCatching { indexSnapshot.refresh(chatId, channelDocuments, snapshots) }
                .onFailure { SafeLog.w(TAG, "Index snapshot refresh failed", it) }
        }
        return result
    }

    private suspend fun applySnapshot(chatId: Long, quiet: Boolean): Int {
        val documents = runCatching { indexSnapshot.load(chatId) }
            .onFailure { SafeLog.w(TAG, "Index snapshot load failed", it) }
            .getOrNull()
            .orEmpty()
            .filter { !isInternalDocument(it) && !manifestCodec.isLocked(it.caption) }
        if (documents.isEmpty()) return 0
        val folderCache = mutableMapOf<String, String?>()
        var inserted = 0
        documents.sortedByDescending { it.messageId }.chunked(PAGE_SIZE).forEach { page ->
            val known = existingForPage(page)
            database.inImmediateTransaction {
                for (document in page) {
                    if (reconcile(document, folderCache, known) == ReconcileResult.INSERTED) {
                        inserted++
                    }
                }
            }
            if (!quiet) _indexedSoFar.value = inserted
        }
        SafeLog.d(TAG, "Index snapshot added $inserted files")
        return inserted
    }

    private suspend fun finishSync(
        chatId: Long,
        seenMessageIds: Set<Long>,
        mappedBeforeScan: Map<String, Long?>,
        inserted: Int,
        updated: Int,
        locked: Int,
        partial: Boolean
    ): AppResult<SyncStats> {
        var detached = 0
        // Locked files count as seen, so pruning needs no restored key. Uploads that finish
        // mid-scan are newer than the pages read.
        if (!partial) {
            val stale = fileDao.filesWithRemote().filter { entity ->
                val messageId = entity.messageId
                messageId != null && entity.chatId == chatId && messageId !in seenMessageIds &&
                        mappedBeforeScan[entity.id] == messageId
            }
            detached = detach(stale)
        }
        _lockedFileCounts.update { counts ->
            val known = if (partial) maxOf(counts[chatId] ?: 0, locked) else locked
            counts + (chatId to known)
        }
        SafeLog.d(TAG, "Sync done: +$inserted ~$updated -$detached, $locked locked")
        return AppResult.Success(
            SyncStats(inserted, updated, detached, locked)
        )
    }

    private suspend fun detach(stale: List<FileEntity>): Int {
        if (stale.isEmpty()) return 0
        database.inImmediateTransaction {
            val (orphaned, localOnly) = stale.partition { it.localPath == null }
            if (orphaned.isNotEmpty()) {
                fileDao.deleteByIds(orphaned.map { it.id })
            }
            localOnly.forEach { entity -> fileDao.detachRemote(entity.id) }
        }
        return stale.size
    }

    /**
     * Collapses the duplicate rows the caption-less adoption churn accumulated:
     * several local rows bound to the SAME channel message, re-inserted pass after
     * pass because a label-less document could never be matched back to the row an
     * earlier pass created for it. Only rows sharing an exact message id are merged —
     * rows that merely share a content (unique) id are distinct messages, true
     * duplicate uploads, and stay distinct. The keeper is the most complete row: one
     * carrying a manifest baseline first, else the oldest created. Idempotent: once
     * merged, later full syncs find nothing to do. Rows bound to messages that have
     * since left the channel need no help from this sweep — [finishSync]'s prune
     * already removes a bound row whose message a full scan no longer sees (deleting
     * it when it has no local copy, detaching it when it has).
     */
    private suspend fun mergeDuplicateMessageRows(chatId: Long) {
        val duplicateGroups = fileDao.boundRowsInChat(chatId)
            .groupBy { it.messageId!! }
            .values.filter { it.size > 1 }
        var merged = 0
        if (duplicateGroups.isNotEmpty()) {
            database.inImmediateTransaction {
                for (rows in duplicateGroups) {
                    val keeper = rows.sortedWith(
                        compareBy(
                            { it.remoteBase == null },
                            { it.createdAt },
                            { it.addedAt },
                            { it.id }
                        )
                    ).first()
                    val losers = rows.filter { it.id != keeper.id }
                    filePartDao.deleteFor(losers.map { it.id })
                    fileDao.deleteByIds(losers.map { it.id })
                    merged += losers.size
                }
            }
        }
        SafeLog.d(TAG, "Message-row merge: collapsed $merged duplicate rows")
    }

    override suspend fun followRemoteChanges() {
        activeChannel.observe().filterNotNull().collectLatest { chatId ->
            runCatching { telegramClient.openChat(chatId) }
            try {
                ChangeBatcher(LIVE_BATCH_MS).run(telegramClient.messageChanges(chatId)) { updated, deleted ->
                    applyRemoteChanges(chatId, updated, deleted)
                }
            } finally {
                withContext(NonCancellable) { runCatching { telegramClient.closeChat(chatId) } }
            }
        }
    }

    /**
     * Only writes the local database, so applying another device's edit never publishes it back.
     */
    internal suspend fun applyRemoteChanges(
        chatId: Long,
        updated: Set<Long>,
        deleted: Set<Long>
    ) = syncMutex.withLock {
        try {
            val pendingDeletes = pendingDeleteDao.messageIdsIn(chatId).toSet()
            val documents = updated.sortedDescending()
                .filter { it !in pendingDeletes }
                .mapNotNull { telegramClient.getDocument(chatId, it) }

            if (documents.any { it.isFolderState() }) {
                runCatching { folderStateSynchronizer.pull() }
                    .onFailure { SafeLog.w(TAG, "Folder state pull failed", it) }
                if (folderDao.pendingPublishCount(chatId) > 0) publishScheduler.kick()
            }

            val files = documents.filter {
                !isInternalDocument(it) && !manifestCodec.isLocked(it.caption)
            }
            if (files.isNotEmpty()) {
                val repairsBefore = captionRepairsQueued.size
                val known = existingForPage(files)
                val folderCache = mutableMapOf<String, String?>()
                database.inImmediateTransaction {
                    files.forEach { reconcile(it, folderCache, known) }
                }
                if (
                    captionRepairsQueued.size > repairsBefore ||
                    fileDao.pendingPublishCount(chatId) > 0
                ) {
                    publishScheduler.kick()
                }
            }

            val gone = deleted - pendingDeletes
            if (gone.isNotEmpty()) {
                detach(fileDao.filesWithRemote().filter { it.chatId == chatId && it.messageId in gone })
            }
        } catch (e: TelegramException) {
            SafeLog.w(TAG, "Applying remote changes failed: ${e.code}")
        }
    }

    private fun RemoteDocument.isFolderState(): Boolean =
        fileName == RemoteFolderState.FILE_NAME || caption.startsWith(RemoteFolderState.MARKER)

    /** App-managed bookkeeping documents must never surface as user files. */
    private fun isInternalDocument(document: RemoteDocument): Boolean =
        document.fileName == RemoteFolderState.FILE_NAME ||
                document.fileName == KeyBackupCodec.BACKUP_FILE_NAME ||
                document.fileName == IndexSnapshotFile.FILE_NAME ||
                document.caption.startsWith(RemoteFolderState.MARKER) ||
                document.caption.startsWith(IndexSnapshotFile.MARKER) ||
                document.caption.startsWith(KEY_BACKUP_MARKER) ||
                document.caption.startsWith(ICON_MARKER)

    private fun isNewToLocal(document: RemoteDocument, known: KnownRows): Boolean {
        val existing = known.byUniqueId[document.uniqueFileId] ?: return true
        return existing.messageId != document.messageId
    }

    private suspend fun existingForPage(documents: List<RemoteDocument>): KnownRows {
        val manifests = documents.associateWith { manifestCodec.decode(it.caption) }
        val fileIds = manifests.values.mapNotNull { it?.fileId }.distinct()
        val uniqueIds = documents.map { it.uniqueFileId }.distinct()
        val byId = if (fileIds.isEmpty()) {
            emptyMap()
        } else {
            fileDao.byIds(fileIds).associateBy { it.id }
        }
        val byUniqueId = if (uniqueIds.isEmpty()) {
            emptyMap()
        } else {
            fileDao.byRemoteUniqueIds(uniqueIds).mapNotNull { entity ->
                entity.remoteUniqueId?.let { it to entity }
            }.toMap()
        }
        // Rows bound to the page's exact messages. Duplicate rows can share one
        // message id (the accumulated damage the merge sweep collapses); when they
        // do, a row carrying a manifest baseline wins the map slot, as it is the
        // most complete representative of the message.
        val byMessageId = documents.groupBy { it.chatId }
            .flatMap { (chatId, chatDocuments) ->
                fileDao.byMessageIds(chatId, chatDocuments.map { it.messageId }.distinct())
            }
            .sortedBy { it.remoteBase != null }
            .associateBy { it.messageId!! }
        val parts = if (fileIds.isEmpty()) emptyList() else filePartDao.partsOfAll(fileIds)
        return KnownRows(
            manifests = manifests,
            byId = byId,
            byUniqueId = byUniqueId,
            byMessageId = byMessageId,
            splitCounts = splitCounts(manifests.values, byId, parts),
            firstPartStored = parts.filter { it.partIndex == 0 }.map { it.fileId }.toSet()
        )
    }

    private fun splitCounts(
        manifests: Collection<RemoteFileManifest?>,
        byId: Map<String, FileEntity>,
        parts: List<FilePartEntity>
    ): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        fun note(fileId: String, count: Int) {
            if (count > 1) counts[fileId] = maxOf(counts[fileId] ?: 0, count)
        }
        manifests.forEach { manifest ->
            if (manifest?.isPart == true) note(manifest.fileId, manifest.partCount)
        }
        byId.values.forEach { entity -> note(entity.id, entity.partCount) }
        parts.groupBy { it.fileId }
            .forEach { (fileId, stored) -> note(fileId, stored.maxOf { it.partIndex } + 1) }
        return counts
    }

    /**
     * A part 0 caption can lose its part fields; its siblings, part rows or recorded count still
     * prove the split.
     */
    private fun splitCountOfDamagedFirstPart(document: RemoteDocument, known: KnownRows): Int? {
        val manifest = known.manifests[document] ?: return null
        if (manifest.isPart) return null
        return known.splitCounts[manifest.fileId]
    }

    /**
     * Paths are only a fallback: two folders can share a name, and a trashed file must not recreate
     * its folder.
     */
    private suspend fun resolveFolder(
        manifest: RemoteFileManifest,
        cache: MutableMap<String, String?>,
        chatId: Long,
        messageId: Long
    ): String? {
        manifest.folderId?.let { id ->
            if (cache.containsValue(id)) return id
            if (folderPathResolver.exists(id, chatId)) {
                cache[manifest.folderPath] = id
                return id
            }
        }
        if (cache.containsKey(manifest.folderPath)) return cache[manifest.folderPath]
        val resolved = if (manifest.trashedAt != null) {
            folderPathResolver.resolveExisting(manifest.folderPath, chatId)
        } else {
            folderPathResolver.resolveExisting(manifest.folderPath, chatId)
                ?: folderPathResolver.resolveOrCreate(
                    manifest.folderPath,
                    manifest.folderId,
                    chatId = chatId
                ).also { created ->
                    SafeLog.d(
                        TAG,
                        "Message $messageId recreated its folder path as $created " +
                                "(part ${manifest.partIndex} of ${manifest.partCount})"
                    )
                }
        }
        cache[manifest.folderPath] = resolved
        return resolved
    }

    private suspend fun reconcile(
        document: RemoteDocument,
        folderCache: MutableMap<String, String?>,
        known: KnownRows
    ): ReconcileResult {
        if (InFlightForwardedCopies.isRegistered(document.chatId, document.messageId)) {
            // A copy operation owns this message right now: it was forwarded moments
            // ago and is still caption-less, and the copy will either relabel and
            // record it or delete it. Adopting it here would file it as a stray root
            // duplicate of its source — the failure that once created ~200 stray
            // rows from a single copy. Leave it entirely alone for this pass.
            return ReconcileResult.UNCHANGED
        }
        val manifest = known.manifests[document]
        // A row bound to this exact message IS this document's row — the strongest
        // identity there is, and the only one a caption-less document can be matched
        // by reliably. Matching it first is what lets an adopted orphan settle after
        // one pass: the unique-id route squeezes every row sharing a content
        // id into a single map entry and then refuses any row bound to a different
        // message, so an orphan whose content duplicates its source's could never
        // match the row a previous pass created for it and was re-inserted fresh,
        // with a new random id, on every pass forever.
        //
        // The choice itself lives in RemoteMessageResolver.matchRow, shared
        // with the publish repair ladder (PC-37) so the two can never drift.
        // PC-37 hardening: when the message-id binding and the td1 caption's
        // uuid name DIFFERENT rows, the caption wins — the caption was written
        // by the file's own publisher, while a stored binding can be a stale
        // adoption — and the disagreement is logged loudly instead of the old
        // silent route-1 priority, which let a mis-binding entrench itself
        // forever (every later sync saw the wrong row claiming the message
        // and left it alone).
        val match = RemoteMessageResolver.matchRow(
            document = document,
            manifest = manifest,
            byId = known.byId,
            byUniqueId = known.byUniqueId,
            byMessageId = known.byMessageId
        )
        match.conflict?.let { conflict ->
            SafeLog.w(
                TAG,
                "Binding conflict: message ${document.messageId} in chat " +
                    "${document.chatId} — caption names file ${conflict.captionRowId} " +
                    "but row ${conflict.messageRowId} claims the message; " +
                    "caption uuid wins"
            )
            // The claimant's binding is detached, never deleted: left
            // in place it would still claim this message, and the
            // duplicate-message merge after the scan would collapse
            // the two rows by age — possibly deleting the very row
            // the caption just vindicated. Detached, the claimant
            // stays in the library unbound; a later scan of its own
            // real message (or the repair ladder, if it is pending)
            // can bind it again.
            fileDao.detachRemote(conflict.messageRowId)
        }
        val indexedExisting = match.row
        // existingForPage() is read before the page transaction begins. A user can move a
        // file in that gap. Re-read the current row inside the transaction so pendingPublish
        // and the latest folderId cannot be lost to the older sync snapshot.
        val existing = indexedExisting?.let { indexed ->
            fileDao.byId(indexed.id) ?: indexed
        }

        val publishedBase = FileBase.fromText(existing?.remoteBase)
        val incomingBase = manifest?.let { FileBase.of(it) }
        val currentBase = existing?.let { FileBase.of(it) }

        val stalePublishedManifest = existing != null &&
            !existing.pendingPublish &&
            publishedBase != null &&
            incomingBase != null &&
            incomingBase != publishedBase &&
            currentBase != publishedBase &&
            manifest.modifiedAt < existing.modifiedAt
        if (stalePublishedManifest) {
            // A delayed Telegram update can deliver the pre-move caption after the local
            // publish succeeded. Ignore the stale remote event; a genuine local delta is
            // protected by its own pendingPublish marker, set atomically by every writer.
            return ReconcileResult.UNCHANGED
        }

        val damagedSplitCount = splitCountOfDamagedFirstPart(document, known)
        if (manifest != null && manifest.isPart) {
            recordPart(document, manifest)
        } else if (manifest != null && damagedSplitCount != null &&
            manifest.fileId !in known.firstPartStored
        ) {
            recordPart(document, FileParts.asFirstPart(manifest, damagedSplitCount))
        }
        val queueCaptionRepair = manifest != null && damagedSplitCount != null &&
                existing?.pendingPublish != true && captionRepairsQueued.add(manifest.fileId)

        val laterPart = manifest != null && manifest.isPart && manifest.partIndex != 0
        if (laterPart && existing != null) return ReconcileResult.UNCHANGED

        val local = existing?.takeIf { it.pendingPublish }
        val hasPendingLocalMoveWithoutBaseline = local != null &&
            existing?.remoteBase.isNullOrBlank() &&
            manifest != null &&
            local.folderId != manifest.folderId
        val choice = FieldChoice(
            local = local?.let { FileBase.of(it) },
            remote = manifest?.let { FileBase.of(it) },
            base = FileBase.fromText(existing?.remoteBase),
            localIsNewer = local != null &&
                (manifest == null || local.modifiedAt >= manifest.modifiedAt)
        )

        val folderId = when {
            local != null && (
                hasPendingLocalMoveWithoutBaseline ||
                    choice.keepsLocal { it.folderId }
                ) ->
                local.folderId ?: local.preTrashFolderId

            manifest == null -> existing?.folderId
            laterPart -> manifest.folderId?.takeIf {
                folderPathResolver.exists(it, document.chatId)
            } ?: existing?.folderId
            // An unresolved folder reference must not silently demote a file
            // that already has a folder to the root listing: when the manifest
            // actually names a folder but resolution fails (e.g. a trashed
            // manifest whose path no longer resolves), keep the current
            // placement. A manifest that names no folder legitimately means
            // root, and a remote move back to root must still apply.
            else -> resolveFolder(manifest, folderCache, document.chatId, document.messageId)
                ?: existing?.folderId?.takeIf {
                    manifest.folderId != null || manifest.folderPath.isNotBlank()
                }
        }
        val folderTrashedAt = folderId?.let { folderDao.byId(it)?.trashedAt }

        val name = local?.name?.takeIf { choice.keepsLocal { it.name } }
            ?: manifest?.name ?: existing?.name
            ?: document.fileName.ifBlank { "file-${document.messageId}" }
        // Telegram can label arbitrary uploaded documents as application/octet-stream.
        // A manifest or Telegram MIME value that is generic must not hide a known extension:
        // otherwise valid audio/video/image files are indexed as OTHER and never reach their
        // native preview path.
        val mime = MimeTypes.resolve(name, manifest?.mimeType, document.mimeType)

        val now = System.currentTimeMillis()
        val trashedAt = when {
            local != null && choice.keepsLocal { it.trashedAt } -> local.trashedAt
            manifest != null && choice.remoteChanged { it.trashedAt } ->
                manifest.trashedAt ?: folderTrashedAt

            else -> manifest?.trashedAt ?: folderTrashedAt ?: existing?.trashedAt
        }
        // The baseline is the published state as this client understands it. When the
        // manifest was adopted and its folderId names a folder that no longer exists
        // here (merged away), resolveFolder adopted the file into the surviving folder
        // by path — recording the raw dead id as the baseline would diverge from the
        // adopted row forever and read as an unpublished local move. With a local edit
        // in flight the baseline stays the raw published state: the recovery check and
        // the outbox keep measuring the edit against what is actually on Telegram.
        val adoptedBase = manifest?.let { published ->
            val base = FileBase.of(published)
            if (local != null) {
                base
            } else {
                base.copy(
                    folderId = if (trashedAt != null) {
                        folderId ?: existing?.preTrashFolderId
                    } else {
                        folderId
                    }
                )
            }
        }
        val entity = FileEntity(
            id = manifest?.fileId ?: existing?.id ?: UUID.randomUUID().toString(),
            folderId = if (trashedAt != null) null else folderId,
            name = name,
            sizeBytes = manifest?.sizeBytes ?: document.sizeBytes,
            mimeType = mime,
            category = FileCategory.fromMimeType(mime),
            localPath = existing?.localPath,
            contentHash = manifest?.contentHash ?: existing?.contentHash,
            chatId = document.chatId,
            messageId = document.messageId,
            remoteFileId = document.remoteFileId,
            remoteUniqueId = document.uniqueFileId,
            backupState = BackupState.BACKED_UP,
            isHidden = local?.isHidden?.takeIf { choice.keepsLocal { it.hidden } }
                ?: manifest?.hidden ?: existing?.isHidden ?: false,
            isArchived = local?.isArchived?.takeIf { choice.keepsLocal { it.archived } }
                ?: manifest?.archived ?: existing?.isArchived ?: false,
            isFavorite = local?.isFavorite?.takeIf { choice.keepsLocal { it.favorite } }
                ?: manifest?.favorite ?: existing?.isFavorite ?: false,
            isAvailableOffline = existing?.isAvailableOffline == true,
            isEncrypted = manifest?.encrypted
                ?: manifestCodec.isEncryptedManifest(document.caption),
            width = manifest?.width ?: existing?.width,
            height = manifest?.height ?: existing?.height,
            durationMs = manifest?.durationMs ?: existing?.durationMs,
            trashedAt = trashedAt,
            preTrashFolderId = if (trashedAt != null) {
                folderId ?: existing?.preTrashFolderId
            } else {
                existing?.preTrashFolderId
            },
            pendingPublish = existing?.pendingPublish == true || queueCaptionRepair,
            createdAt = manifest?.createdAt ?: existing?.createdAt
            ?: (document.dateSeconds * 1000L),
            modifiedAt = maxOf(
                local?.modifiedAt ?: Long.MIN_VALUE,
                manifest?.modifiedAt ?: Long.MIN_VALUE,
                existing?.modifiedAt ?: Long.MIN_VALUE
            ).let { latest ->
                if (latest == Long.MIN_VALUE) document.dateSeconds * 1000L else latest
            },
            addedAt = existing?.addedAt ?: now,
            partCount = damagedSplitCount ?: manifest?.partCount ?: existing?.partCount ?: 0,
            iconFileId = manifest?.iconFileId ?: existing?.iconFileId,
            manifestExtra = if (manifest != null) {
                ExtraFields.toText(manifest.extra)
            } else {
                existing?.manifestExtra
            },
            remoteBase = adoptedBase?.toText() ?: existing?.remoteBase
        )

        if (laterPart) {
            fileDao.upsert(
                entity.copy(
                    chatId = null,
                    messageId = null,
                    remoteFileId = null,
                    remoteUniqueId = null
                )
            )
            return ReconcileResult.INSERTED
        }

        // PC-37: every binding write is logged with the file uuid and
        // the message it binds. The 144 mis-bound rows of the
        // 2026-11-21 meltdown could never be traced to the write that
        // bound them because no mapping write was ever logged; a
        // binding that is created or moved here now leaves one line.
        if (entity.messageId != null &&
            (existing == null || existing.chatId != entity.chatId ||
                existing.messageId != entity.messageId)
        ) {
            SafeLog.w(
                TAG,
                "setRemoteMapping: file=${entity.id} chatId=${entity.chatId} " +
                    "messageId=${entity.messageId} " +
                    "remoteUniqueId=${entity.remoteUniqueId} (via sync reconcile)"
            )
        }

        return when {
            existing == null -> {
                fileDao.upsert(entity)
                ReconcileResult.INSERTED
            }

            existing.id != entity.id -> {
                fileDao.deleteByIds(listOf(existing.id))
                fileDao.upsert(entity)
                ReconcileResult.UPDATED
            }

            existing != entity.keepingRemoteIdOf(existing) -> {
                fileDao.upsert(entity)
                ReconcileResult.UPDATED
            }

            else -> ReconcileResult.UNCHANGED
        }
    }

    private fun FileEntity.keepingRemoteIdOf(existing: FileEntity): FileEntity =
        if (remoteUniqueId != null && remoteUniqueId == existing.remoteUniqueId) {
            copy(remoteFileId = existing.remoteFileId)
        } else {
            this
        }

    private suspend fun recordPart(document: RemoteDocument, manifest: RemoteFileManifest) {
        val existing = filePartDao.byPart(manifest.fileId, manifest.partIndex)
        val sameRemotePart = existing != null &&
                existing.chatId == document.chatId &&
                existing.messageId == document.messageId &&
                existing.remoteUniqueId == document.uniqueFileId
        filePartDao.upsert(
            FilePartEntity(
                fileId = manifest.fileId,
                partIndex = manifest.partIndex,
                chatId = document.chatId,
                messageId = document.messageId,
                remoteFileId = document.remoteFileId,
                remoteUniqueId = document.uniqueFileId,
                plainOffset = manifest.partOffset,
                plainSize = manifest.partSize,
                storedSize = document.sizeBytes,
                uploadedAt = document.dateSeconds * 1000L,
                plainHash = manifest.partHash ?: existing?.plainHash?.takeIf { sameRemotePart }
            )
        )
    }

    companion object {
        private const val TAG = "SyncRepository"
        private const val KEY_BACKUP_MARKER = "#teledrive-keybackup"
        private const val ICON_MARKER = "#teledrive-icon"
        private const val PAGE_SIZE = 100
        private const val MAX_PAGES = 2000
        private const val CATCH_UP_INTERVAL_MS = 60_000L
        private const val LIVE_BATCH_MS = 2_000L
    }
}
