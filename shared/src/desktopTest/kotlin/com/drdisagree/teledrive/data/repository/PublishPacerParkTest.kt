package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.core.telegram.TelegramPacer
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.PendingDeleteEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-36: the drainer half of the pacer-park fix. PC-35's drainer
 * tests fake Telegram at the client seam and report their own waits;
 * the field failure was a wait BELOW that seam — the pacer parking
 * on a flood deadline set before the drain started, so no
 * rate-limit result ever reached the drainer and its countdown,
 * budgets and wait-cut never engaged. These tests reproduce that
 * shape at the seam: the client's edit/delete calls tick the
 * caller's waiting callback (one of them through a real
 * [TelegramPacer]) before they may proceed.
 */
class PublishPacerParkTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val files = database.fileDao()
    private val telegram = harness.telegram

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a flood deadline set before the drain surfaces waiting from the first publish and ends the run waiting`() =
        runBlocking {
            // The user's video, at the seam: every publish attempt
            // meets an account flood window that predates the drain.
            // Post-PC-36 the client ticks the wait and then surfaces
            // the 429 (the pacer's cap throw); pre-fix this call
            // simply never returned and nothing fired at all.
            val rows = (1..3).map { publishableFile("fld-$it", 7_000L + it) }
            files.upsertAll(rows)
            telegram.onEditWaiting = { _, onWaiting ->
                onWaiting(870)
                throw TelegramException(429, "FLOOD_WAIT: retry after 870", 870)
            }
            val waiting = Collections.synchronizedList(mutableListOf<Int>())
            val progress = Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

            val drained = drainer().drain(
                isStopped = { false },
                onProgress = { published, total -> progress += published to total },
                onWaiting = { waiting += it }
            )

            assertFalse(
                "the run must end in the waiting state, not complete or hang",
                drained
            )
            assertTrue(
                "the wait surfaced from the first publish attempts",
                waiting.contains(870)
            )
            assertTrue(
                "no progress may be reported against the flood",
                progress.all { it.first == 0 }
            )
            assertTrue(telegram.editedCaptions.isEmpty())
            assertEquals(
                "every file stays pending for the next run",
                3,
                files.pendingPublishCount(TEST_CHAT)
            )
        }

    @Test
    fun `a real pacer park in front of the first publish ticks the drain and then lets it finish`() =
        runBlocking {
            // The genuine article: a real TelegramPacer whose flood
            // deadline (~2 s) is already in effect when the drain
            // starts. Each edit rides the pacer (tick, wait, proceed)
            // exactly as DesktopTelegramClient now threads it.
            val pacer = TelegramPacer()
            pacer.recordFloodWait(1)
            telegram.onEditWaiting = { _, onWaiting -> pacer.pacedEdit({ }, onWaiting) }
            val rows = (1..2).map { publishableFile("park-$it", 8_000L + it) }
            files.upsertAll(rows)
            val waiting = Collections.synchronizedList(mutableListOf<Int>())

            val drained = drainer().drain(
                isStopped = { false },
                onWaiting = { waiting += it }
            )

            assertTrue("the drain completes once the window passes", drained)
            assertTrue(
                "the pacer park ticked the drain's waiting callback",
                waiting.isNotEmpty()
            )
            assertEquals(2, telegram.editedCaptions.size)
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
        }

    @Test
    fun `delete replay surfaces a creation lane wait through the drain`() =
        runBlocking {
            // The creation lane (awaitClearance via deleteMessages)
            // had the identical invisible park, in the phase before
            // the first progress callback. Its ticks must reach the
            // drain's waiting callback too.
            val row = publishableFile("del-1", 9_001L)
            files.upsert(row)
            database.pendingDeleteDao().upsertAll(
                listOf(
                    PendingDeleteEntity(
                        chatId = TEST_CHAT,
                        messageId = 9_001L,
                        fileId = "del-1"
                    )
                )
            )
            telegram.onDeleteWaiting = { onWaiting -> onWaiting(42) }
            val waiting = Collections.synchronizedList(mutableListOf<Int>())

            val drained = drainer().drain(
                isStopped = { false },
                onWaiting = { waiting += it }
            )

            assertTrue(drained)
            assertTrue("the delete lane's wait ticked the drain", waiting.contains(42))
            assertEquals(listOf(9_001L), telegram.deletedMessages)
            assertEquals(0, database.pendingDeleteDao().oldest(10).size)
        }

    @Test
    fun `a rate limited delete replay defers the deletes instead of dropping them`() =
        runBlocking {
            // Second-pass finding (PC-36): replayDeletes judged
            // permanence on the raw code, and 429 sits inside its
            // 400..499 range — nearly unreachable while the pacer
            // parked instead of throwing, routine now that the pacer
            // caps and throws. A 429 here must defer (rows and
            // pending entries survive), never drop.
            val row = publishableFile("del-429", 9_101L)
            files.upsert(row)
            database.pendingDeleteDao().upsertAll(
                listOf(
                    PendingDeleteEntity(
                        chatId = TEST_CHAT,
                        messageId = 9_101L,
                        fileId = "del-429"
                    )
                )
            )
            telegram.onDeleteWaiting = { onWaiting ->
                onWaiting(870)
                throw TelegramException(429, "FLOOD_WAIT: retry after 870", 870)
            }
            val failures = Collections.synchronizedList(mutableListOf<String>())

            drainer().drain(
                isStopped = { false },
                onFailure = { fileId, _ -> failures += fileId }
            )

            assertEquals(
                "the delete is deferred, not dropped",
                1,
                database.pendingDeleteDao().oldest(10).size
            )
            assertEquals(listOf("del-429"), failures)
            assertTrue(telegram.deletedMessages.isEmpty())
            assertFalse(
                "publishing is not gated by the deferred delete",
                files.byId("del-429")!!.pendingPublish
            )

            // Next drain, flood gone: the deferred delete replays.
            telegram.onDeleteWaiting = {}
            assertTrue(drainer().drain(isStopped = { false }))
            assertEquals(0, database.pendingDeleteDao().oldest(10).size)
            assertEquals(listOf(9_101L), telegram.deletedMessages)
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
        chatId = TEST_CHAT,
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
        database = database,
        transferDao = database.transferDao(),
        resolver = harness.resolver,
        reuploadEnqueuer = harness.reuploadEnqueuer,
        activeChannel = harness.activeChannel
    )
}
