package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.network.NetworkStatus
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.TransferStage
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.TransferRemainingWork
import com.drdisagree.teledrive.domain.model.TransferWorkTotals
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc179 engine-safety contract for the report-only [TransferType.ARCHIVE_DOWNLOAD] type
 * (report 45 §9 stop-rule answer):
 *
 *  1. The drainer never claims an ARCHIVE_DOWNLOAD row.
 *  2. An interrupted drain fails RUNNING archive rows instead of requeueing them,
 *     while ordinary RUNNING rows are requeued as before.
 *  3. The executor explicitly refuses ARCHIVE_DOWNLOAD without touching any dependency.
 *
 * DAO choice — faithful in-memory fake, NOT a real Room database: on the Android
 * target `Room.databaseBuilder` requires an `android.content.Context`, which host
 * unit tests cannot supply (stub android.jar, no Robolectric on this classpath);
 * the no-Context KMP builder used by desktopTest's BackupHoldDrainTest does not
 * exist for Android (CI proved it: "No value passed for parameter 'context'").
 * [FakeTransferDao] therefore replicates the exact SQL semantics of every
 * TransferDao statement these tests exercise (filters, ordering, state writes).
 * The helpers/fakes from BackupHoldDrainTest are duplicated locally for the same
 * reason RemoteArchiveServiceTest duplicates its suite: desktopTest code is not
 * visible to `testDebugUnitTest`.
 */
private inline fun <reified T : Any> unused(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java)
) { _, method, _ -> throw UnsupportedOperationException(method.name) } as T

private fun namedErrorMessages(): TransferErrorMessages = Proxy.newProxyInstance(
    TransferErrorMessages::class.java.classLoader,
    arrayOf(TransferErrorMessages::class.java)
) { _, method, _ -> method.name } as TransferErrorMessages

private class FakeSettings(initial: UserPreferences) : SettingsRepository by unused() {
    private val state = MutableStateFlow(initial)
    override val preferences: Flow<UserPreferences> = state

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        state.value = transform(state.value)
    }
}

private class FakeNetworkMonitor(initial: NetworkStatus) : NetworkMonitor {
    private val state = MutableStateFlow(initial)
    override val status: Flow<NetworkStatus> = state
    override fun currentStatus(): NetworkStatus = state.value
}

private class FakePowerMonitor(initial: Boolean) : PowerMonitor {
    private val state = MutableStateFlow(initial)
    override val charging: Flow<Boolean> = state
    override fun isCharging(): Boolean = state.value
}

private class NoOpBackupSessionTracker : BackupSessionTracker {
    override suspend fun refreshActive() = Unit
    override suspend fun refresh(sessionId: String?) = Unit
}

private class RecordingBackupResumeScheduler : BackupResumeScheduler {
    val requests = mutableListOf<Pair<Boolean, Boolean>>()

    override fun resumeWhen(chargingOnly: Boolean, wifiOnly: Boolean) {
        requests += chargingOnly to wifiOnly
    }
}

/** In-memory [TransferDao] replicating the SQL semantics of the real DAO. */
private class FakeTransferDao : TransferDao {
    private val rows = LinkedHashMap<String, TransferEntity>()

    private fun queueOrder(list: List<TransferEntity>) =
        list.sortedWith(compareByDescending<TransferEntity> { it.priority }.thenBy { it.createdAt })

    override suspend fun upsert(transfer: TransferEntity) {
        rows[transfer.id] = transfer
    }

    override suspend fun upsertAll(transfers: List<TransferEntity>) =
        transfers.forEach { rows[it.id] = it }

