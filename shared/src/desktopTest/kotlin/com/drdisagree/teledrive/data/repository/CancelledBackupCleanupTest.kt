package com.drdisagree.teledrive.data.repository

import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.TransferEntity
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CancelledBackupCleanupTest {

    private val harness = SyncHarness()
    private val files = harness.database.fileDao()
    private val transfers = harness.database.transferDao()

    @After
    fun tearDown() = harness.close()

    @Test
    fun `a cancelled backup's file record is removed`() = runBlocking {
        files.upsert(file("f1"))
        transfers.upsert(transfer("t1", "f1", TransferState.CANCELLED))

        files.deleteCancelledBackupEntries()

        assertNull(files.byId("f1"))
    }

    @Test
    fun `a cancelled manual upload's file record is removed`() = runBlocking {
        files.upsert(file("f1"))
        transfers.upsert(manualTransfer("t1", "f1", TransferState.CANCELLED))

        files.deleteCancelledBackupEntries()

        assertNull(files.byId("f1"))
    }

    @Test
    fun `a manual upload queued again after a cancel keeps its record`() = runBlocking {
        files.upsert(file("f1"))
        transfers.upsert(manualTransfer("t1", "f1", TransferState.CANCELLED))
        transfers.upsert(manualTransfer("t2", "f1", TransferState.QUEUED))

        files.deleteCancelledBackupEntries()

        assertNotNull(files.byId("f1"))
    }

    @Test
    fun `a cancelled transfer for a file that reached the channel keeps its record`() = runBlocking {
        files.upsert(file("f1").copy(messageId = 42L))
        transfers.upsert(manualTransfer("t1", "f1", TransferState.CANCELLED))

        files.deleteCancelledBackupEntries()

        assertNotNull(files.byId("f1"))
    }

    @Test
    fun `a file queued again after a cancelled backup keeps its record`() = runBlocking {
        files.upsert(file("f1"))
        transfers.upsert(transfer("t1", "f1", TransferState.CANCELLED))
        transfers.upsert(transfer("t2", "f1", TransferState.QUEUED))

        files.deleteCancelledBackupEntries()

        assertNotNull(files.byId("f1"))
    }

    @Test
    fun `upload completion stamps modifiedAt so fresh uploads sort on top`() = runBlocking {
        files.upsert(file("f1"))

        files.setRemoteMapping(
            id = "f1",
            chatId = TEST_CHAT,
            messageId = 7L,
            remoteFileId = "r7",
            remoteUniqueId = "u7",
            state = BackupState.BACKED_UP,
            modifiedAt = 999L
        )

        org.junit.Assert.assertEquals(999L, files.byId("f1")?.modifiedAt)
    }

    private fun file(id: String) = FileEntity(
        id = id,
        folderId = null,
        name = "$id.jpg",
        sizeBytes = 10,
        mimeType = "image/jpeg",
        category = FileCategory.IMAGE,
        localPath = "/storage/emulated/0/DCIM/$id.jpg",
        contentHash = null,
        chatId = TEST_CHAT,
        messageId = null,
        remoteFileId = null,
        remoteUniqueId = null,
        backupState = BackupState.QUEUED,
        createdAt = 1,
        modifiedAt = 1,
        addedAt = 1
    )

    private fun manualTransfer(id: String, fileId: String, state: TransferState) = TransferEntity(
        id = id,
        type = TransferType.UPLOAD,
        fileId = fileId,
        displayName = fileId,
        localPath = null,
        chatId = TEST_CHAT,
        messageId = null,
        remoteFileId = null,
        sizeBytes = 10,
        state = state,
        backupSessionId = null,
        createdAt = 1,
        updatedAt = 1
    )

    private fun transfer(id: String, fileId: String, state: TransferState) = TransferEntity(
        id = id,
        type = TransferType.BACKUP,
        fileId = fileId,
        displayName = fileId,
        localPath = null,
        chatId = TEST_CHAT,
        messageId = null,
        remoteFileId = null,
        sizeBytes = 10,
        state = state,
        backupSessionId = "s1",
        createdAt = 1,
        updatedAt = 1
    )
}
