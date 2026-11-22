package com.drdisagree.teledrive.core.transfer

import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.FilePartialDownload
import com.drdisagree.teledrive.core.files.LocalCleanup
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.network.NetworkStatus
import com.drdisagree.teledrive.core.telegram.RemoteDocument
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramLimits
import com.drdisagree.teledrive.core.telegram.TelegramUploadEvent
import com.drdisagree.teledrive.data.local.database.TeleDriveDatabase
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.remote.telegram.ManifestCodec
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FakeSettings
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.testing.unused
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * PC-39 storm harness: a REAL [TransferQueueDrainer] + REAL [TransferExecutor]
 * over a real Room database, with a fake Telegram client whose uploads and
 * downloads can be delayed, gated per file, and flood-injected per file.
 * In-flight counters record the true overlap, so tests assert measured
 * concurrency — not just final states.
 */
internal class StormHarness(prefs: UserPreferences) : AutoCloseable {

    private val dbFile = File.createTempFile("teledrive-storm", ".db")
    val database: TeleDriveDatabase =
        Room.databaseBuilder<TeleDriveDatabase>(name = dbFile.absolutePath)
            .setDriver(BundledSQLiteDriver())
            .setQueryCoroutineContext(Dispatchers.IO)
            .build()
    val transfers get() = database.transferDao()
    val files get() = database.fileDao()

    val settings = FakeSettings(prefs)
    val telegram = StormTelegram()
    val resume = RecordingBackupResumeScheduler()

    private val root: File = Files.createTempDirectory("storm").toFile()
    val sourceDir = File(root, "sources").apply { mkdirs() }
    private val downloadDir = File(root, "downloads").apply { mkdirs() }
    private val tdlibDir = File(root, "tdlib").apply { mkdirs() }

    init {
        telegram.tdlibDir = tdlibDir
    }

    private val codec = ManifestCodec(
        StreamCrypto(),
        object : WrappedKeyRepository by unused() {
            override fun exists(name: String): Boolean = false
        }
    )

    fun manifestCodec(): ManifestCodec = codec

    private val paths = object : AppStoragePaths {
        override val filesDir: File = File(root, "files").apply { mkdirs() }
        override val cacheDir: File = File(root, "cache").apply { mkdirs() }
        override val externalCacheDir: File? = null
        override val externalStorageRoot: File? = null
    }

    private val writer = object : DownloadWriter {
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String? = runCatching {
            val file = File(downloadDir, fileName)
            file.outputStream().use(body)
            file.absolutePath
        }.getOrNull()

        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
        ): PartialDownload? = FilePartialDownload.open(downloadDir, fileName, token)