    override suspend fun unfinishedFileIds(fileIds: List<String>): List<String> =
        rows.values.filter {
            it.fileId in fileIds && it.state in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)
        }.mapNotNull { it.fileId }

    override suspend fun adoptIntoSession(fileIds: List<String>, sessionId: String) {
        rows.values.filter {
            it.fileId in fileIds && it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }.forEach { rows[it.id] = it.copy(backupSessionId = sessionId) }
    }

    override suspend fun update(transfer: TransferEntity) {
        rows[transfer.id] = transfer
    }

    override suspend fun byId(id: String): TransferEntity? = rows[id]

    override fun observeById(id: String): Flow<TransferEntity?> = flowOf(rows[id])

    override fun observeActive(limit: Int): Flow<List<TransferEntity>> = flowOf(
        queueOrder(rows.values.filter { it.state in setOf(TransferState.QUEUED, TransferState.RUNNING) }).take(limit)
    )

    override fun observePaused(limit: Int): Flow<List<TransferEntity>> =
        flowOf(rows.values.filter { it.state == TransferState.PAUSED }.sortedBy { it.createdAt }.take(limit))

    override fun observeFailed(limit: Int): Flow<List<TransferEntity>> =
        flowOf(rows.values.filter { it.state == TransferState.FAILED }.sortedByDescending { it.updatedAt }.take(limit))

    override fun observeCompleted(limit: Int): Flow<List<TransferEntity>> = flowOf(
        rows.values.filter { it.state == TransferState.COMPLETED }
            .sortedByDescending { it.completedAt ?: it.updatedAt }.take(limit)
    )

    override fun observeCountByState(state: TransferState): Flow<Int> =
        flowOf(rows.values.count { it.state == state })

    override fun observeActiveForFile(fileId: String): Flow<TransferEntity?> = flowOf(
        rows.values.filter {
            it.fileId == fileId && it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }.minByOrNull { it.createdAt }
    )

    override suspend fun unfinishedIdsForFiles(fileIds: List<String>): List<String> =
        rows.values.filter {
            it.fileId in fileIds &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)
        }.map { it.id }

    override suspend fun nextQueued(limit: Int): List<TransferEntity> =
        queueOrder(rows.values.filter { it.state == TransferState.QUEUED }).take(limit)

    override suspend fun nextQueuedExcept(type: TransferType, limit: Int): List<TransferEntity> =
        queueOrder(rows.values.filter { it.state == TransferState.QUEUED && it.type != type }).take(limit)

    override suspend fun nextQueuedOf(types: List<TransferType>, limit: Int): List<TransferEntity> =
        queueOrder(rows.values.filter { it.state == TransferState.QUEUED && it.type in types }).take(limit)

    override suspend fun runningIncomingTransfers(): List<TransferEntity> = queueOrder(
        rows.values.filter {
            it.type in setOf(TransferType.DOWNLOAD, TransferType.RESTORE) && it.state == TransferState.RUNNING
        }
    )

    override suspend fun runningTransferCount(): Int =
        rows.values.count { it.state == TransferState.RUNNING }

    override suspend fun incomingQueueOrder(): List<TransferEntity> = queueOrder(
        rows.values.filter {
            it.type in setOf(TransferType.DOWNLOAD, TransferType.RESTORE) &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED)
        }
    )

    override suspend fun activeIncomingTransfersForPause(): List<TransferEntity> =
        rows.values.filter {
            it.type in setOf(TransferType.DOWNLOAD, TransferType.RESTORE) &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }

    override suspend fun hasQueued(type: TransferType): Boolean =
        rows.values.any { it.state == TransferState.QUEUED && it.type == type }

    override fun observeActiveCount(): Flow<Int> =
        flowOf(rows.values.count { it.state in setOf(TransferState.QUEUED, TransferState.RUNNING) })

    override fun observeCompletedTotals(types: List<TransferType>): Flow<TransferWorkTotals> {
        val done = rows.values.filter {
            it.state == TransferState.COMPLETED && it.type in types
        }
        return flowOf(TransferWorkTotals(done.size.toLong(), done.sumOf { it.sizeBytes }))
    }

    override fun observeRemainingWork(types: List<TransferType>): Flow<TransferRemainingWork> {
        val left = rows.values.filter {
            it.state in setOf(TransferState.QUEUED, TransferState.RUNNING) && it.type in types
        }
        return flowOf(
            TransferRemainingWork(
                left.size.toLong(),
                left.sumOf { it.sizeBytes - it.transferredBytes },
                left.count { it.state == TransferState.RUNNING }.toLong()
            )
        )
    }

    override fun observeActiveDownloads(): Flow<List<TransferEntity>> = flowOf(
        rows.values.filter {
            it.type == TransferType.DOWNLOAD && it.fileId != null &&
                it.state in setOf(TransferState.QUEUED, TransferState.RUNNING)
        }
    )

    override suspend fun setLocalPath(id: String, localPath: String?) {
        rows[id]?.let { rows[id] = it.copy(localPath = localPath) }
    }

    override suspend fun setPriority(id: String, priority: Int, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(priority = priority, updatedAt = updatedAt) }
    }

    override suspend fun setStage(id: String, stage: TransferStage?, updatedAt: Long) {
        rows[id]?.let { rows[id] = it.copy(stage = stage, updatedAt = updatedAt) }
    }

    override suspend fun updateProgress(id: String, transferred: Long, speed: Long, updatedAt: Long) {
        rows[id]?.takeIf { it.state == TransferState.RUNNING }?.let {
            rows[id] = it.copy(transferredBytes = transferred, speedBytesPerSecond = speed, updatedAt = updatedAt)
        }
    }

    override suspend fun updateTransferredBytes(id: String, transferred: Long, updatedAt: Long) {
        rows[id]?.takeIf { it.state == TransferState.RUNNING }?.let {
            rows[id] = it.copy(transferredBytes = transferred, updatedAt = updatedAt)
        }
    }

    override suspend fun setState(id: String, state: TransferState, updatedAt: Long) {
        rows[id]?.let {
            val speed = if (state in setOf(
                    TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED,
                    TransferState.CANCELLED, TransferState.FAILED
                )
            ) 0 else it.speedBytesPerSecond
            rows[id] = it.copy(state = state, speedBytesPerSecond = speed, updatedAt = updatedAt)
        }
    }

    override suspend fun setFailed(id: String, state: TransferState, error: String?, updatedAt: Long) {
        rows[id]?.let {
            rows[id] = it.copy(state = state, errorMessage = error, speedBytesPerSecond = 0, updatedAt = updatedAt)
        }
    }

    override suspend fun setCompleted(id: String, completedAt: Long, completed: TransferState) {
        rows[id]?.let {
            rows[id] = it.copy(
                state = completed, transferredBytes = it.sizeBytes,
                speedBytesPerSecond = 0, completedAt = completedAt, updatedAt = completedAt
            )
        }
    }

    override suspend fun requeueRunning() {
        rows.values.filter {
            it.state == TransferState.RUNNING && it.type != TransferType.ARCHIVE_DOWNLOAD
        }.forEach {
            rows[it.id] = it.copy(state = TransferState.QUEUED, telegramFileId = null, speedBytesPerSecond = 0)
        }
    }

    override suspend fun failInterruptedArchiveDownloads(updatedAt: Long): Int {
        val targets = rows.values.filter {
            it.state == TransferState.RUNNING && it.type == TransferType.ARCHIVE_DOWNLOAD
        }
        targets.forEach {
            rows[it.id] = it.copy(
                state = TransferState.FAILED,
                errorMessage = "Interrupted — the app stopped while this file was being extracted from its ZIP.",
                speedBytesPerSecond = 0,
                updatedAt = updatedAt
            )
        }
        return targets.size
    }

    override suspend fun deleteByIds(ids: List<String>) {
        ids.forEach { rows.remove(it) }
    }

    override suspend fun clearFinished() {
        rows.values.filter {
            it.state in setOf(TransferState.COMPLETED, TransferState.CANCELLED)
        }.map { it.id }.forEach { rows.remove(it) }
    }

    override suspend fun pauseAll(now: Long): Int {
        val targets = rows.values.filter {
            it.state in setOf(TransferState.QUEUED, TransferState.RUNNING) &&
                it.type != TransferType.ARCHIVE_DOWNLOAD
        }
        targets.forEach {
            rows[it.id] = it.copy(state = TransferState.PAUSED, speedBytesPerSecond = 0, updatedAt = now)
        }
        return targets.size
    }

    override suspend fun resumeAll(now: Long): Int {
        val targets = rows.values.filter {
            it.state == TransferState.PAUSED && it.type != TransferType.ARCHIVE_DOWNLOAD
        }
        targets.forEach {
            rows[it.id] = it.copy(state = TransferState.QUEUED, speedBytesPerSecond = 0, updatedAt = now)
        }
        return targets.size
    }

    override suspend fun cancelAll(now: Long): Int {
        val targets = rows.values.filter {
            it.state in setOf(
                TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED, TransferState.FAILED
            ) && it.type != TransferType.ARCHIVE_DOWNLOAD
        }
        targets.forEach {
            rows[it.id] = it.copy(state = TransferState.CANCELLED, speedBytesPerSecond = 0, updatedAt = now)
        }
        return targets.size
    }

    override suspend fun cancelAllArchiveDownloads(updatedAt: Long): Int {
        val targets = rows.values.filter {
            it.type == TransferType.ARCHIVE_DOWNLOAD && it.state in setOf(
                TransferState.QUEUED, TransferState.RUNNING, TransferState.PAUSED, TransferState.FAILED
            )
        }
        targets.forEach {
            rows[it.id] = it.copy(state = TransferState.CANCELLED, speedBytesPerSecond = 0, updatedAt = updatedAt)
        }
        return targets.size
    }

    override suspend fun bySession(sessionId: String): List<TransferEntity> =
        rows.values.filter { it.backupSessionId == sessionId }
}

