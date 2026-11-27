package com.drdisagree.teledrive.desktop.ui

import androidx.lifecycle.SavedStateHandle
import com.drdisagree.teledrive.core.archive.NoOpArchiveDownloadReporter
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DefaultDispatcherProvider
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.PartialDownload
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
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
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.domain.model.UserPreferences
import com.drdisagree.teledrive.domain.repository.FileRepository
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import com.drdisagree.teledrive.domain.repository.TransferRepository
import com.drdisagree.teledrive.domain.repository.TrashRepository
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import com.drdisagree.teledrive.presentation.preview.PreviewContentResolver
import com.drdisagree.teledrive.presentation.preview.PreviewViewModel
import java.io.File
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 A3 parity on desktop: the PC viewer shares [PreviewViewModel]'s
 * content pipeline, and its Download button had the identical hijack — an
 * active transfer for the file replaced the resolved content with
 * DownloadProgress, so a playing video was swapped for a stuck
 * "Preparing… 0 B" screen. Drives the production entry points
 * ([PreviewViewModel.contentFor], [PreviewViewModel.download]) on the real
 * view model, in the DesktopArchiveViewerParityTest construction style: a
 * background transfer must start AND the streamed content must survive it;
 * only the Download-to-view gate (streaming off) follows the transfer.
 */
class DesktopViewerBackgroundDownloadTest {

    private class FakeSettingsRepository(
        initial: UserPreferences
    ) : SettingsRepository {
        private val prefs = MutableStateFlow(initial)
        override val preferences: Flow<UserPreferences> = prefs
        override suspend fun update(transform: (UserPreferences) -> UserPreferences) {
            prefs.value = transform(prefs.value)
        }

        override suspend fun getTelegramCredentials(): TelegramCredentials? = null
        override suspend fun setTelegramCredentials(credentials: TelegramCredentials) = Unit
        override suspend fun hasStoredTelegramCredentials(): Boolean = false
        override suspend fun clearTelegramCredentials() = Unit
    }

    private class RecordingDownloadWriter : DownloadWriter {
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String = "/Downloads/$fileName"

        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
        ): PartialDownload? = null

        override fun freeBytes(folderPath: String?): Long? = null
    }

    /** Records enqueues and mirrors production: the row appears as RUNNING. */
    private class FakeTransfers {
        val active = MutableStateFlow<TransferTask?>(null)
        val enqueued = CopyOnWriteArrayList<String>()
        val repository: TransferRepository = archiveTestProxy { method, args ->
            when (method) {
                "observeActiveForFile" -> active
                "enqueueDownload" -> {
                    val fileId = args!![0] as String
                    enqueued += fileId
                    active.value = TransferTask(
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
                    AppResult.Success("transfer-1")
                }
                else -> throw UnsupportedOperationException(method)
            }
        }
    }

    private fun videoFile() = DriveFile(
        id = "vid-1",
        folderId = null,
        name = "movie.mp4",
        sizeBytes = 5_000_000,
        mimeType = "video/mp4",
        category = FileCategory.VIDEO,
        localPath = null,
        contentHash = null,
        chatId = 100L,
        messageId = 200L,
        remoteFileId = "remote-vid",
        remoteUniqueId = "unique-vid",
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

    private fun await(description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue("timed out waiting for: $description", condition())
    }

    private fun viewModel(
        file: DriveFile,
        prefs: UserPreferences,
        transfers: FakeTransfers
    ): PreviewViewModel {
        val telegram = archiveFakeTelegram(ByteArray(0))
        val settings = FakeSettingsRepository(prefs)
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
        val downloadWriter = RecordingDownloadWriter()
        val remoteArchiveService = RemoteArchiveService(
            telegram, filePartDao, downloadWriter, folderPathResolver
        )
        val storagePaths = object : AppStoragePaths {
            private val root = File.createTempFile("viewer-download", "").also {
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
        return PreviewViewModel(
            savedStateHandle = SavedStateHandle(mapOf("fileId" to file.id)),
            fileRepository = fileRepository,
            trashRepository = archiveTestProxy<TrashRepository> { m, _ ->
                throw UnsupportedOperationException(m)
            },
            transferRepository = transfers.repository,
            settingsRepository = settings,
            contentResolver = resolver,
            remoteArchiveService = remoteArchiveService,
            archiveDownloadReporter = NoOpArchiveDownloadReporter
        )
    }

    @Test
    fun `download while a video plays starts a background transfer and playback content never changes`() {
        val file = videoFile()
        val transfers = FakeTransfers()
        val viewModel = viewModel(file, UserPreferences(), transfers)
        val seen = CopyOnWriteArrayList<PreviewContent>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { viewModel.contentFor(file).collect { seen += it } }
        try {
            await("streamed media resolves") { seen.lastOrNull() is PreviewContent.StreamedMedia }

            viewModel.download(file)

            await("the background transfer is enqueued") {
                transfers.enqueued.toList() == listOf("vid-1")
            }
            await("content re-settles on the video with the transfer active") {
                transfers.active.value != null &&
                    seen.count { it is PreviewContent.StreamedMedia } >= 2
            }
            assertTrue(
                "viewer content must stay the streamed video, was ${seen.lastOrNull()}",
                seen.lastOrNull() is PreviewContent.StreamedMedia
            )
            assertTrue(
                "no DownloadProgress may appear once the video can play: $seen",
                seen.dropWhile { it is PreviewContent.Loading }
                    .none { it is PreviewContent.DownloadProgress }
            )
        } finally {
            job.cancel()
        }
    }

    @Test
    fun `the download-to-view gate still follows its transfer to completion`() {
        val file = videoFile()
        val transfers = FakeTransfers()
        val viewModel = viewModel(
            file,
            UserPreferences(streamBeforeDownload = false),
            transfers
        )
        val seen = CopyOnWriteArrayList<PreviewContent>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { viewModel.contentFor(file).collect { seen += it } }
        try {
            await("gate resolves") { seen.lastOrNull() is PreviewContent.RequiresDownload }

            viewModel.download(file)

            await("gate shows the running transfer's progress") {
                seen.lastOrNull() is PreviewContent.DownloadProgress
            }
            val progress = seen.last() as PreviewContent.DownloadProgress
            assertEquals(123, progress.transferred)
            assertEquals(5_000_000, progress.total)
        } finally {
            job.cancel()
        }
    }
}
