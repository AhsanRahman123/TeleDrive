package com.drdisagree.teledrive.core.publish

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.dao.PendingDeleteDao
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.database.inImmediateTransaction
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.repository.FileBase
import com.drdisagree.teledrive.data.repository.FileManifestPublisher
import com.drdisagree.teledrive.data.repository.FolderStateSynchronizer
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
    private val database: TeleDriveDatabase
) {

    suspend fun drain(
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit = { _, _ -> },
        onWaiting: suspend (secondsLeft: Int) -> Unit = {},
        onFailure: suspend (fileId: String, error: AppError) -> Unit = { _, _ -> }
    ): Boolean {
        var lastPublished = 0
        var lastTotal = 0
        SafeLog.d(
            TAG,
            "Drain started: ${fileDao.pendingPublishCount()} files and " +
                "${folderDao.pendingPublishCount()} folders pending"
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
        if (!replayDeletes(isStopped, onFailure)) return false

        // PC-32: only rows that can actually be published are fetched.
        // The old fetch took the oldest pending rows of ANY kind, so a
        // backlog whose head was local-only import rows (no channel
        // message yet — nothing to caption-edit) filled every batch,
        // published nothing, failed the drain, and was refetched
        // identically forever: the pending count could not move by one
        // and no error ever surfaced. Those rows keep their flag here
        // and publish once their upload lands. Rows this drain finds
        // permanently rejected join [excludedIds] for the rest of the
        // drain so one poisoned row cannot block the rows behind it
        // either; the next drain retries them.
        val excludedIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        val published = AtomicInteger(0)
        while (true) {
            if (isStopped()) return false
            val batch = nextBatch(excludedIds)
            if (batch.isEmpty()) break
            onProgress(published.get(), published.get() + fileDao.pendingPublishCount())
            if (!publishBatch(batch, published, excludedIds, isStopped, onProgress, onWaiting, onFailure)) {
                return false
            }
        }

        SafeLog.d(
            TAG,
            "Folder phase started: ${folderDao.pendingPublishCount()} folders pending"
        )
        repeat(MAX_FOLDER_PUSHES) {
            if (folderDao.pendingPublishCount() == 0) return true
            val before = folderSnapshot()
            val pushed = runCatching { folderStateSynchronizer.push() }
                .onFailure { SafeLog.w(TAG, "Folder state push failed", it) }
                .isSuccess
            if (!pushed) return false
            database.inImmediateTransaction {
                if (folderSnapshot() == before) folderDao.clearPendingPublish()
            }
        }
        return folderDao.pendingPublishCount() == 0
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
    private suspend fun nextBatch(excludedIds: Set<String>): List<FileEntity> {
        if (excludedIds.isEmpty()) return fileDao.pendingPublishable(BATCH_SIZE)
        val window = fileDao.pendingPublishable(BATCH_SIZE + excludedIds.size)
        return window.filter { it.id !in excludedIds }.take(BATCH_SIZE)
    }

    /**
     * Publishes one batch with up to [MAX_PARALLEL_PUBLISHES] caption edits in flight at
     * once. Everything on the publish path already tolerates concurrent callers: the
     * manifest publisher is stateless per file, the Telegram pacer serializes edit starts
     * and holds an account-wide flood deadline, and [clearIfUnchanged] seals each file
     * inside its own transaction. Per-file outcomes (PC-32):
     * success seals and clears the row; a short rate limit waits and retries the same
     * file; a local-only row (its message vanished between fetch and publish) is
     * excluded for the rest of this drain and stays pending for a later one; a
     * permanent rejection keeps the row pending, is reported through `onFailure`,
     * excludes the row for the rest of this drain, and does NOT stop it — the rows
     * behind it still publish; a transient failure or an over-long rate limit stops
     * the drain with unstarted files still flagged for the next run.
     */
    private suspend fun publishBatch(
        batch: List<FileEntity>,
        published: AtomicInteger,
        excludedIds: MutableSet<String>,
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit,
        onWaiting: suspend (secondsLeft: Int) -> Unit,
        onFailure: suspend (fileId: String, error: AppError) -> Unit
    ): Boolean = coroutineScope {
        val slots = Semaphore(MAX_PARALLEL_PUBLISHES)
        val aborted = AtomicBoolean(false)
        val progressLock = Mutex()

        suspend fun reportProgress() {
            val done = published.incrementAndGet()
            progressLock.withLock { onProgress(done, done + fileDao.pendingPublishCount()) }
        }

        val jobs = batch.map { entity ->
            async {
                slots.withPermit {
                    while (true) {
                        if (aborted.get() || isStopped()) return@withPermit
                        if (!awaitInteractionWindow(isStopped)) {
                            aborted.set(true)
                            return@withPermit
                        }
                        when (val result = manifestPublisher.publish(entity)) {
                            is AppResult.Success -> {
                                clearIfUnchanged(entity, accepted = true)
                                reportProgress()
                                return@withPermit
                            }
                            is AppResult.Failure -> {
                                val error = result.error
                                if (error is AppError.RateLimited) {
                                    if (error.retryAfterSeconds > INLINE_WAIT_LIMIT ||
                                        !waitOut(error.retryAfterSeconds + 1, isStopped, onWaiting)
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
                                } else if (isPermanent(error)) {
                                    // Never dead-letter into silence: the row
                                    // stays pending with its baseline intact
                                    // and the failure is surfaced, but the
                                    // drain continues — one rejected caption
                                    // must not wedge the backlog behind it
                                    // (PC-32). The next drain retries it.
                                    SafeLog.w(
                                        TAG,
                                        "Caption publish permanently rejected for " +
                                            "${entity.id} (message ${entity.messageId}); " +
                                            "keeping it pending for retry: $error"
                                    )
                                    excludedIds += entity.id
                                    onFailure(entity.id, error)
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
        !aborted.get() && !isStopped()
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

    private suspend fun folderSnapshot() = Pair(
        folderDao.allFoldersAnyOwner().map { it.copy(pendingPublish = false) }.sortedBy { it.id },
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
                    telegramClient.deleteMessages(chatId, messageIds)
                    fileDao.deleteByIds(pending.map { it.fileId })
                    pendingDeleteDao.clear(chatId, messageIds)
                } catch (e: TelegramException) {
                    if (e.code in PERMANENT_CODES) {
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
        const val WAIT_TICK_SECONDS = 15
        const val MAX_FOLDER_PUSHES = 3
        const val INTERACTION_POLL_MS = 200L
        const val MAX_INTERACTION_WAIT_MS = 30_000L
        val PERMANENT_CODES = 400..499
    }
}
