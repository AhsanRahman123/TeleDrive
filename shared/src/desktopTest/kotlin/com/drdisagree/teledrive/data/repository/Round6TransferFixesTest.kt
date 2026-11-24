package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.files.StorageInspector
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramLimits
import com.drdisagree.teledrive.core.transfer.CountingBackupSessionTracker
import com.drdisagree.teledrive.core.transfer.TransferScheduler
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.usecase.ValidateUploadUseCase
import com.drdisagree.teledrive.testing.unused
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 6 (PC-26) regression pins, at the repository level:
 *
 *  - Enqueueing the same large tree twice — sequentially or concurrently —
 *    yields exactly ONE unfinished transfer per file. (The meltdown's
 *    queue could only fill with repeats if duplicate FILE rows existed;
 *    these tests pin the transfer layer's dedupe, and the import layer's
 *    matching is pinned by the localOnlyMatch test below.)
 *  - Cancel removes the cancelled item's engine-staged artifacts
 *    (encryption staging copy + split-upload parts) and nothing else's.
 *  - Cancel-all sweeps every staged artifact.
 *  - Startup recovery sweeps staged artifacts whose file has no
 *    unfinished transfer, and keeps the ones a resumable transfer owns.
 */
class Round6TransferFixesTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val paths = harness.storagePaths
    private val sourceDir: File = Files.createTempDirectory("round6-sources").toFile()

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
        storagePaths = paths,
        archiveExtractionJobs = com.drdisagree.teledrive.core.archive.ArchiveExtractionJobs()
    )

    /** A real local file + its still-local row, as a folder import creates them. */
    private suspend fun localFile(id: String, name: String, sizeBytes: Int): String {
        val source = File(sourceDir, "$id-$name")
        source.writeBytes(ByteArray(sizeBytes) { 7 })
        database.fileDao().upsert(
            FileEntity(
                id = id,
                folderId = null,
                name = name,
                sizeBytes = sizeBytes.toLong(),
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

    private suspend fun unfinishedCount(fileIds: List<String>): Int =
        database.transferDao().unfinishedIdsForFiles(fileIds).size

    @Test
    fun `enqueueing the same large tree twice yields one transfer per file`() = runBlocking {
        val repo = transfers()
        val ids = (1..40).map { localFile("dup-$it", "file-$it.bin", 64) }
        val generation = repo.uploadQueueGeneration()

        ids.forEach { repo.enqueueUploadForGeneration(it, generation) }
        // The meltdown's second pass: the same tree enqueued again.
        ids.forEach { repo.enqueueUploadForGeneration(it, generation) }
        // And once more through the plain entry point.
        ids.forEach { repo.enqueueUpload(it) }

        assertEquals(40, unfinishedCount(ids))
    }

    @Test
    fun `concurrent enqueue of the same tree yields one transfer per file`() = runBlocking {
        val repo = transfers()
        val ids = (1..30).map { localFile("conc-$it", "file-$it.bin", 64) }
        val generation = repo.uploadQueueGeneration()

        coroutineScope {
            (1..3).map {
                async { ids.forEach { id -> repo.enqueueUploadForGeneration(id, generation) } }
            }.awaitAll()
        }

        assertEquals(30, unfinishedCount(ids))
    }

    @Test
    fun `cancel removes the cancelled item's staged artifacts and only those`() = runBlocking {
        val repo = transfers()
        val cancelled = localFile("cancel-a", "a.bin", 64)
        val bystander = localFile("cancel-b", "b.bin", 64)
        repo.enqueueUpload(cancelled)
        val transferId = database.transferDao().unfinishedIdsForFiles(listOf(cancelled)).single()

        val stagingDir = File(paths.cacheDir, "staging").apply { mkdirs() }
        val partsDir = File(paths.cacheDir, "parts").apply { mkdirs() }
        val stagedA = File(stagingDir, "$cancelled.tde").apply { writeBytes(ByteArray(8)) }
        val partA0 = File(partsDir, "$cancelled.0.part").apply { writeBytes(ByteArray(8)) }
        val partA1 = File(partsDir, "$cancelled.1.part").apply { writeBytes(ByteArray(8)) }
        val stagedB = File(stagingDir, "$bystander.tde").apply { writeBytes(ByteArray(8)) }

        repo.cancel(transferId)

        assertEquals(
            TransferState.CANCELLED,
            database.transferDao().byId(transferId)?.state
        )
        assertFalse("cancelled item's staging copy must be deleted", stagedA.exists())
        assertFalse("cancelled item's part 0 must be deleted", partA0.exists())
        assertFalse("cancelled item's part 1 must be deleted", partA1.exists())
        assertTrue("another file's staging copy must survive", stagedB.exists())
    }

    @Test
    fun `cancel all sweeps every staged artifact`() = runBlocking {
        val repo = transfers()
        val first = localFile("all-a", "a.bin", 64)
        val second = localFile("all-b", "b.bin", 64)
        repo.enqueueUpload(first)
        repo.enqueueUpload(second)

        val stagingDir = File(paths.cacheDir, "staging").apply { mkdirs() }
        val partsDir = File(paths.cacheDir, "parts").apply { mkdirs() }
        File(stagingDir, "$first.tde").writeBytes(ByteArray(8))
        File(stagingDir, "$second.tde").writeBytes(ByteArray(8))
        File(partsDir, "$first.0.part").writeBytes(ByteArray(8))

        repo.cancelAll()

        assertEquals(0, unfinishedCount(listOf(first, second)))
        assertTrue(stagingDir.listFiles().orEmpty().isEmpty())
        assertTrue(partsDir.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `recovery sweeps staging of finished files and keeps resumable ones`() = runBlocking {
        val repo = transfers()
        val resumable = localFile("rec-a", "a.bin", 64)
        val orphan = localFile("rec-b", "b.bin", 64)
        repo.enqueueUpload(resumable)

        val stagingDir = File(paths.cacheDir, "staging").apply { mkdirs() }
        val kept = File(stagingDir, "$resumable.tde").apply { writeBytes(ByteArray(8)) }
        val swept = File(stagingDir, "$orphan.tde").apply { writeBytes(ByteArray(8)) }

        repo.recoverOrphanedTransfers()

        assertTrue("a queued transfer's staging copy is kept for its resume", kept.exists())
        assertFalse("staging with no unfinished transfer is a kill leftover", swept.exists())
    }

    @Test
    fun `localOnlyMatch finds only the still local row for the same folder name and size`() =
        runBlocking {
            val dao = database.fileDao()
            // files.folderId is a foreign key: the folder row must exist.
            database.folderDao().upsert(
                com.drdisagree.teledrive.data.local.entity.FolderEntity(
                    id = "folder-1",
                    chatId = TEST_CHAT,
                    parentId = null,
                    name = "Folder",
                    createdAt = 1,
                    modifiedAt = 1
                )
            )
            suspend fun row(
                id: String,
                folderId: String?,
                name: String,
                size: Long,
                messageId: Long?,
                trashedAt: Long?,
                localPath: String?
            ) {
                dao.upsert(
                    FileEntity(
                        id = id,
                        folderId = folderId,
                        name = name,
                        sizeBytes = size,
                        mimeType = "application/octet-stream",
                        category = FileCategory.OTHER,
                        localPath = localPath,
                        contentHash = null,
                        chatId = TEST_CHAT,
                        messageId = messageId,
                        remoteFileId = null,
                        remoteUniqueId = null,
                        backupState = BackupState.NONE,
                        partCount = 1,
                        createdAt = 1,
                        modifiedAt = 1,
                        addedAt = 1,
                        trashedAt = trashedAt
                    )
                )
            }

            row("match", "folder-1", "a.jpg", 100, null, null, "/copies/a.jpg")
            row("remote", "folder-1", "b.jpg", 100, 42, null, "/copies/b.jpg")
            row("trashed", "folder-1", "c.jpg", 100, null, 5, "/copies/c.jpg")
            row("pathless", "folder-1", "d.jpg", 100, null, null, null)

            assertNotNull(dao.localOnlyMatch("folder-1", "a.jpg", 100, TEST_CHAT))
            assertNull("size must match", dao.localOnlyMatch("folder-1", "a.jpg", 101, TEST_CHAT))
            assertNull("folder must match", dao.localOnlyMatch("folder-2", "a.jpg", 100, TEST_CHAT))
            assertNull("a row with a remote copy is not an import to resume",
                dao.localOnlyMatch("folder-1", "b.jpg", 100, TEST_CHAT))
            assertNull("a trashed row is not an import to resume",
                dao.localOnlyMatch("folder-1", "c.jpg", 100, TEST_CHAT))
            assertNull("a row without a local copy is not an import to resume",
                dao.localOnlyMatch("folder-1", "d.jpg", 100, TEST_CHAT))
            assertNull("root import matches only root rows",
                dao.localOnlyMatch(null, "a.jpg", 100, TEST_CHAT))
        }
}
