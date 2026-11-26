package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-archive video playback (vc181 B11): tap-only routing, the pager
 * exclusion, the single-entry sequence a tapped video publishes, and the
 * free-space preflight predicate that refuses an extraction before it
 * starts when the temp dir cannot hold the entry.
 *
 * vc182 adds the video queue: a tapped video still publishes the single-entry
 * overlay sequence, PLUS the archive's video queue and the tapped video's
 * position in it, which the player's SkipPrevious/SkipNext buttons step
 * through (clamped, no wrap) without videos ever joining the swipe pager.
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
    fun `a tapped video also publishes its queue and position in it`() {
        // vc182 contract: the overlay sequence stays single-entry (above),
        // and alongside it the view model stores the video queue plus the
        // tapped video's position — the pair the Skip buttons step through.
        val listing = listOf(
            entry("a/first.mp4"),
            entry("a/photo.jpg"),
            entry("b/second.mp4"),
            entry("b/third.mkv")
        )
        val queue = archiveVideoQueueIndices(listing)
        assertEquals(listOf(0, 2, 3), queue)
        assertEquals(1, archivePreviewPagerPosition(queue, 2))
        // The overlay sequence for that same tap is still just the video.
        assertEquals(listOf(2), archiveVideoPreviewSequence(2))
    }

    // ---- Video queue (vc182) ----

    @Test
    fun `video queue follows the flattened display order and excludes non-videos`() {
        val listing = listOf(
            entry("b/clip2.mp4"),     // 0
            entry("a/movie.mp4"),     // 1
            entry("a/photo.jpg"),     // 2 image: excluded
            entry("b/clip1.mp4"),     // 3
            entry("song.mp3"),        // 4 audio: excluded
            entry("root.mkv"),        // 5
            entry("notes.txt"),       // 6 text: excluded
            entry("fake.mp4", 0).copy(isDirectory = true) // 7 directory: excluded
        )
        // Fully expanded NAME-ascending rows, folders first per level:
        // a/movie(1), a/photo(2), b/clip1(3), b/clip2(0), fake(7), notes(6),
        // root(5), song(4) — videos only, in that display order.
        assertEquals(listOf(1, 3, 0, 5), archiveVideoQueueIndices(listing))
    }

    @Test
    fun `video queue order follows the archive sort`() {
        val listing = listOf(
            entry("a.mp4", 30),
            entry("b.mp4", 10),
            entry("c.mp4", 20)
        )
        assertEquals(
            listOf(0, 2, 1),
            archiveVideoQueueIndices(listing, FileSortField.SIZE, SortDirection.DESCENDING)
        )
        assertEquals(
            listOf(1, 2, 0),
            archiveVideoQueueIndices(listing, FileSortField.SIZE, SortDirection.ASCENDING)
        )
    }

    @Test
    fun `video queue keeps duplicate names as distinct indices`() {
        val listing = listOf(
            entry("dup.mp4", 1),
            entry("other.mp4", 2),
            entry("dup.mp4", 3)
        )
        val queue = archiveVideoQueueIndices(listing)
        // Display order is by name: both dup.mp4 entries (0 and 2, kept as
        // distinct indices in listing order) come before other.mp4 (1).
        assertEquals(listOf(0, 2, 1), queue)
        assertEquals(1, archivePreviewPagerPosition(queue, 2))
    }

    @Test
    fun `video queue is empty for an empty listing and for a listing without videos`() {
        assertEquals(emptyList<Int>(), archiveVideoQueueIndices(emptyList()))
        assertEquals(
            emptyList<Int>(),
            archiveVideoQueueIndices(listOf(entry("photo.jpg"), entry("song.mp3")))
        )
    }

    @Test
    fun `video queue holds a single video`() {
        val listing = listOf(entry("photo.jpg"), entry("only.mp4"), entry("notes.txt"))
        assertEquals(listOf(1), archiveVideoQueueIndices(listing))
    }

    @Test
    fun `video queue stepping clamps at both ends without wrapping`() {
        // The view model steps with archivePreviewPagerTarget over
        // (queue.size, queuePosition): pin that arithmetic for a 3-video queue.
        assertEquals(1, archivePreviewPagerTarget(pagerSize = 3, position = 0, delta = 1))
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 3, position = 0, delta = -1))
        assertEquals(1, archivePreviewPagerTarget(pagerSize = 3, position = 2, delta = -1))
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 3, position = 2, delta = 1))
        // A single-video queue cannot step in either direction.
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 1, position = 0, delta = 1))
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 1, position = 0, delta = -1))
        // No open video (position -1) never steps.
        assertEquals(null, archivePreviewPagerTarget(pagerSize = 3, position = -1, delta = 1))
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
