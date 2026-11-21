package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Item9SyncFixesTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val files = database.fileDao()
    private val folders = database.folderDao()
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `split-brain folders with spaced spellings merge and children are refiled`() = runBlocking {
        folders.upsert(folder("a", "roots", createdAt = 1_000))
        folders.upsert(folder("b", "r o o t s", createdAt = 2_000))
        folders.upsert(folder("c", " Music ", createdAt = 1_000))
        folders.upsert(folder("d", "Music", createdAt = 2_000))
        files.upsert(file("f1", "a"))
        files.upsert(file("f2", "b"))
        files.upsert(file("f3", "d"))

        harness.folderMerger.mergeDuplicates(TEST_CHAT)

        assertNotNull(folders.byId("a"))
        assertNull(folders.byId("b"))
        assertEquals("a", files.byId("f2")!!.folderId)
        // " Music " and "Music" differ only by surrounding whitespace.
        assertEquals(1, listOfNotNull(folders.byId("c"), folders.byId("d")).size)
        assertTrue(files.byId("f3")!!.folderId in listOf("c", "d"))
    }

    @Test
    fun `a permanently rejected caption edit keeps the file pending and its baseline`() =
        runBlocking {
            telegram.documents = listOf(document(20, harness.caption(manifest("f1", "a.jpg"))))
            harness.newSync().fullResync()
            val baselineBefore = files.byId("f1")!!.remoteBase
            files.rename("f1", "b.jpg", 5_000)
            files.markPendingPublish(listOf("f1"))
            telegram.onEditCaption = { throw TelegramException(400, "MESSAGE_ID_INVALID") }
            val failures = mutableListOf<String>()

            val drained = drainer().drain(
                isStopped = { false },
                onFailure = { fileId, _ -> failures += fileId }
            )

            // PC-32: a permanent rejection no longer fails the drain —
            // the row is skipped for this drain and surfaced instead, so
            // it cannot wedge the backlog behind it. What must never
            // change: the row stays pending and its baseline is intact.
            assertTrue(drained)
            assertEquals(listOf("f1"), failures)
            assertTrue(files.byId("f1")!!.pendingPublish)
            assertEquals(baselineBefore, files.byId("f1")!!.remoteBase)
        }

    @Test
    fun `a local-only file's flag resolves without sealing it as published`() = runBlocking {
        files.upsert(file("local1", null).copy(pendingPublish = true))

        // PC-32 (round 8 correction): a local-only row has no channel
        // caption to correct — its manifest ships as the upload's
        // initial caption — so the drain RESOLVES its flag instead of
        // leaving it as permanent unreachable backlog (the round-7
        // keep-the-flag behaviour is what stalled the user's count at
        // the local-only floor). What must never change: nothing is
        // sealed — no baseline is adopted for a publish that did not
        // happen, and the row itself stays, still local-only.
        val drained = drainer().drain(isStopped = { false })

        assertTrue(drained)
        assertFalse(files.byId("local1")!!.pendingPublish)
        assertNull(files.byId("local1")!!.remoteBase)
        assertNull(files.byId("local1")!!.messageId)
    }

    private fun drainer() = PublishOutboxDrainer(
        fileDao = files,
        folderDao = folders,
        pendingDeleteDao = database.pendingDeleteDao(),
        telegramClient = telegram,
        manifestPublisher = FileManifestPublisher(
            telegram, harness.codec, harness.folderPaths, database.filePartDao()
        ),
        folderStateSynchronizer = harness.folderState,
        tombstoneDao = database.folderTombstoneDao(),
        database = database
    )

    private fun folder(id: String, name: String, createdAt: Long) = FolderEntity(
        id = id,
        chatId = TEST_CHAT,
        parentId = null,
        name = name,
        createdAt = createdAt,
        modifiedAt = createdAt,
        changedAt = createdAt
    )

    private fun file(id: String, folderId: String?) = FileEntity(
        id = id,
        folderId = folderId,
        name = "$id.jpg",
        sizeBytes = 10,
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
