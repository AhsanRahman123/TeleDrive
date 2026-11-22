package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.LogSink
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.publish.PublishOutboxDrainer
import com.drdisagree.teledrive.core.telegram.TelegramException
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-37: the 144 field rows whose stored message ids point at deleted
 * messages (`MESSAGE_ID_INVALID`, 88) or at other files' messages
 * (`Message can't be edited`, 56). A plain retry can never publish
 * them; the drainer's repair ladder re-establishes the binding from
 * evidence — probe, caption uuid, unique id — re-uploads from the
 * local copy when the Telegram copy is unrecoverable, and marks the
 * row durably remote-missing when there is no local copy. The old
 * message is never edited or deleted: the consecutive id blocks in
 * the field data mean it likely belongs to another file.
 */
class MisboundRepairTest {

    private val harness = SyncHarness()
    private val database = harness.database
    private val files = database.fileDao()
    private val telegram = harness.telegram

    @After
    fun tearDown() {
        SafeLog.sink = null
        harness.close()
    }

    @Test
    fun `a misbound row heals through its caption uuid and publishes in the same drain`() =
        runBlocking {
            files.upsert(row("heal", messageId = 50))
            telegram.documents = listOf(
                document(900, harness.caption(manifest("heal", "heal.jpg")))
            )
            telegram.onEditCaption = { messageId ->
                if (messageId == 50L) throw TelegramException(400, "MESSAGE_ID_INVALID")
            }
            val logs = captureLogs()

            drainer().drain(isStopped = { false })

            assertTrue("the publish retries against the found message",
                telegram.editedCaptions.containsKey(900L))
            val healed = files.byId("heal")!!
            assertEquals(900L, healed.messageId)
            assertEquals("remote-900", healed.remoteFileId)
            assertFalse(healed.pendingPublish)
            assertTrue(
                "every binding write leaves one log line (the 144 were untraceable without it)",
                logs.any {
                    it.contains("setRemoteMapping: file=heal chatId=77 messageId=900")
                }
            )
        }

    @Test
    fun `a misbound row heals through its remote unique id when the found message carries no manifest`() =
        runBlocking {
            files.upsert(row("byunique", messageId = 51, remoteUniqueId = "unique-xyz"))
            telegram.documents = listOf(
                document(901, "").copy(uniqueFileId = "unique-xyz")
            )
            telegram.onEditCaption = { messageId ->
                if (messageId == 51L) throw TelegramException(400, "Message can't be edited")
            }

            drainer().drain(isStopped = { false })

            assertTrue(telegram.editedCaptions.containsKey(901L))
            val healed = files.byId("byunique")!!
            assertEquals(901L, healed.messageId)
            assertFalse(healed.pendingPublish)
        }

    @Test
    fun `an uneditable own message with a local copy is re-uploaded and the old message is never touched`() =
        runBlocking {
            val local = File.createTempFile("own", ".jpg").apply { writeText("bytes") }
            files.upsert(
                row("own", messageId = 60).copy(localPath = local.absolutePath)
            )
            telegram.documents = listOf(
                document(60, harness.caption(manifest("own", "own.jpg")))
            )
            telegram.onEditCaption = { messageId ->
                if (messageId == 60L) throw TelegramException(400, "Message can't be edited")
            }

            drainer().drain(isStopped = { false })

            assertEquals(listOf("own"), harness.reuploads)
            assertEquals(
                "the stale binding is detached BEFORE the re-upload is queued, " +
                    "so the transfer's superseded-message cleanup can never " +
                    "delete the message at the old id",
                listOf<Long?>(null), harness.reuploadMessageIdsAtCall
            )
            val after = files.byId("own")!!
            assertEquals(null, after.messageId)
            assertTrue("the pending change survives until the upload lands",
                after.pendingPublish)
            assertTrue("the probe concluded this case — no channel scan was needed",
                telegram.fetchDocumentsCalls == 0)
            assertTrue(telegram.deletedMessages.isEmpty())
            assertTrue(telegram.editedCaptions.isEmpty())
            assertTrue(local.delete())
        }

