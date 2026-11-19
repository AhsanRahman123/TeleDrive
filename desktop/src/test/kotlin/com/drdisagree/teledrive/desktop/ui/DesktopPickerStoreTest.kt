package com.drdisagree.teledrive.desktop.ui

import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPickerStoreTest {

    private fun tempNode(): Preferences =
        Preferences.userRoot().node("teledrive-tests/picker-${UUID.randomUUID()}")

    private fun withNode(block: (Preferences) -> Unit) {
        val node = tempNode()
        try {
            block(node)
        } finally {
            runCatching { node.removeNode() }
            runCatching { node.flush() }
        }
    }

    @Test
    fun `file and folder positions round trip independently`() = withNode { node ->
        val dirA = Files.createTempDirectory("picker-a").toFile()
        val dirB = Files.createTempDirectory("picker-b").toFile()
        try {
            assertNull(DesktopPickerStore.lastFileDirectory(node))
            assertNull(DesktopPickerStore.lastFolderDirectory(node))

            DesktopPickerStore.saveFileDirectory(node, dirA)
            DesktopPickerStore.saveFolderDirectory(node, dirB)

            assertEquals(dirA.absolutePath, DesktopPickerStore.lastFileDirectory(node)?.absolutePath)
            assertEquals(dirB.absolutePath, DesktopPickerStore.lastFolderDirectory(node)?.absolutePath)
        } finally {
            dirA.deleteRecursively()
            dirB.deleteRecursively()
        }
    }

    @Test
    fun `a remembered directory that no longer exists is ignored`() = withNode { node ->
        val gone = File(System.getProperty("java.io.tmpdir"), "picker-gone-${UUID.randomUUID()}")
        DesktopPickerStore.saveFileDirectory(node, gone)
        assertNull(DesktopPickerStore.lastFileDirectory(node))
    }

    @Test
    fun `start directory falls back remembered then home then root`() {
        val remembered = Files.createTempDirectory("picker-remembered").toFile()
        val home = Files.createTempDirectory("picker-home").toFile()
        val root = Files.createTempDirectory("picker-root").toFile()
        try {
            assertEquals(
                remembered,
                DesktopPickerStore.resolveStartDirectory(remembered, home, listOf(root))
            )
            assertEquals(
                home,
                DesktopPickerStore.resolveStartDirectory(null, home, listOf(root))
            )
            assertEquals(
                root,
                DesktopPickerStore.resolveStartDirectory(null, null, listOf(root))
            )
            assertNull(DesktopPickerStore.resolveStartDirectory(null, null, emptyList()))
        } finally {
            remembered.deleteRecursively()
            home.deleteRecursively()
            root.deleteRecursively()
        }
    }

    @Test
    fun `root detection walks up to the matching root`() {
        val root = File(System.getProperty("java.io.tmpdir")).absoluteFile
        val nested = File(root, "a/b/c")
        assertTrue(isOnRoot(nested, root))
        assertTrue(isOnRoot(root, root))
    }
}
