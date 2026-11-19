package com.drdisagree.teledrive.data.repository

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * The folder tree a backup lands in is derived from the file's parent path.
 * On Windows that path uses backslashes and a drive root; before the fix it
 * became ONE flat folder named by the raw Windows path, so PC backups did
 * not appear on other devices with Android's folder organization.
 */
class BackupFolderPathTest {

    private val harness = SyncHarness()
    private val database = harness.database

    @After
    fun tearDown() = harness.close()

    @Test
    fun `windows parent path becomes a folder tree below the drive root`() {
        assertEquals(
            "Users/Pranto/Documents/Backups",
            BackupRepositoryImpl.backupFolderPath("""C:\Users\Pranto\Documents\Backups""", null)
        )
        assertEquals(
            "Media/Photos",
            BackupRepositoryImpl.backupFolderPath("""D:\Media\Photos""", null)
        )
        assertEquals(
            "Pictures",
            BackupRepositoryImpl.backupFolderPath("C:/Pictures", null)
        )
    }

    @Test
    fun `windows drive root itself maps to the library root`() {
        assertEquals("", BackupRepositoryImpl.backupFolderPath("""C:\""", null))
        assertEquals("", BackupRepositoryImpl.backupFolderPath("C:", null))
        assertEquals("", BackupRepositoryImpl.backupFolderPath("C:/", null))
    }

    @Test
    fun `windows storage root is honoured with either separator`() {
        assertEquals(
            "Documents/Backups",
            BackupRepositoryImpl.backupFolderPath(
                """C:\Users\Pranto\Documents\Backups""",
                """C:\Users\Pranto"""
            )
        )
        assertEquals(
            "",
            BackupRepositoryImpl.backupFolderPath("""C:\Users\Pranto""", "C:/Users/Pranto")
        )
    }

    @Test
    fun `android paths keep their established shape`() {
        assertEquals(
            "DCIM/Camera",
            BackupRepositoryImpl.backupFolderPath(
                "/storage/emulated/0/DCIM/Camera",
                "/storage/emulated/0"
            )
        )
        assertEquals(
            "",
            BackupRepositoryImpl.backupFolderPath("/storage/emulated/0", "/storage/emulated/0")
        )
        assertEquals(
            "Pictures",
            BackupRepositoryImpl.backupFolderPath(
                "/storage/ABCD-1234/Pictures",
                "/storage/emulated/0"
            )
        )
        assertEquals(
            "home/pranto/Pictures",
            BackupRepositoryImpl.backupFolderPath("/home/pranto/Pictures", null)
        )
    }

    @Test
    fun `resolver builds a tree from a backslash path and finds it by slashes`() = runBlocking {
        val resolver = FolderPathResolver(
            database.folderDao(),
            ActiveChannel(harness.settings),
            database.folderTombstoneDao()
        )

        val leaf = resolver.resolveOrCreate("""Users\Pranto\Documents\Backups""", chatId = TEST_CHAT)

        assertNotNull(leaf)
        assertEquals("Users/Pranto/Documents/Backups", resolver.pathOf(leaf))
        // The same tree is found again under the canonical separator, so a
        // second scan (or another device's sync) reuses it instead of
        // creating a parallel flat folder.
        assertEquals(leaf, resolver.resolveExisting("Users/Pranto/Documents/Backups", TEST_CHAT))
        assertEquals(
            leaf,
            resolver.resolveOrCreate("Users/Pranto/Documents/Backups", chatId = TEST_CHAT)
        )
    }
}
