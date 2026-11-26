package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-archive video playback (vc181 B11): tap-only routing, the pager
 * exclusion, the single-entry sequence a tapped video publishes, and the
 * free-space preflight predicate that refuses an extraction before it
 * starts when the temp dir cannot hold the entry.
 */
class InZipVideoTest {

    private fun entry(name: String, size: Long = 10) = ArchiveEntry(
        name = name,
        sizeBytes = size,
        compressedBytes = size,
        isDirectory = false,
        modifiedAtMillis = 0
    )

    @Test
    fun `video taps play but are never pager previewable`() {
        val video = entry("a/movie.mp4", size = 5_000_000)
        assertEquals(ArchiveEntryTapAction.PLAY_VIDEO, archiveEntryTapAction(video))
        assertFalse(archiveEntryPreviewable(video))
    }

    @Test
    fun `pager indices are unchanged by video entries in the listing`() {
        val listing = listOf(
            entry("a/photo.jpg"),
            entry("a/movie.mp4", size = 5_000_000),
            entry("a/song.mp3"),
            entry("a/clip.mkv", size = 5_000_000),
            entry("a/notes.txt")
        )
        // Exactly the vc180 sequence for the same listing: the archive NAME
        // sort orders the folder's rows clip.mkv, movie.mp4, notes.txt,
        // photo.jpg, song.mp3 — image, audio and text pages only, the two
        // videos contribute no page.
        assertEquals(listOf(4, 0, 2), archivePreviewPagerIndices(listing))
    }

    @Test
    fun `a tapped video publishes a single entry sequence`() {
        assertEquals(listOf(7), archiveVideoPreviewSequence(7))
        assertEquals(0, archivePreviewPagerPosition(archiveVideoPreviewSequence(7), 7))
        // A step from the only page leaves the sequence: the pager cannot move.
        assertEquals(null, archivePreviewPagerTarget(1, 0, 1))
        assertEquals(null, archivePreviewPagerTarget(1, 0, -1))
    }

    @Test
    fun `space preflight allows an entry that fits with margin to spare`() {
        val size = 100L * 1024 * 1024
        assertTrue(
            archiveVideoFitsInFreeSpace(size, size + ARCHIVE_VIDEO_SPACE_MARGIN_BYTES)
        )
        assertTrue(
            archiveVideoFitsInFreeSpace(size, size + ARCHIVE_VIDEO_SPACE_MARGIN_BYTES + 1)
        )
        assertTrue(archiveVideoFitsInFreeSpace(0, ARCHIVE_VIDEO_SPACE_MARGIN_BYTES))
    }

    @Test
    fun `space preflight refuses an entry that cannot fit`() {
        val size = 100L * 1024 * 1024
        // Exactly the entry size free: no margin left.
        assertFalse(archiveVideoFitsInFreeSpace(size, size))
        // One byte short of entry + margin.
        assertFalse(
            archiveVideoFitsInFreeSpace(size, size + ARCHIVE_VIDEO_SPACE_MARGIN_BYTES - 1)
        )
        assertFalse(archiveVideoFitsInFreeSpace(size, 0))
        assertFalse(archiveVideoFitsInFreeSpace(size, size - 1))
    }

    @Test
    fun `space preflight is safe at extreme values`() {
        assertFalse(archiveVideoFitsInFreeSpace(Long.MAX_VALUE, Long.MAX_VALUE))
        assertFalse(archiveVideoFitsInFreeSpace(-1, Long.MAX_VALUE))
        assertTrue(
            archiveVideoFitsInFreeSpace(
                Long.MAX_VALUE - ARCHIVE_VIDEO_SPACE_MARGIN_BYTES,
                Long.MAX_VALUE
            )
        )
    }
}
