package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-archive video playback (vc181 B11): tap routing and the free-space
 * preflight predicate that refuses an extraction before it starts when the
 * temp dir cannot hold the entry.
 *
 * vc182 added a video-only queue for the player's Skip buttons. vc183
 * (Pranto's field order) supersedes that: videos join the ONE unified pager
 * sequence over all previewable types, swipe and prev/next cross
 * video<->image in both directions, and a video page reached by swipe/step
 * is a placeholder — no extraction until the deliberate Play tap. A direct
 * list tap on a video still extracts and plays immediately. The video-only
 * queue helper remains as a subsequence view and its pins stay.
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
    fun `video taps play and videos are pager previewable as placeholder pages`() {
        // vc183 contract change (field order overrides the vc181/182 pin):
        // a video has a page in the unified sequence. Previewable here means
        // "has a page", NOT "a swipe extracts it" — the view model publishes
        // a VideoPlaceholder for a swiped-to video and extracts only on the
        // deliberate Play tap or a direct list tap.
        val video = entry("a/movie.mp4", size = 5_000_000)
        assertEquals(ArchiveEntryTapAction.PLAY_VIDEO, archiveEntryTapAction(video))
        assertTrue(archiveEntryPreviewable(video))
        assertTrue(archiveEntryIsVideo(video))
    }

    @Test
    fun `pager indices include video entries in display order`() {
        // vc183 contract change: was "pager indices are unchanged by video
        // entries" (vc180-182). The unified sequence now contains every
        // previewable type: NAME sort orders the folder's rows clip.mkv,
        // movie.mp4, notes.txt, photo.jpg, song.mp3 — all five are pages.
        val listing = listOf(
            entry("a/photo.jpg"),
            entry("a/movie.mp4", size = 5_000_000),
            entry("a/song.mp3"),
            entry("a/clip.mkv", size = 5_000_000),
            entry("a/notes.txt")
        )
        assertEquals(listOf(3, 1, 4, 0, 2), archivePreviewPagerIndices(listing))
        assertEquals(
            archivePreviewPagerIndices(listing),
            archiveUnifiedPreviewIndices(listing)
        )
    }

    @Test
    fun `a tapped video publishes its position in the unified sequence`() {
        // vc183 contract change: was "a tapped video publishes a single
        // entry sequence" (vc181/182). The legacy helper still returns the
        // single-entry list, but the view model no longer publishes it.
        assertEquals(listOf(7), archiveVideoPreviewSequence(7))
        val listing = listOf(
            entry("a/photo.jpg"),
            entry("a/movie.mp4", size = 5_000_000),
            entry("a/notes.txt")
        )
        // Display order: movie(1), notes(2), photo(0) — the video is page 0
        // of 3, with real neighbours on the next side.
        val unified = archiveUnifiedPreviewIndices(listing)
        assertEquals(listOf(1, 2, 0), unified)
        assertEquals(0, archivePreviewPagerPosition(unified, 1))
        assertEquals(1, archivePreviewPagerTarget(unified.size, 0, 1))
    }

    @Test
    fun `the video queue is the video subsequence of the unified sequence`() {
        // vc183: the Skip buttons follow the unified sequence, not this
        // queue; the queue remains as its video-only subsequence view.
        val listing = listOf(
            entry("a/first.mp4"),
            entry("a/photo.jpg"),
            entry("b/second.mp4"),
            entry("b/third.mkv")
        )
        val queue = archiveVideoQueueIndices(listing)
        assertEquals(listOf(0, 2, 3), queue)
        assertEquals(1, archivePreviewPagerPosition(queue, 2))
        // The unified sequence for the same listing interleaves the photo.
        assertEquals(listOf(0, 1, 2, 3), archiveUnifiedPreviewIndices(listing))
    }

    // ---- Unified mixed-type stepping (vc183) ----

    @Test
    fun `mixed-type sequence keeps display order across all previewable types`() {
        val listing = listOf(
            entry("z/photo.jpg"),        // 0 image
            entry("a/movie.mp4"),        // 1 video
            entry("m/notes.txt"),        // 2 text
            entry("m/book.pdf"),         // 3 pdf
            entry("m/song.mp3"),         // 4 audio
            entry("m/blob.zip"),         // 5 download-only: no page
            entry("m/novel.epub")        // 6 document
        )
        // Display order (folders first per level, NAME asc):
        // a/movie(1), m/blob(5), m/book(3), m/notes(2), m/novel(6), m/song(4), z/photo(0)
        assertEquals(listOf(1, 3, 2, 6, 4, 0), archiveUnifiedPreviewIndices(listing))
    }

    @Test
    fun `video to image and image to video steps both work over the unified sequence`() {
        val listing = listOf(
            entry("a/photo.jpg"),   // 0
            entry("a/movie.mp4"),   // 1
            entry("a/photo2.jpg")   // 2
        )
        // Display order: movie(1), photo(0), photo2(2).
        val unified = archiveUnifiedPreviewIndices(listing)
        assertEquals(listOf(1, 0, 2), unified)
        // Video page (position 0) -> next is the image: the step the field
        // test said failed in vc182 (video-only queue had no image target).
        assertEquals(1, archivePreviewPagerTarget(unified.size, 0, 1))
        assertFalse(archivePagerPageIsVideo(listing, unified, 1))
        // Image page (position 1) -> previous is the video, and the pure
        // decision says that target is a video page (placeholder, no extract).
        assertEquals(0, archivePreviewPagerTarget(unified.size, 1, -1))
        assertTrue(archivePagerPageIsVideo(listing, unified, 0))
        // And forward again from the second image clamps at the end.
        assertEquals(null, archivePreviewPagerTarget(unified.size, 2, 1))
    }

    @Test
    fun `unified sequence keeps duplicate names as distinct pages`() {
        val listing = listOf(
            entry("dup.mp4", 1),
            entry("dup.jpg", 2),
            entry("dup.mp4", 3),
            entry("dup.jpg", 4)
        )
        // Display order by name: dup.jpg(1), dup.jpg(3), dup.mp4(0), dup.mp4(2).
        val unified = archiveUnifiedPreviewIndices(listing)
        assertEquals(listOf(1, 3, 0, 2), unified)
        assertEquals(3, archivePreviewPagerPosition(unified, 2))
        assertTrue(archivePagerPageIsVideo(listing, unified, 3))
        assertFalse(archivePagerPageIsVideo(listing, unified, 0))
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
