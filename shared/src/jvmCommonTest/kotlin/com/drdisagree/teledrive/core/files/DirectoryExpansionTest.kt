package com.drdisagree.teledrive.core.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-22(a): a folder pick must carry its COMPLETE directory structure —
 * including directories that contain no files — or empty folders
 * silently never reach the library. These tests pin the expansion the
 * import flow consumes: the exact folder set in parent-before-child
 * order, plus the file list (whose behaviour is unchanged: zero-length
 * files stay excluded, as before).
 */
class DirectoryExpansionTest {

    /** picked/{a.txt, zero.bin(0), sub/{b.txt, emptyDeep/}, emptyMid/, chain/inner/} */
    private fun fixture(): File {
        val base = Files.createTempDirectory("expansion-fixture").toFile()
        val picked = File(base, "picked").apply { mkdirs() }
        File(picked, "a.txt").writeText("a")
        File(picked, "zero.bin").writeBytes(ByteArray(0))
        val sub = File(picked, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeText("bb")
        File(sub, "emptyDeep").mkdirs()
        File(picked, "emptyMid").mkdirs()
        File(picked, "chain/inner").mkdirs()
        return picked
    }

    @Test
    fun `expansion lists every directory - empty ones included - parents before children`() {
        val picked = fixture()
        try {
            val expansion = expandDirectoryTree(picked.absolutePath)!!
            assertEquals(
                setOf(
                    "picked",
                    "picked/sub",
                    "picked/sub/emptyDeep",
                    "picked/emptyMid",
                    "picked/chain",
                    "picked/chain/inner"
                ),
                expansion.folders.toSet()
            )
            // Parent-before-child: the picked folder is first, and every
            // folder's parent (when in the set) appears before it.
            assertEquals("picked", expansion.folders.first())
            expansion.folders.forEachIndexed { index, folder ->
                val parent = folder.substringBeforeLast('/', "")
                if (parent.isNotEmpty()) {
                    assertTrue(
                        "$parent must precede $folder",
                        expansion.folders.indexOf(parent) in 0 until index
                    )
                }
            }
        } finally {
            picked.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `expansion files keep their relative folders and skip zero-length files`() {
        val picked = fixture()
        try {
            val expansion = expandDirectoryTree(picked.absolutePath)!!
            val byName = expansion.files.associateBy { File(it.reference).name }
            assertEquals(setOf("a.txt", "b.txt"), byName.keys)
            assertEquals("picked", byName.getValue("a.txt").relativeFolder)
            assertEquals("picked/sub", byName.getValue("b.txt").relativeFolder)
            // expandDirectory (the file-only view other callers use) is
            // the same list — its behaviour is unchanged by the tree.
            assertEquals(expansion.files, expandDirectory(picked.absolutePath))
        } finally {
            picked.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `an entirely empty picked folder expands to just itself`() {
        val base = Files.createTempDirectory("expansion-empty").toFile()
        val picked = File(base, "pickedEmpty").apply { mkdirs() }
        try {
            val expansion = expandDirectoryTree(picked.absolutePath)!!
            assertEquals(listOf("pickedEmpty"), expansion.folders)
            assertEquals(emptyList<ImportSource>(), expansion.files)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `a plain file reference does not expand`() {
        val base = Files.createTempDirectory("expansion-file").toFile()
        val file = File(base, "one.txt").apply { writeText("x") }
        try {
            assertNull(expandDirectoryTree(file.absolutePath))
            assertNull(expandDirectory(file.absolutePath))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `parentBeforeChild orders parents before children and removes duplicates`() {
        val shuffled = listOf(
            "picked/chain/inner",
            "picked/sub",
            "picked",
            "picked/chain",
            "picked/sub",
            "picked/sub/emptyDeep"
        )
        val ordered = parentBeforeChild(shuffled)
        assertEquals(5, ordered.size)
        assertEquals("picked", ordered.first())
        ordered.forEachIndexed { index, folder ->
            val parent = folder.substringBeforeLast('/', "")
            if (parent.isNotEmpty()) {
                assertTrue(ordered.indexOf(parent) in 0 until index)
            }
        }
    }
}
