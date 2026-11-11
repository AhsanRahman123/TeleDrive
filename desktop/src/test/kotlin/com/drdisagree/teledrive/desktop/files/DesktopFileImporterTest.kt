package com.drdisagree.teledrive.desktop.files

import com.drdisagree.teledrive.core.files.AppStoragePaths
import java.io.File
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopFileImporterTest {

    @Test
    fun `import copies selected source into app managed storage without owning the original`() {
        val root = createTempDirectory("teledrive-import-test").toFile()
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

    private fun testStoragePaths(filesDir: File): AppStoragePaths = object : AppStoragePaths {
        override val filesDir: File = filesDir
        override val cacheDir: File = File(filesDir.parentFile, "cache")
        override val externalCacheDir: File? = null
        override val externalStorageRoot: File? = null
    }
}
