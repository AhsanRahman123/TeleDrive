package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 N1 + N5: the two pure decisions behind the viewer fixes.
 *
 * N1 — [viewerShowsDownloadAction] is the ONE condition both the top
 * bar and the overflow menu consult. The field failure was a video
 * opened from a Gallery album (a remote-backed video that already had
 * a local copy): the pre-1.6.27 condition `hasRemoteCopy &&
 * !hasLocalCopy` removed Download from both surfaces at once. The
 * matrix below is the route audit in executable form — every viewer
 * route (Files, Gallery/album, Favorites, Search, Collection,
 * Transfers-by-fileId) converges on the same DesktopPreviewScreen and
 * the same DriveFile, so the file's category/copy state is the only
 * input that can differ.
 *
 * N5 — [previewHeaderVisible] is the decision DesktopPreviewScreen
 * applies to its header. The headless test scene has no VLC, so a real
 * player's hide cannot be driven there (the bound the 1.6.26 MF-1
 * tests document); the decision table is pinned here instead, and the
 * wiring is pinned by DesktopViewerDownloadButtonTest's screen-level
 * composition plus code inspection of the player's report channel.
 */
class ViewerChromePolicyTest {

    private fun file(
        category: FileCategory,
        localPath: String?,
        chatId: Long?,
        messageId: Long?
    ) = DriveFile(
        id = "file-1",
        folderId = null,
        name = "sample",
        sizeBytes = 10,
        mimeType = "application/octet-stream",
        category = category,
        localPath = localPath,
        contentHash = null,
        chatId = chatId,
        messageId = messageId,
        remoteFileId = "remote-1",
        remoteUniqueId = "unique-1",
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

    private fun remoteOnly(category: FileCategory) =
        file(category, localPath = null, chatId = 1L, messageId = 2L)

    private fun remoteAndLocal(category: FileCategory) =
        file(category, localPath = "/library/sample", chatId = 1L, messageId = 2L)

    private fun localOnly(category: FileCategory) =
        file(category, localPath = "/library/sample", chatId = null, messageId = null)

    // ---- N1: Download action matrix ----

    @Test
    fun `download offered for remote-only photo and video`() {
        assertTrue(viewerShowsDownloadAction(remoteOnly(FileCategory.IMAGE)))
        assertTrue(viewerShowsDownloadAction(remoteOnly(FileCategory.VIDEO)))
    }

    @Test
    fun `download offered for photo and video that already have a local copy`() {
        // The Gallery-album field case: remote-backed media with a
        // local copy showed no Download in the top bar or the menu.
        assertTrue(viewerShowsDownloadAction(remoteAndLocal(FileCategory.IMAGE)))
        assertTrue(viewerShowsDownloadAction(remoteAndLocal(FileCategory.VIDEO)))
    }

    @Test
    fun `download not offered when there is no remote copy to download`() {
        assertFalse(viewerShowsDownloadAction(localOnly(FileCategory.IMAGE)))
        assertFalse(viewerShowsDownloadAction(localOnly(FileCategory.VIDEO)))
    }

    @Test
    fun `non-media categories keep the previous rule unchanged`() {
        // Remote-only: offered before this round and still offered.
        assertTrue(viewerShowsDownloadAction(remoteOnly(FileCategory.AUDIO)))
        assertTrue(viewerShowsDownloadAction(remoteOnly(FileCategory.DOCUMENT)))
        assertTrue(viewerShowsDownloadAction(remoteOnly(FileCategory.ARCHIVE)))
        assertTrue(viewerShowsDownloadAction(remoteOnly(FileCategory.OTHER)))
        // With a local copy: not offered before, still not offered —
        // the relaxation is scoped to photo/video only.
        assertFalse(viewerShowsDownloadAction(remoteAndLocal(FileCategory.AUDIO)))
        assertFalse(viewerShowsDownloadAction(remoteAndLocal(FileCategory.DOCUMENT)))
        assertFalse(viewerShowsDownloadAction(remoteAndLocal(FileCategory.ARCHIVE)))
        assertFalse(viewerShowsDownloadAction(remoteAndLocal(FileCategory.OTHER)))
    }

    // ---- N5: header visibility decision table ----

    private fun header(
        mediaCategory: FileCategory? = null,
        archiveMediaPreviewOpen: Boolean = false,
        fileCategory: FileCategory? = null,
        playerChromeVisible: Boolean = true,
        archivePlayerChromeVisible: Boolean = true,
        imageChromeVisible: Boolean = true
    ) = previewHeaderVisible(
        mediaCategory = mediaCategory,
        archiveMediaPreviewOpen = archiveMediaPreviewOpen,
        fileCategory = fileCategory,
        playerChromeVisible = playerChromeVisible,
        archivePlayerChromeVisible = archivePlayerChromeVisible,
        imageChromeVisible = imageChromeVisible
    )

    @Test
    fun `route media header follows the route player chrome`() {
        assertFalse(
            header(mediaCategory = FileCategory.VIDEO, playerChromeVisible = false)
        )
        assertFalse(
            header(mediaCategory = FileCategory.AUDIO, playerChromeVisible = false)
        )
        assertTrue(
            header(mediaCategory = FileCategory.VIDEO, playerChromeVisible = true)
        )
    }

    @Test
    fun `in-zip media header follows the in-zip player chrome`() {
        // The N5 field case: the viewed file is the ZIP (no route media
        // category), an in-zip video/audio entry is playing, and hiding
        // the player UI must hide the header too.
        assertFalse(
            header(
                fileCategory = FileCategory.ARCHIVE,
                archiveMediaPreviewOpen = true,
                archivePlayerChromeVisible = false
            )
        )
        assertTrue(
            header(
                fileCategory = FileCategory.ARCHIVE,
                archiveMediaPreviewOpen = true,
                archivePlayerChromeVisible = true
            )
        )
    }

    @Test
    fun `zip contents list and non-media entries always show the header`() {
        assertTrue(
            header(
                fileCategory = FileCategory.ARCHIVE,
                archiveMediaPreviewOpen = false,
                archivePlayerChromeVisible = false
            )
        )
        assertTrue(header(fileCategory = FileCategory.DOCUMENT))
        assertTrue(header(fileCategory = null))
    }

    @Test
    fun `photo header follows the image chrome, the reference behaviour`() {
        assertFalse(
            header(fileCategory = FileCategory.IMAGE, imageChromeVisible = false)
        )
        assertTrue(
            header(fileCategory = FileCategory.IMAGE, imageChromeVisible = true)
        )
    }

    @Test
    fun `route media takes priority over the image chrome`() {
        // A video file is not an image, but pin the priority anyway:
        // while a media player owns the surface, its chrome decides.
        assertFalse(
            header(
                mediaCategory = FileCategory.VIDEO,
                fileCategory = FileCategory.VIDEO,
                playerChromeVisible = false,
                imageChromeVisible = true
            )
        )
    }
}