class ArchiveDownloadSafetyTest {

    private val transfers = FakeTransferDao()
    private val resume = RecordingBackupResumeScheduler()

    // ---- Clause 1a: archive-only queue is never claimed ---------------------

    @Test
    fun `C1a only a queued archive download - drain completes, row never claimed, no runnable work`() =
        runBlocking {
            queue("archive", TransferType.ARCHIVE_DOWNLOAD)
            val drainer = drainer()

            assertFalse(drainer.hasRunnableWork())

            val result = drainer.drain(isStopped = { false }, onTerminalFailure = {})

            assertEquals(TransferDrainResult.COMPLETED, result)
            val row = transfers.byId("archive")!!
            // Never claimed: a claim would have set RUNNING and the executor's
            // refusal (or an unused() dependency) would have ended it FAILED.
            assertEquals(TransferState.QUEUED, row.state)
            assertEquals(null, row.errorMessage)
            assertFalse(drainer.hasRunnableWork())
        }

    // ---- Clause 1b: a real download alongside is still claimed --------------

    @Test
    fun `C1b archive plus download - download is claimed and executed, archive stays queued`() =
        runBlocking {
            queue("archive", TransferType.ARCHIVE_DOWNLOAD)
            queue("download", TransferType.DOWNLOAD)
            val drainer = drainer()

            assertTrue(drainer.hasRunnableWork())
            drainer.drain(isStopped = { false }, onTerminalFailure = {})

            // The download row has fileId = null, so the executor fails it with
            // noFileReference — FAILED here proves it WAS claimed and executed.
            assertEquals(TransferState.FAILED, transfers.byId("download")!!.state)
            val archive = transfers.byId("archive")!!
            assertEquals(TransferState.QUEUED, archive.state)
            assertEquals(null, archive.errorMessage)
        }

