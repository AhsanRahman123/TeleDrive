package com.drdisagree.teledrive.testing

import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.TransferStage
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Proxy that throws on ANY method call, so a test fails if the code touches the dependency. */
inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java)
) { _, method, _ -> throw UnsupportedOperationException(method.name) } as T

/**
 * Faithful in-memory [TransferDao] fake for the vc179 archive-download tests,
 * replicating the exact SQL semantics of every statement (filters, ordering,
 * state writes, bulk-op ARCHIVE_DOWNLOAD exclusions) — the same semantics as
 * ArchiveDownloadSafetyTest's file-private fake, which is not visible outside
 * that file. Room cannot be built in android host tests (needs a Context).
 *
 * Unlike the safety fake, the observe* flows here are backed by a version
 * StateFlow over the store, so collectors see later mutations, not just a
 * snapshot taken when the flow was created.
 */
class FakeTransferDao : TransferDao {
    private val rows = LinkedHashMap<String, TransferEntity>()
    private val version = MutableStateFlow(0)

    private fun snapshot(): List<TransferEntity> = synchronized(rows) { rows.values.toList() }

    private fun mutate(block: () -> Unit) {
        synchronized(rows) { block() }
        version.value += 1
    }

    private fun replace(entity: TransferEntity) {
        rows[entity.id] = entity
    }

    private fun queueOrder(list: List<TransferEntity>) =
        list.sortedWith(compareByDescending<TransferEntity> { it.priority }.thenBy { it.createdAt })

    override suspend fun upsert(transfer: TransferEntity) = mutate { replace(transfer) }

    override suspend fun upsertAll(transfers: List<TransferEntity>) =
        mutate { transfers.forEach(::replace) }