    @Test
    fun `an uneditable own message without a local copy is marked remote missing`() =
        runBlocking {
            files.upsert(row("own-gone", messageId = 61))
            telegram.documents = listOf(
                document(61, harness.caption(manifest("own-gone", "own-gone.jpg")))
            )
            val attempts = AtomicInteger(0)
            telegram.onEditCaption = { messageId ->
                if (messageId == 61L) {
                    attempts.incrementAndGet()
                    throw TelegramException(400, "Message can't be edited")
                }
            }
            val failures = mutableListOf<AppError>()
            drainer().drain(
                isStopped = { false },
                onFailure = { _, error -> failures += error }
            )

            val after = files.byId("own-gone")!!
            assertTrue(after.remoteMissing)
            assertFalse(after.pendingPublish)
            assertTrue(failures.any { it is AppError.RemoteMissing })

            drainer().drain(isStopped = { false })
            assertEquals("a remote-missing row leaves the retry cycle for good",
                1, attempts.get())
        }

    @Test
    fun `a dead binding re-uploads and the pending change publishes once the upload lands`() =
        runBlocking {
            val local = File.createTempFile("arc", ".jpg").apply { writeText("bytes") }
            files.upsert(
                row("arc", messageId = 70).copy(
                    localPath = local.absolutePath,
                    trashedAt = 5_000,
                    folderId = null,
                    preTrashFolderId = "trips"
                )
            )
            telegram.documents = emptyList()
            telegram.onEditCaption = { messageId ->
                if (messageId == 70L) throw TelegramException(400, "MESSAGE_ID_INVALID")
            }

            // Drain 1: probe misses, the (complete) scan misses, the
            // local copy exists — detach and queue the re-upload.
            drainer().drain(isStopped = { false })
            assertEquals(listOf("arc"), harness.reuploads)
            assertEquals(listOf<Long?>(null), harness.reuploadMessageIdsAtCall)
            assertTrue(files.byId("arc")!!.pendingPublish)

            // While the upload is in flight the row looks local-only;
            // the pending flag must NOT be swept — it is the only
            // thing that gets the row's trashed state published
            // against the new message once the upload lands.
            database.transferDao().upsert(
                TransferEntity(
                    id = "transfer-arc",
                    type = TransferType.BACKUP,
                    fileId = "arc",
                    displayName = "arc.jpg",
                    localPath = local.absolutePath,
                    chatId = null,
                    messageId = null,
                    remoteFileId = null,
                    sizeBytes = 5,
                    state = TransferState.QUEUED,
                    createdAt = 1,
                    updatedAt = 1
                )
            )
            drainer().drain(isStopped = { false })
            assertTrue(files.byId("arc")!!.pendingPublish)

            // The upload lands at message 999; the transfer finishes.
            files.setRemoteMapping(
                "arc", TEST_CHAT, 999, "remote-999", "unique-999",
                BackupState.BACKED_UP
            )
            database.transferDao().upsert(
                database.transferDao().byId("transfer-arc")!!
                    .copy(state = TransferState.COMPLETED)
            )
            telegram.onEditCaption = {}

            // Drain 3: the pending trashed state publishes against the
            // new message — the content exists in the channel again
            // and the Trash entry stays restorable.
            drainer().drain(isStopped = { false })
            assertTrue(telegram.editedCaptions.containsKey(999L))
            val landed = files.byId("arc")!!
            assertFalse(landed.pendingPublish)
            assertEquals(999L, landed.messageId)
            assertEquals(5_000L, landed.trashedAt)
            assertTrue(telegram.deletedMessages.isEmpty())
            assertTrue(local.delete())
        }

    @Test
    fun `a dead binding without a local copy is marked remote missing by name and leaves the retry cycle`() =
        runBlocking {
            files.upsert(
                row("gone", messageId = 71).copy(trashedAt = 7_000, folderId = null)
            )
            telegram.documents = emptyList()
            val attempts = AtomicInteger(0)
            telegram.onEditCaption = { messageId ->
                if (messageId == 71L) {
                    attempts.incrementAndGet()
                    throw TelegramException(400, "MESSAGE_ID_INVALID")
                }
            }
            val failures = mutableListOf<Pair<String, AppError>>()
            drainer().drain(
                isStopped = { false },
                onFailure = { id, error -> failures += id to error }
            )

            val after = files.byId("gone")!!
            assertTrue("the row is kept, marked — never silently dropped",
                after.remoteMissing)
            assertFalse(after.pendingPublish)
            assertEquals("its restorable state is untouched", 7_000L, after.trashedAt)
            assertEquals(listOf("gone"), files.remoteMissingRows().map { it.id })
            assertEquals(0, files.pendingPublishCount(TEST_CHAT))
            val missing = failures.single().second
            assertTrue(missing is AppError.RemoteMissing)
            assertEquals("gone.jpg", (missing as AppError.RemoteMissing).fileName)

            drainer().drain(isStopped = { false })
            assertEquals(1, attempts.get())
            assertTrue(telegram.deletedMessages.isEmpty())
        }

