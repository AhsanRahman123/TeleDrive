package com.drdisagree.teledrive.presentation.preview

import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.LocalArchiveService
import com.drdisagree.teledrive.core.archive.NoOpArchiveDownloadReporter
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileQuerySpec
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.testing.unused
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withTimeout

/**
 * Host harness for driving the REAL [PreviewViewModel] through its production
 * entry points (vc184): a fake Telegram client serving one in-memory file
 * (the same pattern as RemoteArchiveServiceTest), the real
 * [RemoteArchiveService]/[LocalArchiveService]/[PreviewContentResolver], and
 * hand fakes only for the repository interfaces. Nothing in the viewer's own
 * logic is stubbed.
 */

internal class PreviewFakeSettings(initial: UserPreferences) : SettingsRepository by unused() {
    private val state = MutableStateFlow(initial)
    override val preferences: Flow<UserPreferences> = state
    override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
        state.value = transform(state.value)
    }
}

internal class PreviewFakeFileRepository(
    private val file: DriveFile
) : FileRepository by unused() {
    override suspend fun getFile(id: String): DriveFile? = file.takeIf { it.id == id }
    override fun observeFiles(spec: FileQuerySpec): Flow<List<DriveFile>> = flowOf(listOf(file))
    override fun observeFile(id: String): Flow<DriveFile?> = flowOf(file.takeIf { it.id == id })
}

/**
 * Records download requests and mirrors production: an accepted enqueue makes
 * the file's active-transfer flow emit the RUNNING row, exactly what the Room
 * observer hands the viewer in the app.
 */
internal class PreviewFakeTransferRepository : TransferRepository by unused() {
    val activeForFile = MutableStateFlow<TransferTask?>(null)
    val enqueuedFileIds = mutableListOf<String>()

    override fun observeActiveForFile(fileId: String): Flow<TransferTask?> = activeForFile

    override suspend fun enqueueDownload(fileId: String, priority: Int): AppResult<String> {
        enqueuedFileIds += fileId
        activeForFile.value = TransferTask(
            id = "transfer-1",
            type = TransferType.DOWNLOAD,
            fileId = fileId,
            displayName = "movie.mp4",
            sizeBytes = 5_000_000,
            transferredBytes = 123,
            state = TransferState.RUNNING,
            priority = 0,
            errorMessage = null,
            speedBytesPerSecond = 0,
            createdAt = 1,
            updatedAt = 1,
            completedAt = null
        )
        return AppResult.Success("transfer-1")
    }
}

internal class PreviewFakeFilePartDao : FilePartDao {
    override suspend fun partsOf(fileId: String): List<FilePartEntity> = emptyList()
    override suspend fun upsert(part: FilePartEntity) = throw UnsupportedOperationException()
    override suspend fun partsOfAll(fileIds: List<String>): List<FilePartEntity> =
        throw UnsupportedOperationException()
    override suspend fun countOf(fileId: String): Int = 0
    override suspend fun deleteFor(fileIds: List<String>) = throw UnsupportedOperationException()
    override suspend fun byRemoteUniqueId(uniqueId: String): FilePartEntity? =
        throw UnsupportedOperationException()
    override suspend fun byPart(fileId: String, partIndex: Int): FilePartEntity? =
        throw UnsupportedOperationException()
}

internal class PreviewFakeDownloadWriter : DownloadWriter {
    override fun write(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        body: (OutputStream) -> Unit
    ): String {
        val out = ByteArrayOutputStream()
        body(out)
        return "/Downloads/TeleDrive/$fileName"
    }

    override fun openPartial(
        fileName: String,
        mimeType: String,
        folderPath: String?,
        token: String?
    ): PartialDownload? = null

    override fun freeBytes(folderPath: String?): Long? = null
}

/** Minimal TelegramClient serving one in-memory file as already fully downloaded. */
internal fun previewFakeTelegram(bytes: ByteArray): TelegramClient {
    val info = TelegramFileInfo(
        fileId = 7,
        sizeBytes = bytes.size.toLong(),
        localPath = null,
        isDownloadingCompleted = true,
        isDownloadingActive = false,
        downloadOffset = 0,
        downloadedPrefixSize = bytes.size.toLong()
    )
    val handler = InvocationHandler { _, method, args ->
        when (method.name) {
            "resolveFile" -> info
            "getFileInfo" -> info
            "readFilePart" -> {
                val offset = (args[1] as Number).toLong()
                val count = (args[2] as Number).toLong()
                if (offset >= bytes.size) ByteArray(0)
                else bytes.copyOfRange(
                    offset.toInt(),
                    minOf(offset + count, bytes.size.toLong()).toInt()
                )
            }
            "requestFileRange", "cancelFileDownload" -> Unit
            "fileUpdates" -> flowOf(info)
            else -> throw UnsupportedOperationException(method.name)
        }
    }
    return Proxy.newProxyInstance(
        TelegramClient::class.java.classLoader,
        arrayOf(TelegramClient::class.java),
        handler
    ) as TelegramClient
}

