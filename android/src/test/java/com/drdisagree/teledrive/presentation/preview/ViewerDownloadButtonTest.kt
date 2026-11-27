package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.isTransferMessage
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.message_download_queued
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc183 item 8: the normal viewer's top-bar Download button. The button itself
 * is Compose chrome in PreviewScreen; what is pinned here is the pure routing
 * seam it is built on — which categories get the button (photos and videos,
 * from Files or Gallery alike, since both open this same screen) — and that
 * the message its action produces (PreviewViewModel.download ->
 * enqueueDownload -> message_download_queued, the Files list's own download
 * path) is a transfer message, so the snackbar carries the View action that
 * opens Transfers, exactly like a list-row download.
 */
class ViewerDownloadButtonTest {

    @Test
    fun `photos and videos get the viewer download button`() {
        assertTrue(showsViewerDownloadButton(FileCategory.IMAGE))
        assertTrue(showsViewerDownloadButton(FileCategory.VIDEO))
    }

    @Test
    fun `other preview types keep their existing chrome without the button`() {
        assertFalse(showsViewerDownloadButton(FileCategory.AUDIO))
        assertFalse(showsViewerDownloadButton(FileCategory.DOCUMENT))
        assertFalse(showsViewerDownloadButton(FileCategory.ARCHIVE))
        assertFalse(showsViewerDownloadButton(FileCategory.OTHER))
    }

    @Test
    fun `the routed download message carries the transfers view action`() {
        assertTrue(UiText.Resource(Res.string.message_download_queued).isTransferMessage())
    }
}
