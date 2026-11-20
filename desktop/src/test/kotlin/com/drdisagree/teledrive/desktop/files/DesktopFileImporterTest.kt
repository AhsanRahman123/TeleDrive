package com.drdisagree.teledrive.desktop.files

import com.drdisagree.teledrive.core.files.AppStoragePaths
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFileImporterTest {

    @Test
    fun `import copies selected source into app managed storage without owning the original`() {
        val root = Files.createTempDirectory("teledrive-import-test").toFile()
        try {
            val filesDir = File(root, "app/files").apply { mkdirs() }
            val source = File(root, "external/source.bin").apply {
                parentFile.mkdirs()
                writeBytes(byteArrayOf(1, 2, 3, 4, 5))
            }
            val importer = DesktopFileImporter(testStoragePaths(filesDir))

            val imported = importer.import(source.absolutePath)
                ?: error("Expected the source file to be imported")

            assertNotEquals(source.absolutePath, imported.path)
            assertTrue(imported.path.startsWith(File(filesDir, "local-copies").absolutePath))
            assertTrue(File(imported.path).isFile)
            assertEquals(source.readBytes().toList(), File(imported.path).readBytes().toList())
            assertTrue(source.isFile)
            assertFalse(importer.isStaged(imported.path))

            importer.discard(imported)

            assertFalse(File(imported.path).exists())
            assertTrue(source.isFile)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sweep reclaims local copies no row references and keeps referenced ones`() {
        val root = Files.createTempDirectory("teledrive-sweep-test").toFile()
        try {
            val filesDir = File(root, "app/files").apply { mkdirs() }
            val copiesDir = File(filesDir, "local-copies").apply { mkdirs() }
            val referenced = File(copiesDir, "referenced.bin").apply {
                writeBytes(byteArrayOf(1))
            }
            val orphaned = File(copiesDir, "orphaned.bin").apply {
                writeBytes(byteArrayOf(2))
            }
            val importer = DesktopFileImporter(testStoragePaths(filesDir))

            importer.sweepOrphans(setOf(referenced.absolutePath))

            assertTrue("a copy a surviving row references is kept", referenced.isFile)
            assertFalse(
                "a copy whose row was deleted (the PC-26 leak) is reclaimed",
                orphaned.exists()
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun testStoragePaths(appFilesDir: File): AppStoragePaths = object : AppStoragePaths {
        override val filesDir: File = appFilesDir
        override val cacheDir: File = File(appFilesDir.parentFile, "cache")
        override val externalCacheDir: File? = null
        override val externalStorageRoot: File? = null
    }
}
