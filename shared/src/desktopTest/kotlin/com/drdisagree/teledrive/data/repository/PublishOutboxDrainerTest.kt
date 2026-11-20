package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-32: the publish drain wedged permanently on the user's real
 * backlog. These tests reproduce the exact shapes that froze it — a
 * head of local-only rows, a permanently rejected row, a cancel in the
 * middle of a large backlog — against the real DAO and publisher.
 */
class PublishOutboxDrainerTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val files = database.fileDao()
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `local-only rows at the head cannot block the publishable backlog behind them`() =
        runBlocking {
            // The user's frozen 4370: meltdown-era import registrations
            // whose uploads were cancelled before a channel message ever
            // existed. They are the OLDEST pending rows, so the old
            // unordered fetch met them first, in every batch, forever.
            val localOnly = (1..150).map { localOnlyFile("local-$it") }
            val publishable = (1..50).map { publishableFile("pub-$it", 1_000L + it) }
            files.upsertAll(localOnly + publishable)
            assertEquals(200, files.pendingPublishCount())

            val drained = drainer().drain(isStopped = { false })

            assertTrue("the drain must complete behind a local-only head", drained)
            assertEquals(50, telegram.editedCaptions.size)
            // Lossless: the local-only rows keep their flag — they publish
            // once their upload lands — and everything behind them cleared.
            assertEquals(150, files.pendingPublishCount())
            assertTrue(files.byId("local-1")!!.pendingPublish)
            assertFalse(files.byId("pub-1")!!.pendingPublish)
            assertFalse(files.byId("pub-50")!!.pendingPublish)
        }

    @Test
    fun `a local-only row publishes once its upload lands`() = runBlocking {
        files.upsert(localOnlyFile("late-1"))
        assertTrue(drainer().drain(isStopped = { false }))
        assertTrue(files.byId("late-1")!!.pendingPublish)

        // The upload lands: the row gains its channel message.
        files.upsert(localOnlyFile("late-1").copy(chatId = TEST_CHAT, messageId = 7_777L))

        assertTrue(drainer().drain(isStopped = { false }))
        assertFalse(files.byId("late-1")!!.pendingPublish)
        assertEquals(setOf(7_777L), telegram.editedCaptions.keys)
    }

    @Test
    fun `a permanently rejected row does not block the rows behind it and is surfaced`() =
        runBlocking {
            val poison = publishableFile("poison", 4_242L)
            val behind = (1..120).map { publishableFile("ok-$it", 2_000L + it) }
            files.upsertAll(listOf(poison) + behind)
            telegram.onEditCaption = { messageId ->
                if (messageId == 4_242L) {
                    throw TelegramException(400, "MESSAGE_ID_INVALID")
                }
            }
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())

            val drained = drainer().drain(
                isStopped = { false },
                onFailure = { fileId, _ -> failures += fileId }
            )

            assertTrue("one rejected caption must not fail the whole drain", drained)
            assertEquals(120, telegram.editedCaptions.size)
            // The rejected row stays pending (never silently dropped) and
            // is the only one left; the failure was surfaced by id.
            assertEquals(1, files.pendingPublishCount())
            assertTrue(files.byId("poison")!!.pendingPublish)
            assertEquals(listOf("poison"), failures)
        }

    @Test
    fun `cancel mid-drain leaves exactly the unpublished remainder pending and resumable`() =
        runBlocking {
            val rows = (1..250).map { publishableFile("bulk-$it", 3_000L + it) }
            files.upsertAll(rows)
            val stop = AtomicBoolean(false)
            val publishedCount = AtomicInteger(0)

            val drained = drainer().drain(
                isStopped = { stop.get() },
                onProgress = { published, _ ->
                    publishedCount.set(published)
                    if (published >= 10) stop.set(true)
                }
            )

            assertFalse("a stopped drain reports itself unfinished", drained)
            val publishedSoFar = publishedCount.get()
            assertTrue("some files published before the stop", publishedSoFar >= 10)
            // Lossless: every published row cleared, every other row kept
            // its flag — no partial state in between.
            assertEquals(250 - publishedSoFar, files.pendingPublishCount())

            // Sync now after a cancel resumes exactly the remainder.
            assertTrue(drainer().drain(isStopped = { false }))
            assertEquals(0, files.pendingPublishCount())
            assertEquals(250, telegram.editedCaptions.size)
        }

    private fun localOnlyFile(id: String) = FileEntity(
        id = id,
        folderId = null,
        name = "$id.jpg",
        sizeBytes = 10,
        mimeType = "image/jpeg",
        category = FileCategory.IMAGE,
        localPath = null,
        contentHash = null,
        chatId = null,
        messageId = null,
        remoteFileId = null,
        remoteUniqueId = null,
        backupState = BackupState.NONE,
        pendingPublish = true,
        createdAt = 1_000,
        modifiedAt = 1_000,
        addedAt = 1_000
    )

    private fun publishableFile(id: String, messageId: Long) =
        localOnlyFile(id).copy(chatId = TEST_CHAT, messageId = messageId)

    private fun drainer() = PublishOutboxDrainer(
        fileDao = files,
        folderDao = database.folderDao(),
        pendingDeleteDao = database.pendingDeleteDao(),
        telegramClient = telegram,
        manifestPublisher = FileManifestPublisher(
            telegram, harness.codec, harness.folderPaths, database.filePartDao()
        ),
        folderStateSynchronizer = harness.folderState,
        tombstoneDao = database.folderTombstoneDao(),
        database = database
    )
}
