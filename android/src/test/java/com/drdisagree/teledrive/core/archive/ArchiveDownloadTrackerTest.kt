package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import com.drdisagree.teledrive.testing.FakeTransferDao
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc179: ArchiveDownloadTracker writes report-only ARCHIVE_DOWNLOAD rows that
 * mirror an in-ZIP extraction's lifecycle, with throttled progress writes.
 * Uses the faithful in-memory TransferDao fake (Room needs a Context in host tests).
 */
class ArchiveDownloadTrackerTest {

    private val dao = FakeTransferDao()
    private val jobs = ArchiveExtractionJobs()
    private val tracker = ArchiveDownloadTracker(dao, jobs)

    /** Bounded poll for the tracker's asynchronous progress writes. */
    private suspend fun awaitTrue(description: String, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(10)
        }
        throw AssertionError("Timed out waiting for: $description")
    }

    @Test
    fun `begin inserts a running archive download row with no file or telegram references`() =
        runBlocking {
            val id = tracker.begin("photo.jpg", 123_456L)

            assertNotNull(id)
            val row = dao.byId(id!!)!!
            assertEquals(TransferType.ARCHIVE_DOWNLOAD, row.type)
            assertEquals(TransferState.RUNNING, row.state)
            assertEquals("photo.jpg", row.displayName)
            assertEquals(123_456L, row.sizeBytes)
            assertEquals(0L, row.transferredBytes)
            assertNull(row.fileId)
            assertNull(row.chatId)
            assertNull(row.messageId)
            assertNull(row.remoteFileId)
            assertNull(row.telegramFileId)
            assertNull(row.localPath)
            assertEquals(row.createdAt, row.updatedAt)
        }

    @Test
    fun `progress writes are throttled by time and advance, and always land at the total`() =
        runBlocking {
            val size = 1_000_000L
            val id = tracker.begin("big.bin", size)!!

            // Small increment, immediately: below both thresholds, no write yet.
            tracker.progress(id, 1_024L)
            delay(100)
            assertEquals(0L, dao.byId(id)!!.transferredBytes)

            // A >=256 KiB jump since the last written sample flushes.
            tracker.progress(id, 300_000L)
            awaitTrue("256 KiB jump written") { dao.byId(id)!!.transferredBytes == 300_000L }

            // Reaching the total always flushes, whatever the thresholds say.
            tracker.progress(id, size)
            awaitTrue("total written") { dao.byId(id)!!.transferredBytes == size }
        }

    // ---- vc181 A2: honest speed (cumulative average) and monotonic progress ----

    @Test
    fun `burst samples report the cumulative average speed, not the burst rate`() =
        runBlocking {
            var now = 0L
            val clocked = ArchiveDownloadTracker(dao, jobs) { now }
            val size = 4_000_000L
            val id = clocked.begin("photo.jpg", size)!!

            // Three seconds of network wait, then a 256 KiB inflater burst lands:
            // the old per-write window divided the burst by its ~1 ms decompression
            // time and wrote 250.0 MB/s. The cumulative average over the whole
            // extraction so far is ~85 KiB/s.
            now = 3_000L
            clocked.progress(id, 262_144L)
            awaitTrue("first burst written") { dao.byId(id)!!.transferredBytes == 262_144L }
            assertEquals(262_144L * 1_000L / 3_000L, dao.byId(id)!!.speedBytesPerSecond)

            // A second identical burst 1 ms later must stay at the true average.
            now = 3_001L
            clocked.progress(id, 524_288L)
            awaitTrue("second burst written") { dao.byId(id)!!.transferredBytes == 524_288L }
            val speed = dao.byId(id)!!.speedBytesPerSecond
            assertEquals(524_288L * 1_000L / 3_001L, speed)
            assertTrue("burst speed is still a fantasy number: $speed", speed < 1_000_000L)
        }

    @Test
    fun `speed stays zero until one second of the extraction has elapsed`() = runBlocking {
        var now = 0L
        val clocked = ArchiveDownloadTracker(dao, jobs) { now }
        val id = clocked.begin("fast.bin", 10_000_000L)!!

        now = 500L
        clocked.progress(id, 300_000L)
        awaitTrue("early write landed") { dao.byId(id)!!.transferredBytes == 300_000L }
        assertEquals(0L, dao.byId(id)!!.speedBytesPerSecond)
    }

    @Test
    fun `out of order and repeated samples never move progress backwards`() = runBlocking {
        var now = 0L
        val clocked = ArchiveDownloadTracker(dao, jobs) { now }
        val id = clocked.begin("photo.jpg", 1_000_000L)!!

        now = 2_000L
        clocked.progress(id, 500_000L)
        awaitTrue("half written") { dao.byId(id)!!.transferredBytes == 500_000L }

        // Late, replayed and duplicate samples — the old unordered-launch
        // regression — are dropped before any write is launched.
        clocked.progress(id, 300_000L)
        clocked.progress(id, 499_999L)
        clocked.progress(id, 500_000L)
        delay(150)
        assertEquals(500_000L, dao.byId(id)!!.transferredBytes)
    }

    @Test
    fun `dao progress writes never regress transferred bytes`() = runBlocking {
        // The MAX(transferredBytes, :transferred) guard, mirrored by the fake:
        // even a directly out-of-order pair of writes cannot move a row backwards.
        val id = tracker.begin("guard.bin", 1_000L)!!
        dao.updateProgress(id, 800L, 10L, System.currentTimeMillis())
        dao.updateProgress(id, 200L, 10L, System.currentTimeMillis())
        assertEquals(800L, dao.byId(id)!!.transferredBytes)
    }

    @Test
    fun `complete sets the local path and completed state with full bytes`() = runBlocking {
        val id = tracker.begin("song.mp3", 777L)!!
        tracker.complete(id, "/Downloads/TeleDrive/song.mp3")

        val row = dao.byId(id)!!
        assertEquals(TransferState.COMPLETED, row.state)
        assertEquals("/Downloads/TeleDrive/song.mp3", row.localPath)
        assertEquals(777L, row.transferredBytes)
        assertNotNull(row.completedAt)
        assertTrue(jobs.activeIds().isEmpty())
    }

    @Test
    fun `fail marks the row failed with the message`() = runBlocking {
        val id = tracker.begin("doc.pdf", 10L)!!
        tracker.fail(id, "CRC mismatch")

        val row = dao.byId(id)!!
        assertEquals(TransferState.FAILED, row.state)
        assertEquals("CRC mismatch", row.errorMessage)
        assertTrue(jobs.activeIds().isEmpty())
    }

    @Test
    fun `complete on a paused row leaves it paused with no local path`() = runBlocking {
        val id = tracker.begin("song.mp3", 777L)!!
        dao.setState(id, TransferState.PAUSED, System.currentTimeMillis())
        tracker.complete(id, "/Downloads/TeleDrive/song.mp3")

        val row = dao.byId(id)!!
        assertEquals(TransferState.PAUSED, row.state)
        assertNull(row.localPath)
    }

    @Test
    fun `fail on a cancelled row leaves it cancelled with no error message`() = runBlocking {
        val id = tracker.begin("doc.pdf", 10L)!!
        dao.setState(id, TransferState.CANCELLED, System.currentTimeMillis())
        tracker.fail(id, "CRC mismatch")

        val row = dao.byId(id)!!
        assertEquals(TransferState.CANCELLED, row.state)
        assertNull(row.errorMessage)
    }

    @Test
    fun `ownerStopped cancels a still running row`() = runBlocking {
        val id = tracker.begin("a.txt", 10L)!!
        tracker.ownerStopped(id)
        assertEquals(TransferState.CANCELLED, dao.byId(id)!!.state)
    }

    @Test
    fun `ownerStopped leaves a row the repository already paused untouched`() = runBlocking {
        val id = tracker.begin("a.txt", 10L)!!
        dao.setState(id, TransferState.PAUSED, System.currentTimeMillis())
        tracker.ownerStopped(id)
        assertEquals(TransferState.PAUSED, dao.byId(id)!!.state)
    }

    @Test
    fun `attach registers the job so the registry can cancel it`() = runBlocking {
        val id = tracker.begin("a.txt", 10L)!!
        val job = Job()
        tracker.attach(id, job)

        assertTrue(jobs.activeIds().contains(id))
        assertTrue(jobs.cancel(id))
        assertTrue(job.isCancelled)
    }

    @Test
    fun `null record ids are no-ops and the NoOp reporter never tracks`() = runBlocking {
        tracker.attach(null, Job())
        tracker.progress(null, 100L)
        tracker.complete(null, "/nowhere")
        tracker.fail(null, "x")
        tracker.ownerStopped(null)
        assertTrue(jobs.activeIds().isEmpty())

        assertNull(NoOpArchiveDownloadReporter.begin("x", 1L))
        NoOpArchiveDownloadReporter.attach(null, Job())
        NoOpArchiveDownloadReporter.progress(null, 1L)
        NoOpArchiveDownloadReporter.complete(null, "/x")
        NoOpArchiveDownloadReporter.fail(null, null)
        NoOpArchiveDownloadReporter.ownerStopped(null)
    }
}

class ArchiveExtractionJobsTest {

    @Test
    fun `registration auto-unregisters when the job completes`() = runBlocking {
        val jobs = ArchiveExtractionJobs()
        val job = Job()
        jobs.register("rec-1", job)
        assertEquals(setOf("rec-1"), jobs.activeIds())

        job.complete()
        // invokeOnCompletion fires synchronously for an already-completing job on
        // this dispatcher-less Job; poll briefly to stay implementation-agnostic.
        val deadline = System.currentTimeMillis() + 2_000
        while (jobs.activeIds().isNotEmpty() && System.currentTimeMillis() < deadline) {
            delay(10)
        }
        assertTrue(jobs.activeIds().isEmpty())
    }

    @Test
    fun `cancel of an unknown id returns false and unregister drops the entry`() {
        val jobs = ArchiveExtractionJobs()
        assertEquals(false, jobs.cancel("missing"))
        val job = Job()
        jobs.register("rec-2", job)
        jobs.unregister("rec-2")
        assertTrue(jobs.activeIds().isEmpty())
        assertEquals(false, jobs.cancel("rec-2"))
    }
}
