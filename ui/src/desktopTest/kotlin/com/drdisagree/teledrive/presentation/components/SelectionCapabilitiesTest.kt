package com.drdisagree.teledrive.presentation.components

import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionCapabilitiesTest {

    @Test
    fun `remote and local copies expose download and free up space together`() {
        val capabilities = SelectionCapabilities.of(
            listOf(file(hasRemoteCopy = true, localPath = "C:/local/test.bin"))
        )

        assertTrue(capabilities.canDownload)
        assertTrue(capabilities.canFreeUpSpace)
        assertFalse(capabilities.canUpload)
    }

    @Test
    fun `remote-only file can be downloaded`() {
        val capabilities = SelectionCapabilities.of(
            listOf(file(hasRemoteCopy = true, localPath = null))
        )

        assertTrue(capabilities.canDownload)
        assertFalse(capabilities.canFreeUpSpace)
    }

    @Test
    fun `local-only file can be uploaded but not downloaded`() {
        val capabilities = SelectionCapabilities.of(
            listOf(file(hasRemoteCopy = false, localPath = "C:/local/test.bin"))
        )

        assertTrue(capabilities.canUpload)
        assertFalse(capabilities.canDownload)
        assertFalse(capabilities.canFreeUpSpace)
    }

    private fun file(hasRemoteCopy: Boolean, localPath: String?) = DriveFile(
        id = "test-id",
        folderId = null,
        name = "test.bin",
        sizeBytes = 1L,
        mimeType = "application/octet-stream",
        category = FileCategory.OTHER,
        localPath = localPath,
        contentHash = null,
        chatId = if (hasRemoteCopy) 1L else null,
        messageId = if (hasRemoteCopy) 1L else null,
        remoteFileId = if (hasRemoteCopy) "remote-file" else null,
        remoteUniqueId = if (hasRemoteCopy) "remote-unique" else null,
        backupState = if (hasRemoteCopy) BackupState.BACKED_UP else BackupState.NONE,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        isEncrypted = false,
        width = null,
        height = null,
        durationMs = null,
        trashedAt = null,
        createdAt = 0L,
        modifiedAt = 0L,
        addedAt = 0L
    )
}
