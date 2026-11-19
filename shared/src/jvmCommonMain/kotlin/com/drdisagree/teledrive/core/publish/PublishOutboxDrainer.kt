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
        onWaiting: suspend (secondsLeft: Int) -> Unit = {}
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
            onWaiting = onWaiting
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
        onWaiting: suspend (secondsLeft: Int) -> Unit
    ): Boolean {
        if (!replayDeletes(isStopped)) return false

        val published = AtomicInteger(0)
        while (true) {
            if (isStopped()) return false
            val batch = fileDao.pendingPublish(BATCH_SIZE)
            if (batch.isEmpty()) break
            onProgress(published.get(), published.get() + fileDao.pendingPublishCount())
            if (!publishBatch(batch, published, isStopped, onProgress, onWaiting)) return false
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
     * Publishes one batch with up to [MAX_PARALLEL_PUBLISHES] caption edits in flight at
     * once. Everything on the publish path already tolerates concurrent callers: the
     * manifest publisher is stateless per file, the Telegram pacer serializes edit starts
     * and holds an account-wide flood deadline, and [clearIfUnchanged] seals each file
     * inside its own transaction. Per-file outcomes match the old one-by-one loop exactly:
     * success seals and clears the row, a short rate limit waits and retries the same
     * file, a permanent rejection keeps the row pending and stops the drain, and a transient failure or an
     * over-long rate limit stops the drain with unstarted files still flagged for the
     * next run.
     */
    private suspend fun publishBatch(
        batch: List<FileEntity>,
        published: AtomicInteger,
        isStopped: () -> Boolean,
        onProgress: suspend (published: Int, total: Int) -> Unit,
        onWaiting: suspend (secondsLeft: Int) -> Unit
    ): Boolean = coroutineScope {
        val slots = Semaphore(MAX_PARALLEL_PUBLISHES)
        val aborted = AtomicBoolean(false)
        val deferred = AtomicInteger(0)
        val publishedAtStart = published.get()
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
                                    // lands) but do not block this drain.
                                    SafeLog.d(
                                        TAG,
                                        "Deferring publish for local-only file ${entity.id}"
                                    )
                                    deferred.incrementAndGet()
                                    return@withPermit
                                } else if (isPermanent(error)) {
                                    // Never dead-letter into silence: the row
                                    // stays pending with its baseline intact,
                                    // the failure is logged, and the drain
                                    // stops so the scheduler retries with
                                    // backoff instead of the caption edit
                                    // being dropped forever.
                                    SafeLog.w(
                                        TAG,
                                        "Caption publish permanently rejected for " +
                                            "${entity.id} (message ${entity.messageId}); " +
                                            "keeping it pending for retry: $error"
                                    )
                                    aborted.set(true)
                                    return@withPermit
                                } else {
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
     */
    private suspend fun replayDeletes(isStopped: () -> Boolean): Boolean {
        while (true) {
            if (isStopped()) return false
            val batch = pendingDeleteDao.oldest(BATCH_SIZE)
            if (batch.isEmpty()) return true

            for ((chatId, pending) in batch.groupBy { it.chatId }) {
                val messageIds = pending.map { it.messageId }
                try {
                    telegramClient.deleteMessages(chatId, messageIds)
                } catch (e: TelegramException) {
                    if (e.code !in PERMANENT_CODES) {
                        SafeLog.w(TAG, "Replaying ${messageIds.size} deletes failed", e)
                        return false
                    }
                    SafeLog.w(TAG, "Dropping unrepeatable delete: ${e.message}")
                }
                fileDao.deleteByIds(pending.map { it.fileId })
                pendingDeleteDao.clear(chatId, messageIds)
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
