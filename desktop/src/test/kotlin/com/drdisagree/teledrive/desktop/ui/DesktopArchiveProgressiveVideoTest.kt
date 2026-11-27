package com.drdisagree.teledrive.desktop.ui

import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.ArchiveDownloadTracker
import com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramFileInfo
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTombstoneDao
import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.FilePartEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.data.repository.ActiveChannel
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.preview.ArchiveEntryPreview
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 item N4 at the ViewModel seam: an in-zip video must be
 * PUBLISHED (handed to the player) while its entry is still being fetched.
 *
 * The fake Telegram client below models the report-52 TDLib mechanics the
 * production byte source is built on — one download window per file,
 * part-granular readiness, all-or-nothing ReadFilePart gated on the
 * contiguous prefix from the read offset — with one twist: the parts
 * covering zip offsets [1 MiB, 2 MiB) never land until [HeldTelegram]
 * is released. The video entry's data spans that range, so extraction
 * CANNOT complete before the release: if the preview is published while
 * the window is held, the publish provably happened on a prefix.
 */
class DesktopArchiveProgressiveVideoTest {

    private class HeldTelegram(
        private val bytes: ByteArray,
        private val holdFrom: Long,
        private val holdUntil: Long
    ) {
        private val partSize = 32L * 1024
        private val numParts = ((bytes.size + partSize - 1) / partSize).toInt()
        private val ready = BooleanArray(numParts)

        @Volatile
        private var released = false
        private var windowOffset = 0L
        private var windowLimit = 0L
        private var active = false

        fun releaseHeld() {
            synchronized(this) {
                released = true
                applyWindowLocked()
            }
        }

        /** True while a window is stalled on the held parts. */
        fun isHolding(): Boolean = synchronized(this) { !released && active }

        @Synchronized
        private fun contiguousFrom(offset: Long): Long {
            if (offset < 0 || offset >= bytes.size || numParts == 0) return 0L
            var part = (offset / partSize).toInt()
            if (!ready[part]) return 0L
            var ones = 0
            while (part + ones < numParts && ready[part + ones]) ones++
            val end = minOf(bytes.size.toLong(), (part + ones) * partSize)
            return end - offset
        }

        @Synchronized
        private fun info(): TelegramFileInfo {
            val completed = numParts > 0 && ready.all { it }
            return TelegramFileInfo(
                fileId = 7,
                sizeBytes = bytes.size.toLong(),
                localPath = null,
                isDownloadingCompleted = completed,
                isDownloadingActive = active,
                downloadOffset = windowOffset,
                downloadedPrefixSize =
                    if (completed) bytes.size.toLong() else contiguousFrom(windowOffset)
            )
        }

        @Synchronized
        private fun applyWindowLocked() {
            val size = bytes.size.toLong()
            val end = if (windowLimit == 0L) size else minOf(size, windowOffset + windowLimit)
            var allReady = true
            for (p in 0 until numParts) {
                val pStart = p * partSize
                val pEnd = minOf(size, pStart + partSize)
                if (pStart < end && windowOffset < pEnd) {
                    val held = !released && pEnd > holdFrom && pStart < holdUntil
                    if (held) {
                        allReady = false
                    } else {
                        ready[p] = true
                    }
                }
            }
            active = !allReady
        }

        @Synchronized
        private fun request(offset: Long, limit: Long) {
            windowOffset = offset
            windowLimit = limit
            applyWindowLocked()
        }

        @Synchronized
        private fun read(offset: Long, count: Long): ByteArray {
            if (count == 0L) {
                val available = contiguousFrom(offset)
                if (available == 0L) return ByteArray(0)
                return bytes.copyOfRange(offset.toInt(), (offset + available).toInt())
            }
            if (contiguousFrom(offset) < count) {
                throw TelegramException(
                    400,
                    "There is not enough downloaded bytes in the file to read"
                )
            }
            val end = minOf(offset + count, bytes.size.toLong())
            return bytes.copyOfRange(offset.toInt(), end.toInt())
        }

        val client: TelegramClient = Proxy.newProxyInstance(
            TelegramClient::class.java.classLoader,
            arrayOf(TelegramClient::class.java),
            InvocationHandler { _, method, args ->
                try {
                    dispatch(method.name, args)
                } catch (failure: TelegramException) {
                    // Same continuation-resume bridge as the shared
                    // FakeTdLibTelegramClient: a Proxy would otherwise wrap
                    // the exception and hide the 400 gate from the caller.
                    val continuation = args?.lastOrNull() as? Continuation<*>
                        ?: throw failure
                    @Suppress("UNCHECKED_CAST")
                    (continuation as Continuation<Any?>)
                        .resumeWith(Result.failure(failure))
                    COROUTINE_SUSPENDED
                }
            }
        ) as TelegramClient

        private fun dispatch(methodName: String, args: Array<out Any?>?): Any? =
            when (methodName) {
                "resolveFile", "getFileInfo" -> info()
                "requestFileRange" -> {
                    request((args!![1] as Number).toLong(), (args[2] as Number).toLong())
                    Unit
                }
                "readFilePart" -> read(
                    (args!![1] as Number).toLong(),
                    (args[2] as Number).toLong()
                )
                "cancelFileDownload", "deleteLocalCopy" -> Unit
                "fileUpdates" -> flow {
                    while (true) {
                        emit(info())
                        delay(20)
                    }
                }
                "toString" -> "HeldTelegram"
                "hashCode" -> 7
                "equals" -> false
                else -> throw UnsupportedOperationException(methodName)
            }
    }

