package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Visible-row → entry mapping for the ZIP viewer (vc177).
 *
 * The ghosting / wrong-row-selection defect came from the archive list animating
 * item placement on Android while selection hit-testing worked in final layout
 * space. The rendering half is fixed in PreviewScreen (no animateItem on archive
 * rows; row height independent of selection mode). These tests pin the identity
 * half the fix relies on: the single `rows` list that is rendered is the same
 * list selection resolves through, every row key is unique and stable across
 * sort/filter transforms, and selecting visible row N selects exactly the entry
 * displayed on row N — even with merged same-name folders and dozens of
 * identically named files.
 */
class ArchiveRowMappingTest {

    private fun entry(name: String, size: Long = 10, isDirectory: Boolean = false) =
        ArchiveEntry(
            name = name,
            sizeBytes = size,
            compressedBytes = size,
            isDirectory = isDirectory,
            modifiedAtMillis = 0
        )

    /**
     * PecoFence-like listing (126 entries): two interleaved "sources" writing into
     * the SAME top-level folder name, one folder with 114 files (38 of them all
     * named Shortcut.lnk), duplicate file names in different folders, root files.
     */
    private fun fixture(): List<ArchiveEntry> {
        val listing = mutableListOf<ArchiveEntry>()
        listing += entry("root-a.txt", 500)                                    // 0
        listing += entry("root-b.txt", 5)                                      // 1
        listing += entry("PecoFence_Backups/alpha/readme.txt", 10)             // 2
        listing += entry("PecoFence_Backups/beta/readme.txt", 20)              // 3
        // The big folder: 114 files, every third one named Shortcut.lnk, all
        // with distinct sizes so SIZE ordering is fully deterministic.
        for (i in 0 until 114) {
            val name = if (i % 3 == 0) "Shortcut.lnk" else "file-%03d.dat".format(i)
            listing += entry("PecoFence_Backups/Big/$name", 1_000L + i)        // 4..117
        }
        // Second source into the same top-level folder, after the big block.
        listing += entry("PecoFence_Backups/alpha/extra.txt", 30)              // 118
        listing += entry("PecoFence_Backups/shared.txt", 40)                   // 119
        listing += entry("Other/shared.txt", 41)                               // 120
        listing += entry("Docs/report.txt", 50)                                // 121
        listing += entry("Docs/readme.txt", 51)                                // 122
        listing += entry("Docs/a.txt", 52)                                     // 123
        listing += entry("Docs/b.txt", 53)                                     // 124
        listing += entry("Docs/c.txt", 54)                                     // 125
        return listing
    }

    /** Mirrors ArchiveList's row construction for an unfiltered view. */
    private fun rowsFor(
        listing: List<ArchiveEntry>,
        field: FileSortField = FileSortField.NAME,
        direction: SortDirection = SortDirection.ASCENDING
    ): Pair<ArchiveNode, List<ArchiveRowState>> {
        val root = buildArchiveTree(listing)
        val rows = flattenArchive(root, allFolderPaths(root), field, direction)
        return root to rows
    }

    /** Mirrors ArchiveList's row construction under a search filter. */
    private fun filteredRowsFor(
        listing: List<ArchiveEntry>,
        query: String,
        field: FileSortField = FileSortField.NAME,
        direction: SortDirection = SortDirection.ASCENDING
    ): Pair<ArchiveNode, List<ArchiveRowState>> {
        val visible = archiveFilterIndices(listing, query)
        val root = buildArchiveTreeIndexed(
            listing.mapIndexedNotNull { index, e ->
                if (index in visible) IndexedArchiveEntry(index, e) else null
            }
        )
        val rows = flattenArchive(root, allFolderPaths(root), field, direction)
        return root to rows
    }

    private fun parentPathOf(fullPath: String): String = fullPath.substringBeforeLast("/", "")

    /** (b) Every visible row resolves back to exactly the listing entry it displays. */
    private fun assertRowMapping(root: ArchiveNode, rows: List<ArchiveRowState>, listing: List<ArchiveEntry>) {
        val folderPaths = allFolderPaths(root)
        for (row in rows) {
            val node = row.node
            if (node.isDirectory) {
                assertTrue("folder row path ${node.path} must exist in the tree", node.path in folderPaths)
            } else {
                val index = node.entryIndex
                assertNotNull("file row ${node.path} must carry a listing index", index)
                val listed = listing[index!!]
                assertEquals(
                    "row ${node.path} must display the listed entry's file name",
                    listed.name.substringAfterLast("/"), node.name
                )
                assertEquals(
                    "row ${node.path} must sit under the listed entry's parent path",
                    parentPathOf(listed.name), parentPathOf(node.path)
                )
            }
        }
    }

    @Test
    fun `fixture is at least 124 entries with the intended duplicates`() {
        val listing = fixture()
        assertTrue(listing.size >= 124)
        assertEquals(38, listing.count { it.name.endsWith("/Shortcut.lnk") })
        assertEquals(3, listing.count { it.name.substringAfterLast("/") == "readme.txt" })
    }

    @Test
    fun `two same-named top folders merge into one row containing both sources`() {
        val listing = fixture()
        val (root, rows) = rowsFor(listing)
        val topFolders = rows.filter { it.depth == 0 && it.node.isDirectory && it.node.name == "PecoFence_Backups" }
        assertEquals(1, topFolders.size)
        val merged = topFolders.single().node
        // Both sources' children live under the single merged folder node.
        assertEquals(
            setOf("alpha", "beta", "Big"),
            merged.children.filter { it.isDirectory }.map { it.name }.toSet()
        )
        val leafCount = countLeaves(merged)
        assertEquals(listing.count { it.name.startsWith("PecoFence_Backups/") }, leafCount)
        assertRowMapping(root, rows, listing)
    }

