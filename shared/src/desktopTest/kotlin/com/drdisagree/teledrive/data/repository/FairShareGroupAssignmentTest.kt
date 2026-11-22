package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppResult
import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramLimits
import com.drdisagree.teledrive.core.transfer.CountingBackupSessionTracker
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.unused
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-40 (1.6.18): where fair-share groups come from. One user operation
 * (folder import, one selection, one backup session, one startup
 * requeue) must land its transfers in ONE group (batchId); an enqueue
 * that names no group mints a fresh one-item group — and never the
 * legacy NULL group, which belongs to pre-1.6.18 rows alone.
 */
class FairShareGroupAssignmentTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val paths = harness.storagePaths
    private val sourceDir: File = Files.createTempDirectory("pc40-groups").toFile()

    @After
    fun tearDown() {
        harness.close()
        sourceDir.deleteRecursively()
    }

    private fun transfers() = TransferRepositoryImpl(
        transferDao = database.transferDao(),
        fileDao = database.fileDao(),
        telegramClient = object : TelegramClient by unused() {
            override suspend fun getLimits() = TelegramLimits.REGULAR
        },
        transferScheduler = object : TransferScheduler {
            override fun kick(allowMetered: Boolean) = Unit
            override fun rekick(allowMetered: Boolean) = Unit
        },
        settingsRepository = harness.settings,
        validateUpload = ValidateUploadUseCase(),
        storageInspector = StorageInspector(paths),
        backupSessionTracker = CountingBackupSessionTracker(
            database.transferDao(),
            database.backupDao()
        ),
        storagePaths = paths
    )

    private suspend fun localFile(id: String, name: String): String {
        val source = File(sourceDir, "$id-$name")
        source.writeBytes(ByteArray(64) { 7 })
        database.fileDao().upsert(
            FileEntity(
                id = id,
                folderId = null,
                name = name,
                sizeBytes = 64,
                mimeType = "application/octet-stream",
                category = FileCategory.OTHER,
                localPath = source.absolutePath,
                contentHash = null,
                chatId = TEST_CHAT,
                messageId = null,
                remoteFileId = null,
                remoteUniqueId = null,
                backupState = BackupState.NONE,
                partCount = 1,
                createdAt = 1,
                modifiedAt = 1,
                addedAt = 1
            )
        )
        return id
    }

    private suspend fun remoteFile(id: String, name: String): String {
        database.fileDao().upsert(
            FileEntity(
                id = id,
                folderId = null,
                name = name,
                sizeBytes = 64,
                mimeType = "application/octet-stream",
                category = FileCategory.OTHER,
                localPath = null,
                contentHash = null,
                chatId = TEST_CHAT,
                messageId = 42,
                remoteFileId = "rf-$id",
                remoteUniqueId = "uq-$id",
                backupState = BackupState.BACKED_UP,
                partCount = 1,
                createdAt = 1,
                modifiedAt = 1,
                addedAt = 1
            )
        )
        return id
    }

    private suspend fun batchOf(fileId: String): String? {
        val ids = database.transferDao().unfinishedIdsForFiles(listOf(fileId))
        assertEquals("exactly one unfinished transfer for $fileId", 1, ids.size)
        return database.transferDao().byId(ids.single())!!.batchId
    }

    @Test
    fun `one import shares the batch id it was given`() = runBlocking {
        val repo = transfers()
        val ids = (1..3).map { localFile("imp-$it", "f$it.bin") }
        val generation = repo.uploadQueueGeneration()

        ids.forEach { repo.enqueueUploadForGeneration(it, generation, batchId = "import-1") }

        ids.forEach { assertEquals("import-1", batchOf(it)) }
    }

    @Test
    fun `an enqueue with no group mints a fresh one-item group`() = runBlocking {
        val repo = transfers()
        val first = localFile("solo-a", "a.bin")
        val second = localFile("solo-b", "b.bin")

        repo.enqueueUpload(first)
        repo.enqueueUpload(second)

        val firstBatch = batchOf(first)
        val secondBatch = batchOf(second)
        assertNotNull("a fresh group is minted, never the legacy NULL group", firstBatch)
        assertNotNull(secondBatch)
        assertNotEquals(firstBatch, secondBatch)
    }

    @Test
    fun `a backup batch groups under its session id across calls`() = runBlocking {
        val repo = transfers()
        val first = localFile("bak-a", "a.bin")
        val second = localFile("bak-b", "b.bin")
        val third = localFile("bak-c", "c.bin")

        assertEquals(
            AppResult.Success(2),
            repo.enqueueBackupBatch(listOf(first, second), "session-9")
        )
        assertEquals(
            AppResult.Success(1),
            repo.enqueueBackupBatch(listOf(third), "session-9")
        )

        assertEquals("session-9", batchOf(first))
        assertEquals("session-9", batchOf(second))
        assertEquals("session-9", batchOf(third))
    }

    @Test
    fun `the startup pending-uploads requeue forms one group`() = runBlocking {
        val repo = transfers()
        val first = localFile("pend-a", "a.bin")
        val second = localFile("pend-b", "b.bin")

        assertEquals(AppResult.Success(2), repo.enqueuePendingUploads())

        val group = batchOf(first)
        assertNotNull(group)
        assertEquals(group, batchOf(second))
    }

    @Test
    fun `downloads carry the group they were enqueued with`() = runBlocking {
        val repo = transfers()
        val grouped = remoteFile("dl-a", "a.bin")
        val solo = remoteFile("dl-b", "b.bin")

        assertTrue(repo.enqueueDownload(grouped, batchId = "selection-1") is AppResult.Success)
        assertTrue(repo.enqueueDownload(solo) is AppResult.Success)

        assertEquals("selection-1", batchOf(grouped))
        assertNotNull(batchOf(solo))
        assertNotEquals("selection-1", batchOf(solo))
    }
}