    private class RecordingDownloadWriter : DownloadWriter {
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
            val out = ByteArrayOutputStream()
            body(out)
            return "/Downloads/$fileName"
        }

        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: <redacted>
        ): PartialDownload? = null

        override fun freeBytes(folderPath: String?): Long? = null
    }

    private class FakeSettingsRepository : SettingsRepository {
        private val prefs = MutableStateFlow(UserPreferences())
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            prefs.value = transform(prefs.value)
        }

        override suspend fun getTelegramCredentials(): TelegramCredentials? = null
        override suspend fun setTelegramCredentials(credentials: TelegramCredentials) = Unit
        override suspend fun hasStoredTelegramCredentials(): Boolean = false
        override suspend fun clearTelegramCredentials() = Unit
    }

    private class FakeTransferDao {
        val rows = ConcurrentHashMap<String, TransferEntity>()
        val dao: TransferDao = archiveTestProxy { method, args ->
            when (method) {
                "upsert" -> {
                    val entity = args!![0] as TransferEntity
                    rows[entity.id] = entity
                    Unit
                }
                "byId" -> rows[args!![0] as String]
                "setLocalPath" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row -> row.copy(localPath = args[1] as String?) }
                    Unit
                }
                "setCompleted" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row ->
                        row.copy(
                            state = TransferState.COMPLETED,
                            transferredBytes = row.sizeBytes,
                            completedAt = args[1] as Long
                        )
                    }
                    Unit
                }
                "updateProgress" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row ->
                        row.copy(transferredBytes = args[1] as Long)
                    }
                    Unit
                }
                "setFailed" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row ->
                        row.copy(state = args[1] as TransferState, errorMessage = args[2] as String?)
                    }
                    Unit
                }
                "setState" -> {
                    val id = args!![0] as String
                    rows.computeIfPresent(id) { _, row ->
                        row.copy(state = args[1] as TransferState) }
                    Unit
                }
                else -> throw UnsupportedOperationException(method)
            }
        }
    }

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for: $description", condition())
    }

    private fun zipFile(id: String): DriveFile = DriveFile(
        id = id,
        folderId = null,
        name = "test.zip",
        sizeBytes = 100,
        mimeType = "application/zip",
        category = FileCategory.ARCHIVE,
        localPath = null,
        contentHash = null,
        chatId = 1L,
        messageId = 2L,
        remoteFileId = "remote-1",
        remoteUniqueId = "unique-1",
        backupState = BackupState.BACKED_UP,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        isEncrypted = false,
        width = null,
        height = null,
        durationMs = null,
        trashedAt = null,
        createdAt = 0L,
        modifiedAt = 0L,
        addedAt = 0L
    )

    private fun buildViewModel(telegram: TelegramClient, file: DriveFile): PreviewViewModel {
        val downloadWriter = RecordingDownloadWriter()
        val transferDao = FakeTransferDao()
        val tracker = ArchiveDownloadTracker(transferDao.dao, ArchiveExtractionJobs())
        val settings = FakeSettingsRepository()
        val filePartDao = archiveTestProxy<FilePartDao> { method, _ ->
            when (method) {
                "partsOf" -> emptyList<FilePartEntity>()
                "countOf" -> 0
                else -> throw UnsupportedOperationException(method)
            }
        }
        val folderPathResolver = FolderPathResolver(
            archiveTestProxy<FolderDao> { m, _ -> throw UnsupportedOperationException(m) },
            ActiveChannel(settings),
            archiveTestProxy<FolderTombstoneDao> { m, _ -> throw UnsupportedOperationException(m) }
        )
        val remoteArchiveService = RemoteArchiveService(
            telegram, filePartDao, downloadWriter, folderPathResolver
        )
        val storagePaths = object : AppStoragePaths {
            private val root = File.createTempFile("archive-progressive", "").also {
                it.delete()
                it.mkdirs()
            }
            override val filesDir: File = File(root, "files").also { it.mkdirs() }
            override val cacheDir: File = File(root, "cache").also { it.mkdirs() }
            override val externalCacheDir: File? = null
            override val externalStorageRoot: File? = null
        }
        val resolver = PreviewContentResolver(
            storagePaths = storagePaths,
            telegramClient = telegram,
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = archiveTestProxy<WrappedKeyRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            cacheDao = archiveTestProxy<CacheDao> { m, _ -> throw UnsupportedOperationException(m) },
            filePartDao = filePartDao,
            settingsRepository = settings,
            thumbnailStore = archiveTestProxy<ThumbnailStore> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            dispatchers = DefaultDispatcherProvider(),
            remoteArchiveService = remoteArchiveService
        )
        val fileRepository = archiveTestProxy<FileRepository> { method, _ ->
            when (method) {
                "getFile" -> file
                "observeFiles" -> flowOf(listOf(file))
                "observeFile" -> flowOf(file)
                "getFolder" -> null
                else -> throw UnsupportedOperationException(method)
            }
        }
        val transferRepository = archiveTestProxy<TransferRepository> { method, _ ->
            when (method) {
                "observeActiveForFile" -> flowOf(null)
                else -> throw UnsupportedOperationException(method)
            }
        }
        return PreviewViewModel(
            savedStateHandle = SavedStateHandle(mapOf("fileId" to file.id)),
            fileRepository = fileRepository,
            trashRepository = archiveTestProxy<TrashRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            transferRepository = transferRepository,
            settingsRepository = settings,
            contentResolver = resolver,
            remoteArchiveService = remoteArchiveService,
            archiveDownloadReporter = tracker
        )
    }

    private val videoData = Random(97).nextBytes(3 * 1024 * 1024)

    private fun heldSetup(fileId: String): Triple<HeldTelegram, PreviewViewModel, DriveFile> {
        val zipBytes = archiveZipBytes(
            listOf(
                "movie.mkv" to videoData,
                "notes.txt" to ByteArray(1_000) { 5 }
            )
        )
        val telegram = HeldTelegram(
            zipBytes,
            holdFrom = 1024L * 1024,
            holdUntil = 2L * 1024 * 1024
        )
        val file = zipFile(fileId)
        val viewModel = buildViewModel(telegram.client, file)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.contentFor(file).collect {} }
        await("remote listing stored") {
            viewModel.uiState.value.archiveListings.containsKey(file.id)
        }
        return Triple(telegram, viewModel, file)
    }

    @Test
    fun `video preview is published on a prefix while the entry is still fetching`() {
        val (telegram, viewModel, file) = heldSetup("vm-progressive-zip")

        assertTrue(viewModel.previewArchiveVideoEntry(file, 0))
        // The held window makes completion IMPOSSIBLE until releaseHeld():
        // a published preview here was necessarily handed over on a prefix.
        await("video preview published while fetch is held") {
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Video
        }
        assertTrue("fetch must still be held at publish time", telegram.isHolding())

        val preview = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Video
        assertEquals("movie.mkv", preview.name)
        val temp = File(preview.path)
        val lengthAtPublish = temp.length()
        assertTrue(
            "temp at publish held $lengthAtPublish of ${videoData.size} bytes",
            lengthAtPublish >= 512L * 1024 && lengthAtPublish < videoData.size.toLong()
        )
        // The published prefix is the entry's own first bytes, byte-exact.
        val prefix = temp.inputStream().use { it.readNBytes(512 * 1024) }
        assertArrayEquals(videoData.copyOfRange(0, 512 * 1024), prefix)
        // Honest in-progress state: loading is visible and partial, the
        // busy gate is still held by the background fetch.
        val loading = viewModel.uiState.value.archivePreviewLoading
        assertTrue("loading state must be present during the fetch", loading != null)
        loading!!
        assertTrue(
            "loading progress must be partial (${loading.producedBytes} of ${loading.totalBytes})",
            loading.producedBytes in 1 until loading.totalBytes
        )
        assertEquals(videoData.size.toLong(), loading.totalBytes)
        assertTrue(viewModel.uiState.value.archiveEntryBusyIndex != null)

        // Let the fetch finish: the same temp completes byte-exact and the
        // loading/busy state clears.
        telegram.releaseHeld()
        await("busy cleared after fetch completes") {
            viewModel.uiState.value.archiveEntryBusyIndex == null
        }
        await("loading cleared after fetch completes") {
            viewModel.uiState.value.archivePreviewLoading == null
        }
        assertArrayEquals(videoData, temp.readBytes())
        temp.delete()
    }

    @Test
    fun `dismissing during the background fetch cancels it and frees the gate`() {
        val (telegram, viewModel, file) = heldSetup("vm-progressive-dismiss-zip")

        assertTrue(viewModel.previewArchiveVideoEntry(file, 0))
        await("video preview published while fetch is held") {
            viewModel.uiState.value.archivePreview is ArchiveEntryPreview.Video
        }
        assertTrue(telegram.isHolding())

        viewModel.dismissArchivePreview()
        await("busy cleared after dismiss") {
            viewModel.uiState.value.archiveEntryBusyIndex == null
        }
        assertEquals(null, viewModel.uiState.value.archivePreview)
        assertEquals(null, viewModel.uiState.value.archivePreviewLoading)
    }
}
