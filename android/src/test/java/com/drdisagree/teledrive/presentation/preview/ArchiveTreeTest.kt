package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Duplicate-name handling in the archive browser: file leaves must keep their listing
 * identity (index) through the tree and the flattened rows, and the occurrence the
 * ViewModel sends to RemoteArchiveService must be computed from that index.
 */
class ArchiveTreeTest {

    private fun entry(name: String, size: Long, isDirectory: Boolean = false) =
        ArchiveEntry(name = name, sizeBytes = size, compressedBytes = size, isDirectory = isDirectory)

    @Test
    fun `duplicate leaf names render as separate rows carrying their own listing index`() {
        val entries = listOf(
            entry("dup.txt", 10),
            entry("other.txt", 30),
            entry("dup.txt", 20)
        )
        val rows = flattenArchive(buildArchiveTree(entries), expanded = emptySet())
        assertEquals(3, rows.size)
        val dupRows = rows.filter { it.node.name == "dup.txt" }
        assertEquals(2, dupRows.size)
        assertEquals(listOf(0, 2), dupRows.map { it.node.entryIndex })
        assertEquals(listOf(10L, 20L), dupRows.map { it.node.sizeBytes })
    }

    @Test
    fun `duplicate leaves inside a folder stay separate while the folder merges`() {
        val entries = listOf(
            entry("dir/dup.txt", 10),
            entry("dir/other.txt", 30),
            entry("dir/dup.txt", 20)
        )
        val root = buildArchiveTree(entries)
        assertEquals(1, countFolders(root))
        val rows = flattenArchive(root, expanded = setOf("dir"))
        assertEquals(listOf("dir", "dup.txt", "dup.txt", "other.txt"), rows.map { it.node.name })
        val dupRows = rows.filter { it.node.name == "dup.txt" }
        assertEquals(listOf(0, 2), dupRows.map { it.node.entryIndex })
        // Folder size aggregates BOTH duplicates.
        assertEquals(60L, rows.first().node.sizeBytes)
    }

    @Test
    fun `occurrence is computed by listing index, not by entry equality`() {
        // The two dup.txt entries are deliberately EQUAL as data-class values apart from
        // position, so an equality-based indexOf would resolve both taps to occurrence 0.
        val listing = listOf(
            entry("dup.txt", 10),
            entry("dir/", 0, isDirectory = true),
            entry("dup.txt", 10)
        )
        assertEquals(0, archiveEntryOccurrence(listing, 0))
        assertEquals(1, archiveEntryOccurrence(listing, 2))
        assertEquals(-1, archiveEntryOccurrence(listing, 3))
        assertEquals(-1, archiveEntryOccurrence(listing, -1))
    }

    @Test
    fun `occurrence ignores directory entries with the same name`() {
        val listing = listOf(
            ArchiveEntry("dup.txt", 0, 0, isDirectory = true),
            entry("dup.txt", 10),
            entry("dup.txt", 20)
        )
        assertEquals(0, archiveEntryOccurrence(listing, 1))
        assertEquals(1, archiveEntryOccurrence(listing, 2))
        assertTrue(flattenArchive(buildArchiveTree(listing), emptySet()).isNotEmpty())
    }
}
