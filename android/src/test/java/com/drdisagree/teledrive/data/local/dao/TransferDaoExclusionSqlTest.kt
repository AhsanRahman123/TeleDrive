package com.drdisagree.teledrive.data.local.dao

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * vc179: guards the REAL bulk-op exclusion SQL in [TransferDao] and the
 * drainer's claim filter. The behavioural tests run against hand-written
 * fakes, so nothing else in CI would catch someone deleting
 * `AND type != 'ARCHIVE_DOWNLOAD'` from the real `@Query` SQL.
 */
class TransferDaoExclusionSqlTest {

    private fun locate(relativePath: String): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        repeat(8) {
            val current = dir ?: return@repeat
            val candidate = File(current, relativePath)
            if (candidate.isFile) return candidate
            dir = current.parentFile
        }
        fail("Could not locate $relativePath walking up from ${System.getProperty("user.dir")}")
        throw AssertionError("unreachable")
    }

    private fun normalized(file: File): String =
        file.readText().replace(Regex("\\s+"), " ")

    private fun countOccurrences(text: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val idx = text.indexOf(needle, from)
            if (idx < 0) return count
            count++
            from = idx + needle.length
        }
    }

    @Test
    fun `real TransferDao SQL excludes archive downloads from bulk ops and targets them explicitly`() {
        val daoFile = locate(
            "shared/src/commonMain/kotlin/com/drdisagree/teledrive/data/local/dao/TransferDao.kt"
        )
        val sql = normalized(daoFile)

        val exclusions = countOccurrences(sql, "type != 'ARCHIVE_DOWNLOAD'")
        assertTrue(
            "Expected at least 4 occurrences of \"type != 'ARCHIVE_DOWNLOAD'\" " +
                "(requeueRunning, pauseAll, resumeAll, cancelAll) in TransferDao.kt, found $exclusions",
            exclusions >= 4
        )

        val targeted = countOccurrences(sql, "type = 'ARCHIVE_DOWNLOAD'")
        assertTrue(
            "Expected at least 2 occurrences of \"type = 'ARCHIVE_DOWNLOAD'\" " +
                "(failInterruptedArchiveDownloads, cancelAllArchiveDownloads) in TransferDao.kt, found $targeted",
            targeted >= 2
        )
    }

    @Test
    fun `real TransferQueueDrainer claim filter excludes archive downloads`() {
        val drainerFile = locate(
            "shared/src/jvmCommonMain/kotlin/com/drdisagree/teledrive/core/transfer/TransferQueueDrainer.kt"
        )
        val text = normalized(drainerFile)

        assertTrue(
            "Expected TransferQueueDrainer.kt's nextClaimable filter to contain " +
                "\"type != TransferType.ARCHIVE_DOWNLOAD\"",
            text.contains("type != TransferType.ARCHIVE_DOWNLOAD")
        )
    }
}
