package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sorting inside the ZIP viewer (item 6): the main browser's keys and direction
 * semantics applied per folder level, folders first, with the missing-timestamp
 * placement rule — entries with no time (0) always sort after dated entries.
 */
class ArchiveSortTest {

    private fun entry(
        name: String,
        size: Long = 10,
        modified: Long = 0,
        isDirectory: Boolean = false
    ) = ArchiveEntry(
        name = name,
        sizeBytes = size,
        compressedBytes = size,
        isDirectory = isDirectory,
        modifiedAtMillis = modified
    )

    private fun names(
        entries: List<ArchiveEntry>,
        field: FileSortField,
        direction: SortDirection
    ): List<String> = flattenArchive(
        buildArchiveTree(entries), emptySet(), field, direction
    ).map { it.node.name }

    @Test
    fun `name sort is case-insensitive like the browser collation`() {
        val entries = listOf(entry("banana.txt"), entry("Apple.txt"), entry("cherry.txt"))
        assertEquals(
            listOf("Apple.txt", "banana.txt", "cherry.txt"),
            names(entries, FileSortField.NAME, SortDirection.ASCENDING)
        )
        assertEquals(
            listOf("cherry.txt", "banana.txt", "Apple.txt"),
            names(entries, FileSortField.NAME, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `size sort uses byte size in both directions`() {
        val entries = listOf(entry("a.bin", 30), entry("b.bin", 10), entry("c.bin", 20))
        assertEquals(
            listOf("b.bin", "c.bin", "a.bin"),
            names(entries, FileSortField.SIZE, SortDirection.ASCENDING)
        )
        assertEquals(
            listOf("a.bin", "c.bin", "b.bin"),
            names(entries, FileSortField.SIZE, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `type sort orders by mime type string`() {
        val entries = listOf(entry("song.mp3"), entry("pic.png"), entry("notes.txt"))
        // audio/mpeg < image/png < text/plain
        assertEquals(
            listOf("song.mp3", "pic.png", "notes.txt"),
            names(entries, FileSortField.TYPE, SortDirection.ASCENDING)
        )
    }

    @Test
    fun `date sort places entries without a timestamp last in both directions`() {
        val entries = listOf(
            entry("new.txt", modified = 3_000),
            entry("unknown.txt", modified = 0),
            entry("old.txt", modified = 1_000),
            entry("mid.txt", modified = 2_000)
        )
        assertEquals(
            listOf("old.txt", "mid.txt", "new.txt", "unknown.txt"),
            names(entries, FileSortField.DATE_MODIFIED, SortDirection.ASCENDING)
        )
        assertEquals(
            listOf("new.txt", "mid.txt", "old.txt", "unknown.txt"),
            names(entries, FileSortField.DATE_MODIFIED, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `folders stay first under every sort`() {
        val entries = listOf(
            entry("aaa.txt", 1),
            entry("zzz/inner.txt", 1)
        )
        for (field in listOf(FileSortField.NAME, FileSortField.SIZE, FileSortField.DATE_MODIFIED)) {
            val rows = flattenArchive(
                buildArchiveTree(entries), setOf("zzz"), field, SortDirection.ASCENDING
            )
            assertEquals("zzz", rows.first().node.name)
        }
    }

    @Test
    fun `equal keys keep listing order`() {
        val entries = listOf(entry("same.txt", 10), entry("same.txt", 10), entry("same.txt", 10))
        val rows = flattenArchive(
            buildArchiveTree(entries), emptySet(), FileSortField.SIZE, SortDirection.DESCENDING
        )
        assertEquals(listOf(0, 1, 2), rows.map { it.node.entryIndex })
    }
}