internal fun previewDriveFile(
    id: String,
    name: String,
    mimeType: String,
    category: FileCategory,
    sizeBytes: Long
) = DriveFile(
    id = id,
    folderId = null,
    name = name,
    sizeBytes = sizeBytes,
    mimeType = mimeType,
    category = category,
    localPath = null,
    contentHash = null,
    chatId = 100L,
    messageId = 200L,
    remoteFileId = "remote-$id",
    remoteUniqueId = "unique-$id",
    backupState = BackupState.BACKED_UP,
    isHidden = false,
    isArchived = false,
    isFavorite = false,
    isEncrypted = false,
    width = null,
    height = null,
    durationMs = null,
    trashedAt = null,
    createdAt = 0,
    modifiedAt = 0,
    addedAt = 0
)

internal fun buildZipBytes(entries: List<Pair<String, ByteArray>>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        for ((name, bytes) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    return out.toByteArray()
}

internal fun buildPreviewViewModel(
    file: DriveFile,
    rootDir: File,
    prefs: UserPreferences = UserPreferences(),
    transferRepository: PreviewFakeTransferRepository = PreviewFakeTransferRepository(),
    telegramBytes: ByteArray = ByteArray(0)
): PreviewViewModel {
    val paths = object : AppStoragePaths {
        override val filesDir: File = rootDir
        override val cacheDir: File = rootDir
        override val externalCacheDir: File? = null
        override val externalStorageRoot: File? = null
    }
    val telegram = previewFakeTelegram(telegramBytes)
    val settings = PreviewFakeSettings(prefs)
    val folderPathResolver = FolderPathResolver(
        unused<FolderDao>(),
        ActiveChannel(unused<SettingsRepository>()),
        unused<FolderTombstoneDao>()
    )
    val downloadWriter = PreviewFakeDownloadWriter()
    val remoteArchiveService = RemoteArchiveService(
        telegramClient = telegram,
        filePartDao = PreviewFakeFilePartDao(),
        downloadWriter = downloadWriter,
        folderPathResolver = folderPathResolver
    )
    val resolver = PreviewContentResolver(
        storagePaths = paths,
        telegramClient = telegram,
        streamCrypto = StreamCrypto(),
        wrappedKeyRepository = unused<WrappedKeyRepository>(),
        cacheDao = unused<CacheDao>(),
        filePartDao = PreviewFakeFilePartDao(),
        settingsRepository = settings,
        dispatchers = object : DispatcherProvider {
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
        },
        remoteArchiveService = remoteArchiveService
    )
    return PreviewViewModel(
        savedStateHandle = SavedStateHandle(mapOf("fileId" to file.id)),
        fileRepository = PreviewFakeFileRepository(file),
        trashRepository = unused<TrashRepository>(),
        transferRepository = transferRepository,
        settingsRepository = settings,
        contentResolver = resolver,
        remoteArchiveService = remoteArchiveService,
        localArchiveService = LocalArchiveService(downloadWriter, folderPathResolver),
        archiveDownloadReporter = NoOpArchiveDownloadReporter
    )
}

/**
 * Throwables delivered to the default uncaught-exception handler during a
 * VM test. The view model's scope is a supervisor: an exception in its init
 * or content coroutines never reaches the test's runBlocking — it lands
 * here instead, and [awaitPreviewCondition] turns the first one into an
 * assertion failure whose message carries the full stack (Gradle's console
 * summary otherwise shows only a recovered, message-less RuntimeException).
 */
internal val uncaughtThrowables = java.util.concurrent.CopyOnWriteArrayList<Throwable>()

internal fun installUncaughtRecorder() {
    Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
        uncaughtThrowables += throwable
    }
}

internal fun runVmTest(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
    uncaughtThrowables.clear()
    installUncaughtRecorder()
    try {
        kotlinx.coroutines.runBlocking { block() }
    } catch (t: Throwable) {
        // Re-report with the full stack in the message: a bare rethrow is
        // recovered by the coroutine machinery into a message-less
        // RuntimeException in Gradle's console summary.
        throw AssertionError("VM test body failed:\n" + t.stackTraceToString())
    }
}

/** Polls [condition] until it holds; fails the test with [description] on timeout. */
internal suspend fun awaitPreviewCondition(
    description: String,
    timeoutMs: Long = 20_000,
    condition: () -> Boolean
) {
    withTimeout(timeoutMs) {
        while (!condition()) {
            uncaughtThrowables.firstOrNull()?.let { throwable ->
                throw AssertionError(
                    "uncaught exception while waiting for '$description':\n" +
                        throwable.stackTraceToString()
                )
            }
            delay(10)
        }
    }
}
