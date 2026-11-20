package com.drdisagree.teledrive.desktop.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PC-29: the picker's select-all set is every FILE in the visible
 * listing — folders are navigation, never selectable — computed by the
 * one function both Ctrl+A and the select-all button use.
 */
class DesktopPickerSelectAllTest {

    @Test
    fun `select all paths contains files only`() {
        val dir = java.nio.file.Files.createTempDirectory("picker-select-all").toFile()
        val sub = File(dir, "sub").apply { mkdirs() }
        val a = File(dir, "a.txt").apply { writeText("a") }
        val b = File(dir, "b.mkv").apply { writeText("b") }

        assertEquals(
            setOf(a.absolutePath, b.absolutePath),
            pickerSelectAllPaths(listOf(sub, a, b))
        )
        dir.deleteRecursively()
    }

    @Test
    fun `select all of an empty or folders-only listing is empty`() {
        val dir = java.nio.file.Files.createTempDirectory("picker-select-none").toFile()
        val sub = File(dir, "sub").apply { mkdirs() }

        assertEquals(emptySet<String>(), pickerSelectAllPaths(emptyList()))
        assertEquals(emptySet<String>(), pickerSelectAllPaths(listOf(sub)))
        dir.deleteRecursively()
    }
}
