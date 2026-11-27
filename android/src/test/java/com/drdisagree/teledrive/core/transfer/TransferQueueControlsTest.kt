package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.network.NetworkMonitor
import com.drdisagree.teledrive.core.network.NetworkStatus
import com.drdisagree.teledrive.core.power.PowerMonitor
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.data.repository.TransferRepositoryImpl
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.FakeTransferDao
import com.drdisagree.teledrive.testing.unused
import java.io.File
import java.io.OutputStream
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 A2 — queue-level regression tests for the Transfers controls on
 * REGULAR downloads, exercising the production route end to end:
 * [TransferRepositoryImpl] bulk/per-row controls → [TransferQueueDrainer] →
 * [TransferExecutor] → DAO. The only fakes are the seams Android itself
 * injects: the DAO (faithful in-memory [FakeTransferDao] — Room needs a
 * Context host tests cannot supply, the established pattern in this source
 * set), Telegram (scripted), and the WorkManager plumbing, mirrored by
 * [WorkerScheduler] line-for-line from WorkTransferScheduler's policies
 * (kick = start a drain unless one is running or the pause hold is set;
 * rekick/resume = REPLACE: cancel the running drain, start a new one;
 * pause = set the drainer hold + stop the worker).
 *
 * The four field failures from Pranto's vc183 recording, one test each:
 *  (a) displayed speed was fabricated from TDLib's replayed per-file counter
 *      (23–37 MB/s shown while ~100 B/s moved) and progress jumped/regressed;
 *  (b) Cancel all left queued downloads running inside TDLib's persistent
 *      download list — only the Room rows were flipped;
 *  (c) Pause all held nothing: the scheduler's pause() was a no-op, so the
 *      next kick restarted the queue and new downloads started;
 *  (d) Resume replaced the WorkManager worker, the cancelled drain stranded
 *      its RUNNING rows forever, and in-flight work never restarted.
 */
class TransferQueueControlsTest {

    private val workDir: File = Files.createTempDirectory("queue-controls").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
        workDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- (a)

