package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.FilePartialDownload
import com.drdisagree.teledrive.core.files.Hashing
import com.drdisagree.teledrive.core.files.LocalCleanup
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FakeSettings
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.data.repository.SyncHarness
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.testing.unused
import java.io.ByteArrayInputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 P7 ground truth at the PRODUCTION seam: the real [TransferExecutor]
 * downloads through a scripted Telegram flow that delivers bytes at a
 * KNOWN rate in real time, after an attach replay like TDLib's (a large
 * counter jump inside the first moments, for bytes this execution never
 * moved). The speed stored on the transfer row — the number the PC
 * Transfers screen displays — must approximate the driven rate for that
 * row. The pre-fix code (windowed ProgressTicker over the replayed
 * counter) displayed the replay as >13 MB/s here, so these assertions
 * discriminate the fix by an order of magnitude.
 *
 * Real time is used deliberately (the executor reads the wall clock);
 * tolerances are ±30% to absorb CI scheduling jitter, far inside the
 * gap between the honest rate and the fake one.
 */
class DownloadSpeedGroundTruthTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val workDir: File = Files.createTempDirectory("speed-truth").toFile()
    private val destination = File(workDir, "Downloads").apply { mkdirs() }

    @After
    fun tearDown() {
        harness.close()
        workDir.deleteRecursively()
    }

    private class Script(
        val totalBytes: Long,
        val replayBytes: Long,
        val rateBytesPerSecond: Long,
        val payload: ByteArray
    )

    private val scripts = mutableMapOf<String, Script>()

    private suspend fun prepare(
        fileId: String,
        transferId: String,
        remoteFileId: String,
        script: Script
    ) {
        scripts[remoteFileId] = script
        database.fileDao().upsert(
            FileEntity(
                id = fileId, folderId = null, name = "$fileId.bin",
                sizeBytes = script.totalBytes, mimeType = "application/octet-stream",
                category = FileCategory.OTHER, localPath = null,
                contentHash = Hashing.sha256(ByteArrayInputStream(script.payload)),
                chatId = 1, messageId = 10, remoteFileId = remoteFileId,
                remoteUniqueId = "unique-$fileId",
                backupState = BackupState.BACKED_UP, partCount = 0, createdAt = 1,
                modifiedAt = 1, addedAt = 1
            )
        )
        database.transferDao().upsert(
            TransferEntity(
                id = transferId, type = TransferType.DOWNLOAD, fileId = fileId,
                displayName = "$fileId.bin", localPath = null, chatId = 1,
                messageId = 10, remoteFileId = remoteFileId,
                sizeBytes = script.totalBytes,
                state = TransferState.RUNNING, createdAt = 1, updatedAt = 1
            )
        )
    }

    private fun scriptedFlow(remoteFileId: String): Flow<TelegramDownloadEvent> = flow {
        val script = scripts.getValue(remoteFileId)
        emit(TelegramDownloadEvent.Progress(0L, script.totalBytes))
        delay(150)
        // The attach replay: the counter jumps by bytes fetched before
        // this execution attached. No network bytes move for us here.
        emit(TelegramDownloadEvent.Progress(script.replayBytes, script.totalBytes))
        var bytes = script.replayBytes
        val stepBytes = script.rateBytesPerSecond / 20 // one step per 50 ms
        while (bytes < script.totalBytes) {
            delay(50)
            bytes = minOf(bytes + stepBytes, script.totalBytes)
            emit(TelegramDownloadEvent.Progress(bytes, script.totalBytes))
        }
        val file = File(workDir, "tdlib-$remoteFileId").apply { writeBytes(script.payload) }
        emit(TelegramDownloadEvent.Completed(file.absolutePath, script.totalBytes))
    }

    private fun executor(): TransferExecutor {
        val settings = FakeSettings(UserPreferences())
        val codec = ManifestCodec(StreamCrypto(), unused())
        val telegram = object : TelegramClient by unused() {
            override fun downloadDocument(
                remoteFileId: String,
                chatId: Long?,
                messageId: Long?,
                queuePriority: Int
            ): Flow<TelegramDownloadEvent> = scriptedFlow(remoteFileId)

            override suspend fun deleteLocalCopy(remoteFileId: String) {
                File(workDir, "tdlib-$remoteFileId").delete()
            }

            override suspend fun removeManagedDownload(remoteFileId: String) = Unit
        }
        val writer = object : DownloadWriter {
            override fun write(
                fileName: String,
                mimeType: String,
                folderPath: String?,
                body: (OutputStream) -> Unit
            ): String? = runCatching {
                val file = File(destination, fileName)
                file.outputStream().use(body)
                file.absolutePath
            }.getOrNull()

            override fun openPartial(
                fileName: String,
                mimeType: String,
                folderPath: String?,
                token: String?
            ): PartialDownload? = FilePartialDownload.open(destination, fileName, token)

            override fun freeBytes(folderPath: String?): Long? = null
        }
        val paths = object : AppStoragePaths by unused() {
            override val filesDir: File = workDir
        }
        val dispatchers = object : DispatcherProvider {
            override val main = Dispatchers.Unconfined
            override val io = Dispatchers.IO
            override val default = Dispatchers.Default
        }
        return TransferExecutor(
            messages = namedErrorMessages(),
            storagePaths = paths,
            telegramClient = telegram,
            transferDao = database.transferDao(),
            fileDao = database.fileDao(),
            fileRepository = object : FileRepository by unused() {
                override suspend fun deleteLocalCopy(ids: List<String>) =
                    AppResult.Success(LocalCleanup(deletedCount = 0))
            },
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
            downloadWriter = writer,
            fileImporter = unused(),
            settingsRepository = settings,
            filePartDao = database.filePartDao(),
            partUploader = PartUploader(
                unused(), unused(), database.filePartDao(), codec, StreamCrypto(), unused(),
                unused(), unused<DispatcherProvider>(),
                ApkIconUploader(unused(), unused(), StreamCrypto(), unused(), unused())
            ),
            partDownloader = PartDownloader(
                telegram, database.filePartDao(), StreamCrypto(), unused(), dispatchers
            ),
            apkIconUploader = ApkIconUploader(unused(), unused(), StreamCrypto(), unused(), unused()),
            localCopyDeleter = unused()
        )
    }

    /** Polls a row's stored speed until the row completes; returns the samples. */
    private suspend fun sampleSpeeds(
        transferId: String,
        done: () -> Boolean
    ): List<Long> {
        val samples = CopyOnWriteArrayList<Long>()
        while (!done()) {
            database.transferDao().byId(transferId)?.let { samples += it.speedBytesPerSecond }
            delay(50)
        }
        return samples
    }

    private fun assertHonest(samples: List<Long>, expectedRate: Long, label: String) {
        assertTrue("$label: no speed samples were recorded", samples.isNotEmpty())
        val fakeCeiling = expectedRate * 7 / 4
        assertTrue(
            "$label: a sampled speed reached ${samples.maxOrNull()} B/s — the replay is " +
                "being displayed as speed (honest ceiling $fakeCeiling B/s)",
            samples.all { it <= fakeCeiling }
        )
        val measured = samples.filter { it > 0L }
        assertTrue("$label: no measured (non-zero) speed was ever stored", measured.isNotEmpty())
        val last = measured.last()
        val low = expectedRate * 7 / 10
        val high = expectedRate * 13 / 10
        assertTrue(
            "$label: final measured speed $last B/s is not within ±30% of the driven " +
                "$expectedRate B/s",
            last in low..high
        )
    }

    @Test
    fun `a download driven at 2 MB per second stores approximately 2 MB per second for its row`() =
        runBlocking {
            val total = 30_000_000L
            prepare(
                "f1", "t1", "r-speed",
                Script(
                    totalBytes = total,
                    replayBytes = 20_000_000L,
                    rateBytesPerSecond = 2_000_000L,
                    payload = ByteArray(total.toInt()) { (it % 251).toByte() }
                )
            )
            val transfer = database.transferDao().byId("t1")!!
            val running = async { executor().execute(transfer) }
            val samples = sampleSpeeds("t1") { running.isCompleted }
            assertEquals(TransferOutcome.Completed, running.await())
            assertHonest(samples, 2_000_000L, "single row")
        }

    @Test
    fun `two concurrent downloads at different rates store different speeds summing near the combined rate`() =
        runBlocking {
            prepare(
                "fa", "ta", "r-a",
                Script(
                    totalBytes = 18_000_000L,
                    replayBytes = 10_000_000L,
                    rateBytesPerSecond = 1_500_000L,
                    payload = ByteArray(18_000_000) { (it % 241).toByte() }
                )
            )
            prepare(
                "fb", "tb", "r-b",
                Script(
                    totalBytes = 13_000_000L,
                    replayBytes = 10_000_000L,
                    rateBytesPerSecond = 500_000L,
                    payload = ByteArray(13_000_000) { (it % 239).toByte() }
                )
            )
            val executor = executor()
            val runA = async { executor.execute(database.transferDao().byId("ta")!!) }
            val runB = async { executor.execute(database.transferDao().byId("tb")!!) }
            val samplesA = CopyOnWriteArrayList<Long>()
            val samplesB = CopyOnWriteArrayList<Long>()
            while (!runA.isCompleted || !runB.isCompleted) {
                database.transferDao().byId("ta")?.let { samplesA += it.speedBytesPerSecond }
                database.transferDao().byId("tb")?.let { samplesB += it.speedBytesPerSecond }
                delay(50)
            }
            assertEquals(TransferOutcome.Completed, runA.await())
            assertEquals(TransferOutcome.Completed, runB.await())
            assertHonest(samplesA, 1_500_000L, "row A (1.5 MB/s)")
            assertHonest(samplesB, 500_000L, "row B (0.5 MB/s)")
            val lastA = samplesA.last { it > 0L }
            val lastB = samplesB.last { it > 0L }
            assertTrue(
                "concurrent rows must not display the identical figure (both were $lastA/$lastB)",
                lastA != lastB
            )
            val sum = lastA + lastB
            assertTrue(
                "row speeds sum to $sum B/s, not within ±30% of the combined driven 2,000,000 B/s",
                sum in 1_400_000L..2_600_000L
            )
        }

    @Test
    fun `stored progress never regresses when a re-attached execution restarts its counter`() =
        runBlocking {
            prepare(
                "f1", "t1", "r-speed",
                Script(
                    totalBytes = 30_000_000L,
                    replayBytes = 20_000_000L,
                    rateBytesPerSecond = 2_000_000L,
                    payload = ByteArray(30_000_000) { (it % 251).toByte() }
                )
            )
            val dao = database.transferDao()
            dao.updateProgress("t1", 25_000_000L, 123_456L, 100L)
            assertEquals(25_000_000L, dao.byId("t1")!!.transferredBytes)
            // A re-attach whose counter restarted near zero writes a lower
            // absolute value: the row's bytes (its %) must not move back.
            dao.updateProgress("t1", 2_000_000L, 0L, 200L)
            assertEquals(25_000_000L, dao.byId("t1")!!.transferredBytes)
            Unit
        }
}
