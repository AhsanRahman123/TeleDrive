package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The in-zip pager failure-resync contract (second-pass MF-1): when a pager step's
 * extraction fails or the step is refused, the view model applies
 * [PreviewUiState.withArchivePreviewStepFailure]. The published preview and its
 * position must not move — only the resync count advances, so the pager's snap-back
 * effect (keyed on position + count) re-runs and scrolls back to the published page.
 * PreviewViewModel itself is not host-testable in this repo's setup (no fake
 * repositories / coroutine test harness for it), so the pure state transition the
 * view model applies on every failure/refusal path is what is pinned here; the
 * Compose effect keying is covered by review, not by a host test.
 */
class ArchivePreviewResyncTest {

    private fun publishedState() = PreviewUiState(
        archivePreview = ArchiveEntryPreview.Image("photo.jpg", "/tmp/photo.jpg", entryIndex = 3),
        archivePreviewEntries = listOf(1, 3, 5),
        archivePreviewPosition = 1,
        archivePreviewFileId = "zip-1"
    )

    @Test
    fun `a failed step keeps the published preview and position`() {
        val state = publishedState()

        val after = state.withArchivePreviewStepFailure()

        assertSame(state.archivePreview, after.archivePreview)
        assertEquals(state.archivePreviewPosition, after.archivePreviewPosition)
        assertEquals(state.archivePreviewEntries, after.archivePreviewEntries)
        assertEquals(state.archivePreviewFileId, after.archivePreviewFileId)
    }

    @Test
    fun `a failed step advances the resync count by one`() {
        val state = publishedState()

        assertEquals(0, state.archivePreviewResyncCount)
        assertEquals(1, state.withArchivePreviewStepFailure().archivePreviewResyncCount)
    }

    @Test
    fun `repeated failures keep advancing the count so each one re-triggers the snap-back`() {
        // The desync case: the pager is settled on an unpublished page and the
        // published position never changes between failures, so the count alone
        // must produce a fresh state value every time.
        val afterThree = publishedState()
            .withArchivePreviewStepFailure()
            .withArchivePreviewStepFailure()
            .withArchivePreviewStepFailure()

        assertEquals(3, afterThree.archivePreviewResyncCount)
        assertEquals(1, afterThree.archivePreviewPosition)
    }
}