    @Test
    fun `download speed excludes the attach replay and progress never regresses`() = runBlocking {
        val h = Harness(concurrency = 1)
        h.addFile("f1", size = 40_000_000)
        h.addRow("dl", "f1", TransferState.QUEUED, createdAt = 1)
        val collections = AtomicInteger(0)
        h.telegram.script = { _ ->
            if (collections.incrementAndGet() == 1) {
                flow {
                    // Attach: TDLib first reports its pre-download state, then
                    // replays the counter for bytes already on disk (fetched by
                    // an earlier execution / a preview / its own list) — a
                    // 30 MB jump inside 150 ms that moved no network bytes for
                    // this execution. Genuine movement afterwards: 0.5 MB/s.
                    emit(TelegramDownloadEvent.Progress(0, 40_000_000))
                    delay(150)
                    emit(TelegramDownloadEvent.Progress(30_000_000, 40_000_000))
                    var bytes = 30_000_000L
                    repeat(16) {
                        delay(200)
                        bytes += 100_000
                        emit(TelegramDownloadEvent.Progress(bytes, 40_000_000))
                    }
                    emit(TelegramDownloadEvent.Completed(h.payloadFile().absolutePath, 1000))
                }
            } else {
                flow { delay(60_000) }
            }
        }
        val speeds = CopyOnWriteArrayList<Long>()
        val byteSamples = CopyOnWriteArrayList<Long>()
        h.scheduler.kick(true)
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            val row = h.dao.byId("dl")!!
            speeds += row.speedBytesPerSecond
            byteSamples += row.transferredBytes
            if (row.state == TransferState.COMPLETED) break
            delay(25)
        }
        assertEquals(TransferState.COMPLETED, h.dao.byId("dl")!!.state)
        assertTrue(
            "stored speeds must stay honest (max was ${speeds.maxOrNull()} B/s)",
            speeds.all { it < 2_000_000 }
        )
        assertTrue(
            "transferredBytes regressed: $byteSamples",
            byteSamples.zipWithNext().all { (a, b) -> b >= a }
        )
        Unit
    }

    @Test
    fun `reattaching after pause does not drag progress backwards when the counter restarted`() =
        runBlocking {
            val h = Harness(concurrency = 1)
            h.addFile("f1", size = 40_000_000)
            h.addRow("dl", "f1", TransferState.QUEUED, createdAt = 1)
            val collections = AtomicInteger(0)
            h.telegram.script = { _ ->
                if (collections.incrementAndGet() == 1) {
                    flow {
                        emit(TelegramDownloadEvent.Progress(30_000_000, 40_000_000))
                        delay(60_000)
                    }
                } else {
                    // Second execution: TDLib re-added the file and its counter
                    // restarted near zero.
                    flow {
                        emit(TelegramDownloadEvent.Progress(2_000_000, 40_000_000))
                        delay(60_000)
                    }
                }
            }
            h.scheduler.kick(true)
            awaitTrue { h.dao.byId("dl")!!.transferredBytes >= 30_000_000 }
            h.repository.pause("dl")
            awaitTrue { h.dao.byId("dl")!!.state == TransferState.PAUSED }
            h.repository.resume("dl")
            awaitTrue { collections.get() >= 2 }
            // Give the second execution's first progress write time to land.
            delay(750)
            assertTrue(
                "progress regressed to ${h.dao.byId("dl")!!.transferredBytes}",
                h.dao.byId("dl")!!.transferredBytes >= 30_000_000
            )
            Unit
        }

    // ---------------------------------------------------------------- (b)

    @Test
    fun `cancel all reaches queued running and paused downloads including the native queue`() =
        runBlocking {
            val h = Harness(concurrency = 1)
            h.addFile("f1", size = 10_000); h.addRow("run", "f1", TransferState.QUEUED, createdAt = 1)
            h.addFile("f2", size = 10_000); h.addRow("queued", "f2", TransferState.QUEUED, createdAt = 2)
            h.addFile("f3", size = 10_000); h.addRow("paused", "f3", TransferState.PAUSED, createdAt = 3)
            h.telegram.script = { remote -> h.hangingFlow(remote) }

            h.scheduler.kick(true)
            awaitTrue { h.dao.byId("run")!!.state == TransferState.RUNNING }
            awaitTrue { h.telegram.started.contains("r-f1") }

            h.repository.cancelAll()

            assertEquals(TransferState.CANCELLED, h.dao.byId("run")!!.state)
            assertEquals(TransferState.CANCELLED, h.dao.byId("queued")!!.state)
            assertEquals(TransferState.CANCELLED, h.dao.byId("paused")!!.state)
            // The running executor actually stops collecting.
            awaitTrue { h.telegram.stopped.contains("r-f1") }
            // The queued row was never started, before or after the cancel.
            assertFalse(h.telegram.started.contains("r-f2"))
            // Cancel reached TDLib's persistent list for ALL THREE rows — the
            // queued and paused rows have no running executor to remove theirs.
            awaitTrue { h.telegram.removed.containsAll(listOf("r-f1", "r-f2", "r-f3")) }
            // The drain ends instead of claiming anything new.
            awaitTrue { h.scheduler.job?.isActive != true }
            assertFalse(h.telegram.started.contains("r-f2"))
            Unit
        }

    @Test
    fun `cancelling a queued download removes its native queue entry`() = runBlocking {
        val h = Harness(concurrency = 1)
        h.addFile("f1", size = 10_000)
        h.addRow("queued", "f1", TransferState.QUEUED, createdAt = 1)

        h.repository.cancel("queued")

        assertEquals(TransferState.CANCELLED, h.dao.byId("queued")!!.state)
        assertTrue(h.telegram.removed.contains("r-f1"))
        Unit
    }

    // ---------------------------------------------------------------- (c)

    @Test
    fun `pause all holds the whole queue until resume all`() = runBlocking {
        val h = Harness(concurrency = 1)
        h.addFile("f1", size = 10_000); h.addRow("first", "f1", TransferState.QUEUED, createdAt = 1)
        h.addFile("f2", size = 10_000); h.addRow("second", "f2", TransferState.QUEUED, createdAt = 2)
        h.telegram.script = { remote -> h.hangingFlow(remote) }

        h.scheduler.kick(true)
        awaitTrue { h.dao.byId("first")!!.state == TransferState.RUNNING }

        h.repository.pauseAll()

        assertEquals(TransferState.PAUSED, h.dao.byId("first")!!.state)
        assertEquals(TransferState.PAUSED, h.dao.byId("second")!!.state)
        awaitTrue { h.telegram.stopped.contains("r-f1") }
        assertTrue(h.drainer.isPauseHeld())

        // A download enqueued while the hold is on must wait with the rest of
        // the queue, even when production kick paths fire (enqueue kicks, and
        // wakeWorker rekicks).
        h.addFile("f3", size = 10_000)
        val enqueued = h.repository.enqueueDownload("f3")
        assertTrue(enqueued is AppResult.Success)
        h.repository.wakeWorker()
        h.scheduler.kick(true)
        delay(1_500)
        assertFalse("second download started during pause hold", h.telegram.started.contains("r-f2"))
        assertFalse("new download started during pause hold", h.telegram.started.contains("r-f3"))
        assertEquals(TransferState.PAUSED, h.dao.byId("second")!!.state)

        // Resume all releases the hold and the whole queue drains.
        h.telegram.completing = true
        h.repository.resumeAll()
        assertFalse(h.drainer.isPauseHeld())
        awaitTrue(20_000) { h.dao.byId("first")!!.state == TransferState.COMPLETED }
        awaitTrue(20_000) { h.dao.byId("second")!!.state == TransferState.COMPLETED }
        awaitTrue(20_000) {
            h.dao.byId((enqueued as AppResult.Success).value)!!.state == TransferState.COMPLETED
        }
        Unit
    }

    // ------------------------------------------------------- vc186 A2

    /**
     * The vc184 field failure, at the seam the vc184 tests could not see:
     * production pause/cancel must reach TDLib through the SOURCE-AWARE
     * client calls, carrying the chat/message identity the download was
     * added under. The Android client adds managed downloads under the
     * message-attached file object; resolving the native entry from the
     * remote id alone can land on a different TDLib file that owns no
     * download-list entry, so a pause/remove addressed that way silently
     * leaves the real entry downloading. These tests pin the repository +
     * executor side of that contract: every bulk/per-row native call goes
     * out source-aware, with the row's own source ids (harness files all
     * live at chat 1, message 10).
     */
    @Test
    fun `pause all pauses native downloads through their source message identity`() =
        runBlocking {
            val h = Harness(concurrency = 1)
            h.addFile("f1", size = 10_000); h.addRow("run", "f1", TransferState.QUEUED, createdAt = 1)
            h.addFile("f2", size = 10_000); h.addRow("queued", "f2", TransferState.QUEUED, createdAt = 2)
            h.telegram.script = { remote -> h.hangingFlow(remote) }

            h.scheduler.kick(true)
            awaitTrue { h.dao.byId("run")!!.state == TransferState.RUNNING }

            h.repository.pauseAll()

            assertEquals(TransferState.PAUSED, h.dao.byId("run")!!.state)
            assertEquals(TransferState.PAUSED, h.dao.byId("queued")!!.state)
            // Both rows' native entries were paused via the source-aware
            // call — the running one AND the queued one (a queued row can
            // already sit in TDLib's persistent list).
            awaitTrue {
                h.telegram.pausedWithSource.containsAll(
                    listOf(Triple("r-f1", 1L, 10L), Triple("r-f2", 1L, 10L))
                )
            }
            Unit
        }

    @Test
    fun `cancel all removes native entries for failed rows whose downloads live only in tdlib`() =
        runBlocking {
            val h = Harness(concurrency = 1)
            h.addFile("f1", size = 10_000); h.addRow("run", "f1", TransferState.QUEUED, createdAt = 1)
            // A FAILED row: its execution already ended, but a native entry
            // can still be downloading inside TDLib (e.g. the stall timeout
            // fired while TDLib kept pulling bytes). It is in neither the
            // active queue nor any running executor — Cancel all must still
            // remove its native entry.
            h.addFile("f2", size = 10_000); h.addRow("failed", "f2", TransferState.FAILED, createdAt = 2)
            h.telegram.script = { remote -> h.hangingFlow(remote) }

            h.scheduler.kick(true)
            awaitTrue { h.dao.byId("run")!!.state == TransferState.RUNNING }

            h.repository.cancelAll()

            assertEquals(TransferState.CANCELLED, h.dao.byId("run")!!.state)
            assertEquals(TransferState.CANCELLED, h.dao.byId("failed")!!.state)
            awaitTrue {
                h.telegram.removedWithSource.containsAll(
                    listOf(Triple("r-f1", 1L, 10L), Triple("r-f2", 1L, 10L))
                )
            }
            Unit
        }

    @Test
    fun `pausing a running download reconciles its native entry through the source identity`() =
        runBlocking {
            val h = Harness(concurrency = 1)
            h.addFile("f1", size = 10_000); h.addRow("run", "f1", TransferState.QUEUED, createdAt = 1)
            h.telegram.script = { remote -> h.hangingFlow(remote) }

            h.scheduler.kick(true)
            awaitTrue { h.dao.byId("run")!!.state == TransferState.RUNNING }
            awaitTrue { h.telegram.started.contains("r-f1") }

            h.repository.pause("run")

            assertEquals(TransferState.PAUSED, h.dao.byId("run")!!.state)
            // The repository's pause and the stopped executor's reconcile
            // both address the native entry source-aware.
            awaitTrue { h.telegram.pausedWithSource.contains(Triple("r-f1", 1L, 10L)) }
            awaitTrue { h.telegram.stopped.contains("r-f1") }
            Unit
        }

    @Test
    fun `a completed download is removed from the native list through the source identity`() =
        runBlocking {
            val h = Harness(concurrency = 1)
            h.addFile("f1", size = 10_000); h.addRow("dl", "f1", TransferState.QUEUED, createdAt = 1)
            h.telegram.script = { _ ->
                flow {
                    emit(TelegramDownloadEvent.Progress(5_000, 10_000))
                    emit(TelegramDownloadEvent.Completed(h.payloadFile().absolutePath, 1000))
                }
            }

            h.scheduler.kick(true)

            awaitTrue { h.dao.byId("dl")!!.state == TransferState.COMPLETED }
            awaitTrue { h.telegram.removedWithSource.contains(Triple("r-f1", 1L, 10L)) }
            Unit
        }

    // ------------------------------------------------------- MF-1 (A3 x A2)

    @Test
    fun `enqueue onto a paused row during pause all keeps the hold and drains only on resume all`() =
        runBlocking {
            val h = Harness(concurrency = 1)
            h.addFile("f1", size = 10_000); h.addRow("first", "f1", TransferState.QUEUED, createdAt = 1)
            h.addFile("f2", size = 10_000); h.addRow("second", "f2", TransferState.QUEUED, createdAt = 2)
            h.telegram.script = { remote -> h.hangingFlow(remote) }

            h.scheduler.kick(true)
            awaitTrue { h.dao.byId("first")!!.state == TransferState.RUNNING }

            h.repository.pauseAll()
            assertTrue(h.drainer.isPauseHeld())
            assertEquals(TransferState.PAUSED, h.dao.byId("first")!!.state)
            assertEquals(TransferState.PAUSED, h.dao.byId("second")!!.state)

            // Another download enqueued during the hold: a fresh QUEUED row
            // that must wait with the rest of the queue.
            h.addFile("f4", size = 10_000)
            val fresh = h.repository.enqueueDownload("f4")
            assertTrue(fresh is AppResult.Success)
            val freshId = (fresh as AppResult.Success).value
            assertEquals(TransferState.QUEUED, h.dao.byId(freshId)!!.state)

            // The A3 dedupe repair, on the PAUSED "second" row, during the
            // hold: before MF-1 this called resume(), whose scheduler resume
            // cleared the global hold and restarted the whole queue.
            val repaired = h.repository.enqueueDownload("f2")
            assertEquals(AppResult.Success("second"), repaired)

            // The target row is re-queued (the A3 repair still happened)...
            assertEquals(TransferState.QUEUED, h.dao.byId("second")!!.state)
            // ...but the hold is still set, and nothing starts: not the
            // target row, and not the other QUEUED row.
            assertTrue("pause hold must survive the enqueue repair", h.drainer.isPauseHeld())
            delay(1_500)
            assertTrue("pause hold must still be set after a wait", h.drainer.isPauseHeld())
            assertFalse("target row started during pause hold", h.telegram.started.contains("r-f2"))
            assertFalse("other queued row started during pause hold", h.telegram.started.contains("r-f4"))
            assertEquals(TransferState.QUEUED, h.dao.byId("second")!!.state)
            assertEquals(TransferState.QUEUED, h.dao.byId(freshId)!!.state)

            // Resume all releases the hold and everything drains.
            h.telegram.completing = true
            h.repository.resumeAll()
            assertFalse(h.drainer.isPauseHeld())
            awaitTrue(20_000) { h.dao.byId("first")!!.state == TransferState.COMPLETED }
            awaitTrue(20_000) { h.dao.byId("second")!!.state == TransferState.COMPLETED }
            awaitTrue(20_000) { h.dao.byId(freshId)!!.state == TransferState.COMPLETED }
            Unit
        }

    // ---------------------------------------------------------------- (d)

    @Test
    fun `replacing the worker mid-run requeues its running row and the next drain restarts it`() =
        runBlocking {
            val h = Harness(concurrency = 2)
            h.addFile("f1", size = 10_000); h.addRow("first", "f1", TransferState.QUEUED, createdAt = 1)
            h.addFile("f2", size = 10_000); h.addRow("second", "f2", TransferState.QUEUED, createdAt = 2)
            h.telegram.script = { remote -> h.hangingFlow(remote) }

            h.scheduler.kick(true)
            awaitTrue { h.dao.byId("first")!!.state == TransferState.RUNNING }
            awaitTrue { h.telegram.started.count { it == "r-f1" } == 1 }

            // What every resume/rekick does on Android: WorkManager REPLACE
            // cancels the running worker and starts a new one.
            h.scheduler.resume(true)

            // The cancelled drain must not strand "first" in RUNNING: its
            // recovery requeues the row and the replacement drain claims it
            // again (a second collection of the same file).
            awaitTrue(15_000) { h.telegram.started.count { it == "r-f1" } == 2 }
            assertEquals(TransferState.RUNNING, h.dao.byId("first")!!.state)
            Unit
        }

    // ------------------------------------------------------------- harness

    private suspend fun awaitTrue(timeoutMs: Long = 10_000, cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            delay(25)
        }
        assertTrue("condition not met within ${timeoutMs}ms", cond())
    }

    private inner class Harness(concurrency: Int) {
        val dao = FakeTransferDao()
        val fileDao = FakeFileDao()
        val telegram = ScriptedTelegram()
        val settings = QueueFakeSettings(
            UserPreferences(transferRetryCount = 0, transferConcurrency = concurrency)
        )
        val drainer: TransferQueueDrainer
        val scheduler: WorkerScheduler
        val repository: TransferRepositoryImpl
        private val payload = File(workDir, "payload-${System.nanoTime()}").apply {
            writeBytes(ByteArray(1000) { 7 })
        }

        fun payloadFile(): File = payload

        init {
            val codec = ManifestCodec(StreamCrypto(), unused())
            val executor = TransferExecutor(
                messages = queueNamedErrorMessages(),
                storagePaths = unused(),
                telegramClient = telegram,
                transferDao = dao,
                fileDao = fileDao,
                fileRepository = unused(),
                backupDao = unused(),
                manifestCodec = codec,
                folderPathResolver = FolderPathResolver(unused(), ActiveChannel(settings), unused()),
                thumbnailStore = unused(),
                streamCrypto = StreamCrypto(),
                wrappedKeyRepository = unused(),
                downloadWriter = FakeDownloadWriter(),
                fileImporter = unused(),
                settingsRepository = settings,
                filePartDao = FakeFilePartDao,
                partUploader = PartUploader(
                    unused(), unused(), unused(), codec, StreamCrypto(), unused(),
                    unused(), unused<DispatcherProvider>(),
                    ApkIconUploader(unused(), unused(), StreamCrypto(), unused(), unused())
                ),
                partDownloader = PartDownloader(
                    unused(), unused(), StreamCrypto(), unused(), unused()
                ),
                apkIconUploader = ApkIconUploader(
                    unused(), unused(), StreamCrypto(), unused(), unused()
                ),
                localCopyDeleter = unused()
            )
            drainer = TransferQueueDrainer(
                transferDao = dao,
                fileDao = fileDao,
                transferExecutor = executor,
                backupSessionTracker = QueueNoOpSessionTracker(),
                settingsRepository = settings,
                networkMonitor = QueueFakeNetworkMonitor(NetworkStatus.UNMETERED),
                powerMonitor = QueueFakePowerMonitor(true),
                backupResumeScheduler = QueueRecordingResumeScheduler()
            )
            scheduler = WorkerScheduler(drainer, scope)
            val paths = object : AppStoragePaths by unused() {
                override val filesDir: File = workDir
            }
            repository = TransferRepositoryImpl(
                transferDao = dao,
                fileDao = fileDao,
                telegramClient = telegram,
                transferScheduler = scheduler,
                settingsRepository = settings,
                validateUpload = ValidateUploadUseCase(),
                storageInspector = StorageInspector(paths),
                backupSessionTracker = QueueNoOpSessionTracker(),
                archiveExtractionJobs = ArchiveExtractionJobs()
            )
        }

        fun addFile(id: String, size: Long) {
            fileDao.files[id] = FileEntity(
                id = id,
                folderId = null,
                name = "$id.bin",
                sizeBytes = size,
                mimeType = "application/octet-stream",
                category = FileCategory.OTHER,
                localPath = null,
                contentHash = null,
                chatId = 1,
                messageId = 10,
                remoteFileId = "r-$id",
                remoteUniqueId = "u-$id",
                backupState = BackupState.BACKED_UP,
                partCount = 0,
                createdAt = 1,
                modifiedAt = 1,
                addedAt = 1
            )
        }

        suspend fun addRow(id: String, fileId: String, state: TransferState, createdAt: Long) {
            dao.upsert(
                TransferEntity(
                    id = id,
                    type = TransferType.DOWNLOAD,
                    fileId = fileId,
                    displayName = id,
                    localPath = null,
                    chatId = 1,
                    messageId = 10,
                    remoteFileId = "r-$fileId",
                    sizeBytes = fileDao.files.getValue(fileId).sizeBytes,
                    state = state,
                    createdAt = createdAt,
                    updatedAt = createdAt
                )
            )
        }

        /** A download that moves slowly forever; [stopped] records collection teardown. */
        fun hangingFlow(remoteFileId: String): Flow<TelegramDownloadEvent> = flow {
            try {
                var bytes = 0L
                while (true) {
                    if (telegram.completing) {
                        emit(TelegramDownloadEvent.Completed(payload.absolutePath, 1000))
                        return@flow
                    }
                    emit(TelegramDownloadEvent.Progress(bytes, 10_000))
                    bytes += 512
                    delay(50)
                }
            } finally {
                telegram.stopped += remoteFileId
            }
        }
    }

    private inner class FakeDownloadWriter : DownloadWriter {
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String? = runCatching {
            val file = File(workDir, "saved-$fileName")
            file.outputStream().use(body)
            file.absolutePath
        }.getOrNull()

        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
        ): PartialDownload? = null

        override fun freeBytes(folderPath: String?): Long? = null
    }

    private class ScriptedTelegram : TelegramClient by unused() {
        val started = CopyOnWriteArrayList<String>()
        val stopped = CopyOnWriteArrayList<String>()
        val removed = CopyOnWriteArrayList<String>()
        val pausedNative = CopyOnWriteArrayList<String>()
        val pausedWithSource = CopyOnWriteArrayList<Triple<String, Long?, Long?>>()
        val removedWithSource = CopyOnWriteArrayList<Triple<String, Long?, Long?>>()

        @Volatile
        var completing = false

        @Volatile
        var script: (String) -> Flow<TelegramDownloadEvent> = { flow { delay(60_000) } }

        override fun downloadDocument(
            remoteFileId: String,
            chatId: Long?,
            messageId: Long?,
            queuePriority: Int
        ): Flow<TelegramDownloadEvent> {
            started += remoteFileId
            return script(remoteFileId)
        }

        override suspend fun pauseManagedDownload(remoteFileId: String) {
            pausedNative += remoteFileId
        }

        override suspend fun removeManagedDownload(remoteFileId: String) {
            removed += remoteFileId
        }

        // vc186 A2: the production repository/executor call the SOURCE-AWARE
        // variants, so the fake records them separately (and mirrors them
        // into the plain lists the vc184 assertions read).
        override suspend fun pauseManagedDownload(
            remoteFileId: String,
            chatId: Long?,
            messageId: Long?
        ) {
            pausedNative += remoteFileId
            pausedWithSource += Triple(remoteFileId, chatId, messageId)
        }

        override suspend fun removeManagedDownload(
            remoteFileId: String,
            chatId: Long?,
            messageId: Long?
        ) {
            removed += remoteFileId
            removedWithSource += Triple(remoteFileId, chatId, messageId)
        }

        override suspend fun deleteLocalCopy(remoteFileId: String) = Unit
    }

    /**
     * The WorkManager plumbing of WorkTransferScheduler + TransferQueueWorker,
     * transcribed policy-for-policy: kick starts a drain unless one is already
     * running or the pause hold is set; rekick/resume REPLACE the running
     * drain (cancel + start fresh), resume clearing the hold first; pause sets
     * the hold and stops the worker. All policy under test (hold, claim,
     * recovery, native calls, speed math) lives in the production classes.
     */
    private class WorkerScheduler(
        private val drainer: TransferQueueDrainer,
        private val scope: CoroutineScope
    ) : TransferScheduler {
        @Volatile
        var job: Job? = null

        override fun kick(allowMetered: Boolean) {
            if (drainer.isPauseHeld()) return
            if (job?.isActive == true) return
            job = scope.launch { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }
        }

        override fun rekick(allowMetered: Boolean) {
            if (drainer.isPauseHeld()) return
            job?.cancel()
            job = scope.launch { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }
        }

        override suspend fun pause() {
            drainer.setPauseHold(true)
            job?.cancelAndJoin()
        }

        override fun resume(allowMetered: Boolean) {
            drainer.setPauseHold(false)
            rekick(allowMetered)
        }

        override fun isPauseHeld(): Boolean = drainer.isPauseHeld()
    }

    private class FakeFileDao : FileDao by unused() {
        val files = ConcurrentHashMap<String, FileEntity>()

        override suspend fun byId(id: String): FileEntity? = files[id]

        override suspend fun byIds(ids: List<String>): List<FileEntity> = ids.mapNotNull { files[it] }

        override suspend fun setLocalPath(id: String, localPath: String?) {
            files.computeIfPresent(id) { _, f -> f.copy(localPath = localPath) }
        }

        override suspend fun setBackupStateIfLocalOnly(id: String, state: BackupState) = Unit

        override suspend fun repairBackedUpStates(): Int = 0

        override suspend fun clearStaleQueuedStates(): Int = 0

        override suspend fun clearStaleFailedStates(): Int = 0

        override suspend fun deleteCancelledBackupEntries(): Int = 0
    }

    private object FakeFilePartDao : FilePartDao by unused() {
        override suspend fun countOf(fileId: String): Int = 0
    }
}

