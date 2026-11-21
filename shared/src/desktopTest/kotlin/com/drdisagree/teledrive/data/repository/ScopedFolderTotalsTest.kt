package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.FileImporter
import com.drdisagree.teledrive.core.files.NoteStore
import com.drdisagree.teledrive.data.local.dao.FolderDao
import com.drdisagree.teledrive.data.local.dao.FolderTotalSize
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.testing.unused
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round 11 (report 29 §6.1 + the first-Files-entry addition): a Files
 * listing used to run the WITH RECURSIVE totals walk over the ENTIRE
 * folder tree and files table — on every invalidation and on the first
 * entry into Files after a launch. Totals are now computed only for the
 * folders actually listed (their subtrees). These tests pin the scoped
 * query's correctness and prove, with a counting DAO, that the
 * repository's folder listing never calls the whole-tree variant.
 */
class ScopedFolderTotalsTest {

    private val harness = SyncHarness()
    private val folders = harness.database.folderDao()
    private val files = harness.database.fileDao()

    @After
    fun tearDown() = harness.close()

    private suspend fun seedTree() {
        folders.upsert(folder("root", "Root"))
        folders.upsert(folder("mid", "Mid", parentId = "root"))
        folders.upsert(folder("leaf", "Leaf", parentId = "mid"))
        folders.upsert(folder("sibling", "Sibling"))
        folders.upsert(folder("other", "Other"))
        folders.upsert(folder("otherChild", "OtherChild", parentId = "other"))
        files.upsert(file("a", "root", size = 100))
        files.upsert(file("b", "mid", size = 200))
        files.upsert(file("c", "leaf", size = 400))
        files.upsert(file("d", "sibling", size = 50))
        files.upsert(file("e", "otherChild", size = 9_000))
        files.upsert(file("f", "leaf", size = 1_000).copy(trashedAt = 5L))
    }

    @Test
    fun `scoped totals cover exactly the requested subtrees`() = runBlocking {
        seedTree()

        val scoped = folders.folderTotalSizesForRoots(TEST_CHAT, listOf("root", "sibling"))
            .associate { it.folderId to it.totalBytes }

        assertEquals(700L, scoped["root"])
        assertEquals(50L, scoped["sibling"])
        // Unrequested roots are absent — their subtrees were not walked
        // into the result (the whole-tree variant would have returned
        // "other" with its 9_000-byte child included).
        assertFalse(scoped.containsKey("other"))
        assertFalse(scoped.containsKey("mid"))
    }

    @Test
    fun `scoped totals match the whole-tree totals for the same roots`() = runBlocking {
        seedTree()

        val wholeTree = folders.folderTotalSizes(TEST_CHAT)
            .associate { it.folderId to it.totalBytes }
        val scoped = folders.folderTotalSizesForRoots(TEST_CHAT, listOf("root", "other"))
            .associate { it.folderId to it.totalBytes }

        assertEquals(wholeTree["root"], scoped["root"])
        assertEquals(wholeTree["other"], scoped["other"])
        assertEquals(9_000L, scoped["other"])
    }

    @Test
    fun `folder listing never calls the whole-tree totals query`() = runBlocking {
        seedTree()
        val counting = CountingFolderDao(harness.database.folderDao())
        val repository = FileRepositoryImpl(
            fileDao = files,
            folderDao = counting,
            filePartDao = harness.database.filePartDao(),
            publishScheduler = harness.scheduler,
            localCopyDeleter = unused(),
            telegramClient = harness.telegram,
            manifestCodec = harness.codec,
            folderPathResolver = harness.folderPaths,
            activeChannel = harness.activeChannel,
            noteStore = NoteStore(
                object : AppStoragePaths by harness.storagePaths {
                    override val filesDir: File = harness.storagePaths.cacheDir
                }
            ),
            streamCrypto = StreamCrypto(),
            wrappedKeyRepository = unused<WrappedKeyRepository>(),
            settingsRepository = harness.settings,
            fileImporter = unused<FileImporter>(),
            storagePaths = harness.storagePaths
        )

        val listed = withTimeout(15_000) {
            repository.observeFolders(
                parentId = null,
                showHidden = true,
                showArchived = true,
                sortField = FileSortField.NAME,
                sortDirection = SortDirection.ASCENDING
            ).first { it.isNotEmpty() }
        }

        assertEquals(0, counting.unscopedTotalsCalls)
        assertTrue(counting.scopedTotalsCalls >= 1)
        val byId = listed.associate { it.id to it.totalSizeBytes }
        assertEquals(700L, byId["root"])
        assertEquals(50L, byId["sibling"])
        assertEquals(9_000L, byId["other"])
    }

    /** Delegates everything; counts only the two totals entry points. */
    private class CountingFolderDao(
        private val delegate: FolderDao
    ) : FolderDao by delegate {
        var unscopedTotalsCalls = 0
        var scopedTotalsCalls = 0

        override fun observeFolderTotalSizes(chatId: Long?): Flow<List<FolderTotalSize>> {
            unscopedTotalsCalls++
            return delegate.observeFolderTotalSizes(chatId)
        }

        override fun observeFolderTotalSizesForRoots(
            chatId: Long?,
            rootIds: List<String>
        ): Flow<List<FolderTotalSize>> {
            scopedTotalsCalls++
            return delegate.observeFolderTotalSizesForRoots(chatId, rootIds)
        }
    }

    private fun folder(id: String, name: String, parentId: String? = null) = FolderEntity(
        id = id,
        chatId = TEST_CHAT,
        parentId = parentId,
        name = name,
        createdAt = 1_000,
        modifiedAt = 1_000,
        changedAt = 1_000
    )

    private fun file(id: String, folderId: String, size: Long) = FileEntity(
        id = id,
        folderId = folderId,
        name = "$id.jpg",
        sizeBytes = size,
        mimeType = "image/jpeg",
        category = FileCategory.IMAGE,
        localPath = null,
        contentHash = null,
        chatId = TEST_CHAT,
        messageId = null,
        remoteFileId = null,
        remoteUniqueId = null,
        backupState = BackupState.NONE,
        createdAt = 1_000,
        modifiedAt = 1_000,
        addedAt = 1_000
    )
}
