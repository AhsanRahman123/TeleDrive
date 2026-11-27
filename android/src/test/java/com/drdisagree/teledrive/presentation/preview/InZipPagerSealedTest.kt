package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc184 P3-verify: the two-level switching rule on Android. A viewer switches
 * within the level it is in — inside a ZIP, switching must move ONLY within
 * that ZIP's entries, never to the outer folder's next file.
 *
 * Sealing is structural in production code, and these tests pin it at the
 * production entry points the Android screen actually uses:
 *  - the pager's sequence is built ONLY from the archive listing
 *    ([archiveUnifiedPreviewIndices] / [comicPageSequence], called by
 *    PreviewViewModel.previewArchiveEntry — the outer folder's file list,
 *    PreviewUiState.files, is never an input);
 *  - steps go through [archivePreviewPagerTarget] (called by
 *    PreviewViewModel.stepArchivePreview), which clamps to the sequence and
 *    returns null at either end — no wrap-around, no fall-through;
 *  - the Android overlay's HorizontalPager page count is exactly
 *    archivePreviewEntries.size (PreviewScreen), i.e. this sequence's size.
 */
class InZipPagerSealedTest {

    private fun entry(name: String, size: Long = 10, isDirectory: Boolean = false) =
        ArchiveEntry(
            name = name,
            sizeBytes = size,
            compressedBytes = size,
            isDirectory = isDirectory,
            modifiedAtMillis = 0
        )

    private val listing = listOf(
        entry("photos/", isDirectory = true),
        entry("photos/a.jpg"),
        entry("photos/b.png"),
        entry("notes.txt"),
        entry("song.mp3"),
        entry("movie.mp4"),
        entry("book.pdf"),
        entry("blob.zip"),
        entry("manual.docx")
    )

    @Test
    fun `unified sequence contains only listing indices of previewable entries`() {
        val indices = archiveUnifiedPreviewIndices(listing)
        assertTrue(indices.isNotEmpty())
        // Every page is an index into THIS zip's listing — nothing outside it
        // can ever be addressed by the pager.
        assertTrue(indices.all { it in listing.indices })
        assertTrue(indices.all { archiveEntryPreviewable(listing[it]) })
        // No page twice, and the download-only entry has no page.
        assertEquals(indices.size, indices.toSet().size)
        assertTrue(7 !in indices) // blob.zip is download-only
    }

    @Test
    fun `sequence covers every previewable type in the zip`() {
        val indices = archiveUnifiedPreviewIndices(listing)
        val names = indices.map { listing[it].name }
        assertTrue("photos/a.jpg" in names)
        assertTrue("notes.txt" in names)
        assertTrue("song.mp3" in names)
        assertTrue("movie.mp4" in names)
        assertTrue("book.pdf" in names)
        assertTrue("manual.docx" in names)
    }

    @Test
    fun `steps clamp at both ends of the zip sequence with no wrap`() {
        val indices = archiveUnifiedPreviewIndices(listing)
        // At the last in-zip page, Next is null (stay) — it must NOT wrap to
        // the first page or fall through to the outer folder's next file.
        assertNull(archivePreviewPagerTarget(indices.size, indices.lastIndex, +1))
        // At the first in-zip page, Previous is likewise null.
        assertNull(archivePreviewPagerTarget(indices.size, 0, -1))
        // Interior steps move exactly one page within the sequence.
        assertEquals(1, archivePreviewPagerTarget(indices.size, 0, +1))
        assertEquals(indices.lastIndex - 1, archivePreviewPagerTarget(indices.size, indices.lastIndex, -1))
    }

    @Test
    fun `walking the pager from the first page visits only zip entries and stops`() {
        val indices = archiveUnifiedPreviewIndices(listing)
        val visited = mutableListOf<Int>()
        var position: Int? = 0
        while (position != null) {
            visited += indices[position]
            position = archivePreviewPagerTarget(indices.size, position, +1)
        }
        // The walk terminates at the zip's last previewable entry and visited
        // exactly the sequence, in order — there is no step beyond it.
        assertEquals(indices, visited)
    }

    @Test
    fun `cbz sequence is sealed to image entries of the same listing`() {
        val cbz = listOf(
            entry("page10.jpg"),
            entry("page2.jpg"),
            entry("ComicInfo.xml"),
            entry("page1.jpg"),
            entry("notes.txt")
        )
        val pages = comicPageSequence(cbz)
        assertTrue(pages.all { it in cbz.indices })
        assertEquals(listOf("page1.jpg", "page2.jpg", "page10.jpg"), pages.map { cbz[it].name })
    }

    @Test
    fun `empty and download-only listings give an empty sequence`() {
        assertTrue(archiveUnifiedPreviewIndices(emptyList()).isEmpty())
        assertTrue(
            archiveUnifiedPreviewIndices(listOf(entry("blob.zip"), entry("data.bin", size = 5)))
                .isEmpty()
        )
        assertNull(archivePreviewPagerTarget(0, 0, +1))
    }
}
