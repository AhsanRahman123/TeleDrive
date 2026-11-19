package com.drdisagree.teledrive.desktop

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleInstanceLockTest {

    @Test
    fun `first instance acquires and a second one is refused`() {
        val directory = Files.createTempDirectory("teledrive-lock-test").toFile()
        val first = SingleInstanceLock(directory)
        assertTrue(first.acquire())

        // Same JVM: tryLock surfaces contention as an OverlappingFileLockException
        // rather than a null return; either way the second instance must be
        // refused, never admitted.
        val second = SingleInstanceLock(directory)
        assertFalse(second.acquire())
    }

    @Test
    fun `an unusable lock location fails closed instead of admitting the instance`() {
        // A regular file where the lock directory should be: the lock file can
        // never be opened there. The old implementation failed OPEN on this IO
        // error and let the instance start without the lock.
        val file = File.createTempFile("teledrive-lock-test", ".tmp")
        try {
            assertFalse(SingleInstanceLock(file).acquire())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `a missing lock directory is created and acquired`() {
        val base = Files.createTempDirectory("teledrive-lock-test").toFile()
        val nested = File(base, "files")
        assertTrue(SingleInstanceLock(nested).acquire())
        assertTrue(File(nested, "app.lock").isFile)
    }
}