        override fun freeBytes(folderPath: String?): Long? = null
    }

    fun drainer(): TransferQueueDrainer {
        val executor = TransferExecutor(
            messages = namedErrorMessages(),
            storagePaths = paths,
            telegramClient = telegram,
            transferDao = transfers,
            fileDao = files,
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
            thumbnailStore = object : ThumbnailStore by unused() {
                override suspend fun uploadThumbnailFile(fileId: String): File? = null
            },
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = unused(),
            downloadWriter = writer,
            fileImporter = object : FileImporter by unused() {
                override fun isStaged(path: String): Boolean = false
            },
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
        return TransferQueueDrainer(
            transferDao = transfers,
            fileDao = files,
            transferExecutor = executor,
            backupSessionTracker = NoOpBackupSessionTracker(),
            settingsRepository = settings,
            networkMonitor = FakeNetworkMonitor(NetworkStatus.UNMETERED),
            powerMonitor = FakePowerMonitor(true),
            backupResumeScheduler = resume
        )
    }

    /** A local file + its queued UPLOAD transfer, as a folder import creates them. */
    suspend fun seedUpload(id: String, name: String, bytes: ByteArray, createdAt: Long) {
        val source = File(sourceDir, name)
        source.writeBytes(bytes)
        files.upsert(
            FileEntity(
                id = id,
                folderId = null,
                name = name,
                sizeBytes = bytes.size.toLong(),
                mimeType = "application/octet-stream",
                category = FileCategory.OTHER,
                localPath = source.absolutePath,
                contentHash = null,
                chatId = telegram.chatId,
                messageId = null,
                remoteFileId = null,
                remoteUniqueId = null,
                backupState = BackupState.NONE,
                partCount = 0,
                createdAt = createdAt,
                modifiedAt = createdAt,
                addedAt = createdAt
            )
        )
        transfers.upsert(
            TransferEntity(
                id = "t-$id",
                type = TransferType.UPLOAD,
                fileId = id,
                displayName = name,
                localPath = source.absolutePath,
                chatId = telegram.chatId,
                messageId = null,
                remoteFileId = null,
                sizeBytes = bytes.size.toLong(),
                state = TransferState.QUEUED,
                priority = 0,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
    }

    /** A backed-up file + its queued DOWNLOAD transfer. */
    suspend fun seedDownload(id: String, name: String, bytes: ByteArray, createdAt: Long, index: Int) {
        val remoteFileId = "rf-$id"
        telegram.downloadContents[remoteFileId] = bytes
        files.upsert(
            FileEntity(
                id = id,
                folderId = null,
                name = name,
                sizeBytes = bytes.size.toLong(),
                mimeType = "application/octet-stream",
                category = FileCategory.OTHER,
                localPath = null,
                contentHash = null,
                chatId = telegram.chatId,
                messageId = 9_000L + index,
                remoteFileId = remoteFileId,
                remoteUniqueId = "uq-$id",
                backupState = BackupState.BACKED_UP,
                partCount = 0,
                createdAt = createdAt,
                modifiedAt = createdAt,
                addedAt = createdAt
            )
        )
        transfers.upsert(
            TransferEntity(
                id = "t-$id",
                type = TransferType.DOWNLOAD,
                fileId = id,
                displayName = name,
                localPath = null,
                chatId = telegram.chatId,
                messageId = 9_000L + index,
                remoteFileId = remoteFileId,
                sizeBytes = bytes.size.toLong(),
                state = TransferState.QUEUED,
                priority = 0,
                createdAt = createdAt,
                updatedAt = createdAt
            )
        )
    }

    override fun close() {
        database.close()
        dbFile.delete()
        root.deleteRecursively()
    }
}

/**
 * A Telegram fake built for storms: per-file delays, per-file gates,
 * per-file flood plans, and honest in-flight accounting. A flooded
 * attempt throws BEFORE the transfer counts as started — the send was
 * rejected, exactly as Telegram behaves.
 */
internal class StormTelegram : TelegramClient by unused() {

    val chatId = 777L
    lateinit var tdlibDir: File

    private val nextMessageId = AtomicLong(5_000L)

    data class UploadRecord(val fileName: String, val caption: String, val messageId: Long)

    val uploads: MutableList<UploadRecord> =
        Collections.synchronizedList(mutableListOf())
    val startedUploads: MutableList<String> =
        Collections.synchronizedList(mutableListOf())
    val startedDownloads: MutableList<String> =
        Collections.synchronizedList(mutableListOf())

    val inFlightUploads = AtomicInteger(0)
    val maxInFlightUploads = AtomicInteger(0)
    val inFlightDownloads = AtomicInteger(0)
    val maxInFlightDownloads = AtomicInteger(0)

    /** fileName / remoteFileId -> attempts so far. */
    val uploadAttempts = ConcurrentHashMap<String, AtomicInteger>()
    val downloadAttempts = ConcurrentHashMap<String, AtomicInteger>()

    /** fileName / remoteFileId -> how many of its first attempts must 429. */
    val uploadFloods = ConcurrentHashMap<String, Int>()
    val downloadFloods = ConcurrentHashMap<String, Int>()

    val downloadContents = ConcurrentHashMap<String, ByteArray>()

    @Volatile
    var uploadDelayMs = 0L

    @Volatile
    var downloadDelayMs = 0L

    @Volatile
    var gated = false

    private val uploadGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val downloadGates = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private fun uploadGate(name: String): CompletableDeferred<Unit> =
        uploadGates.computeIfAbsent(name) { CompletableDeferred() }

    private fun downloadGate(name: String): CompletableDeferred<Unit> =
        downloadGates.computeIfAbsent(name) { CompletableDeferred() }

    fun releaseUpload(name: String) {
        uploadGate(name).complete(Unit)
    }

    fun releaseDownload(name: String) {
        downloadGate(name).complete(Unit)
    }

    fun releaseAll() {
        gated = false
        uploadGates.values.forEach { it.complete(Unit) }
        downloadGates.values.forEach { it.complete(Unit) }
    }

    override suspend fun ensureStorageChat(knownChatId: Long?): Long = chatId

    override suspend fun getLimits(): TelegramLimits = TelegramLimits.REGULAR

    override fun uploadDocument(
        chatId: Long,
        localPath: String,
        fileName: String,
        mimeType: String,
        caption: String,
        thumbnailPath: String?
    ): Flow<TelegramUploadEvent> = flow {
        val attempt = uploadAttempts.computeIfAbsent(fileName) { AtomicInteger(0) }
            .incrementAndGet()
        if (attempt <= (uploadFloods[fileName] ?: 0)) {
            throw TelegramException(429, "FLOOD_WAIT: retry after 1", 1)
        }
        val now = inFlightUploads.incrementAndGet()
        maxInFlightUploads.updateAndGet { maxOf(it, now) }
        startedUploads += fileName
        try {
            emit(TelegramUploadEvent.Started(0L))
            if (uploadDelayMs > 0) delay(uploadDelayMs)
            val gate = uploadGate(fileName)
            if (gated) gate.await()
            val messageId = nextMessageId.getAndIncrement()
            uploads += UploadRecord(fileName, caption, messageId)
            emit(
                TelegramUploadEvent.Completed(
                    RemoteDocument(
                        chatId = this@StormTelegram.chatId,
                        messageId = messageId,
                        remoteFileId = "rf-$messageId",
                        uniqueFileId = "uq-$messageId",
                        fileName = fileName,
                        mimeType = mimeType,
                        sizeBytes = File(localPath).length(),
                        caption = caption,
                        dateSeconds = 100
                    )
                )
            )
        } finally {
            inFlightUploads.decrementAndGet()
        }
    }

    override fun downloadDocument(
        remoteFileId: String,
        chatId: Long?,
        messageId: Long?,
        queuePriority: Int
    ): Flow<TelegramDownloadEvent> = flow {
        val attempt = downloadAttempts.computeIfAbsent(remoteFileId) { AtomicInteger(0) }
            .incrementAndGet()
        if (attempt <= (downloadFloods[remoteFileId] ?: 0)) {
            throw TelegramException(429, "FLOOD_WAIT: retry after 1", 1)
        }
        val now = inFlightDownloads.incrementAndGet()
        maxInFlightDownloads.updateAndGet { maxOf(it, now) }
        startedDownloads += remoteFileId
        try {
            if (downloadDelayMs > 0) delay(downloadDelayMs)
            val gate = downloadGate(remoteFileId)
            if (gated) gate.await()
            val bytes = downloadContents[remoteFileId] ?: ByteArray(0)
            val file = File(tdlibDir, "td-$remoteFileId").apply { writeBytes(bytes) }
            emit(TelegramDownloadEvent.Completed(file.absolutePath, bytes.size.toLong()))
        } finally {
            inFlightDownloads.decrementAndGet()
        }
    }

    override suspend fun removeManagedDownload(remoteFileId: String) = Unit

    override suspend fun deleteLocalCopy(remoteFileId: String) = Unit

    override suspend fun pauseManagedDownload(remoteFileId: String) = Unit
}
