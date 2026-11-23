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
