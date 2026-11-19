package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderTotalSizeTest {

    private val harness = SyncHarness()
    private val folders = harness.database.folderDao()
    private val files = harness.database.fileDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `folder totals include every descendant file and exclude trashed files`() = runBlocking {
        folders.upsert(folder("root", "Root"))
        folders.upsert(folder("mid", "Mid", parentId = "root"))
        folders.upsert(folder("leaf", "Leaf", parentId = "mid"))
        folders.upsert(folder("sibling", "Sibling"))
        files.upsert(file("a", "root", size = 100))
        files.upsert(file("b", "mid", size = 200))
        files.upsert(file("c", "leaf", size = 400))
        files.upsert(file("d", "sibling", size = 50))
        files.upsert(file("e", "leaf", size = 1_000).copy(trashedAt = 5L))

        val totals = folders.folderTotalSizes(TEST_CHAT).associate { it.folderId to it.totalBytes }

        assertEquals(700L, totals["root"])
        assertEquals(600L, totals["mid"])
        assertEquals(400L, totals["leaf"])
        assertEquals(50L, totals["sibling"])
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
