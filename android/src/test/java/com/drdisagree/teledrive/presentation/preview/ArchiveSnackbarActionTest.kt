package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.isTransferMessage
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.message_download_queued
import com.drdisagree.teledrive.resources.preview_archive_entries_saved
import com.drdisagree.teledrive.resources.preview_archive_entry_saved
import com.drdisagree.teledrive.resources.preview_archive_failed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 2 wiring: the ZIP download completion messages are transfer messages, so
 * [com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages] gives them the
 * same "View" action (label snackbar_view_transfers, opening Transfers through
 * LocalOpenTransfers) that the normal download-queued message gets.
 */
class ArchiveSnackbarActionTest {

    @Test
    fun `zip completion messages carry the view action like normal downloads`() {
        assertTrue(UiText.Resource(Res.string.message_download_queued).isTransferMessage())
        assertTrue(
            UiText.Resource(Res.string.preview_archive_entry_saved, "song.mp3").isTransferMessage()
        )
        assertTrue(
            UiText.Resource(Res.string.preview_archive_entries_saved, 3).isTransferMessage()
        )
    }

    @Test
    fun `unrelated preview messages do not gain the action`() {
        assertFalse(UiText.Resource(Res.string.preview_archive_failed).isTransferMessage())
        assertFalse(UiText.Plain("Saved song.mp3 to Downloads").isTransferMessage())
    }
}
