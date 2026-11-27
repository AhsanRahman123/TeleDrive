package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PC 1.6.27 item N7: progress writes through the production DAO are
 * MAX-monotonic in transferredBytes. A re-attached download execution
 * whose TDLib counter restarted (or replayed lower) must never drag a
 * row's displayed percentage backwards, while its speed sample still
 * lands. Forward movement and the RUNNING state guard are unchanged.
 */
class TransferProgressMonotonicTest {

    private val harness = SyncHarness()
    private val database = harness.database

    @After
    fun tearDown() {
        harness.close()
    }

    private suspend fun runningRow(id: String, transferred: Long) {
        database.transferDao().upsert(
            TransferEntity(
                id = id,
                type = TransferType.DOWNLOAD,
                fileId = null,
                displayName = "$id.bin",
                localPath = null,
                chatId = null,
                messageId = null,
                remoteFileId = null,
                sizeBytes = 100_000,
                transferredBytes = transferred,
                speedBytesPerSecond = 0,
                state = TransferState.RUNNING,
                createdAt = 1,
                updatedAt = 1
            )
        )
    }

    @Test
    fun `a lower byte write never regresses transferred bytes but still updates speed`() =
        runBlocking {
            runningRow("dl-1", transferred = 40_000)
            val dao = database.transferDao()

            // Re-attach with a restarted counter: bytes lower, speed fresh.
            dao.updateProgress("dl-1", transferred = 5_000, speed = 1_234, updatedAt = 2)
            val afterRestart = dao.byId("dl-1")!!
            assertEquals(40_000, afterRestart.transferredBytes)
            assertEquals(1_234, afterRestart.speedBytesPerSecond)

            // Genuine forward movement still lands exactly.
            dao.updateProgress("dl-1", transferred = 41_500, speed = 2_000, updatedAt = 3)
            val afterForward = dao.byId("dl-1")!!
            assertEquals(41_500, afterForward.transferredBytes)
            assertEquals(2_000, afterForward.speedBytesPerSecond)
        }

    @Test
    fun `progress writes still land only on running rows`() = runBlocking {
        runningRow("dl-2", transferred = 10_000)
        val dao = database.transferDao()
        dao.setState("dl-2", TransferState.PAUSED, updatedAt = 2)

        dao.updateProgress("dl-2", transferred = 99_000, speed = 9_999, updatedAt = 3)
        val paused = dao.byId("dl-2")!!
        assertEquals(10_000, paused.transferredBytes)
        assertEquals(0, paused.speedBytesPerSecond)
    }
}
