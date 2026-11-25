package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.data.local.dao.TransferDao
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.TransferState
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Report-51 hang pins (1.6.22): an archive extraction's Transfers record must ALWAYS
 * reach a terminal state.
 *
 * The pre-fix mechanism: the tracker's terminal writes ran as plain suspend calls in
 * the extraction coroutine. Once that coroutine was cancelled, the DAO read inside
 * `ownerStopped`/`fail` threw CancellationException before writing and the row stayed
 * RUNNING at 0% forever ("No recent progress"). Also, an internal byte-source timeout
 * (a [TimeoutCancellationException] nobody asked for) was reported as a user stop.
 */
class ArchiveTerminalWriteTest {

    /** Minimal in-memory TransferDao (Proxy) covering only the methods the tracker calls. */
    private class MiniTransferDao {
        val rows = LinkedHashMap<String, TransferEntity>()

        val dao: TransferDao = Proxy.newProxyInstance(
            TransferDao::class.java.classLoader,
            arrayOf(TransferDao::class.java),
            InvocationHandler { _, method, args ->
                when (method.name) {
                    "upsert" -> {
                        val e = args[0] as TransferEntity
                        rows[e.id] = e
                        Unit
                    }
                    "byId" -> rows[args[0] as String]
                    "updateProgress" -> {
                        val id = args[0] as String
                        rows[id]?.let {
                            rows[id] = it.copy(
                                transferredBytes = (args[1] as Number).toLong(),
                                updatedAt = (args[3] as Number).toLong()
                            )
                        }
                        Unit
                    }
                    "setLocalPath" -> {
                        val id = args[0] as String
                        rows[id]?.let { rows[id] = it.copy(localPath = args[1] as String?) }
                        Unit
                    }
                    "setCompleted" -> {
                        val id = args[0] as String
                        rows[id]?.let {
                            rows[id] = it.copy(
                                state = TransferState.COMPLETED,
                                transferredBytes = it.sizeBytes,
                                completedAt = (args[1] as Number).toLong()
                            )
                        }
                        Unit
                    }
                    "setFailed" -> {
                        val id = args[0] as String
                        rows[id]?.let {
                            rows[id] = it.copy(
                                state = args[1] as TransferState,
                                errorMessage = args[2] as String?,
                                updatedAt = (args[3] as Number).toLong()
                            )
                        }
                        Unit
                    }
                    "setState" -> {
                        val id = args[0] as String
                        rows[id]?.let {
                            rows[id] = it.copy(
                                state = args[1] as TransferState,
                                updatedAt = (args[2] as Number).toLong()
                            )
                        }
                        Unit
                    }
                    else -> throw UnsupportedOperationException(method.name)
                }
            }
        ) as TransferDao
    }

    private fun fixture(): Triple<MiniTransferDao, ArchiveExtractionJobs, ArchiveDownloadTracker> {
        val mini = MiniTransferDao()
        val jobs = ArchiveExtractionJobs()
        return Triple(mini, jobs, ArchiveDownloadTracker(mini.dao, jobs))
    }

    @Test
    fun `internal timeout cancellation lands FAILED, never stuck RUNNING`() = runBlocking {
        val (mini, _, tracker) = fixture()
        val id = tracker.begin("song.mp3", 100L)!!

        // The extraction coroutine dies of its OWN withTimeout (the byte source's
        // download-window deadline) while the surrounding scope stays alive — the
        // exact masquerade from the field hang.
        val extraction = async {
            try {
                withTimeout(50) { delay(60_000) }
            } catch (failure: TimeoutCancellationException) {
                tracker.ownerStopped(id, failure)
                throw failure
            }
        }
        runCatching { extraction.await() }

        val row = mini.rows[id]!!
        assertEquals(TransferState.FAILED, row.state)
        assertNotNull(row.errorMessage)
    }

    @Test
    fun `user cancel via the registry lands CANCELLED as before`() = runBlocking {
        val (mini, jobs, tracker) = fixture()
        val id = tracker.begin("song.mp3", 100L)!!

        val extraction = async {
            try {
                delay(60_000)
            } catch (failure: CancellationException) {
                tracker.ownerStopped(id, failure)
                throw failure
            }
        }
        tracker.attach(id, extraction)
        delay(50)
        assertEquals(true, jobs.cancel(id))
        runCatching { extraction.await() }

        assertEquals(TransferState.CANCELLED, mini.rows[id]!!.state)
    }

    @Test
    fun `terminal fail write executes even when the calling coroutine is already cancelled`() =
        runBlocking {
            val (mini, _, tracker) = fixture()
            val id = tracker.begin("song.mp3", 100L)!!

            // The extraction job is cancelled from OUTSIDE the registry (teardown);
            // its catch block reports the terminal state from an already-cancelled
            // context. Pre-fix, the DAO read threw CancellationException and the row
            // stayed RUNNING forever.
            val scope = CoroutineScope(Job())
            val extraction = scope.async {
                try {
                    delay(60_000)
                } catch (failure: CancellationException) {
                    tracker.fail(id, "boom")
                    throw failure
                }
            }
            delay(50)
            extraction.cancelAndJoin()

            val row = mini.rows[id]!!
            assertEquals(TransferState.FAILED, row.state)
            assertEquals("boom", row.errorMessage)
        }

    @Test
    fun `terminal ownerStopped write executes even when the calling coroutine is already cancelled`() =
        runBlocking {
            val (mini, jobs, tracker) = fixture()
            val id = tracker.begin("song.mp3", 100L)!!

            val scope = CoroutineScope(Job())
            val extraction = scope.async {
                try {
                    delay(60_000)
                } catch (failure: CancellationException) {
                    tracker.ownerStopped(id, failure)
                    throw failure
                }
            }
            tracker.attach(id, extraction)
            delay(50)
            jobs.cancel(id)
            extraction.join()

            assertEquals(TransferState.CANCELLED, mini.rows[id]!!.state)
        }

    @Test
    fun `registry cancel mark is consumed so a recycled id is not misread`() = runBlocking {
        val (_, jobs, tracker) = fixture()
        val id = tracker.begin("a.bin", 1L)!!
        val job = Job()
        tracker.attach(id, job)
        assertEquals(true, jobs.cancel(id))
        assertEquals(true, jobs.wasCancelledViaRegistry(id))
        tracker.ownerStopped(id, CancellationException("cancelled"))
        assertEquals(false, jobs.wasCancelledViaRegistry(id))
    }
}
