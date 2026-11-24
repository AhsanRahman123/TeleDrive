package com.drdisagree.teledrive.core.publish

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.dao.PendingDeleteDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.inImmediateTransaction
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FileBase
import com.drdisagree.teledrive.data.repository.FileManifestPublisher
import com.drdisagree.teledrive.data.repository.FolderStateSynchronizer
import com.drdisagree.teledrive.data.repository.RemoteMessageResolver
import com.drdisagree.teledrive.domain.model.BackupState
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Rows keep their flag until the caption is accepted, so a failed edit is retried rather than
 * reverted by the next sync.
 */
class PublishOutboxDrainer(
    private val fileDao: FileDao,
    private val folderDao: FolderDao,
    private val pendingDeleteDao: PendingDeleteDao,
    private val telegramClient: TelegramClient,
    private val manifestPublisher: FileManifestPublisher,
    private val folderStateSynchronizer: FolderStateSynchronizer,
    private val tombstoneDao: FolderTombstoneDao,
    private val database: TeleDriveDatabase,
    private val transferDao: TransferDao,
    private val resolver: RemoteMessageResolver,
    private val reuploadEnqueuer: ReuploadEnqueuer,
    private val activeChannel: ActiveChannel
) {

    /**
     * Seconds left in the Telegram pacer's account-wide flood wait
     * (PC-36), read by the publish run loop so a watchdog-ended run
     * can name the flood wait as its reason.
     */
    val floodWaitSeconds: Int get() = telegramClient.floodWaitSeconds

    suspend fun drain(
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit = { _, _ -> },
        onWaiting: suspend (secondsLeft: Int) -> Unit = {},
        onFailure: suspend (fileId: String, error: AppError) -> Unit = { _, _ -> }
    ): Boolean {
        var lastPublished = 0
        var lastTotal = 0
        val startChatId = activeChannel.id()
        SafeLog.d(
            TAG,
            "Drain started: ${fileDao.pendingPublishCount(startChatId)} files and " +
                "${folderDao.pendingPublishCount(startChatId)} folders pending"
        )
        val drained = drainOutbox(
            isStopped = isStopped,
            onProgress = { publishedNow, total ->
                lastPublished = publishedNow
                lastTotal = total
                onProgress(publishedNow, total)
            },
            onWaiting = onWaiting,
            onFailure = onFailure
        )
        SafeLog.d(
            TAG,
            "Drain finished: drained=$drained, published $lastPublished of $lastTotal"
        )
        return drained
    }

    private suspend fun drainOutbox(
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit,
        onWaiting: suspend (secondsLeft: Int) -> Unit,
        onFailure: suspend (fileId: String, error: AppError) -> Unit
    ): Boolean {
        if (!replayDeletes(isStopped, onWaiting, onFailure)) return false
        if (isStopped()) return false

        // PC-32, second re-investigation (round 8, his test video):
        // rows with no channel message are not sync work at all — the
        // upload that eventually lands writes the manifest as the
        // message's initial caption from the row's current state, so
        // there is no stale caption to correct. Resolving their flags
        // here is what lets the pending count reach zero: while the
        // round-7 drain merely skipped them, the banner kept counting
        // them, drains completed "successfully" in seconds, and the
        // count stalled at exactly the local-only floor (4,370 →
        // 1,056 on his machine) with Sync now an instant no-op and the
        // loop Idle — indistinguishable from a frozen sync. The rows
        // themselves are untouched (see FileDao).
        // PC-38: the sweep is scoped to the ACTIVE drive — resolving
        // another drive's local-only flags from here would silently
        // change that drive's outbox while the user looks at this one.
        val sweepChatId = activeChannel.id()
        val localOnly = fileDao.pendingLocalOnlyCount(sweepChatId)
        if (localOnly > 0) {
            val resolved = fileDao.clearPendingPublishForLocalOnly(sweepChatId)
            SafeLog.d(
                TAG,
                "Resolved $resolved local-only pending rows (no channel " +
                    "message to publish; their manifest ships with the upload)"
            )
        }

        // PC-32: only rows that can actually be published are fetched.
        // The old fetch took the oldest pending rows of ANY kind, so a
        // backlog whose head was local-only import rows (no channel
        // message yet — nothing to caption-edit) filled every batch,
        // published nothing, failed the drain, and was refetched
        // identically forever: the pending count could not move by one
        // and no error ever surfaced. Rows this drain finds permanently
        // rejected join [excludedIds] for the rest of the drain so one
        // poisoned row cannot block the rows behind it either; the
        // next drain retries them.
        val excludedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        // PC-37: mis-bound rows met during this drain share one repair
        // context — the ladder runs at most once per file, and the
        // channel scan behind it is built once per chat, not per file.
        val repairContext = RepairContext()
        val published = AtomicInteger(0)
        // PC-35: rate-limit budget accounting for the whole drain. A
        // file that spends its per-file budget steps aside (see
        // publishBatch) and sets [rateLimitDeferred]; the drain then
        // ends after that batch in the waiting state instead of
        // marching into the next batch against an account-wide limit.
        val rateLimitDeferred = AtomicBoolean(false)
        val drainRateLimitWaitSeconds = AtomicInteger(0)
        while (true) {
            if (isStopped()) return false
            // PC-38: the active drive is re-read for EVERY batch.
            // A drive switch cancels the run (ChannelRepositoryImpl),
            // but if a run ever races the switch, it follows the
            // newly active drive instead of marching on through the
            // previous drive's backlog under the new drive's banner.
            val batchChatId = activeChannel.id()
            val batch = nextBatch(batchChatId, excludedIds)
            if (batch.isEmpty()) break
            onProgress(published.get(), published.get() + fileDao.pendingPublishCount(batchChatId))
            if (!publishBatch(
                    batch, batchChatId, published, excludedIds, rateLimitDeferred,
                    drainRateLimitWaitSeconds, repairContext,
                    isStopped, onProgress, onWaiting, onFailure
                )
            ) {
                return false
            }
            if (rateLimitDeferred.get()) {
                SafeLog.w(
                    TAG,
                    "Ending the drain: a file spent its rate-limit budget; " +
                        "the rest stays pending for the next run"
                )
                return false
            }
        }

        // PC-38: the folder phase is scoped to the active drive too.
        // The push itself already targets the active chat, but the
        // snapshot comparison and the flag clear used to be global —
        // a drain on the new drive could clear the OLD drive's folder
        // flags without the old channel ever receiving its folder
        // state, silently swallowing that drive's folder push.
        val folderChatId = activeChannel.id()
        SafeLog.d(
            TAG,
            "Folder phase started: ${folderDao.pendingPublishCount(folderChatId)} folders pending"
        )
        repeat(MAX_FOLDER_PUSHES) {
            if (folderDao.pendingPublishCount(folderChatId) == 0) return true
            val before = folderSnapshot(folderChatId)
            val pushed = runCatching { folderStateSynchronizer.push() }
                .onFailure { SafeLog.w(TAG, "Folder state push failed", it) }
                .isSuccess
            if (!pushed) return false
            database.inImmediateTransaction {
                if (folderSnapshot(folderChatId) == before) {
                    folderDao.clearPendingPublish(folderChatId)
                }
            }
        }
        return folderDao.pendingPublishCount(folderChatId) == 0
    }

    /**
     * The next batch of publishable rows, skipping [excludedIds] in
     * memory rather than in a `NOT IN (:ids)` parameter list: a drain
     * that meets hundreds of rejected rows would push the parameter
     * count past SQLite's variable limit on strict drivers and fail
     * the whole drain (second-pass finding, round 7). Fetching one
     * batch-window past the excluded count guarantees a full batch of
     * fresh rows whenever that many publishable rows remain — the
     * excluded rows inside the window number at most [excludedIds]'
     * size — and an empty result means no fresh publishable rows are
     * left, so the loop still terminates.
     */
    private suspend fun nextBatch(chatId: Long?, excludedIds: Set<String>): List<FileEntity> {
        if (excludedIds.isEmpty()) return fileDao.pendingPublishable(chatId, BATCH_SIZE)
        val window = fileDao.pendingPublishable(chatId, BATCH_SIZE + excludedIds.size)
        return window.filter { it.id !in excludedIds }.take(BATCH_SIZE)
    }

    /**
     * Publishes one batch with up to [MAX_PARALLEL_PUBLISHES] caption edits in flight at
     * once. Everything on the publish path already tolerates concurrent callers: the
     * manifest publisher is stateless per file, the Telegram pacer serializes edit starts
     * and holds an account-wide flood deadline, and [clearIfUnchanged] seals each file
     * inside its own transaction. Per-file outcomes (PC-32, rate limits PC-35):
     * success seals and clears the row; a short rate limit waits (visibly, through
     * `onWaiting`) and retries the same file, but only within a bounded budget —
     * [MAX_RATE_LIMIT_WAITS_PER_FILE] waits and
     * [MAX_RATE_LIMIT_WAIT_SECONDS_PER_FILE] cumulative seconds per file, plus a
     * drain-wide wait budget; a file that spends its budget, or meets an over-long
     * limit, steps aside: it stays pending, is reported through `onFailure`, the
     * rows behind it still publish, and the drain ends after the batch in the
     * waiting state. (Pre-PC-35 the rate-limit branch retried the same file
     * forever with no progress and no log, which froze the banner at "0 of N"
     * for hours.) A local-only row (its message vanished between fetch and
     * publish) is excluded for the rest of this drain and stays pending for a
     * later one; a permanent rejection keeps the row pending, is reported
     * through `onFailure`, excludes the row for the rest of this drain, and does
     * NOT stop it — the rows behind it still publish; a transient failure stops
     * the drain with unstarted files still flagged for the next run.
     *
     * PC-37: a MisboundRemote rejection (the stored message id is not
     * the file's own editable message) is intercepted before the
     * permanent branch and runs the repair ladder once per file per
     * drain — see [repairMisbound]. A ladder that concludes nothing
     * falls back to exactly the permanent handling above.
     */
    private suspend fun publishBatch(
        batch: List<FileEntity>,
        batchChatId: Long?,
        published: AtomicInteger,
        excludedIds: MutableSet<String>,
        rateLimitDeferred: AtomicBoolean,
        drainRateLimitWaitSeconds: AtomicInteger,
        repairContext: RepairContext,
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit,
        onWaiting: suspend (secondsLeft: Int) -> Unit,
        onFailure: suspend (fileId: String, error: AppError) -> Unit
    ): Boolean = coroutineScope {
        val slots = Semaphore(MAX_PARALLEL_PUBLISHES)
        val aborted = AtomicBoolean(false)
        val deferred = AtomicInteger(0)
        val publishedAtStart = published.get()
        val progressLock = Mutex()

        suspend fun reportProgress() {
            val done = published.incrementAndGet()
            progressLock.withLock {
                onProgress(done, done + fileDao.pendingPublishCount(batchChatId))
            }
        }

        /**
         * The permanent-rejection handling, shared by genuinely
         * permanent errors and by a mis-bound row whose repair ladder
         * concluded nothing this drain (PC-37): the row stays pending
         * with its baseline intact, the failure is surfaced, the row
         * is excluded for the rest of this drain, and the rows behind
         * it still publish.
         */
        suspend fun reportPermanentRejection(row: FileEntity, error: AppError) {
            SafeLog.w(
                TAG,
                "Caption publish permanently rejected for " +
                    "${row.id} (message ${row.messageId}); " +
                    "keeping it pending for retry: $error"
            )
            excludedIds += row.id
            onFailure(row.id, error)
        }

        val jobs = batch.map { entity ->
            async {
                slots.withPermit {
                    // PC-35: this file's rate-limit budget for the drain.
                    var rateLimitWaits = 0
                    var rateLimitWaitedSeconds = 0
                    // PC-37: the row as currently bound. A repair-ladder
                    // adoption replaces it, and the loop's one retry
                    // publishes against the fresh binding.
                    var current = entity
                    while (true) {
                        if (aborted.get() || isStopped()) return@withPermit
                        if (!awaitInteractionWindow(isStopped)) {
                            aborted.set(true)
                            return@withPermit
                        }
                        when (val result = manifestPublisher.publish(current, onWaiting)) {
                            is AppResult.Success -> {
                                clearIfUnchanged(current, accepted = true)
                                reportProgress()
                                return@withPermit
                            }
                            is AppResult.Failure -> {
                                val error = result.error
                                if (error is AppError.RateLimited) {
                                    val waitSeconds = error.retryAfterSeconds + 1
                                    val fileBudgetLeft =
                                        rateLimitWaits < MAX_RATE_LIMIT_WAITS_PER_FILE &&
                                            rateLimitWaitedSeconds + waitSeconds <=
                                            MAX_RATE_LIMIT_WAIT_SECONDS_PER_FILE
                                    val drainBudgetLeft =
                                        drainRateLimitWaitSeconds.get() + waitSeconds <=
                                            MAX_DRAIN_RATE_LIMIT_WAIT_SECONDS
                                    if (error.retryAfterSeconds > INLINE_WAIT_LIMIT ||
                                        !fileBudgetLeft || !drainBudgetLeft
                                    ) {
                                        // The bounded budget is spent: the
                                        // file steps aside — it stays
                                        // pending, the failure is surfaced,
                                        // and the drain ends in the waiting
                                        // state after this batch — instead of
                                        // retrying this file forever.
                                        SafeLog.w(
                                            TAG,
                                            "Rate limit budget spent for ${entity.id} " +
                                                "(message ${current.messageId}); leaving it " +
                                                "pending for the next run: $error"
                                        )
                                        rateLimitDeferred.set(true)
                                        onFailure(entity.id, error)
                                        return@withPermit
                                    }
                                    rateLimitWaits++
                                    rateLimitWaitedSeconds += waitSeconds
                                    drainRateLimitWaitSeconds.addAndGet(waitSeconds)
                                    SafeLog.w(
                                        TAG,
                                        "Rate limited publishing ${entity.id} " +
                                            "(message ${current.messageId}); waiting " +
                                            "${waitSeconds}s (wait $rateLimitWaits of " +
                                            "$MAX_RATE_LIMIT_WAITS_PER_FILE, " +
                                            "${rateLimitWaitedSeconds}s of the " +
                                            "$MAX_RATE_LIMIT_WAIT_SECONDS_PER_FILE s " +
                                            "per-file budget)"
                                    )
                                    if (!waitOut(
                                            waitSeconds,
                                            isStopped = { isStopped() || aborted.get() },
                                            onWaiting = onWaiting
                                        )
                                    ) {
                                        aborted.set(true)
                                        return@withPermit
                                    }
                                    // Waited out: loop retries the same file.
                                } else if (error is AppError.TelegramError &&
                                    error.code == FileManifestPublisher.LOCAL_ONLY_CODE
                                ) {
                                    // No channel message yet: keep the row
                                    // pending (it publishes once the upload
                                    // lands) and exclude it for the rest of
                                    // this drain so it cannot be refetched.
                                    // (The drain fetches publishable rows
                                    // only, so this is now just the race
                                    // where the message vanished mid-drain.)
                                    SafeLog.d(
                                        TAG,
                                        "Deferring publish for local-only file ${entity.id}"
                                    )
                                    excludedIds += entity.id
                                    return@withPermit
                                } else if (error is AppError.MisboundRemote &&
                                    entity.id !in repairContext.attemptedIds
                                ) {
                                    // PC-37: the stored message id is not
                                    // this file's own editable message —
                                    // retrying the same binding can never
                                    // succeed, so run the repair ladder
                                    // (probe → re-resolve → re-upload /
                                    // remote-missing), once per file per
                                    // drain. An adoption retries the
                                    // publish once against the fresh
                                    // binding; every other conclusion has
                                    // already been surfaced by the ladder.
                                    repairContext.attemptedIds += entity.id
                                    when (
                                        val outcome = repairMisbound(
                                            current, repairContext, isStopped, onFailure
                                        )
                                    ) {
                                        is RepairOutcome.Adopted -> {
                                            current = outcome.entity
                                            continue
                                        }

                                        RepairOutcome.Handled -> {
                                            // Concluded for this drain (a
                                            // re-upload is queued, the row
                                            // is marked remote-missing, or
                                            // an upload is already in
                                            // flight): never refetch it
                                            // into a later batch.
                                            excludedIds += entity.id
                                            return@withPermit
                                        }

                                        RepairOutcome.Unresolved -> {
                                            // No repair concluded: fall back
                                            // to the permanent-rejection
                                            // handling, reported with the
                                            // original Telegram error shape.
                                            reportPermanentRejection(
                                                current,
                                                AppError.TelegramError(
                                                    400, error.telegramMessage
                                                )
                                            )
                                            return@withPermit
                                        }
                                    }
                                } else if (isPermanent(error) ||
                                    error is AppError.MisboundRemote
                                ) {
                                    // Never dead-letter into silence: the row
                                    // stays pending with its baseline intact
                                    // and the failure is surfaced, but the
                                    // drain continues — one rejected caption
                                    // must not wedge the backlog behind it
                                    // (PC-32). The next drain retries it. A
                                    // mis-bound row lands here when its one
                                    // ladder attempt this drain already ran
                                    // (including the publish retry after an
                                    // adoption being refused again).
                                    reportPermanentRejection(
                                        current,
                                        if (error is AppError.MisboundRemote) {
                                            AppError.TelegramError(
                                                400, error.telegramMessage
                                            )
                                        } else {
                                            error
                                        }
                                    )
                                    return@withPermit
                                } else {
                                    onFailure(entity.id, error)
                                    aborted.set(true)
                                    return@withPermit
                                }
                            }
                        }
                    }
                }
            }
        }
        jobs.awaitAll()
        // A batch in which every file was deferred (local-only, nothing could
        // be published) must stop the drain: the rows stay pending, so
        // refetching the batch would spin forever. The scheduler retries
        // later; once the upload lands, the publish proceeds normally.
        val madeProgress = published.get() > publishedAtStart
        !aborted.get() && !isStopped() && !(deferred.get() > 0 && !madeProgress)
    }

    /**
     * Per-drain repair state (PC-37): the ladder runs at most once per
     * file per drain, and the channel scan behind it is built once per
     * chat and shared by every mis-bound file in the drain — one
     * channel pass, not one per file.
     */
    private class RepairContext {
        val attemptedIds: MutableSet<String> =
            java.util.Collections.synchronizedSet(mutableSetOf())
        private val scans = mutableMapOf<Long, RemoteMessageResolver.ChannelIndex>()
        private val scanMutex = Mutex()

        suspend fun scan(
            resolver: RemoteMessageResolver,
            chatId: Long,
            isStopped: () -> Boolean
        ): RemoteMessageResolver.ChannelIndex = scanMutex.withLock {
            scans.getOrPut(chatId) { resolver.scanChannel(chatId, isStopped) }
        }
    }

    private sealed interface RepairOutcome {
        /** The row was re-bound to its real message; retry the publish once with [entity]. */
        data class Adopted(val entity: FileEntity) : RepairOutcome

        /** The ladder fully handled the row (re-upload queued, remote-missing marked, or an upload already in flight). */
        data object Handled : RepairOutcome

        /** No repair concluded this drain; the caller applies the permanent-rejection handling. */
        data object Unresolved : RepairOutcome
    }

    /**
     * The mis-binding repair ladder (PC-37), run once per file per
     * drain from the publish loop's MisboundRemote branch. The stored
     * message id of a mis-bound row points at nothing
     * (`MESSAGE_ID_INVALID`) or at a message that is not the file's
     * own editable post (`Message can't be edited`); retrying the
     * edit against it can never succeed, so the ladder re-establishes
     * the binding from evidence, in order:
     *
     * (a) probe the stored message — if it resolves and its caption
     *     names this file, the binding is right and the message
     *     itself refuses edits: skip to (d);
     * (b) re-resolve by the td1 caption uuid over one shared channel
     *     scan (the same matching machinery sync uses, factored into
     *     [RemoteMessageResolver]);
     * (c) re-resolve by Telegram unique id — only a caption-less
     *     document may be adopted this way, since a document whose
     *     manifest names a file is spoken for;
     * (d) [repairUnrecoverable]: re-upload from the local copy, or
     *     mark the row durably remote-missing when there is none.
     *
     * A scan MISS is only conclusive over a complete scan: concluding
     * "gone" from a partial pass could mark a live file
     * remote-missing, or re-upload a duplicate of a message the scan
     * never reached.
     *
     * Throughout the ladder, the message at the OLD stored id is never
     * edited or deleted: the consecutive id blocks in the field data
     * mean it likely belongs to another file, and touching it would
     * corrupt that file. Rebinding changes only this row's mapping.
     */
    private suspend fun repairMisbound(
        entity: FileEntity,
        repairContext: RepairContext,
        isStopped: () -> Boolean,
        onFailure: suspend (fileId: String, error: AppError) -> Unit
    ): RepairOutcome {
        val chatId = entity.chatId ?: return RepairOutcome.Unresolved
        val messageId = entity.messageId ?: return RepairOutcome.Unresolved

        // (a) Probe.
        val probed = try {
            telegramClient.getDocument(chatId, messageId)
        } catch (e: TelegramException) {
            SafeLog.w(
                TAG,
                "Repair probe failed for ${entity.id} (message $messageId): ${e.message}"
            )
            return RepairOutcome.Unresolved
        }
        if (probed != null && resolver.manifestOf(probed)?.fileId == entity.id) {
            SafeLog.w(
                TAG,
                "Repair: message $messageId IS ${entity.id}'s own message " +
                    "but Telegram refuses to edit it"
            )
            return repairUnrecoverable(entity, onFailure)
        }

        // (b) + (c) Re-resolve over the shared scan.
        val index = repairContext.scan(resolver, chatId, isStopped)
        val byUuid = index.byUuid[entity.id]
        if (byUuid != null) {
            val foundManifest = resolver.manifestOf(byUuid)
            // Split files: every part message carries the parent uuid,
            // but only part 0's message is the row's binding. A later
            // part is not adoptable — and its presence proves nothing
            // about part 0, so this is a miss, not a hit.
            val adoptable = foundManifest != null &&
                !(foundManifest.isPart && foundManifest.partIndex != 0)
            if (adoptable) {
                if (byUuid.messageId == messageId) {
                    // Our manifest sits at the stored id after all (the
                    // probe raced a caption write): own message, edit
                    // refused — same conclusion as a probe hit.
                    return repairUnrecoverable(entity, onFailure)
                }
                SafeLog.w(
                    TAG,
                    "Repair: found ${entity.id}'s manifest at message " +
                        "${byUuid.messageId} (stored binding said $messageId); adopting it"
                )
                return adopt(entity, byUuid)
            }
        }
        val uniqueId = entity.remoteUniqueId
        if (uniqueId != null) {
            val byUnique = index.byUniqueId[uniqueId]
            if (byUnique != null && resolver.manifestOf(byUnique) == null) {
                if (byUnique.messageId == messageId) {
                    // The stored message carries this file's content
                    // (unique id) but no manifest: it is the file's own
                    // message in caption-less form, and it refuses the
                    // edit — re-upload is the repair.
                    return repairUnrecoverable(entity, onFailure)
                }
                SafeLog.w(
                    TAG,
                    "Repair: found a caption-less message ${byUnique.messageId} " +
                        "carrying ${entity.id}'s unique id " +
                        "(stored binding said $messageId); adopting it"
                )
                return adopt(entity, byUnique)
            }
        }

        if (!index.complete) {
            SafeLog.w(
                TAG,
                "Repair: channel scan incomplete; deferring repair of " +
                    "${entity.id} to the next drain"
            )
            return RepairOutcome.Unresolved
        }
        return repairUnrecoverable(entity, onFailure)
    }

    /**
     * Rebinds [entity] to [document] — this row's mapping only. A
     * different row claiming the found message is detached, never
     * deleted: its caption does not name that row, so its claim is the
     * stale one, but the row itself may still heal through its own
     * ladder (or be a duplicate the sync merge sweep settles).
     */
    private suspend fun adopt(entity: FileEntity, document: RemoteDocument): RepairOutcome {
        database.inImmediateTransaction {
            val claimants = fileDao.byMessageIds(document.chatId, listOf(document.messageId))
                .filter { it.id != entity.id }
            for (claimant in claimants) {
                SafeLog.w(
                    TAG,
                    "Repair adoption conflict: message ${document.messageId} in chat " +
                        "${document.chatId} is claimed by row ${claimant.id} but its " +
                        "caption names ${entity.id}; detaching the claimant's " +
                        "mapping (row kept)"
                )
                fileDao.detachRemote(claimant.id)
            }
            fileDao.setRemoteMappingLogged(
                id = entity.id,
                chatId = document.chatId,
                messageId = document.messageId,
                remoteFileId = document.remoteFileId,
                remoteUniqueId = document.uniqueFileId,
                state = BackupState.BACKED_UP
            )
        }
        SafeLog.w(
            TAG,
            "Repair: file ${entity.id} re-bound to message ${document.messageId} " +
                "in chat ${document.chatId}"
        )
        val fresh = fileDao.byId(entity.id) ?: return RepairOutcome.Unresolved
        return RepairOutcome.Adopted(fresh)
    }

    /**
     * Ladder step (d): the file's Telegram message is gone or not
     * editable and no reference finds it.
     *
     * With a local copy, the repair is a re-upload through the normal
     * transfer path: a fresh message with the correct td1 caption is
     * created, the row binds to it on completion, and the row's
     * pending change — including its trashed state — then publishes
     * against the new message on the next drain, so the file's content
     * exists in the channel again and Trash stays restorable. The
     * stale binding is detached BEFORE enqueueing, because the
     * transfer executor deletes the message at the row's stored id
     * after a successful re-upload (its superseded-message cleanup) —
     * and here that id likely belongs to another file. Detached first,
     * the executor sees no superseded message and deletes nothing.
     *
     * Without a local copy, the Telegram copy was the only copy and it
     * is gone: the row is marked durably remote-missing (its pending
     * flag is cleared only together with that mark), taken out of the
     * retry cycle, and surfaced by name. The row is never deleted and
     * never counted as saved.
     */
    private suspend fun repairUnrecoverable(
        entity: FileEntity,
        onFailure: suspend (fileId: String, error: AppError) -> Unit
    ): RepairOutcome {
        val localPath = entity.localPath
        if (localPath != null && File(localPath).exists()) {
            // An upload already in flight for this file may BE the
            // repair (or the user's own retry): it captured the row
            // when it started, so detaching under it could still let
            // its completion delete the old message. Step aside; the
            // next drain re-evaluates with the transfer settled.
            if (transferDao.unfinishedIdsForFiles(listOf(entity.id)).isNotEmpty()) {
                SafeLog.w(
                    TAG,
                    "Repair: an upload is already in flight for ${entity.id}; " +
                        "leaving its repair to the next drain"
                )
                return RepairOutcome.Handled
            }
            fileDao.detachRemote(entity.id)
            return when (val result = reuploadEnqueuer.enqueue(entity.id)) {
                is AppResult.Success -> {
                    SafeLog.w(
                        TAG,
                        "Repair: re-upload queued for ${entity.id} " +
                            "('${entity.name}') as transfer ${result.value}; its " +
                            "pending change publishes against the new message " +
                            "once the upload lands"
                    )
                    RepairOutcome.Handled
                }

                is AppResult.Failure -> {
                    SafeLog.w(
                        TAG,
                        "Repair: re-upload could not be queued for " +
                            "${entity.id}: ${result.error}"
                    )
                    onFailure(entity.id, result.error)
                    RepairOutcome.Handled
                }
            }
        }
        fileDao.markRemoteMissing(entity.id)
        SafeLog.w(
            TAG,
            "Repair: Telegram copy of ${entity.id} ('${entity.name}') is " +
                "unrecoverable and no local copy exists; marked " +
                "remote-missing (row kept, out of the publish cycle)"
        )
        onFailure(entity.id, AppError.RemoteMissing(entity.name))
        return RepairOutcome.Handled
    }

    /**
     * Publishing one manifest ends in a `files`-table write, and every such
     * write invalidates the paging source behind the Files/Gallery lists,
     * forcing a visible-list reload on the main thread. While the user is
     * scrolling or a folder/tab change is settling (PublishInteractionGate),
     * wait for a quiet window instead of landing that reload mid-gesture.
     * Fails open after [MAX_INTERACTION_WAIT_MS] so interaction can delay a
     * drain but never starve it.
     */
    private suspend fun awaitInteractionWindow(isStopped: () -> Boolean): Boolean {
        var waitedMs = 0L
        while (PublishInteractionGate.isBusy()) {
            if (isStopped()) return false
            if (waitedMs >= MAX_INTERACTION_WAIT_MS) return true
            delay(INTERACTION_POLL_MS.milliseconds)
            waitedMs += INTERACTION_POLL_MS
        }
        return true
    }

    private suspend fun waitOut(
        seconds: Int,
        isStopped: () -> Boolean,
        onWaiting: suspend (Int) -> Unit
    ): Boolean {
        var left = seconds
        while (left > 0) {
            if (isStopped()) return false
            onWaiting(left)
            val step = minOf(left, WAIT_TICK_SECONDS)
            delay((step * 1000L).milliseconds)
            left -= step
        }
        return true
    }

    private suspend fun clearIfUnchanged(published: FileEntity, accepted: Boolean) {
        database.inImmediateTransaction {
            val current = fileDao.byId(published.id) ?: return@inImmediateTransaction
            if (accepted && published.messageId != null) {
                fileDao.setRemoteBase(published.id, FileBase.of(published).toText())
            }
            val unchanged = current.copy(
                pendingPublish = published.pendingPublish,
                remoteBase = published.remoteBase
            ) == published
            if (unchanged) {
                fileDao.clearPendingPublish(published.id)
            }
        }
    }

    private suspend fun folderSnapshot(chatId: Long?) = Pair(
        folderDao.allFolders(chatId).map { it.copy(pendingPublish = false) }.sortedBy { it.id },
        tombstoneDao.all().sortedBy { it.id }
    )

    /**
     * Deleting a message that is already gone is accepted, so replaying after a crash costs
     * nothing.
     *
     * Round 8 (PC-32 re-investigation): this replay used to GATE the
     * whole drain — it runs before any file publishing, and the first
     * non-permanent delete failure returned false, so the drain ended
     * having published ZERO files. A persistently failing delete batch
     * (at the user's scale the meltdown left a large pending-delete
     * backlog) therefore froze the publish queue at its full count
     * indefinitely: every Sync now press honestly re-ran the loop and
     * re-failed at the same gate, before a single caption edit, and
     * the only trace was a log line. Deletes are idempotent and
     * independent of publishing, so a failing batch is now DEFERRED
     * for the rest of this drain — its rows and pending entries stay
     * untouched for the next drain, the failure is reported through
     * [onFailure] instead of vanishing — and replay continues with
     * the remaining batches. Publishing can no longer be blocked by
     * delete replay; only a user stop aborts it.
     */
    private suspend fun replayDeletes(
        isStopped: () -> Boolean,
        onWaiting: suspend (secondsLeft: Int) -> Unit,
        onFailure: suspend (fileId: String, error: AppError) -> Unit
    ): Boolean {
        // Attempts are keyed by (chatId, messageId) — the entity's own
        // primary key: a message id is only unique inside its chat.
        val attempted = mutableSetOf<Pair<Long, Long>>()
        while (true) {
            if (isStopped()) return false
            // Fetch one window past this drain's attempts and filter in
            // memory (same pattern as nextBatch): deferred batches stay
            // in the table, so a plain oldest(BATCH_SIZE) refetch would
            // meet them first forever.
            val batch = pendingDeleteDao.oldest(BATCH_SIZE + attempted.size)
                .filter { (it.chatId to it.messageId) !in attempted }
                .take(BATCH_SIZE)
            if (batch.isEmpty()) return true

            for ((chatId, pending) in batch.groupBy { it.chatId }) {
                val messageIds = pending.map { it.messageId }
                attempted += pending.map { it.chatId to it.messageId }
                try {
                    telegramClient.deleteMessages(chatId, messageIds, onWaiting)
                    fileDao.deleteByIds(pending.map { it.fileId })
                    pendingDeleteDao.clear(chatId, messageIds)
                } catch (e: TelegramException) {
                    // PC-36: a rate limit is never "unrepeatable".
                    // Since the pacer caps its flood sleep and throws
                    // the remainder as a 429, a delete replay starting
                    // inside a long flood window now routinely meets
                    // one here — it must defer like any transient
                    // failure. Dropping on 429 would delete the local
                    // rows while the Telegram messages live on, and
                    // the files would resurrect at the next sync.
                    if (!e.isRateLimit && e.code in PERMANENT_CODES) {
                        SafeLog.w(TAG, "Dropping unrepeatable delete: ${e.message}")
                        fileDao.deleteByIds(pending.map { it.fileId })
                        pendingDeleteDao.clear(chatId, messageIds)
                    } else {
                        SafeLog.w(
                            TAG,
                            "Deferring ${messageIds.size} deletes to the next drain",
                            e
                        )
                        val error = AppError.TelegramError(e.code, e.message)
                        pending.forEach { onFailure(it.fileId, error) }
                    }
                }
            }
        }
    }

    /** A rejected edit never succeeds on retry, unlike a rate limit or an outage. */
    private fun isPermanent(error: AppError): Boolean =
        error is AppError.TelegramError && error.code in PERMANENT_CODES

    private companion object {
        const val TAG = "PublishOutbox"
        const val BATCH_SIZE = 100
        const val MAX_PARALLEL_PUBLISHES = 4
        const val INLINE_WAIT_LIMIT = 900
        const val WAIT_TICK_SECONDS = 1
        const val MAX_RATE_LIMIT_WAITS_PER_FILE = 3
        const val MAX_RATE_LIMIT_WAIT_SECONDS_PER_FILE = 180
        const val MAX_DRAIN_RATE_LIMIT_WAIT_SECONDS = 300
        const val MAX_FOLDER_PUSHES = 3
        const val INTERACTION_POLL_MS = 200L
        const val MAX_INTERACTION_WAIT_MS = 30_000L
        val PERMANENT_CODES = 400..499
    }
}
