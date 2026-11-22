package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-39 storm evidence for the parallel transfer lanes. The historic
 * failure family (report 37) is cross-binding: under concurrency a file
 * row ends up bound to ANOTHER file's Telegram message. Every upload
 * test here therefore asserts the full binding chain per file — the
 * message id on the row is the one the client issued for that file, and
 * the caption that went out with that message decodes to that file's
 * own manifest uuid.
 */
class ParallelTransferStormTest {

    @Test
    fun `many small uploads complete concurrently with every binding its own`() = runBlocking {
        val count = 24
        StormHarness(
            UserPreferences(uploadConcurrency = 6, downloadConcurrency = 6, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.uploadDelayMs = 40
            val ids = (1..count).map { i ->
                val id = "up-$i"
                harness.seedUpload(id, "file-$i.bin", ByteArray(64 + i) { i.toByte() }, i.toLong())
                id
            }
            val terminalFailures = mutableListOf<String>()

            val result = withTimeout(60.seconds) {
                harness.drainer().drain(
                    isStopped = { false },
                    onTerminalFailure = { terminalFailures += it.id }
                )
            }

            assertEquals(TransferDrainResult.COMPLETED, result)
            assertTrue("no terminal failures: $terminalFailures", terminalFailures.isEmpty())
            assertTrue(
                "uploads actually overlapped (max in flight was " +
                    "${harness.telegram.maxInFlightUploads.get()})",
                harness.telegram.maxInFlightUploads.get() >= 3
            )
            assertEquals(count, harness.telegram.uploads.size)
            assertBindingsAreOwn(harness, ids)
            ids.forEach { id ->
                assertEquals(
                    TransferState.COMPLETED,
                    harness.transfers.byId("t-$id")!!.state
                )
            }
        }
    }

    @Test
    fun `uploads and downloads storm simultaneously in their own lanes`() = runBlocking {
        val perDirection = 6
        StormHarness(
            UserPreferences(uploadConcurrency = 6, downloadConcurrency = 6, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            val upIds = (1..perDirection).map { i ->
                val id = "up-$i"
                harness.seedUpload(id, "up-file-$i.bin", ByteArray(32) { 1 }, i.toLong())
                id
            }
            val downIds = (1..perDirection).map { i ->
                val id = "down-$i"
                harness.seedDownload(
                    id, "down-file-$i.bin", ByteArray(48) { 2 }, 100L + i, i
                )
                id
            }
            val drainer = harness.drainer()
            val drain = async {
                drainer.drain(isStopped = { false }, onTerminalFailure = {})
            }

            // Both lanes fill completely while every transfer is parked at
            // its gate: the two directions are in flight AT THE SAME TIME,
            // neither waiting for the other — the old shared pool could
            // never show this (its combined cap was the sum of both).
            withTimeout(20.seconds) {
                while (harness.telegram.startedUploads.size < perDirection ||
                    harness.telegram.startedDownloads.size < perDirection
                ) {
                    delay(25)
                }
            }
            assertEquals(perDirection, harness.telegram.inFlightUploads.get())
            assertEquals(perDirection, harness.telegram.inFlightDownloads.get())

            harness.telegram.releaseAll()
            val result = withTimeout(60.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            assertBindingsAreOwn(harness, upIds)
            (upIds + downIds).forEach { id ->
                assertEquals(
                    TransferState.COMPLETED,
                    harness.transfers.byId("t-$id")!!.state
                )
            }
            downIds.forEach { id ->
                assertTrue(
                    "downloaded file saved locally",
                    harness.files.byId(id)!!.localPath != null
                )
            }
        }
    }

    @Test
    fun `flood injection at maximum concurrency loses nothing and binds nothing wrong`() =
        runBlocking {
            val uploads = 12
            val downloads = 12
            StormHarness(
                UserPreferences(
                    uploadConcurrency = 6,
                    downloadConcurrency = 6,
                    transferRetryCount = 10
                )
            ).use { harness ->
                harness.telegram.uploadDelayMs = 20
                harness.telegram.downloadDelayMs = 20
                // Every third upload and every fourth download is rejected
                // with a 429 on its first attempt — a flood storm at the
                // maximum setting (6/6). The pacer-level behaviour is the
                // client's; here the drainer must retry per policy and
                // land every file exactly once.
                val upIds = (1..uploads).map { i ->
                    val id = "up-$i"
                    harness.seedUpload(id, "flood-up-$i.bin", ByteArray(48) { 3 }, i.toLong())
                    if (i % 3 == 0) harness.telegram.uploadFloods["flood-up-$i.bin"] = 1
                    id
                }
                val downIds = (1..downloads).map { i ->
                    val id = "down-$i"
                    harness.seedDownload(id, "flood-down-$i.bin", ByteArray(48) { 4 }, 200L + i, i)
                    if (i % 4 == 0) harness.telegram.downloadFloods["rf-$id"] = 1
                    id
                }
                val terminalFailures = mutableListOf<String>()

                val result = withTimeout(90.seconds) {
                    harness.drainer().drain(
                        isStopped = { false },
                        onTerminalFailure = { terminalFailures += it.id }
                    )
                }

                assertEquals(TransferDrainResult.COMPLETED, result)
                assertTrue("no terminal failures: $terminalFailures", terminalFailures.isEmpty())
                // Exactly one successful upload per file: no file was
                // double-published by a retry.
                assertEquals(uploads, harness.telegram.uploads.size)
                (upIds + downIds).forEach { id ->
                    assertEquals(
                        TransferState.COMPLETED,
                        harness.transfers.byId("t-$id")!!.state
                    )
                }
                // The flooded transfers really were rejected once and retried.
                (3..uploads step 3).forEach { i ->
                    assertEquals(
                        2,
                        harness.telegram.uploadAttempts["flood-up-$i.bin"]!!.get()
                    )
                }
                (4..downloads step 4).forEach { i ->
                    assertEquals(2, harness.telegram.downloadAttempts["rf-down-$i"]!!.get())
                }
                assertBindingsAreOwn(harness, upIds)
                // The user's persisted numbers were never rewritten by the storm.
                val prefs = harness.settings.preferences.first()
                assertEquals(6, prefs.uploadConcurrency)
                assertEquals(6, prefs.downloadConcurrency)
            }
        }

    @Test
    fun `cancel all mid storm stops promptly and leaves no zombie running rows`() = runBlocking {
        val count = 10
        StormHarness(
            UserPreferences(uploadConcurrency = 6, downloadConcurrency = 6, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            val ids = (1..count).map { i ->
                val id = "up-$i"
                harness.seedUpload(id, "cancel-$i.bin", ByteArray(40) { 5 }, i.toLong())
                id
            }
            val drainer = harness.drainer()
            val drain = async {
                drainer.drain(isStopped = { false }, onTerminalFailure = {})
            }
            withTimeout(20.seconds) {
                while (harness.telegram.startedUploads.size < 6) delay(25)
            }

            // Cancel all, exactly as the repository does it: every active
            // row becomes CANCELLED. The gates stay SHUT — the in-flight
            // uploads are parked inside the client flow emitting nothing,
            // so only the executor's row-state polling (its designed
            // control path) can free them. The drain finishing at all is
            // the promptness proof.
            ids.forEach { id ->
                harness.transfers.setState(
                    "t-$id",
                    TransferState.CANCELLED,
                    System.currentTimeMillis()
                )
            }

            val result = withTimeout(30.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            ids.forEach { id ->
                val row = harness.transfers.byId("t-$id")!!
                assertEquals("row $id must end CANCELLED, never stuck", TransferState.CANCELLED, row.state)
                // Nothing was published for a cancelled upload.
                assertEquals(null, harness.files.byId(id)!!.messageId)
            }
            assertTrue(harness.telegram.uploads.isEmpty())
        }
    }

    /**
     * The report-37 assertion: for every file, the row's message id is
     * the message the client created for THAT file, and the caption on
     * that message carries THAT file's manifest uuid — no cross-binding,
     * no guessed ids.
     */
    private suspend fun assertBindingsAreOwn(harness: StormHarness, fileIds: List<String>) {
        val byName = harness.telegram.uploads.associateBy { it.fileName }
        fileIds.forEach { id ->
            val row = harness.files.byId(id)!!
            val record = byName[row.name]
            assertTrue("an upload exists for ${row.name}", record != null)
            assertEquals(
                "row $id bound to the message created for it",
                record!!.messageId,
                row.messageId
            )
            assertEquals("remote id matches the issued message", "rf-${record.messageId}", row.remoteFileId)
            val manifest = harness.manifestCodec().decode(record.caption)
            assertEquals("caption on the message names its own file", id, manifest?.fileId)
        }
    }
}
