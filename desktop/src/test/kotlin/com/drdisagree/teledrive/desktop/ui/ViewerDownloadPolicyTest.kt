package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 item P1: the route × media-type audit of the PC viewer's Download
 * action, in executable form. Every PC viewer route (Files, Gallery,
 * Gallery album, Favorites/Collection, Search, Transfers-by-fileId)
 * converges on DesktopPreviewScreen with the same DriveFile, so the
 * audit dimension that decides the action is the file's category ×
 * copy state, pinned here through [viewerShowsDownloadAction] — the
 * one condition both the top bar and the overflow menu use.
 *
 * The Gallery-album field case is the (VIDEO, remote + local) cell:
 * before the fix it was false (no Download anywhere in the viewer);
 * the in-zip route is audited separately — its overlay bar carries
 * Download unconditionally for image/video/audio/text/pdf entries
 * (DesktopArchiveViewerParityTest pins the image case).
 */
class ViewerDownloadPolicyTest {

    private fun file(
        category: FileCategory,
        remote: Boolean,
        local: Boolean
    ) = DriveFile(
        id = "f",
        folderId = null,
        name = "file",
        sizeBytes = 10,
        mimeType = "application/octet-stream",
        category = category,
        localPath = if (local) "/tmp/file" else null,
        contentHash = null,
        chatId = if (remote) 1L else null,
        messageId = if (remote) 2L else null,
        remoteFileId = if (remote) "remote-1" else null,
        remoteUniqueId = if (remote) "unique-1" else null,
        backupState = BackupState.BACKED_UP,
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

    @Test
    fun `media with a remote copy always offers Download, with or without a local copy`() {
        for (category in listOf(FileCategory.VIDEO, FileCategory.IMAGE, FileCategory.AUDIO)) {
            assertTrue(
                "$category remote-only must offer Download",
                viewerShowsDownloadAction(file(category, remote = true, local = false))
            )
            assertTrue(
                "$category remote+local (the Gallery-album case) must offer Download",
                viewerShowsDownloadAction(file(category, remote = true, local = true))
            )
        }
    }

    @Test
    fun `media without a remote copy never offers Download`() {
        for (category in listOf(FileCategory.VIDEO, FileCategory.IMAGE, FileCategory.AUDIO)) {
            assertFalse(
                viewerShowsDownloadAction(file(category, remote = false, local = true))
            )
            assertFalse(
                viewerShowsDownloadAction(file(category, remote = false, local = false))
            )
        }
    }

    @Test
    fun `non-media keeps the previous rule - remote and not yet downloaded`() {
        for (category in listOf(FileCategory.DOCUMENT, FileCategory.ARCHIVE, FileCategory.OTHER)) {
            assertTrue(
                "$category remote-only must offer Download (unchanged)",
                viewerShowsDownloadAction(file(category, remote = true, local = false))
            )
            assertFalse(
                "$category remote+local must NOT gain Download (unchanged)",
                viewerShowsDownloadAction(file(category, remote = true, local = true))
            )
            assertFalse(
                viewerShowsDownloadAction(file(category, remote = false, local = false))
            )
        }
    }
}
