package com.drdisagree.teledrive.core.transfer

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.network.NetworkStatus
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
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
 * Android-CI copy pattern (see RemoteArchiveServiceTest): the shared host tests in
 * desktopTest do not run under `testDebugUnitTest`, so the fakes/helpers from
 * BackupHoldDrainTest are duplicated locally here. The database is a REAL Room
 * [TeleDriveDatabase] on a temp file via BundledSQLiteDriver — the shared module
 * exposes `androidx.sqlite:sqlite-bundled` as `api`, so it is on this classpath.
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

class ArchiveDownloadSafetyTest {

    private val dbFile = File.createTempFile("teledrive-archive-safety", ".db")
    private val database: TeleDriveDatabase =
        Room.databaseBuilder<TeleDriveDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    private val transfers = database.transferDao()
    private val resume = RecordingBackupResumeScheduler()

    @After
    fun tearDown() {
        database.close()
        dbFile.delete()
    }

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

    // ---- Harness (mirrors BackupHoldDrainTest) --------------------------------

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
            fileDao = database.fileDao(),
            backupDao = database.backupDao(),
            manifestCodec = codec,
            folderPathResolver = FolderPathResolver(
                database.folderDao(),
                ActiveChannel(settings),
                database.folderTombstoneDao()
            ),
            thumbnailStore = unused(),
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = unused(),
            downloadWriter = unused(),
            fileImporter = unused(),
            settingsRepository = settings,
            filePartDao = database.filePartDao(),
            partUploader = PartUploader(
                unused(), unused(), database.filePartDao(), codec, StreamCrypto(), unused(),
                unused(), unused<DispatcherProvider>(),
                ApkIconUploader(unused(), unused(), StreamCrypto(), unused(), unused())
            ),
            partDownloader = PartDownloader(
                unused(), database.filePartDao(), StreamCrypto(), unused(), unused()
            ),
            apkIconUploader = ApkIconUploader(unused(), unused(), StreamCrypto(), unused(), unused()),
            localCopyDeleter = unused()
        )
    }

    private fun drainer(): TransferQueueDrainer {
        val settings = FakeSettings(UserPreferences(transferRetryCount = 0))
        return TransferQueueDrainer(
            transferDao = transfers,
            fileDao = database.fileDao(),
            transferExecutor = executor(),
            backupSessionTracker = NoOpBackupSessionTracker(),
            settingsRepository = settings,
            networkMonitor = FakeNetworkMonitor(NetworkStatus.UNMETERED),
            powerMonitor = FakePowerMonitor(charging = true),
            backupResumeScheduler = resume
        )
    }
}
