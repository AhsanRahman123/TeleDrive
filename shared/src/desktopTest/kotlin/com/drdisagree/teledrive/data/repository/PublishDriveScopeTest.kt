package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.FolderEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-38: the publish outbox is per-drive state. Field proof: after
 * restoring everything in the old drive and creating a new one, the
 * new drive's Home showed the OLD drive's 12,922-row backlog —
 * the outbox queries were global, so the new drive's banner counted,
 * its drains fetched and its local-only sweep resolved the old
 * drive's rows (the file publishes themselves always landed in the
 * old channel, because the publisher addresses each row's own chat).
 * These tests pin the scoping: a drain counts, fetches, sweeps and
 * folder-clears ONLY the active drive's rows, and a mid-drain switch
 * stops further fetches of the previous drive's rows.
 */
class PublishDriveScopeTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val files = database.fileDao()
    private val folders = database.folderDao()
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a drain on the active drive never counts fetches sweeps or publishes another drive's rows`() =
        runBlocking {
            files.upsertAll((1..3).map { boundRow("a-$it", TEST_CHAT, 1_000L + it) })
            files.upsertAll((1..5).map { boundRow("b-$it", OTHER_CHAT, 2_000L + it) })
            files.upsertAll((1..2).map { localOnlyRow("b-local-$it", OTHER_CHAT) })
            assertEquals(3, files.pendingPublishCount(TEST_CHAT))
            assertEquals(7, files.pendingPublishCount(OTHER_CHAT))

            val drained = drainer().drain(isStopped = { false })

            assertTrue(drained)
            assertEquals(3, telegram.editedCaptions.size)
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            assertEquals(7, files.pendingPublishCount(OTHER_CHAT))
            assertTrue(
                "the other drive's local-only rows are not swept by this drive's drain",
                files.byId("b-local-1")!!.pendingPublish
            )
            assertTrue(files.byId("b-1")!!.pendingPublish)
        }

    @Test
    fun `switching the active drive mid-drain stops fetches of the previous drive's rows`() =
        runBlocking {
            files.upsertAll((1..150).map { boundRow("a-$it", TEST_CHAT, 1_000L + it) })
            files.upsertAll((1..3).map { boundRow("b-$it", OTHER_CHAT, 2_000L + it) })
            var flipped = false

            drainer().drain(
                isStopped = { false },
                onProgress = { published, _ ->
                    if (!flipped && published >= 100) {
                        flipped = true
                        harness.settings.update { it.copy(storageChatId = OTHER_CHAT) }
                    }
                }
            )

            assertTrue(flipped)
            assertEquals(
                "the in-flight batch of the old drive finishes (its rows " +
                    "self-address to their own channel), then the drain " +
                    "follows the newly active drive",
                103, telegram.editedCaptions.size
            )
            assertEquals(50, files.pendingPublishCount(TEST_CHAT))
            assertEquals(0, files.pendingPublishCount(OTHER_CHAT))
        }

    @Test
    fun `the folder phase clears only the active drive's folder flags`() = runBlocking {
        folders.upsert(folder("fa", TEST_CHAT, pending = true))
        folders.upsert(folder("fb", OTHER_CHAT, pending = true))

        drainer().drain(isStopped = { false })

        assertFalse(folders.byId("fa")!!.pendingPublish)
        assertTrue(
            "the inactive drive's folder push is not swallowed by this drive's folder phase",
            folders.byId("fb")!!.pendingPublish
        )
        assertEquals(1, folders.pendingPublishCount(OTHER_CHAT))
    }

    @Test
    fun `marking folder state dirty touches only that drive's folders`() = runBlocking {
        folders.upsert(folder("fa", TEST_CHAT, pending = false))
        folders.upsert(folder("fb", OTHER_CHAT, pending = false))

        folders.markPendingPublish(OTHER_CHAT)

        assertFalse(folders.byId("fa")!!.pendingPublish)
        assertTrue(folders.byId("fb")!!.pendingPublish)
    }

    @Test
    fun `the observed pending counts are per drive`() = runBlocking {
        files.upsertAll((1..2).map { boundRow("a-$it", TEST_CHAT, 1_000L + it) })
        files.upsertAll((1..5).map { boundRow("b-$it", OTHER_CHAT, 2_000L + it) })

        assertEquals(2, files.observePendingPublishCount(TEST_CHAT).first())
        assertEquals(5, files.observePendingPublishCount(OTHER_CHAT).first())
    }

    private fun boundRow(id: String, chatId: Long, messageId: Long) = FileEntity(
        id = id,
        folderId = null,
        name = "$id.jpg",
        sizeBytes = 10,
        mimeType = "image/jpeg",
        category = FileCategory.IMAGE,
        localPath = null,
        contentHash = null,
        chatId = chatId,
        messageId = messageId,
        remoteFileId = "remote-$messageId",
        remoteUniqueId = "unique-$messageId",
        backupState = BackupState.BACKED_UP,
        pendingPublish = true,
        createdAt = 1_000,
        modifiedAt = 1_000,
        addedAt = 1_000
    )

    private fun localOnlyRow(id: String, chatId: Long) =
        boundRow(id, chatId, 0).copy(messageId = null, remoteFileId = null, remoteUniqueId = null)

    private fun folder(id: String, chatId: Long, pending: Boolean) = FolderEntity(
        id = id,
        chatId = chatId,
        parentId = null,
        name = id,
        createdAt = 1_000,
        modifiedAt = 1_000,
        changedAt = 1_000,
        pendingPublish = pending
    )

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
        database = database,
        transferDao = database.transferDao(),
        resolver = harness.resolver,
        reuploadEnqueuer = harness.reuploadEnqueuer,
        activeChannel = harness.activeChannel
    )

    private companion object {
        const val OTHER_CHAT = 78L
    }
}
