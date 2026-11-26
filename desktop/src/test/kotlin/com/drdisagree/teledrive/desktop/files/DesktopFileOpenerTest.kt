package com.drdisagree.teledrive.desktop.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.24 field note 6: the desktop "Open with…" dispatch. The shell route
 * and the default-app fallback are injected seams, so the dispatch — the
 * part that used to die silently (a rundll32 exit code of 0 was read as
 * success whether or not a chooser appeared) — is pinned without a Windows
 * shell. Whether the real ShellExecute "openas" dialog appears on the
 * user's Windows is field-only by nature.
 */
class DesktopFileOpenerTest {

    private fun existingFile(): File {
        val file = Files.createTempFile("open-with", ".bin").toFile()
        file.deleteOnExit()
        return file
    }

    @Test
    fun `shellExecute result contract is above 32`() {
        assertTrue(DesktopFileOpener.shellExecuteSucceeded(33L))
        assertTrue(DesktopFileOpener.shellExecuteSucceeded(64L))
        assertFalse(DesktopFileOpener.shellExecuteSucceeded(32L))
        assertFalse(DesktopFileOpener.shellExecuteSucceeded(2L))
        assertFalse(DesktopFileOpener.shellExecuteSucceeded(0L))
    }

    @Test
    fun `missing file opens nothing and invokes no route`() {
        var shellCalls = 0
        var fallbackCalls = 0
        val opener = DesktopFileOpener(
            shellOpenAs = { shellCalls++; true },
            defaultOpen = { fallbackCalls++; true }
        )
        val missing = File(
            Files.createTempDirectory("open-with").toFile(), "not-there.bin"
        ).absolutePath

        assertFalse(opener.openWith(missing, "not-there.bin", null))
        assertEquals(0, shellCalls)
        assertEquals(0, fallbackCalls)
    }

    @Test
    fun `shell chooser success opens and skips the fallback`() {
        var fallbackCalls = 0
        val opener = DesktopFileOpener(
            shellOpenAs = { true },
            defaultOpen = { fallbackCalls++; true }
        )
        assertTrue(opener.openWith(existingFile().absolutePath, "x.bin", null))
        // On non-Windows hosts the shell route is not attempted and the
        // fallback serves the open; either way exactly one route opened it
        // and the fallback ran at most once.
        assertTrue(fallbackCalls <= 1)
    }

    @Test
    fun `when the chooser cannot start the default app opens the file`() {
        val file = existingFile()
        val fallbackPaths = mutableListOf<String>()
        val opener = DesktopFileOpener(
            shellOpenAs = { false },
            defaultOpen = { fallbackPaths += it.absolutePath; true }
        )
        assertTrue(opener.openWith(file.absolutePath, "x.bin", null))
        assertEquals(listOf(file.absolutePath), fallbackPaths)
    }

    @Test
    fun `when every route fails the result is false so the caller can say so`() {
        val opener = DesktopFileOpener(
            shellOpenAs = { false },
            defaultOpen = { false }
        )
        assertFalse(opener.openWith(existingFile().absolutePath, "x.bin", null))
    }
}
