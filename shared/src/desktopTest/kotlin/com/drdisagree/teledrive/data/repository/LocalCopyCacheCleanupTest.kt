package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.LocalCleanup
import com.drdisagree.teledrive.core.files.LocalCopyDeleter
import com.drdisagree.teledrive.core.files.NoteStore
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.testing.unused
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCopyCacheCleanupTest {

    private val harness = SyncHarness()
    private val fileDao = harness.database.fileDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `free up space removes app copy and TDLib cache before clearing the database path`() =
        runBlocking {
            val local = localFile()
            fileDao.upsert(fileEntity(local))

            val result = repository().deleteLocalCopy(listOf("file-1"))
            val cleanup = (result as AppResult.Success<LocalCleanup>).value

            assertFalse(local.exists())
            assertEquals(listOf("remote-1"), harness.telegram.managedDownloadRemovalRequests)
            assertNull(fileDao.byId("file-1")?.localPath)
            assertEquals(1, cleanup.deletedCount)
        }

    @Test
    fun `failed TDLib cache removal keeps the database path for a later retry`() = runBlocking {
        val local = localFile()
        fileDao.upsert(fileEntity(local))
        harness.telegram.failManagedDownloadRemoval = true

        val first = repository().deleteLocalCopy(listOf("file-1"))
        val firstCleanup = (first as AppResult.Success<LocalCleanup>).value

        assertFalse(local.exists())
        assertEquals(local.absolutePath, fileDao.byId("file-1")?.localPath)
        assertEquals(0, firstCleanup.deletedCount)
        assertEquals(listOf("remote-1"), harness.telegram.managedDownloadRemovalRequests)

        harness.telegram.failManagedDownloadRemoval = false
        val retry = repository().deleteLocalCopy(listOf("file-1"))
        val retryCleanup = (retry as AppResult.Success<LocalCleanup>).value

        assertNull(fileDao.byId("file-1")?.localPath)
        assertEquals(listOf("remote-1", "remote-1"), harness.telegram.managedDownloadRemovalRequests)
        assertEquals(1, retryCleanup.deletedCount)
    }

    @Test
    fun `available offline files are preserved and never removed from TDLib`() = runBlocking {
        val local = localFile()
        fileDao.upsert(fileEntity(local).copy(isAvailableOffline = true))

        val result = repository().deleteLocalCopy(listOf("file-1"))
        val cleanup = (result as AppResult.Success<LocalCleanup>).value

        assertTrue(local.isFile)
        assertEquals(local.absolutePath, fileDao.byId("file-1")?.localPath)
        assertTrue(harness.telegram.managedDownloadRemovalRequests.isEmpty())
        assertEquals(1, cleanup.keptOffline)
        assertEquals(0, cleanup.deletedCount)
    }

    @Test
    fun `files without a remote ID are preserved instead of losing their only copy`() = runBlocking {
        val local = localFile()
        fileDao.upsert(fileEntity(local).copy(remoteFileId = null))

        val result = repository().deleteLocalCopy(listOf("file-1"))
        val cleanup = (result as AppResult.Success<LocalCleanup>).value

        assertTrue(local.isFile)
        assertEquals(local.absolutePath, fileDao.byId("file-1")?.localPath)
        assertTrue(harness.telegram.managedDownloadRemovalRequests.isEmpty())
        assertEquals(0, cleanup.deletedCount)
    }

    private fun repository() = FileRepositoryImpl(
        fileDao = fileDao,
        folderDao = harness.database.folderDao(),
        publishScheduler = harness.scheduler,
        localCopyDeleter = localCopyDeleter,
        telegramClient = harness.telegram,
        manifestCodec = harness.codec,
        folderPathResolver = harness.folderPaths,
        activeChannel = harness.activeChannel,
        noteStore = NoteStore(object : AppStoragePaths by harness.storagePaths {
            override val filesDir: File = harness.storagePaths.cacheDir
        }),
        streamCrypto = StreamCrypto(),
        wrappedKeyRepository = unused<WrappedKeyRepository>(),
        settingsRepository = harness.settings,
        fileImporter = unused<FileImporter>()
    )

    private val localCopyDeleter = object : LocalCopyDeleter {
        override fun delete(paths: List<String>): LocalCleanup {
            var deleted = 0
            for (path in paths) {
                val file = File(path)
                if (!file.exists() || file.delete()) deleted++
            }
            return LocalCleanup(deletedCount = deleted)
        }

        override fun isGone(path: String): Boolean = !File(path).exists()
    }

    private fun localFile(): File = File.createTempFile("teledrive-local-copy", ".bin").apply {
        writeBytes(byteArrayOf(1, 2, 3, 4))
    }

    private fun fileEntity(local: File) = FileEntity(
        id = "file-1",
        folderId = null,
        name = "file.bin",
        sizeBytes = local.length(),
        mimeType = "application/octet-stream",
        category = FileCategory.OTHER,
        localPath = local.absolutePath,
        contentHash = null,
        chatId = TEST_CHAT,
        messageId = 100L,
        remoteFileId = "remote-1",
        remoteUniqueId = "unique-1",
        backupState = BackupState.BACKED_UP,
        createdAt = 1L,
        modifiedAt = 1L,
        addedAt = 1L
    )
}
