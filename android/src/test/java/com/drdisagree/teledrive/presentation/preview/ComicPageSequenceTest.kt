package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The CBZ comic pager sequence (vc181 B5): image entries only, in natural
 * full-path order, keyed by listing index. Pins the two ways a comic differs
 * from a plain ZIP pager — page order (1, 2, …, 10, not 1, 10, 2) and the
 * exclusion of non-image entries that the ZIP pager's text rule would
 * otherwise turn into pages (ComicInfo.xml above all).
 */
class ComicPageSequenceTest {

    private fun entry(name: String, isDirectory: Boolean = false) = ArchiveEntry(
        name = name,
        sizeBytes = 10,
        compressedBytes = 10,
        isDirectory = isDirectory
    )

    @Test
    fun `pages sort naturally, not lexicographically`() {
        val listing = listOf(
            entry("page10.jpg"),
            entry("page2.jpg"),
            entry("page1.jpg"),
            entry("page20.jpg"),
            entry("page3.jpg")
        )
        assertEquals(listOf(2, 1, 4, 0, 3), comicPageSequence(listing))
    }

    @Test
    fun `zero padded and unpadded pages interleave by value`() {
        val listing = listOf(
            entry("010.png"),
            entry("2.png"),
            entry("001.png"),
            entry("10.png")
        )
        // 001 (=1), 2, 010 (=10, listing index 0), 10 (=10, listing index 3):
        // numerically equal runs keep listing order between themselves.
        assertEquals(listOf(2, 1, 0, 3), comicPageSequence(listing))
    }

    @Test
    fun `pages inside one folder sort by their full path`() {
        val listing = listOf(
            entry("chapter/page10.jpg"),
            entry("chapter/page9.jpg"),
            entry("cover.jpg")
        )
        // "chapter/…" < "cover.jpg" segment-wise ('h' < 'o'), then 9 before 10.
        assertEquals(listOf(1, 0, 2), comicPageSequence(listing))
    }

    @Test
    fun `only image entries are pages - ComicInfo and junk are skipped`() {
        val listing = listOf(
            entry("ComicInfo.xml"),
            entry("page1.jpg"),
            entry("notes.txt"),
            entry("Thumbs.db"),
            entry("__MACOSX/page1.jpg"),
            entry("page2.webp"),
            entry("inner.cbz"),
            entry("folder/", isDirectory = true),
            entry("page3.png")
        )
        val sequence = comicPageSequence(listing)
        // __MACOSX/page1.jpg IS an image entry by mime; like other comic
        // readers' raw listings it stays a page candidate only if it were a
        // real page — here it must NOT appear: it is under __MACOSX.
        assertEquals(listOf(1, 5, 8), sequence)
    }

    @Test
    fun `duplicate page names keep their own listing indices`() {
        val listing = listOf(
            entry("page1.jpg"),
            entry("page1.jpg"),
            entry("page2.jpg")
        )
        assertEquals(listOf(0, 1, 2), comicPageSequence(listing))
    }

    @Test
    fun `natural comparator handles case, prefixes and long digit runs`() {
        assertTrue(compareNaturalPaths("Page2.jpg", "page10.jpg") < 0)
        assertTrue(compareNaturalPaths("a/b2.jpg", "a/b10.jpg") < 0)
        assertTrue(compareNaturalPaths("page.jpg", "page1.jpg") < 0)
        assertTrue(compareNaturalPaths("p99999999999999999999.jpg", "p100000000000000000000.jpg") < 0)
        assertEquals(0, compareNaturalPaths("same1.jpg", "same1.jpg"))
    }

    @Test
    fun `empty and image-free listings have no pages`() {
        assertEquals(emptyList<Int>(), comicPageSequence(emptyList()))
        assertEquals(
            emptyList<Int>(),
            comicPageSequence(listOf(entry("ComicInfo.xml"), entry("a.txt")))
        )
    }
}