    @Test
    fun `row mapping holds under NAME and SIZE sorts`() {
        val listing = fixture()
        val (nameRoot, nameRows) = rowsFor(listing, FileSortField.NAME, SortDirection.ASCENDING)
        assertRowMapping(nameRoot, nameRows, listing)
        val (sizeRoot, sizeRows) = rowsFor(listing, FileSortField.SIZE, SortDirection.DESCENDING)
        assertRowMapping(sizeRoot, sizeRows, listing)
        // Same visible row set under both sorts: 6 folder rows + every file.
        assertEquals(nameRows.size, sizeRows.size)
        assertEquals(listing.size, nameRows.count { !it.node.isDirectory })
    }

    @Test
    fun `row mapping holds under a search filter`() {
        val listing = fixture()
        val (root, rows) = filteredRowsFor(listing, "shortcut")
        assertRowMapping(root, rows, listing)
        // Only the 38 Shortcut.lnk files are visible as file rows.
        assertEquals(38, rows.count { !it.node.isDirectory })
        assertTrue(rows.filter { !it.node.isDirectory }.all { it.node.name == "Shortcut.lnk" })
    }

    @Test
    fun `row keys are pairwise unique`() {
        val listing = fixture()
        val (_, rows) = rowsFor(listing)
        val keys = rows.map { archiveRowKey(it) }
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `row keys are stable across sort and filter transformations`() {
        val listing = fixture()
        val (_, nameRows) = rowsFor(listing, FileSortField.NAME, SortDirection.ASCENDING)
        val (_, sizeRows) = rowsFor(listing, FileSortField.SIZE, SortDirection.DESCENDING)
        // Same rows, different order: identical key sets…
        assertEquals(nameRows.map { archiveRowKey(it) }.toSet(), sizeRows.map { archiveRowKey(it) }.toSet())
        // …and the same key for the same entry identity in both orders.
        val nameKeyByIndex = nameRows.mapNotNull { r -> r.node.entryIndex?.let { it to archiveRowKey(r) } }.toMap()
        val sizeKeyByIndex = sizeRows.mapNotNull { r -> r.node.entryIndex?.let { it to archiveRowKey(r) } }.toMap()
        assertEquals(nameKeyByIndex, sizeKeyByIndex)
        // Filtering only subsets the same key space.
        val (_, filteredRows) = filteredRowsFor(listing, "shortcut")
        val filteredKeys = filteredRows.map { archiveRowKey(it) }
        assertEquals(filteredKeys.size, filteredKeys.toSet().size)
        assertTrue(nameRows.map { archiveRowKey(it) }.toSet().containsAll(filteredKeys))
    }

    @Test
    fun `selecting visible row N selects exactly the entry displayed on row N`() {
        val listing = fixture()
        val (_, rows) = rowsFor(listing)
        // Probe the first row, a folder row, root files, and rows inside the big folder.
        val probes = listOf(0, 1, 2, 10, 60, rows.lastIndex)
        for (n in probes) {
            val resolved = archiveRowAtListIndex(rows, n + ARCHIVE_LIST_HEADER_ITEMS)
            assertNotNull("list index for row $n must resolve", resolved)
            assertTrue(resolved === rows[n])
            val node = resolved!!.node
            if (node.isDirectory) {
                assertEquals(node.path, rows[n].node.path)
            } else {
                val selection = toggleArchiveSelection(emptySet(), node.entryIndex!!)
                assertEquals(setOf(node.entryIndex), selection)
                assertEquals(node.name, listing[node.entryIndex!!].name.substringAfterLast("/"))
            }
        }
    }

    @Test
    fun `each duplicate Shortcut row selects its own listing index in flatten order`() {
        val listing = fixture()
        val (_, rows) = rowsFor(listing)
        val shortcutRows = rows.filter { !it.node.isDirectory && it.node.name == "Shortcut.lnk" }
        assertEquals(38, shortcutRows.size)
        val indices = shortcutRows.map { it.node.entryIndex!! }
        // Distinct identities, kept in listing order by the stable sort.
        assertEquals(indices.size, indices.toSet().size)
        assertEquals(indices.sorted(), indices)
        assertEquals(
            listing.indices.filter { listing[it].name.endsWith("/Shortcut.lnk") },
            indices
        )
        // Toggling any one of them selects exactly that one entry.
        for (index in indices) {
            assertEquals(setOf(index), toggleArchiveSelection(emptySet(), index))
        }
    }

    @Test
    fun `duplicate names in different folders select their own entries under SIZE sort and filter`() {
        val listing = fixture()
        val (_, sizeRows) = rowsFor(listing, FileSortField.SIZE, SortDirection.DESCENDING)
        val readmes = sizeRows.filter { !it.node.isDirectory && it.node.name == "readme.txt" }
        assertEquals(3, readmes.size)
        assertEquals(
            setOf(2, 3, 122),
            readmes.map { it.node.entryIndex!! }.toSet()
        )
        // Under the "readme" filter each visible row still maps to its own entry.
        val (fRoot, fRows) = filteredRowsFor(listing, "readme")
        assertRowMapping(fRoot, fRows, listing)
        val filteredReadmes = fRows.filter { !it.node.isDirectory }
        assertEquals(setOf(2, 3, 122), filteredReadmes.map { it.node.entryIndex!! }.toSet())
        for (row in filteredReadmes) {
            val resolved = archiveRowAtListIndex(fRows, fRows.indexOf(row) + ARCHIVE_LIST_HEADER_ITEMS)
            assertEquals(row.node.entryIndex, resolved!!.node.entryIndex)
        }
    }

    private fun countLeaves(node: ArchiveNode): Int =
        if (!node.isDirectory) 1 else node.children.sumOf { countLeaves(it) }
}