    override suspend fun unfinishedFileIds(fileIds: List<String>): List<String> =
        snapshot().filter {
            it.fileId in fileIds && it.state in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)
        }.mapNotNull { it.fileId }

    override suspend fun adoptIntoSession(fileIds: List<String>, sessionId: String) = mutate {
        snapshot().filter {
            it.fileId in fileIds && it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }.forEach { replace(it.copy(backupSessionId = sessionId)) }
    }

    override suspend fun update(transfer: TransferEntity) = mutate { replace(transfer) }

    override suspend fun byId(id: String): TransferEntity? = snapshot().firstOrNull { it.id == id }

    override fun observeById(id: String): Flow<TransferEntity?> =
        version.map { byIdBlocking(id) }

    private fun byIdBlocking(id: String): TransferEntity? = synchronized(rows) { rows[id] }

    override fun observeActive(limit: Int): Flow<List<TransferEntity>> = version.map {
        queueOrder(snapshot().filter { it.state in setOf(TransferState.QUEUED, TransferState.RUNNING) }).take(limit)
    }

    override fun observePaused(limit: Int): Flow<List<TransferEntity>> = version.map {
        snapshot().filter { it.state == TransferState.PAUSED }.sortedBy { it.createdAt }.take(limit)
    }

    override fun observeFailed(limit: Int): Flow<List<TransferEntity>> = version.map {
        snapshot().filter { it.state == TransferState.FAILED }.sortedByDescending { it.updatedAt }.take(limit)
    }

    override fun observeCompleted(limit: Int): Flow<List<TransferEntity>> = version.map {
        snapshot().filter { it.state == TransferState.COMPLETED }
            .sortedByDescending { it.completedAt ?: it.updatedAt }.take(limit)
    }

    override fun observeCountByState(state: TransferState): Flow<Int> =
        version.map { snapshot().count { it.state == state } }

    override fun observeActiveForFile(fileId: String): Flow<TransferEntity?> = version.map {
        snapshot().filter {
            it.fileId == fileId && it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }.minByOrNull { it.createdAt }
    }

    override suspend fun unfinishedIdsForFiles(fileIds: List<String>): List<String> =
        snapshot().filter {
            it.fileId in fileIds &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)
        }.map { it.id }

    override suspend fun nextQueued(limit: Int): List<TransferEntity> =
        queueOrder(snapshot().filter { it.state == TransferState.QUEUED }).take(limit)

    override suspend fun nextQueuedExcept(type: TransferType, limit: Int): List<TransferEntity> =
        queueOrder(snapshot().filter { it.state == TransferState.QUEUED && it.type != type }).take(limit)

    override suspend fun nextQueuedOf(types: List<TransferType>, limit: Int): List<TransferEntity> =
        queueOrder(snapshot().filter { it.state == TransferState.QUEUED && it.type in types }).take(limit)

    override suspend fun runningIncomingTransfers(): List<TransferEntity> = queueOrder(
        snapshot().filter {
            it.type in setOf(TransferType.DOWNLOAD, TransferType.RESTORE) && it.state == TransferState.RUNNING
        }
    )

    override suspend fun runningTransferCount(): Int =
        snapshot().count { it.state == TransferState.RUNNING }

    override suspend fun incomingQueueOrder(): List<TransferEntity> = queueOrder(
        snapshot().filter {
            it.type in setOf(TransferType.DOWNLOAD, TransferType.RESTORE) &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)
        }
    )

    override suspend fun activeIncomingTransfersForPause(): List<TransferEntity> =
        snapshot().filter {
            it.type in setOf(TransferType.DOWNLOAD, TransferType.RESTORE) &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }

    override suspend fun hasQueued(type: TransferType): Boolean =
        snapshot().any { it.state == TransferState.QUEUED && it.type == type }

    override fun observeActiveCount(): Flow<Int> =
        version.map { snapshot().count { it.state in setOf(TransferState.QUEUED, TransferState.RUNNING) } }

    override fun observeActiveDownloads(): Flow<List<TransferEntity>> = version.map {
        snapshot().filter {
            it.type == TransferType.DOWNLOAD && it.fileId != null &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }
    }

    override suspend fun setLocalPath(id: String, localPath: String?) = mutate {
        rows[id]?.let { replace(it.copy(localPath = localPath)) }
    }

    override suspend fun setPriority(id: String, priority: Int, updatedAt: Long) = mutate {
        rows[id]?.let { replace(it.copy(priority = priority, updatedAt = updatedAt)) }
    }

    override suspend fun setStage(id: String, stage: TransferStage?, updatedAt: Long) = mutate {
        rows[id]?.let { replace(it.copy(stage = stage, updatedAt = updatedAt)) }
    }

    override suspend fun updateProgress(id: String, transferred: Long, speed: Long, updatedAt: Long) =
        mutate {
            rows[id]?.takeIf { it.state == TransferState.RUNNING }?.let {
                // Mirrors the real DAO's MAX(transferredBytes, :transferred) guard.
                replace(
                    it.copy(
                        transferredBytes = maxOf(it.transferredBytes, transferred),
                        speedBytesPerSecond = speed,
                        updatedAt = updatedAt
                    )
                )
            }
        }

    override suspend fun updateTransferredBytes(id: String, transferred: Long, updatedAt: Long) =
        mutate {
            rows[id]?.takeIf { it.state == TransferState.RUNNING }?.let {
                replace(it.copy(transferredBytes = transferred, updatedAt = updatedAt))
            }
        }

    override suspend fun setState(id: String, state: TransferState, updatedAt: Long) = mutate {
        rows[id]?.let {
            val speed = if (state in setOf(
                    TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED,
                    TransferState.CANCELLED, TransferState.FAILED
                )
            ) 0 else it.speedBytesPerSecond
            replace(it.copy(state = state, speedBytesPerSecond = speed, updatedAt = updatedAt))
        }
    }

    override suspend fun setFailed(id: String, state: TransferState, error: String?, updatedAt: Long) =
        mutate {
            rows[id]?.let {
                replace(it.copy(state = state, errorMessage = error, speedBytesPerSecond = 0, updatedAt = updatedAt))
            }
        }

    override suspend fun setCompleted(id: String, completedAt: Long, completed: TransferState) =
        mutate {
            rows[id]?.let {
                replace(
                    it.copy(
                        state = completed, transferredBytes = it.sizeBytes,
                        speedBytesPerSecond = 0, completedAt = completedAt, updatedAt = completedAt
                    )
                )
            }
        }

    override suspend fun requeueRunning() = mutate {
        snapshot().filter {
            it.state == TransferState.RUNNING && it.type != TransferType.ARCHIVE_DOWNLOAD
        }.forEach {
            replace(it.copy(state = TransferState.QUEUED, telegramFileId = null, speedBytesPerSecond = 0))
        }
    }

    override suspend fun failInterruptedArchiveDownloads(updatedAt: Long): Int {
        var count = 0
        mutate {
            snapshot().filter {
                it.state == TransferState.RUNNING && it.type == TransferType.ARCHIVE_DOWNLOAD
            }.forEach {
                count++
                replace(
                    it.copy(
                        state = TransferState.FAILED,
                        errorMessage = "Interrupted — the app stopped while this file was being extracted from its ZIP.",
                        speedBytesPerSecond = 0,
                        updatedAt = updatedAt
                    )
                )
            }
        }
        return count
    }

    override suspend fun deleteByIds(ids: List<String>) = mutate {
        ids.forEach { rows.remove(it) }
    }

    override suspend fun clearFinished() = mutate {
        snapshot().filter {
            it.state in setOf(TransferState.COMPLETED, TransferState.CANCELLED)
        }.map { it.id }.forEach { rows.remove(it) }
    }

    override suspend fun pauseAll(now: Long): Int {
        var count = 0
        mutate {
            snapshot().filter {
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING) &&
                    it.type != TransferType.ARCHIVE_DOWNLOAD
            }.forEach {
                count++
                replace(it.copy(state = TransferState.PAUSED, speedBytesPerSecond = 0, updatedAt = now))
            }
        }
        return count
    }

    override suspend fun resumeAll(now: Long): Int {
        var count = 0
        mutate {
            snapshot().filter {
                it.state == TransferState.PAUSED && it.type != TransferType.ARCHIVE_DOWNLOAD
            }.forEach {
                count++
                replace(it.copy(state = TransferState.QUEUED, speedBytesPerSecond = 0, updatedAt = now))
            }
        }
        return count
    }

    override suspend fun cancelAll(now: Long): Int {
        var count = 0
        mutate {
            snapshot().filter {
                it.state in setOf(
                    TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED, TransferState.FAILED
                ) && it.type != TransferType.ARCHIVE_DOWNLOAD
            }.forEach {
                count++
                replace(it.copy(state = TransferState.CANCELLED, speedBytesPerSecond = 0, updatedAt = now))
            }
        }
        return count
    }

    override suspend fun cancelAllArchiveDownloads(updatedAt: Long): Int {
        var count = 0
        mutate {
            snapshot().filter {
                it.type == TransferType.ARCHIVE_DOWNLOAD && it.state in setOf(
                    TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED, TransferState.FAILED
                )
            }.forEach {
                count++
                replace(it.copy(state = TransferState.CANCELLED, speedBytesPerSecond = 0, updatedAt = updatedAt))
            }
        }
        return count
    }

    override suspend fun bySession(sessionId: String): List<TransferEntity> =
        snapshot().filter { it.backupSessionId == sessionId }
}
