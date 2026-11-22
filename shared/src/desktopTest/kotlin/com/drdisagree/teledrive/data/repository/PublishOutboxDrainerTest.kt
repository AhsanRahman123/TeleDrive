package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.PendingDeleteEntity
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
            assertEquals(200, files.pendingPublishCount(TEST_CHAT))

            val drained = drainer().drain(isStopped = { false })

            assertTrue("the drain must complete behind a local-only head", drained)
            assertEquals(50, telegram.editedCaptions.size)
            // The local-only rows are RESOLVED, not published and not
            // deleted: a local-only row has no caption to correct (its
            // manifest ships as the upload's initial caption), so its
            // flag is not sync work. The round-7 behaviour — keep the
            // flag, skip the row — is exactly what stalled the user's
            // count at the local-only floor (4,370 → 1,056) with every
            // drain "succeeding" and Sync now a no-op.
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            val localRow = files.byId("local-1")!!
            assertFalse(localRow.pendingPublish)
            assertEquals(TEST_CHAT, localRow.chatId)
            assertFalse(files.byId("pub-1")!!.pendingPublish)
            assertFalse(files.byId("pub-50")!!.pendingPublish)
        }

    @Test
    fun `a local-only row needs no publish when its upload lands, and publishes later changes`() =
        runBlocking {
            files.upsert(localOnlyFile("late-1"))
            assertTrue(drainer().drain(isStopped = { false }))
            // Resolved by the drain: no caption exists to correct.
            assertFalse(files.byId("late-1")!!.pendingPublish)
            assertTrue(telegram.editedCaptions.isEmpty())

            // The upload lands: the row gains its channel message, and
            // the manifest already went up as the upload's initial
            // caption — still nothing for sync to do.
            files.upsert(
                localOnlyFile("late-1").copy(
                    chatId = TEST_CHAT,
                    messageId = 7_777L,
                    pendingPublish = false
                )
            )
            assertTrue(drainer().drain(isStopped = { false }))
            assertTrue(telegram.editedCaptions.isEmpty())

            // A change AFTER the upload re-marks the flag through the
            // normal update paths; now the row is publishable and the
            // drain publishes and clears it as usual.
            files.upsert(
                localOnlyFile("late-1").copy(
                    chatId = TEST_CHAT,
                    messageId = 7_777L,
                    pendingPublish = true
                )
            )
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
            // PC-37: MESSAGE_ID_INVALID is a mis-binding, not a generic
            // rejection. The ladder proves the binding dead (no probe
            // hit, no manifest anywhere in the channel) and the row has
            // no local copy, so it is marked durably remote-missing:
            // the row is KEPT and surfaced by id, its pending flag is
            // cleared only together with that mark, and the pending
            // count reaches zero instead of retrying a dead binding on
            // every drain forever. (MisboundRepairTest covers the
            // ladder's other rungs.)
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            val poisonRow = files.byId("poison")!!
            assertFalse(poisonRow.pendingPublish)
            assertTrue(poisonRow.remoteMissing)
            assertEquals(listOf("poison"), failures)
        }

    @Test
    fun `hundreds of rejected rows cannot block the backlog behind them in one drain`() =
        runBlocking {
            // The per-drain exclusion used to ride in a SQL NOT IN (...)
            // parameter list; past a few hundred rejected rows that hits
            // SQLite's variable limit on strict drivers and fails the
            // whole drain (second-pass finding, round 7). The exclusion
            // is filtered in memory now — this many poison rows must
            // still let everything behind them publish in ONE drain.
            val poison = (1..600).map { publishableFile("bad-$it", 5_000L + it) }
            val behind = (1..10).map { publishableFile("good-$it", 9_000L + it) }
            files.upsertAll(poison + behind)
            telegram.onEditCaption = { messageId ->
                if (messageId in 5_001L..5_600L) {
                    throw TelegramException(400, "MESSAGE_ID_INVALID")
                }
            }
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())

            val drained = drainer().drain(
                isStopped = { false },
                onFailure = { fileId, _ -> failures += fileId }
            )

            assertTrue("the drain must complete behind a 600-row poison head", drained)
            assertEquals(10, telegram.editedCaptions.size)
            // PC-37: all 600 are mis-bound rows with no local copy —
            // the ladder marks every one remote-missing in this single
            // drain (over one shared channel scan), so nothing stays
            // pending and every one was surfaced.
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            assertEquals(600, files.remoteMissingRows().size)
            assertEquals(600, failures.size)
            assertFalse(files.byId("good-1")!!.pendingPublish)
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
            assertEquals(250 - publishedSoFar, files.pendingPublishCount(TEST_CHAT))

            // Sync now after a cancel resumes exactly the remainder.
            assertTrue(drainer().drain(isStopped = { false }))
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            assertEquals(250, telegram.editedCaptions.size)
        }

    @Test
    fun `a persistently failing delete batch cannot gate publishing at meltdown scale`() =
        runBlocking {
            // Round 8 (PC-32 re-investigation): at the user's scale —
            // ≈4,370 pending rows of mixed kinds — the drain's FIRST
            // step was pending-delete replay, and one non-permanent
            // delete failure ended the drain before a single file
            // published. The round-7 local-only fix never got a chance
            // to matter behind that gate. This reproduces the shape:
            // 4,000 publishable + 370 local-only rows, and a delete
            // backlog whose Telegram call keeps failing with a
            // non-permanent (500) error.
            val publishable = (1..4_000).map { publishableFile("bulk-pub-$it", 100_000L + it) }
            val localOnly = (1..370).map { localOnlyFile("bulk-local-$it") }
            files.upsertAll(publishable + localOnly)
            val deleteRows = (1..50).map { publishableFile("del-$it", 200_000L + it) }
            files.upsertAll(deleteRows)
            database.pendingDeleteDao().upsertAll(
                deleteRows.map {
                    PendingDeleteEntity(
                        chatId = TEST_CHAT,
                        messageId = it.messageId!!,
                        fileId = it.id
                    )
                }
            )
            telegram.onDeleteMessages = { throw TelegramException(500, "Internal error") }
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())

            val drained = drainer().drain(
                isStopped = { false },
                onFailure = { fileId, _ -> failures += fileId }
            )

            assertTrue("delete replay must not gate the file drain", drained)
            // Every publishable row behind the gate published anyway —
            // the 4,000 bulk rows plus the 50 delete rows themselves,
            // which are publishable pending rows too.
            assertEquals(4_050, telegram.editedCaptions.size)
            // The 370 local-only rows were resolved (not published, not
            // deleted): the pending count reaches zero — the exact
            // property his machine's count never had.
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            assertEquals(TEST_CHAT, files.byId("bulk-local-1")!!.chatId)
            // The deferred deletes are lossless AND surfaced: rows and
            // pending entries survive for the next drain, and every
            // deferred file was reported instead of vanishing.
            assertEquals(50, database.pendingDeleteDao().oldest(100).size)
            assertEquals(50, failures.size)

            // Next drain with Telegram healthy: the deferred deletes
            // replay and clear, exactly as before.
            telegram.onDeleteMessages = {}
            assertTrue(drainer().drain(isStopped = { false }))
            assertEquals(0, database.pendingDeleteDao().oldest(100).size)
        }

    @Test
    fun `a persistently rate limited file steps aside and the run ends waiting with the rest published`() =
        runBlocking {
            // PC-35, the frozen "0 of 144": one file whose caption edit
            // is always answered 429. Before the fix its per-file loop
            // waited and retried forever — no progress, no failure, no
            // log — while the account-wide limit stalled the slots
            // behind it. The waits here are retry-after 0, so each
            // waited retry costs one second.
            val poison = publishableFile("flooded", 6_001L)
            val behind = (1..10).map { publishableFile("ok-$it", 6_100L + it) }
            files.upsertAll(listOf(poison) + behind)
            val attempts = AtomicInteger(0)
            telegram.onEditCaption = { messageId ->
                if (messageId == 6_001L) {
                    attempts.incrementAndGet()
                    throw TelegramException(429, "Too Many Requests: retry after 0", 0)
                }
            }
            val failures =
                java.util.Collections.synchronizedList(mutableListOf<Pair<String, AppError>>())
            val waits = java.util.Collections.synchronizedList(mutableListOf<Int>())

            val startedAt = System.currentTimeMillis()
            val drained = drainer().drain(
                isStopped = { false },
                onWaiting = { waits += it },
                onFailure = { fileId, error -> failures += fileId to error }
            )
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertFalse("a rate-limited run ends in the waiting state, not success", drained)
            // No head-of-line poisoning: the rest of the batch
            // published behind the limited file.
            assertEquals(10, telegram.editedCaptions.size)
            // The limited file stays pending, alone, and was surfaced.
            assertEquals(1, files.pendingPublishCount(TEST_CHAT))
            assertTrue(files.byId("flooded")!!.pendingPublish)
            assertEquals(1, failures.size)
            assertEquals("flooded", failures[0].first)
            assertTrue(failures[0].second is AppError.RateLimited)
            // The budget is exact: the initial attempt plus the
            // drainer's 3 waited retries (MAX_RATE_LIMIT_WAITS_PER_FILE)
            // — never an unbounded loop — and every wait was reported
            // through onWaiting instead of passing in silence.
            assertEquals(1 + 3, attempts.get())
            assertTrue("the waits were surfaced", waits.isNotEmpty())
            assertTrue("bounded in time (was ${elapsedMs}ms)", elapsedMs < 30_000)
        }

    @Test
    fun `a rate limit that clears mid-run lets the drain complete with full progress`() =
        runBlocking {
            val rows = (1..5).map { publishableFile("flaky-$it", 7_000L + it) }
            files.upsertAll(rows)
            val attempts = AtomicInteger(0)
            telegram.onEditCaption = { messageId ->
                if (messageId == 7_001L && attempts.incrementAndGet() <= 2) {
                    throw TelegramException(429, "Too Many Requests: retry after 0", 0)
                }
            }
            val progress =
                java.util.Collections.synchronizedList(mutableListOf<Pair<Int, Int>>())

            val drained = drainer().drain(
                isStopped = { false },
                onProgress = { published, total -> progress += published to total }
            )

            assertTrue(drained)
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            assertEquals(5, telegram.editedCaptions.size)
            // Two limited attempts, then the edit went through.
            assertEquals(3, attempts.get())
            // Honest progress: the counter ends at the full count.
            assertEquals(5, progress.last().first)
        }

    @Test
    fun `when every file is rate limited the run ends promptly with everything still pending`() =
        runBlocking {
            val rows = (1..8).map { publishableFile("flood-$it", 8_000L + it) }
            files.upsertAll(rows)
            telegram.onEditCaption = {
                throw TelegramException(429, "Too Many Requests: retry after 0", 0)
            }
            val failures = java.util.Collections.synchronizedList(mutableListOf<String>())

            val startedAt = System.currentTimeMillis()
            val drained = drainer().drain(
                isStopped = { false },
                onFailure = { fileId, _ -> failures += fileId }
            )
            val elapsedMs = System.currentTimeMillis() - startedAt

            assertFalse(drained)
            // Nothing published, nothing dropped: every file stepped
            // aside after its bounded budget and stays pending, and
            // every one was surfaced.
            assertEquals(8, files.pendingPublishCount(TEST_CHAT))
            assertTrue(telegram.editedCaptions.isEmpty())
            assertEquals(8, failures.size)
            assertTrue("bounded in time (was ${elapsedMs}ms)", elapsedMs < 30_000)
        }

    @Test
    fun `an over-long rate limit steps aside at once and does not block the rows behind it`() =
        runBlocking {
            // A single retry-after far beyond the inline limit used to
            // abort the whole drain on the spot; the rows behind the
            // limited file never got their attempt. Now the file steps
            // aside after ONE attempt and the batch proceeds.
            val poison = publishableFile("long-flood", 9_001L)
            val behind = (1..5).map { publishableFile("ok-$it", 9_100L + it) }
            files.upsertAll(listOf(poison) + behind)
            val attempts = AtomicInteger(0)
            telegram.onEditCaption = { messageId ->
                if (messageId == 9_001L) {
                    attempts.incrementAndGet()
                    throw TelegramException(429, "Too Many Requests: retry after 5000", 5_000)
                }
            }

            val drained = drainer().drain(isStopped = { false })

            assertFalse("the run ends in the waiting state", drained)
            assertEquals(1, attempts.get())
            assertEquals(5, telegram.editedCaptions.size)
            assertEquals(1, files.pendingPublishCount(TEST_CHAT))
            assertTrue(files.byId("long-flood")!!.pendingPublish)
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
