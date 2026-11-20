package com.drdisagree.teledrive.desktop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PC-15 parity contracts (report 18): the desktop picker must behave like
 * Files and like the Android picker. These tests pin the gesture semantics
 * from the user's recording — drag down paints, drag back up ("undrag")
 * un-paints, release commits — plus the Esc ladder and the sort/search
 * behaviour the Android picker already has.
 */
class DesktopPickerParityTest {

    private fun fixture(): Pair<File, List<File>> {
        val dir = java.nio.file.Files.createTempDirectory("picker-parity").toFile()
        val sub = File(dir, "sub").apply { mkdirs() }
        val f1 = File(dir, "a.txt").apply { writeText("a") }
        val f2 = File(dir, "b.txt").apply { writeText("bb") }
        val f3 = File(dir, "c.txt").apply { writeText("ccc") }
        // Folders sort first in the picker, like this listing.
        return dir to listOf(sub, f1, f2, f3)
    }

    private fun paths(entries: List<File>): Set<String> =
        entries.filter { it.isFile }.map { it.absolutePath }.toSet()

    @Test
    fun `dragging back up un-paints rows that leave the range`() {
        val (dir, entries) = fixture()
        val drag = PickerDragSelection()
        drag.onDragStart(emptySet())
        // Drag down across every row: all files paint (folder skipped).
        var selected = drag.onDragRange(entries, 0..3, shouldSelect = true)
        assertEquals(paths(entries), selected)
        // Undrag back to the first file row: rows that left the range must
        // return to their pre-drag state within the SAME gesture.
        selected = drag.onDragRange(entries, 0..1, shouldSelect = true)
        assertEquals(setOf(entries[1].absolutePath), selected)
        drag.onDragEnd()
        dir.deleteRecursively()
    }

    @Test
    fun `a deselect drag restores rows that leave the range`() {
        val (dir, entries) = fixture()
        val all = paths(entries)
        val drag = PickerDragSelection()
        drag.onDragStart(all)
        var selected = drag.onDragRange(entries, 0..3, shouldSelect = false)
        assertEquals(emptySet<String>(), selected)
        selected = drag.onDragRange(entries, 0..1, shouldSelect = false)
        assertEquals(all - entries[1].absolutePath, selected)
        drag.onDragEnd()
        dir.deleteRecursively()
    }

    @Test
    fun `a new gesture starts from the committed selection, not a stale base`() {
        val (dir, entries) = fixture()
        val drag = PickerDragSelection()
        drag.onDragStart(emptySet())
        val committed = drag.onDragRange(entries, 0..1, shouldSelect = true)
        drag.onDragEnd()
        // Second gesture: its base is what the first one committed.
        drag.onDragStart(committed)
        val extended = drag.onDragRange(entries, 2..3, shouldSelect = true)
        assertEquals(paths(entries), extended)
        drag.onDragEnd()
        dir.deleteRecursively()
    }

    @Test
    fun `escape clears the selection before it navigates or closes`() {
        assertEquals(
            PickerEscapeAction.CLEAR_SELECTION,
            pickerEscapeAction(hasSelection = true, hasParent = true)
        )
        assertEquals(
            PickerEscapeAction.CLEAR_SELECTION,
            pickerEscapeAction(hasSelection = true, hasParent = false)
        )
        assertEquals(
            PickerEscapeAction.NAVIGATE_UP,
            pickerEscapeAction(hasSelection = false, hasParent = true)
        )
        assertEquals(
            PickerEscapeAction.CLOSE,
            pickerEscapeAction(hasSelection = false, hasParent = false)
        )
    }

    @Test
    fun `folders stay first in both sort directions`() {
        val (dir, entries) = fixture()
        val ascending = sortPickerEntries(entries, DesktopPickerSort.NAME, descending = false)
        val descending = sortPickerEntries(entries, DesktopPickerSort.NAME, descending = true)
        assertEquals(entries[0], ascending.first())
        assertEquals(entries[0], descending.first())
        assertEquals(
            listOf("a.txt", "b.txt", "c.txt"),
            ascending.drop(1).map { it.name }
        )
        assertEquals(
            listOf("c.txt", "b.txt", "a.txt"),
            descending.drop(1).map { it.name }
        )
        dir.deleteRecursively()
    }

    @Test
    fun `size sort orders by file length`() {
        val (dir, entries) = fixture()
        val sorted = sortPickerEntries(entries, DesktopPickerSort.SIZE, descending = false)
        assertEquals(
            listOf("a.txt", "b.txt", "c.txt"),
            sorted.drop(1).map { it.name }
        )
        dir.deleteRecursively()
    }

    @Test
    fun `search filters by name, case-insensitively, and blank returns everything`() {
        val (dir, entries) = fixture()
        assertEquals(entries, filterPickerEntries(entries, "  "))
        assertEquals(
            listOf(entries[2]),
            filterPickerEntries(entries, "B.TXT")
        )
        assertEquals(emptyList<File>(), filterPickerEntries(entries, "zzz"))
        dir.deleteRecursively()
    }
}
