package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.25 item 5: the in-zip route's classification and caps at Android's
 * level — PDF and EPUB/OOXML entries preview in place under their caps,
 * HTML/Markdown/JSON/XML/subtitle entries preview as text under the 2 MiB
 * cap, SVG/GIF entries are image pages, and nested archives stay
 * download-only (held, as on Android).
 */
class ArchiveViewerParityTest {

    private fun entry(name: String, sizeBytes: Long = 100L, isDirectory: Boolean = false) =
        ArchiveEntry(
            name = name,
            sizeBytes = sizeBytes,
            compressedBytes = sizeBytes,
            isDirectory = isDirectory
        )

    private companion object {
        const val MIB = 1024L * 1024L
    }

    // ---- Classification ----

    @Test
    fun `pdf and document entries preview in place`() {
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_PDF,
            archiveEntryTapAction(entry("book.pdf"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_DOCUMENT,
            archiveEntryTapAction(entry("novel.epub"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_DOCUMENT,
            archiveEntryTapAction(entry("report.docx"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_DOCUMENT,
            archiveEntryTapAction(entry("sheet.xlsx"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_DOCUMENT,
            archiveEntryTapAction(entry("deck.pptx"))
        )
    }

    @Test
    fun `legacy office and nested archives stay download only`() {
        assertEquals(
            ArchiveEntryTapAction.DOWNLOAD,
            archiveEntryTapAction(entry("old.doc"))
        )
        assertEquals(
            ArchiveEntryTapAction.DOWNLOAD,
            archiveEntryTapAction(entry("old.xls"))
        )
        assertEquals(
            ArchiveEntryTapAction.DOWNLOAD,
            archiveEntryTapAction(entry("inner.zip"))
        )
        assertEquals(
            ArchiveEntryTapAction.DOWNLOAD,
            archiveEntryTapAction(entry("inner.7z"))
        )
        assertEquals(
            ArchiveEntryTapAction.DOWNLOAD,
            archiveEntryTapAction(entry("inner.cbz"))
        )
    }

    @Test
    fun `html markdown json xml and subtitle entries preview as text`() {
        for (name in listOf(
            "page.html", "page.htm", "notes.md", "data.json", "data.xml",
            "subs.srt", "subs.vtt", "plain.txt"
        )) {
            assertEquals(
                name,
                ArchiveEntryTapAction.PREVIEW_TEXT,
                archiveEntryTapAction(entry(name))
            )
        }
    }

    @Test
    fun `svg and gif entries are image pages`() {
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_IMAGE,
            archiveEntryTapAction(entry("logo.svg"))
        )
        assertEquals(
            ArchiveEntryTapAction.PREVIEW_IMAGE,
            archiveEntryTapAction(entry("anim.gif"))
        )
    }

    // ---- Caps (Android report-55 section 7 values) ----

    @Test
    fun `pdf cap is 50 mib inclusive`() {
        assertTrue(archiveEntryPdfPreviewable(entry("a.pdf", 50 * MIB)))
        assertFalse(archiveEntryPdfPreviewable(entry("a.pdf", 50 * MIB + 1)))
        assertFalse(archiveEntryPdfPreviewable(entry("a.txt", 50 * MIB)))
    }

    @Test
    fun `document cap is 20 mib inclusive`() {
        assertTrue(archiveEntryDocumentPreviewable(entry("a.epub", 20 * MIB)))
        assertTrue(archiveEntryDocumentPreviewable(entry("a.docx", 20 * MIB)))
        assertFalse(archiveEntryDocumentPreviewable(entry("a.epub", 20 * MIB + 1)))
    }

    @Test
    fun `text cap is 2 mib inclusive and covers html json subtitles`() {
        assertTrue(archiveEntryTextPreviewable(entry("a.html", 2 * MIB)))
        assertTrue(archiveEntryTextPreviewable(entry("a.json", 2 * MIB)))
        assertTrue(archiveEntryTextPreviewable(entry("a.srt", 2 * MIB)))
        assertFalse(archiveEntryTextPreviewable(entry("a.html", 2 * MIB + 1)))
    }

    @Test
    fun `svg is uncapped as an image page`() {
        // SVG rides the image route, which has no size cap in-zip (Android parity).
        assertTrue(archiveEntryPreviewable(entry("big.svg", 500 * MIB)))
        assertTrue(archiveEntryPreviewable(entry("anim.gif", 500 * MIB)))
    }

    @Test
    fun `previewable follows the caps per type`() {
        assertTrue(archiveEntryPreviewable(entry("a.pdf", 50 * MIB)))
        assertFalse(archiveEntryPreviewable(entry("a.pdf", 50 * MIB + 1)))
        assertTrue(archiveEntryPreviewable(entry("a.epub", 20 * MIB)))
        assertFalse(archiveEntryPreviewable(entry("a.epub", 20 * MIB + 1)))
        assertFalse(archiveEntryPreviewable(entry("inner.zip", 1)))
    }

    // ---- CBZ comic sequence ----

    @Test
    fun `comic sequence is image pages in natural order`() {
        val listing = listOf(
            entry("page10.jpg"),
            entry("page2.jpg"),
            entry("ComicInfo.xml"),
            entry("page1.jpg"),
            entry("__MACOSX/page3.jpg"),
            entry("notes.txt"),
            entry("page3.png")
        )
        assertEquals(
            listOf(3, 1, 6, 0),
            comicPageSequence(listing)
        )
    }

    @Test
    fun `comic sequence keeps duplicate names as separate pages`() {
        val listing = listOf(entry("p1.jpg"), entry("p1.jpg"), entry("p2.jpg"))
        assertEquals(listOf(0, 1, 2), comicPageSequence(listing))
    }
}
