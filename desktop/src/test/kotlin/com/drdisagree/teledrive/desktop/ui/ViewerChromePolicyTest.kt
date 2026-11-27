package com.drdisagree.teledrive.desktop.ui

import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 item P5 — pins [previewHeaderVisible], the header decision
 * DesktopPreviewScreen applies to its top bar. The coupling rule: a
 * surface's header hides WITH that surface's UI. The route player and
 * the photo viewer already behaved this way; the in-zip media preview
 * was the exception (its player reported nowhere, so the header stayed
 * up over a hidden player UI). The headless scene has no VLC, so a
 * real player's hide cannot be driven there — the hide path is pinned
 * at this decision seam, the same bound as 1.6.26 MF-1.
 */
class ViewerChromePolicyTest {

    private fun visible(
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
    fun `route media player chrome drives the header`() {
        assertTrue(visible(mediaCategory = FileCategory.VIDEO, playerChromeVisible = true))
        assertFalse(visible(mediaCategory = FileCategory.VIDEO, playerChromeVisible = false))
        assertFalse(visible(mediaCategory = FileCategory.AUDIO, playerChromeVisible = false))
    }

    @Test
    fun `in-zip media preview chrome drives the header while it is open`() {
        // The viewed file is the ZIP itself: no route media category,
        // and its category is not IMAGE — before P5 this fell through
        // to always-visible.
        assertTrue(
            visible(
                fileCategory = FileCategory.ARCHIVE,
                archiveMediaPreviewOpen = true,
                archivePlayerChromeVisible = true
            )
        )
        assertFalse(
            visible(
                fileCategory = FileCategory.ARCHIVE,
                archiveMediaPreviewOpen = true,
                archivePlayerChromeVisible = false
            )
        )
    }

    @Test
    fun `route media wins over an in-zip report`() {
        assertTrue(
            visible(
                mediaCategory = FileCategory.VIDEO,
                archiveMediaPreviewOpen = true,
                playerChromeVisible = true,
                archivePlayerChromeVisible = false
            )
        )
    }

    @Test
    fun `image chrome drives the header for still images`() {
        assertTrue(visible(fileCategory = FileCategory.IMAGE, imageChromeVisible = true))
        assertFalse(visible(fileCategory = FileCategory.IMAGE, imageChromeVisible = false))
    }

    @Test
    fun `zip contents list and other files always show the header`() {
        assertTrue(
            visible(
                fileCategory = FileCategory.ARCHIVE,
                archiveMediaPreviewOpen = false,
                archivePlayerChromeVisible = false,
                playerChromeVisible = false,
                imageChromeVisible = false
            )
        )
        assertTrue(visible(fileCategory = null))
    }
}