    @Test
    fun `another permanent 400 neither scans the channel nor marks the row`() = runBlocking {
        files.upsert(row("other", messageId = 80))
        telegram.onEditCaption = { messageId ->
            if (messageId == 80L) {
                throw TelegramException(400, "Bad Request: chat not found")
            }
        }
        val failures = mutableListOf<AppError>()
        drainer().drain(
            isStopped = { false },
            onFailure = { _, error -> failures += error }
        )

        assertEquals(0, telegram.fetchDocumentsCalls)
        val after = files.byId("other")!!
        assertTrue(after.pendingPublish)
        assertFalse(after.remoteMissing)
        val error = failures.single()
        assertTrue(error is AppError.TelegramError)
        assertEquals(400, (error as AppError.TelegramError).code)
    }

    @Test
    fun `several misbound rows in one drain share a single channel scan`() = runBlocking {
        files.upsert(row("scan-heal", messageId = 50))
        val localA = File.createTempFile("scan-a", ".jpg").apply { writeText("a") }
        val localB = File.createTempFile("scan-b", ".jpg").apply { writeText("b") }
        files.upsert(row("scan-a", messageId = 52).copy(localPath = localA.absolutePath))
        files.upsert(row("scan-b", messageId = 53).copy(localPath = localB.absolutePath))
        telegram.documents = listOf(
            document(900, harness.caption(manifest("scan-heal", "scan-heal.jpg")))
        )
        telegram.onEditCaption = { messageId ->
            if (messageId in setOf(50L, 52L, 53L)) {
                throw TelegramException(400, "MESSAGE_ID_INVALID")
            }
        }

        drainer().drain(isStopped = { false })

        assertEquals(
            "one channel pass serves every repair in the drain " +
                "(a content page plus the terminating empty page — " +
                "per-file scans would cost three passes)",
            2, telegram.fetchDocumentsCalls
        )
        assertTrue(telegram.editedCaptions.containsKey(900L))
        assertEquals(setOf("scan-a", "scan-b"), harness.reuploads.toSet())
        assertTrue(localA.delete())
        assertTrue(localB.delete())
    }

    @Test
    fun `a caption uuid beats a stale message binding in sync and the claimant is detached not deleted`() =
        runBlocking {
            files.upsert(
                row("claimant", messageId = 500).copy(
                    remoteBase = harness.caption(manifest("claimant", "claimant.jpg"))
                )
            )
            files.upsert(row("named", messageId = null))
            telegram.documents = listOf(
                document(500, harness.caption(manifest("named", "named.jpg")))
            )
            val logs = captureLogs()

            harness.newSync().fullResync()

            assertEquals(500L, files.byId("named")!!.messageId)
            val claimant = files.byId("claimant")!!
            assertEquals(null, claimant.messageId)
            assertTrue(
                logs.any { it.contains("Binding conflict") && it.contains("500") }
            )
        }

    private fun row(
        id: String,
        messageId: Long?,
        remoteUniqueId: String? = null
    ) = FileEntity(
        id = id,
        folderId = null,
        name = "$id.jpg",
        sizeBytes = 10,
        mimeType = "image/jpeg",
        category = FileCategory.IMAGE,
        localPath = null,
        contentHash = null,
        chatId = TEST_CHAT,
        messageId = messageId,
        remoteFileId = messageId?.let { "remote-$it" },
        remoteUniqueId = remoteUniqueId ?: messageId?.let { "unique-$it" },
        backupState = BackupState.BACKED_UP,
        pendingPublish = true,
        createdAt = 1_000,
        modifiedAt = 1_000,
        addedAt = 1_000
    )

    private fun captureLogs(): MutableList<String> {
        val logs = Collections.synchronizedList(mutableListOf<String>())
        SafeLog.sink = LogSink { _, _, message, _ -> logs += message }
        return logs
    }

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
