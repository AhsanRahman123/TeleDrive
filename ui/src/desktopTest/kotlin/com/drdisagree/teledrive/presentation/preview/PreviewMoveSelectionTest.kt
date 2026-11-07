package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewMoveSelectionTest {

    @Test
    fun `moving media advances to the next media and skips non-media files`() {
        val files = listOf(
            file("image-a", FileCategory.IMAGE),
            file("document", FileCategory.DOCUMENT),
            file("video-b", FileCategory.VIDEO),
            file("image-c", FileCategory.IMAGE)
        )

        val result = previewAfterMove(files, files[0], currentIndex = 0)

        assertEquals(listOf("video-b", "image-c"), result.files.map { it.id })
        assertEquals(0, result.initialIndex)
        assertFalse(result.closeViewer)
    }

    @Test
    fun `moving the last media item selects the previous media item`() {
        val files = listOf(
            file("image-a", FileCategory.IMAGE),
            file("video-b", FileCategory.VIDEO)
        )

        val result = previewAfterMove(files, files[1], currentIndex = 1)

        assertEquals(listOf("image-a"), result.files.map { it.id })
        assertEquals(0, result.initialIndex)
        assertFalse(result.closeViewer)
    }

    @Test
    fun `moving the final media item closes viewer even when documents remain`() {
        val files = listOf(
            file("image-a", FileCategory.IMAGE),
            file("document", FileCategory.DOCUMENT),
            file("audio", FileCategory.AUDIO)
        )

        val result = previewAfterMove(files, files[0], currentIndex = 0)

        assertTrue(result.files.isEmpty())
        assertEquals(0, result.initialIndex)
        assertTrue(result.closeViewer)
    }

    @Test
    fun `moving a non-media file preserves the remaining sequence`() {
        val files = listOf(
            file("image-a", FileCategory.IMAGE),
            file("document", FileCategory.DOCUMENT),
            file("video-b", FileCategory.VIDEO)
        )

        val result = previewAfterMove(files, files[1], currentIndex = 1)

        assertEquals(listOf("image-a", "video-b"), result.files.map { it.id })
        assertEquals(1, result.initialIndex)
        assertFalse(result.closeViewer)
    }

    private fun file(id: String, category: FileCategory) = DriveFile(
        id = id,
        folderId = "folder",
        name = id,
        sizeBytes = 1L,
        mimeType = "application/octet-stream",
        category = category,
        localPath = null,
        contentHash = null,
        chatId = null,
        messageId = null,
        remoteFileId = null,
        remoteUniqueId = null,
        backupState = BackupState.NONE,
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
