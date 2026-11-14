package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.core.common.AppError
import com.drdisagree.teledrive.core.common.AppResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrashRetentionTest {

    private val harness = SyncHarness()
    private val repository = TrashRepositoryImpl(
        fileDao = harness.database.fileDao(),
        folderDao = harness.database.folderDao(),
        publishScheduler = harness.scheduler,
        activeChannel = harness.activeChannel
    )

    @After
    fun tearDown() = harness.close()

    @Test
    fun `permanent deletion actions are blocked and automatic expiry is a no-op`() = runBlocking {
        assertDeletionBlocked(repository.deleteFilesPermanently(listOf("file-id")))
        assertDeletionBlocked(repository.deleteFolderPermanently("folder-id"))
        assertDeletionBlocked(repository.emptyTrash())
        assertEquals(AppResult.Success(0), repository.clearExpired(days = 1))
        assertEquals(AppResult.Success(0), repository.clearExpired(days = 365))
    }

    private fun assertDeletionBlocked(result: AppResult<Unit>) {
        assertTrue(result is AppResult.Failure)
        assertEquals(AppError.RemoteDeletionDisabled, (result as AppResult.Failure).error)
    }
}