private fun queueNamedErrorMessages(): TransferErrorMessages = Proxy.newProxyInstance(
    TransferErrorMessages::class.java.classLoader,
    arrayOf(TransferErrorMessages::class.java)
) { _, method, _ -> method.name } as TransferErrorMessages

private class QueueFakeSettings(initial: UserPreferences) : SettingsRepository by unused() {
    private val state = MutableStateFlow(initial)
    override val preferences: Flow<UserPreferences> = state

    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        state.value = transform(state.value)
    }
}

private class QueueFakeNetworkMonitor(initial: NetworkStatus) : NetworkMonitor {
    private val state = MutableStateFlow(initial)
    override val status: Flow<NetworkStatus> = state
    override fun currentStatus(): NetworkStatus = state.value
}

private class QueueFakePowerMonitor(initial: Boolean) : PowerMonitor {
    private val state = MutableStateFlow(initial)
    override val charging: Flow<Boolean> = state
    override fun isCharging(): Boolean = state.value
}

private class QueueNoOpSessionTracker : BackupSessionTracker {
    override suspend fun refreshActive() = Unit
    override suspend fun refresh(sessionId: String?) = Unit
}

private class QueueRecordingResumeScheduler : BackupResumeScheduler {
    val requests = mutableListOf<Pair<Boolean, Boolean>>()

    override fun resumeWhen(chargingOnly: Boolean, wifiOnly: Boolean) {
        requests += chargingOnly to wifiOnly
    }
}