    // ---- Clause 2: interrupted drain ----------------------------------------

    @Test
    fun `C2 interrupted drain - running archive fails with interrupted message, running download requeues`() =
        runBlocking {
            queue("archive", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
            queue("download", TransferType.DOWNLOAD, TransferState.RUNNING)
            val drainer = drainer()

            val result = drainer.drain(isStopped = { true }, onTerminalFailure = {})

            assertEquals(TransferDrainResult.INTERRUPTED, result)
            val archive = transfers.byId("archive")!!
            assertEquals(TransferState.FAILED, archive.state)
            assertEquals(
                "Interrupted — the app stopped while this file was being extracted from its ZIP.",
                archive.errorMessage
            )
            assertEquals(0L, archive.speedBytesPerSecond)
            assertEquals(TransferState.QUEUED, transfers.byId("download")!!.state)
        }

    // ---- Clause 3: executor refusal ------------------------------------------

    @Test
    fun `C3 executor refuses archive downloads without touching any dependency`() = runBlocking {
        // Every telegram/storage dependency is unused(): any touch throws
        // UnsupportedOperationException, which execute() does not swallow into a
        // Failed outcome with the refusal message — so passing proves the refusal
        // branch returns before any dependency is used.
        val executor = executor()
        val entity = row("archive", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)

        val outcome = executor.execute(entity)

        assertTrue(outcome is TransferOutcome.Failed)
        assertEquals(
            "Archive downloads are handled by the archive viewer and are never run by the transfer queue.",
            (outcome as TransferOutcome.Failed).message
        )
    }

    // ---- DAO bulk-operation exclusions (contract §2) -------------------------

    @Test
    fun `bulk pause resume cancel all skip archive rows, cancelAllArchiveDownloads targets only them`() =
        runBlocking {
            queue("archive-queued", TransferType.ARCHIVE_DOWNLOAD, TransferState.QUEUED)
            queue("archive-paused", TransferType.ARCHIVE_DOWNLOAD, TransferState.PAUSED)
            queue("download-queued", TransferType.DOWNLOAD, TransferState.QUEUED)
            queue("download-paused", TransferType.DOWNLOAD, TransferState.PAUSED)

            transfers.pauseAll(now = 10)
            assertEquals(TransferState.QUEUED, transfers.byId("archive-queued")!!.state)
            assertEquals(TransferState.PAUSED, transfers.byId("download-queued")!!.state)

            transfers.resumeAll(now = 11)
            assertEquals(TransferState.PAUSED, transfers.byId("archive-paused")!!.state)
            assertEquals(TransferState.QUEUED, transfers.byId("download-paused")!!.state)

            transfers.cancelAll(now = 12)
            assertEquals(TransferState.QUEUED, transfers.byId("archive-queued")!!.state)
            assertEquals(TransferState.PAUSED, transfers.byId("archive-paused")!!.state)
            assertEquals(TransferState.CANCELLED, transfers.byId("download-queued")!!.state)

            val cancelled = transfers.cancelAllArchiveDownloads(updatedAt = 13)
            assertEquals(2, cancelled)
            assertEquals(TransferState.CANCELLED, transfers.byId("archive-queued")!!.state)
            assertEquals(TransferState.CANCELLED, transfers.byId("archive-paused")!!.state)
        }

    @Test
    fun `requeueRunning skips archive rows and failInterrupted only fails running archive rows`() =
        runBlocking {
            queue("archive-running", TransferType.ARCHIVE_DOWNLOAD, TransferState.RUNNING)
            queue("archive-queued", TransferType.ARCHIVE_DOWNLOAD, TransferState.QUEUED)
            queue("download-running", TransferType.DOWNLOAD, TransferState.RUNNING)

            transfers.requeueRunning()
            assertEquals(TransferState.RUNNING, transfers.byId("archive-running")!!.state)
            assertEquals(TransferState.QUEUED, transfers.byId("download-running")!!.state)

            val failed = transfers.failInterruptedArchiveDownloads(updatedAt = 20)
            assertEquals(1, failed)
            assertEquals(TransferState.FAILED, transfers.byId("archive-running")!!.state)
            assertEquals(TransferState.QUEUED, transfers.byId("archive-queued")!!.state)
        }

    @Test
    fun `archive download is report only and not incoming, other types unchanged`() {
        assertTrue(TransferType.ARCHIVE_DOWNLOAD.isReportOnly)
        assertFalse(TransferType.ARCHIVE_DOWNLOAD.isIncoming)
        for (type in TransferType.entries) {
            if (type != TransferType.ARCHIVE_DOWNLOAD) assertFalse(type.isReportOnly)
        }
        assertTrue(TransferType.DOWNLOAD.isIncoming)
        assertTrue(TransferType.RESTORE.isIncoming)
        assertFalse(TransferType.UPLOAD.isIncoming)
        assertFalse(TransferType.BACKUP.isIncoming)
    }

    // ---- Harness (mirrors BackupHoldDrainTest, with the fake DAO above) -------

    private fun row(id: String, type: TransferType, state: TransferState) = TransferEntity(
        id = id,
        type = type,
        fileId = null,
        displayName = id,
        localPath = null,
        chatId = null,
        messageId = null,
        remoteFileId = null,
        sizeBytes = 1,
        state = state,
        createdAt = 1,
        updatedAt = 1
    )

    private suspend fun queue(
        id: String,
        type: TransferType,
        state: TransferState = TransferState.QUEUED
    ) {
        transfers.upsert(row(id, type, state))
    }

    private fun executor(): TransferExecutor {
        val settings = FakeSettings(UserPreferences(transferRetryCount = 0))
        val codec = ManifestCodec(StreamCrypto(), unused())
        return TransferExecutor(
            messages = namedErrorMessages(),
            storagePaths = unused(),
            telegramClient = unused(),
            transferDao = transfers,
            fileDao = unused(),
            fileRepository = unused(),
            backupDao = unused(),
            manifestCodec = codec,
            folderPathResolver = FolderPathResolver(
                unused(),
                ActiveChannel(settings),
                unused()
            ),
            thumbnailStore = unused(),
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = unused(),
            downloadWriter = unused(),
            fileImporter = unused(),
            settingsRepository = settings,
            filePartDao = unused(),
            partUploader = PartUploader(
                unused(), unused(), unused(), codec, StreamCrypto(), unused(),
                unused(), unused<DispatcherProvider>(),
                ApkIconUploader(unused(), unused(), StreamCrypto(), unused(), unused())
            ),
            partDownloader = PartDownloader(
                unused(), unused(), StreamCrypto(), unused(), unused()
            ),
            apkIconUploader = ApkIconUploader(unused(), unused(), StreamCrypto(), unused(), unused()),
            localCopyDeleter = unused()
        )
    }

    private fun drainer(): TransferQueueDrainer {
        val settings = FakeSettings(UserPreferences(transferRetryCount = 0))
        return TransferQueueDrainer(
            transferDao = transfers,
            fileDao = unused(),
            transferExecutor = executor(),
            backupSessionTracker = NoOpBackupSessionTracker(),
            settingsRepository = settings,
            networkMonitor = FakeNetworkMonitor(NetworkStatus.UNMETERED),
            powerMonitor = FakePowerMonitor(true),
            backupResumeScheduler = resume
        )
    }
}
