package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.25 item 7: the unified in-zip stepping sequence — every previewable
 * type in display order, identity by listing index, hard stops at both ends,
 * download-only entries skipped — plus the video free-space preflight and the
 * item-8 download-routing identity (a preview carries its own listing index).
 */
class UnifiedArchivePreviewTest {

    private fun entry(name: String, size: Long = 10, isDirectory: Boolean = false) = ArchiveEntry(
        name = name, sizeBytes = size, compressedBytes = size,
        isDirectory = isDirectory, modifiedAtMillis = 0
    )

    private val listing = listOf(
        entry("a/photo.jpg"),          // 0 image
        entry("a/movie.mp4"),          // 1 video
        entry("a/blob.bin"),           // 2 download-only
        entry("a/song.mp3"),           // 3 audio
        entry("a/notes.txt"),          // 4 text
        entry("a/photo.jpg"),          // 5 duplicate-named image
        entry("a/big.txt", size = ARCHIVE_TEXT_PREVIEW_MAX_BYTES + 1), // 6 oversize text
        entry("a/", isDirectory = true) // 7 folder
    )

    @Test
    fun `unified sequence spans all previewable types in display order`() {
        val sequence = archiveUnifiedPreviewIndices(listing, listing.indices.toList())
        assertEquals(listOf(0, 1, 3, 4, 5), sequence)
    }

    @Test
    fun `unified sequence follows the displayed order, not listing order`() {
        val displayed = listOf(4, 1, 0, 3)
        assertEquals(displayed, archiveUnifiedPreviewIndices(listing, displayed))
    }

    @Test
    fun `stepping crosses types and stops hard at both ends`() {
        val sequence = archiveUnifiedPreviewIndices(listing, listing.indices.toList())
        // video -> audio (mixed-type step)
        assertEquals(3, archiveUnifiedPreviewNeighbour(sequence, 1, +1))
        // image -> video
        assertEquals(1, archiveUnifiedPreviewNeighbour(sequence, 0, +1))
        // text -> duplicate-named image keeps its own identity
        assertEquals(5, archiveUnifiedPreviewNeighbour(sequence, 4, +1))
        assertNull(archiveUnifiedPreviewNeighbour(sequence, 5, +1))
        assertNull(archiveUnifiedPreviewNeighbour(sequence, 0, -1))
        // a download-only entry is not a sequence stop
        assertNull(archiveUnifiedPreviewNeighbour(sequence, 2, +1))
    }

    @Test
    fun `previewable predicate matches the surfaces the desktop viewer can open`() {
        assertTrue(archiveEntryUnifiedPreviewable(entry("x.png")))
        assertTrue(archiveEntryUnifiedPreviewable(entry("x.mkv")))
        assertTrue(archiveEntryUnifiedPreviewable(entry("x.mp3")))
        assertTrue(archiveEntryUnifiedPreviewable(entry("x.txt")))
        assertFalse(archiveEntryUnifiedPreviewable(entry("x.txt", size = ARCHIVE_TEXT_PREVIEW_MAX_BYTES + 1)))
        assertFalse(archiveEntryUnifiedPreviewable(entry("x.zip")))
        assertFalse(archiveEntryUnifiedPreviewable(entry("x/", isDirectory = true)))
    }

    @Test
    fun `video preflight needs the exact size plus the margin`() {
        val margin = ARCHIVE_VIDEO_SPACE_MARGIN_BYTES
        assertTrue(archiveVideoFitsInFreeSpace(100, 100 + margin))
        assertFalse(archiveVideoFitsInFreeSpace(100, 100 + margin - 1))
        assertFalse(archiveVideoFitsInFreeSpace(-1, Long.MAX_VALUE))
        assertTrue(archiveVideoFitsInFreeSpace(0, margin))
    }

    @Test
    fun `every preview type carries its listing index for download routing`() {
        // Item 8: downloadCurrentArchiveEntry resolves (fileId, entryIndex) from
        // the published preview — every variant must carry the index it was
        // extracted from, including duplicate names.
        assertEquals(5, ArchiveEntryPreview.Image("photo.jpg", "/tmp/x", 5).entryIndex)
        assertEquals(1, ArchiveEntryPreview.Video("movie.mp4", "/tmp/x", 1).entryIndex)
        assertEquals(3, ArchiveEntryPreview.Audio("song.mp3", "/tmp/x", 3).entryIndex)
        assertEquals(4, ArchiveEntryPreview.Text("notes.txt", "hi", 4).entryIndex)
    }
}
