package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.telegram.MessageChange
import com.drdisagree.teledrive.data.local.entity.PendingDeleteEntity
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderEntry
import com.drdisagree.teledrive.data.remote.telegram.RemoteFolderState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSyncTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val telegram = harness.telegram
    private val sync = harness.newSync()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a rename on another device is applied without publishing anything back`() = runBlocking {
        telegram.documents = listOf(document(20, harness.caption(manifest("f1", "a.jpg"))))
        sync.fullResync()

        telegram.documents = listOf(
            document(20, harness.caption(manifest("f1", "b.jpg", modifiedAt = 3_000)))
        )
        sync.applyRemoteChanges(TEST_CHAT, setOf(20), emptySet())

        val row = database.fileDao().byId("f1")!!
        assertEquals("b.jpg", row.name)
        assertFalse(row.pendingPublish)
        assertEquals(0, harness.scheduler.kicks)
        assertTrue(telegram.editedCaptions.isEmpty())
        assertTrue(telegram.uploads.isEmpty())
    }

    @Test
    fun `a pending local move survives a stale remote manifest when the local clock is behind`() = runBlocking {
        telegram.documents = listOf(
            document(20, harness.caption(manifest("f1", "a.jpg", modifiedAt = 50_000)))
        )
        sync.fullResync()

        database.folderDao().upsert(
            com.drdisagree.teledrive.data.local.entity.FolderEntity(
                id = "destination",
                chatId = TEST_CHAT,
                parentId = null,
                name = "Destination",
                createdAt = 1_000,
                modifiedAt = 1_000,
                changedAt = 1_000
            )
        )
        val files = database.fileDao()
        files.moveAndMarkPendingPublish(listOf("f1"), "destination", modifiedAt = 1_000)

        // Legacy rows may not have a remote merge baseline. In that case timestamps alone
        // must not let a stale remote folderId overwrite a locally-pending move.
        files.byId("f1")!!.let { files.upsert(it.copy(remoteBase = null)) }

        sync.applyRemoteChanges(TEST_CHAT, setOf(20), emptySet())

        val moved = files.byId("f1")!!
        assertEquals("destination", moved.folderId)
        assertTrue(moved.pendingPublish)
    }

    @Test
    fun `a stale manifest after successful publish cannot revert a local folder move`() = runBlocking {
        telegram.documents = listOf(
            document(20, harness.caption(manifest("f1", "a.jpg", modifiedAt = 50_000)))
        )
        sync.fullResync()

        database.folderDao().upsert(
            com.drdisagree.teledrive.data.local.entity.FolderEntity(
                id = "destination",
                chatId = TEST_CHAT,
                parentId = null,
                name = "Destination",
                createdAt = 1_000,
                modifiedAt = 1_000,
                changedAt = 1_000
            )
        )
        val files = database.fileDao()
        files.moveAndMarkPendingPublish(listOf("f1"), "destination", modifiedAt = 100_000)

        // Simulate the outbox accepting the new manifest: the published baseline now records
        // the destination folder and pendingPublish has been cleared, but a delayed old
        // Telegram event still returns the original manifest.
        val published = files.byId("f1")!!
        files.setRemoteBase("f1", FileBase.of(published).toText())
        files.clearPendingPublish("f1")

        sync.applyRemoteChanges(TEST_CHAT, setOf(20), emptySet())

        val unchanged = files.byId("f1")!!
        assertEquals("destination", unchanged.folderId)
        assertEquals(100_000L, unchanged.modifiedAt)
        assertFalse(unchanged.pendingPublish)
    }

    @Test
    fun `an upload from another device appears`() = runBlocking {
        telegram.documents = listOf(document(30, harness.caption(manifest("new", "new.jpg"))))

        sync.applyRemoteChanges(TEST_CHAT, setOf(30), emptySet())

        assertEquals(30L, database.fileDao().byId("new")!!.messageId)
    }

    @Test
    fun `a new folder document from another device is pulled in`() = runBlocking {
        telegram.publishFolderState(
            RemoteFolderState(
                folders = listOf(
                    RemoteFolderEntry(
                        id = "trips",
                        name = "Trips",
                        createdAt = 1,
                        modifiedAt = System.currentTimeMillis()
                    )
                )
            ),
            messageId = 50
        )

        sync.applyRemoteChanges(TEST_CHAT, setOf(50), emptySet())

        assertEquals("Trips", database.folderDao().byId("trips")!!.name)
        assertEquals(0, database.folderDao().pendingPublishCount())
        assertTrue(telegram.uploads.isEmpty())
    }

    @Test
    fun `a permanent deletion elsewhere detaches only that file`() = runBlocking {
        telegram.documents = listOf(
            document(20, harness.caption(manifest("keep", "keep.jpg"))),
            document(21, harness.caption(manifest("gone", "gone.jpg")))
        )
        sync.fullResync()

        sync.applyRemoteChanges(TEST_CHAT, emptySet(), setOf(21))

        assertNull(database.fileDao().byId("gone"))
        assertNotNull(database.fileDao().byId("keep"))
    }

    @Test
    fun `messages this device is deleting are left alone`() = runBlocking {
        telegram.documents = listOf(document(21, harness.caption(manifest("f1", "a.jpg"))))
        sync.fullResync()
        database.pendingDeleteDao().upsertAll(listOf(PendingDeleteEntity(TEST_CHAT, 21, "f1")))
        telegram.documents = listOf(
            document(21, harness.caption(manifest("f1", "renamed.jpg", modifiedAt = 3_000)))
        )

        sync.applyRemoteChanges(TEST_CHAT, setOf(21), setOf(21))

        assertEquals("a.jpg", database.fileDao().byId("f1")!!.name)
    }

    @Test
    fun `following applies a burst in one pass and closes the chat when stopped`() = runBlocking {
        val names = (1..40).map { "f$it" }
        telegram.documents = names.mapIndexed { index, id ->
            document(100L + index, harness.caption(manifest(id, "$id.jpg")))
        }
        sync.fullResync()
        telegram.documents = names.mapIndexed { index, id ->
            document(100L + index, harness.caption(manifest(id, "$id-renamed.jpg", modifiedAt = 3_000)))
        }

        val following = launch { sync.followRemoteChanges() }
        telegram.changes.subscriptionCount.first { it > 0 }
        names.indices.forEach { telegram.changes.emit(MessageChange.Updated(100L + it)) }
        delay(3_000)
        following.cancel()
        following.join()

        assertTrue(names.all { database.fileDao().byId(it)!!.name == "$it-renamed.jpg" })
        assertEquals(1, telegram.openedChats)
        assertEquals(1, telegram.closedChats)
        assertEquals(0, harness.scheduler.kicks)
    }
}
