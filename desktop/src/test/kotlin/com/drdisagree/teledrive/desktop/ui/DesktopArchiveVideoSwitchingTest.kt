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
import com.drdisagree.teledrive.core.telegram.TelegramCredentials
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
import com.drdisagree.teledrive.presentation.preview.archiveUnifiedPreviewIndices
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.26 P4 — in-zip video switching stops after 2 videos (field).
 *
 * DISCRIMINATING test, written against the 1.6.25 baseline BEFORE any fix:
 * it drives the real [PreviewViewModel] production switching path
 * ([PreviewViewModel.previewArchiveVideoEntry] to open, then
 * [PreviewViewModel.stepArchivePreview] for each switch) over a zip of
 * FOUR mp4 entries, asserting after every stop that the published preview
 * is that entry's own Video preview (name + extracted bytes), that the
 * previous entry's temp was deleted, and that the busy index cleared.
 *
 * - If this passes on the baseline, the ViewModel layer is exonerated and
 *   P4 lives in the DesktopMediaPlayer / vlcj layer.
 * - If it fails at a specific step, that step's seam is the proven one.
 *
 * A5 constraint (Pranto, 2026-11-27): landing on an in-zip video must end
 * in playback with no extra action — the ViewModel half of that contract
 * is that every step PUBLISHES the new entry's Video preview (the player
 * auto-starts whatever preview is published); a step that silently keeps
 * the old preview breaks it.
 */
class DesktopArchiveVideoSwitchingTest {

    private val videoBytes = listOf(
        ByteArray(96) { 1 },
        ByteArray(96) { 2 },
        ByteArray(96) { 3 },
        ByteArray(96) { 4 }
    )

    private val entryData = listOf(
        "vids/one.mp4" to videoBytes[0],
        "vids/two.mp4" to videoBytes[1],
        "vids/three.mp4" to videoBytes[2],
        "vids/four.mp4" to videoBytes[3]
    )

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

    private class RecordingDownloadWriter : DownloadWriter {
        @Volatile var fileName: String? = null
        @Volatile var bytes: ByteArray? = null
        override fun write(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            body: (OutputStream) -> Unit
        ): String {
            this.fileName = fileName
            val out = ByteArrayOutputStream()
            body(out)
            bytes = out.toByteArray()
            return "/Downloads/$fileName"
        }

        override fun openPartial(
            fileName: String,
            mimeType: String,
            folderPath: String?,
            token: String?
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
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertTrue("timed out waiting for: $description", condition())
    }

    private fun buildViewModel(zipBytes: ByteArray, file: DriveFile): PreviewViewModel {
        val telegram = archiveFakeTelegram(zipBytes)
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
            private val root = File.createTempFile("archive-videoswitch", "").also {
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

    @Test
    fun `four in-zip videos open and step through the unified sequence`() {
        val zipBytes = archiveZipBytes(entryData)
        val file = zipFile("vm-video-switch-zip")
        val viewModel = buildViewModel(zipBytes, file)
        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { viewModel.contentFor(file).collect {} }
        await("remote listing stored") { viewModel.uiState.value.archiveListings.containsKey(file.id) }
        val listing = viewModel.uiState.value.archiveListings.getValue(file.id)
        assertEquals(entryData.map { it.first }, listing.map { it.name })
        val sequence = archiveUnifiedPreviewIndices(listing, listing.indices.toList())
        assertEquals(listOf(0, 1, 2, 3), sequence)

        // Open the first video through the desktop video route.
        viewModel.previewArchiveVideoEntry(file, 0)
        await("first video preview") {
            (viewModel.uiState.value.archivePreview as? ArchiveEntryPreview.Video)
                ?.entryIndex == 0
        }
        await("busy cleared after open") { viewModel.uiState.value.archiveEntryBusyIndex == null }

        var current = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Video
        assertEquals("one.mp4", current.name)
        assertArrayEquals(videoBytes[0], File(current.path).readBytes())
        var currentTemp = File(current.path)
        assertTrue(currentTemp.exists())

        // Step +1 three times: videos 2, 3 and 4 must each be published with
        // their own name and bytes, the replaced temp deleted, busy cleared.
        val expectedNames = listOf("two.mp4", "three.mp4", "four.mp4")
        for (step in 1..3) {
            val stepped = viewModel.stepArchivePreview(file, +1, sequence)
            assertTrue("step $step was refused by stepArchivePreview", stepped)
            await("video preview at entry $step") {
                (viewModel.uiState.value.archivePreview as? ArchiveEntryPreview.Video)
                    ?.entryIndex == step
            }
            await("busy cleared after step $step") {
                viewModel.uiState.value.archiveEntryBusyIndex == null
            }
            current = viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Video
            assertEquals(expectedNames[step - 1], current.name)
            assertEquals(step, current.entryIndex)
            assertArrayEquals(videoBytes[step], File(current.path).readBytes())
            assertFalse(
                "step $step leaked the previous video's temp",
                currentTemp.exists()
            )
            currentTemp = File(current.path)
            assertTrue(currentTemp.exists())
        }

        // End of the sequence: no neighbour, step reports false, preview stays.
        assertFalse(viewModel.stepArchivePreview(file, +1, sequence))
        assertEquals(
            3,
            (viewModel.uiState.value.archivePreview as ArchiveEntryPreview.Video).entryIndex
        )

        viewModel.dismissArchivePreview()
        await("preview dismissed") { viewModel.uiState.value.archivePreview == null }
        assertFalse("dismiss leaked the last video temp", currentTemp.exists())
    }
}
