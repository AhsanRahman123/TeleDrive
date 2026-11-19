package com.drdisagree.teledrive.desktop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The picker's drag-selection range mapping (PC-5): the shared
 * drag-select machinery reports index ranges; this mapping is what turns
 * them into the picker's path selection.
 */
class DesktopUploadPickerDragSelectTest {

    private fun fixture(): Pair<File, List<File>> {
        val dir = createTempDir("picker-drag")
        val sub = File(dir, "sub").apply { mkdirs() }
        val f1 = File(dir, "a.txt").apply { writeText("a") }
        val f2 = File(dir, "b.txt").apply { writeText("b") }
        val f3 = File(dir, "c.txt").apply { writeText("c") }
        // Folders sort first in the picker, like this listing.
        return dir to listOf(sub, f1, f2, f3)
    }

    @Test
    fun `a range selects every file inside it`() {
        val (dir, entries) = fixture()
        val result = pickerRangeSelection(entries, emptySet(), 0..3, shouldSelect = true)
        assertEquals(
            entries.filter { it.isFile }.map { it.absolutePath }.toSet(),
            result
        )
        dir.deleteRecursively()
    }

    @Test
    fun `folders inside a range are skipped`() {
        val (dir, entries) = fixture()
        val result = pickerRangeSelection(entries, emptySet(), 0..0, shouldSelect = true)
        assertEquals(emptySet<String>(), result)
        dir.deleteRecursively()
    }

    @Test
    fun `a deselect range removes only the files inside it`() {
        val (dir, entries) = fixture()
        val all = entries.filter { it.isFile }.map { it.absolutePath }.toSet()
        val result = pickerRangeSelection(entries, all, 2..3, shouldSelect = false)
        assertEquals(setOf(entries[1].absolutePath), result)
        dir.deleteRecursively()
    }

    @Test
    fun `indices outside the listing are ignored`() {
        val (dir, entries) = fixture()
        val result = pickerRangeSelection(entries, emptySet(), 3..10, shouldSelect = true)
        assertEquals(setOf(entries[3].absolutePath), result)
        dir.deleteRecursively()
    }
}
