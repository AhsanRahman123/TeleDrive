package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-40 (1.6.18) fair-share evidence. The user's scenario: Folder A is
 * uploading; he adds Folder B and expects BOTH to progress together,
 * sharing the lane — never B waiting for A's whole backlog, and never
 * A freezing while B drains. Claims therefore rotate across enqueue
 * groups (batchId): fewest RUNNING first, least-recently-claimed next,
 * FIFO inside a group. These tests drive the REAL drainer over the
 * storm harness and assert the measured claim split at the gates,
 * where the schedule is fully deterministic.
 */
class FairShareStormTest {

    private fun groupCount(names: List<String>, prefix: String): Int =
        names.count { it.startsWith(prefix) }

    private suspend fun awaitStarted(harness: StormHarness, total: Int) {
        withTimeout(20.seconds) {
            while (harness.telegram.startedUploads.size < total) delay(25)
        }
    }

    @Test
    fun `two groups share the lane from the first claims and both complete`() = runBlocking {
        val perGroup = 60
        StormHarness(
            UserPreferences(uploadConcurrency = 6, downloadConcurrency = 6, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            val aIds = (1..perGroup).map { i ->
                val id = "ga-$i"
                harness.seedUpload(
                    id, "ga-%02d.bin".format(i), ByteArray(48) { 1 }, i.toLong(),
                    batchId = "batch-a"
                )
                id
            }
            val bIds = (1..perGroup).map { i ->
                val id = "gb-$i"
                harness.seedUpload(
                    id, "gb-%02d.bin".format(i), ByteArray(48) { 2 }, 1_000L + i,
                    batchId = "batch-b"
                )
                id
            }
            val drainer = harness.drainer()
            val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

            // Six slots, two groups: the rotation must split them 3/3
            // from the very first claim wave — Folder B does not wait
            // for any of Folder A's backlog beyond sharing the slots.
            awaitStarted(harness, 6)
            val started = harness.telegram.startedUploads.toList()
            assertEquals(3, groupCount(started, "ga-"))
            assertEquals(3, groupCount(started, "gb-"))

            harness.telegram.releaseAll()
            val result = withTimeout(90.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            assertEquals(2 * perGroup, harness.telegram.uploads.size)
            assertBindingsAreOwn(harness, aIds + bIds)
            (aIds + bIds).forEach { id ->
                assertEquals(TransferState.COMPLETED, harness.transfers.byId("t-$id")!!.state)
            }
        }
    }

    @Test
    fun `a small group added late finishes early while the large group keeps progressing`() =
        runBlocking {
            StormHarness(
                UserPreferences(
                    uploadConcurrency = 6,
                    downloadConcurrency = 6,
                    transferRetryCount = 3
                )
            ).use { harness ->
                harness.telegram.gated = true
                val aIds = (1..100).map { i ->
                    val id = "ga-$i"
                    harness.seedUpload(
                        id, "ga-%03d.bin".format(i), ByteArray(40) { 1 }, i.toLong(),
                        batchId = "batch-a"
                    )
                    id
                }
                val bIds = (1..5).map { i ->
                    val id = "gb-$i"
                    harness.seedUpload(
                        id, "gb-$i.bin", ByteArray(40) { 2 }, 1_000L + i,
                        batchId = "batch-b"
                    )
                    id
                }
                val drainer = harness.drainer()
                val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

                awaitStarted(harness, 6)
                var started = harness.telegram.startedUploads.toList()
                assertEquals(3, groupCount(started, "ga-"))
                assertEquals(3, groupCount(started, "gb-"))

                // Let the small group's running three finish. The freed
                // slots must go to the small group's remaining two FIRST
                // (fewest running), then back to the large group — the
                // small group is fully started after 9 claims while the
                // large group has also advanced past its first wave.
                (1..3).forEach { harness.telegram.releaseUpload("gb-$it.bin") }
                withTimeout(20.seconds) {
                    while (true) {
                        val now = harness.telegram.startedUploads.toList()
                        if (groupCount(now, "gb-") == 5 && groupCount(now, "ga-") == 4) break
                        delay(25)
                    }
                }
                started = harness.telegram.startedUploads.toList()
                assertEquals(5, groupCount(started, "gb-"))
                assertEquals(4, groupCount(started, "ga-"))
                bIds.take(3).forEach { id ->
                    assertEquals(
                        TransferState.COMPLETED,
                        harness.transfers.byId("t-$id")!!.state
                    )
                }

                harness.telegram.releaseAll()
                val result = withTimeout(90.seconds) { drain.await() }

                assertEquals(TransferDrainResult.COMPLETED, result)
                assertEquals(105, harness.telegram.uploads.size)
                assertBindingsAreOwn(harness, aIds + bIds)
                (aIds + bIds).forEach { id ->
                    assertEquals(
                        TransferState.COMPLETED,
                        harness.transfers.byId("t-$id")!!.state
                    )
                }
            }
        }

    @Test
    fun `a single legacy group keeps the exact pre fair-share fifo order`() = runBlocking {
        val count = 8
        // Width 1: one slot works the queue end to end, so the recorded
        // start order IS the claim order, with no in-flight reordering.
        StormHarness(
            UserPreferences(uploadConcurrency = 1, downloadConcurrency = 1, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            val names = (1..count).map { i ->
                harness.seedUpload("fifo-$i", "fifo-$i.bin", ByteArray(32) { 3 }, i.toLong())
                "fifo-$i.bin"
            }
            val drainer = harness.drainer()
            val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

            awaitStarted(harness, 1)
            harness.telegram.releaseAll()
            val result = withTimeout(60.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            // One group (batchId NULL — every row written before 1.6.18)
            // claims strictly oldest-first, exactly as the old queue did.
            assertEquals(names, harness.telegram.startedUploads.toList())
        }
    }

    @Test
    fun `when one group finishes the other takes every slot`() = runBlocking {
        StormHarness(
            UserPreferences(uploadConcurrency = 4, downloadConcurrency = 4, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            val aIds = (1..3).map { i ->
                val id = "ga-$i"
                harness.seedUpload(
                    id, "ga-$i.bin", ByteArray(40) { 1 }, i.toLong(), batchId = "batch-a"
                )
                id
            }
            val bIds = (1..6).map { i ->
                val id = "gb-$i"
                harness.seedUpload(
                    id, "gb-$i.bin", ByteArray(40) { 2 }, 100L + i, batchId = "batch-b"
                )
                id
            }
            val drainer = harness.drainer()
            val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

            awaitStarted(harness, 4)
            var started = harness.telegram.startedUploads.toList()
            assertEquals(2, groupCount(started, "ga-"))
            assertEquals(2, groupCount(started, "gb-"))

            // Drain group A completely while B's gates stay shut.
            harness.telegram.releaseUpload("ga-1.bin")
            harness.telegram.releaseUpload("ga-2.bin")
            withTimeout(20.seconds) {
                while (harness.telegram.startedUploads.size < 6) delay(25)
            }
            started = harness.telegram.startedUploads.toList()
            assertEquals(3, groupCount(started, "ga-"))
            assertEquals(3, groupCount(started, "gb-"))
            harness.telegram.releaseUpload("ga-3.bin")
            withTimeout(20.seconds) {
                while (harness.transfers.byId("t-ga-3")!!.state != TransferState.COMPLETED ||
                    harness.telegram.startedUploads.size < 7
                ) {
                    delay(25)
                }
            }
            // A is done; every running slot now belongs to B.
            aIds.forEach { id ->
                assertEquals(TransferState.COMPLETED, harness.transfers.byId("t-$id")!!.state)
            }
            assertEquals(4, harness.telegram.inFlightUploads.get())
            assertEquals(4, groupCount(harness.telegram.startedUploads.toList(), "gb-"))

            harness.telegram.releaseAll()
            val result = withTimeout(60.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            assertEquals(9, harness.telegram.uploads.size)
            assertBindingsAreOwn(harness, aIds + bIds)
        }
    }

    @Test
    fun `three groups share the lane evenly`() = runBlocking {
        StormHarness(
            UserPreferences(uploadConcurrency = 6, downloadConcurrency = 6, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            val ids = mutableListOf<String>()
            listOf("ga" to 0L, "gb" to 100L, "gc" to 200L).forEach { (prefix, base) ->
                (1..4).forEach { i ->
                    val id = "$prefix-$i"
                    harness.seedUpload(
                        id, "$prefix-$i.bin", ByteArray(40) { 1 }, base + i,
                        batchId = "batch-$prefix"
                    )
                    ids += id
                }
            }
            val drainer = harness.drainer()
            val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

            awaitStarted(harness, 6)
            val started = harness.telegram.startedUploads.toList()
            assertEquals(2, groupCount(started, "ga-"))
            assertEquals(2, groupCount(started, "gb-"))
            assertEquals(2, groupCount(started, "gc-"))

            harness.telegram.releaseAll()
            val result = withTimeout(60.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            assertEquals(12, harness.telegram.uploads.size)
            assertBindingsAreOwn(harness, ids)
        }
    }

    @Test
    fun `the downloads lane shares fairly too`() = runBlocking {
        StormHarness(
            UserPreferences(uploadConcurrency = 2, downloadConcurrency = 2, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            val aIds = (1..3).map { i ->
                val id = "dga-$i"
                harness.seedDownload(
                    id, "dga-$i.bin", ByteArray(40) { 1 }, 100L + i, i, batchId = "batch-a"
                )
                id
            }
            val bIds = (1..3).map { i ->
                val id = "dgb-$i"
                harness.seedDownload(
                    id, "dgb-$i.bin", ByteArray(40) { 2 }, 200L + i, 10 + i, batchId = "batch-b"
                )
                id
            }
            val drainer = harness.drainer()
            val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

            // Two download slots, two groups: one each from the start.
            withTimeout(20.seconds) {
                while (harness.telegram.startedDownloads.size < 2) delay(25)
            }
            val started = harness.telegram.startedDownloads.toList()
            assertEquals(1, started.count { it.startsWith("rf-dga-") })
            assertEquals(1, started.count { it.startsWith("rf-dgb-") })

            harness.telegram.releaseAll()
            val result = withTimeout(60.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            (aIds + bIds).forEach { id ->
                assertEquals(TransferState.COMPLETED, harness.transfers.byId("t-$id")!!.state)
                assertTrue(
                    "downloaded file saved locally",
                    harness.files.byId(id)!!.localPath != null
                )
            }
        }
    }

    @Test
    fun `small uploads finish while a large upload is still streaming`() = runBlocking {
        // The user's phase model, pinned: a big file's byte upload in
        // progress must not hold the lane's other slots hostage — small
        // files start, stream, complete AND bind (message id written)
        // while the big one is still parked mid-upload. (In this
        // codebase the td1 caption rides with the send itself and the
        // bind is the completion callback's local write — there is no
        // second serialized phase for an upload to wait on; the serial
        // publish outbox serves library-state changes and never gates
        // transfer slots. This test is the behavioural proof.)
        StormHarness(
            UserPreferences(uploadConcurrency = 4, downloadConcurrency = 4, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.gated = true
            harness.seedUpload(
                "big", "big.zip", ByteArray(4096) { 1 }, createdAt = 1, batchId = "batch-big"
            )
            val smallIds = (1..10).map { i ->
                val id = "small-$i"
                harness.seedUpload(
                    id, "small-$i.txt", ByteArray(24) { 2 }, 100L + i, batchId = "batch-small"
                )
                id
            }
            val drainer = harness.drainer()
            val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

            // Release each small file's gate as it starts; the big
            // file's gate stays shut the whole time.
            withTimeout(30.seconds) {
                while (smallIds.any {
                        harness.transfers.byId("t-$it")!!.state != TransferState.COMPLETED
                    }
                ) {
                    harness.telegram.startedUploads
                        .filter { it.startsWith("small-") }
                        .forEach { harness.telegram.releaseUpload(it) }
                    delay(25)
                }
            }

            // Every small file completed AND bound while big.zip never
            // finished streaming: no upload record exists for it, its
            // row is still RUNNING, its file row has no message yet.
            assertTrue(harness.telegram.uploads.none { it.fileName == "big.zip" })
            assertEquals(
                TransferState.RUNNING,
                harness.transfers.byId("t-big")!!.state
            )
            assertEquals(null, harness.files.byId("big")!!.messageId)
            smallIds.forEach { id ->
                assertTrue(
                    "small file $id bound at completion",
                    harness.files.byId(id)!!.messageId != null
                )
            }

            harness.telegram.releaseAll()
            val result = withTimeout(60.seconds) { drain.await() }

            assertEquals(TransferDrainResult.COMPLETED, result)
            assertEquals(11, harness.telegram.uploads.size)
            assertBindingsAreOwn(harness, listOf("big") + smallIds)
        }
    }

    @Test
    fun `an upload interrupted mid flight recovers and publishes exactly once`() = runBlocking {
        val count = 3
        StormHarness(
            UserPreferences(uploadConcurrency = 2, downloadConcurrency = 2, transferRetryCount = 3)
        ).use { harness ->
            harness.telegram.uploadDelayMs = 30
            val ids = (1..count).map { i ->
                val id = "crash-$i"
                harness.seedUpload(id, "crash-$i.bin", ByteArray(48) { 9 }, i.toLong())
                id
            }
            // The crash: rows left RUNNING with their bytes unpublished —
            // the state a killed process leaves behind. Startup recovery
            // requeues them; the re-run must bind each file to its OWN
            // new message exactly once (the 1.6.15 safeguards' job).
            ids.forEach { id ->
                harness.transfers.setState(
                    "t-$id",
                    TransferState.RUNNING,
                    System.currentTimeMillis()
                )
            }
            harness.transfers.requeueRunning()

            val result = withTimeout(60.seconds) {
                harness.drainer().drain(isStopped = { false }, onTerminalFailure = {})
            }

            assertEquals(TransferDrainResult.COMPLETED, result)
            assertEquals(count, harness.telegram.uploads.size)
            assertBindingsAreOwn(harness, ids)
            ids.forEach { id ->
                assertEquals(TransferState.COMPLETED, harness.transfers.byId("t-$id")!!.state)
            }
        }
    }

    /**
     * The report-37 assertion, as in ParallelTransferStormTest: the
     * row's message id is the message the client created for THAT file,
     * and the caption on it decodes to THAT file's manifest uuid.
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
            assertEquals(
                "remote id matches the issued message",
                "rf-${record.messageId}",
                row.remoteFileId
            )
            val manifest = harness.manifestCodec().decode(record.caption)
            assertEquals("caption on the message names its own file", id, manifest?.fileId)
        }
    }
}
