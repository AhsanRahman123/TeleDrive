package com.drdisagree.teledrive.presentation.transfers

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.TransferTask
import com.drdisagree.teledrive.domain.model.TransferType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The finished-row menu model behind "Open in Pranto Drive" / "Open with…".
 * The vc179 regression: report-only ARCHIVE_DOWNLOAD rows carry a localPath on
 * completion but have no fileId and are not isIncoming, so the old wiring
 * (fileId for in-app, isIncoming for open-with) left their menu with only
 * Details.
 */
class TransferOpenActionsTest {

    private fun task(
        type: TransferType = TransferType.DOWNLOAD,
        state: TransferState = TransferState.COMPLETED,
        fileId: String? = "file-1",
        localPath: String? = "/storage/emulated/0/Download/song.mp3"
    ) = TransferTask(
        id = "transfer-1",
        type = type,
        fileId = fileId,
        displayName = "song.mp3",
        sizeBytes = 100,
        transferredBytes = 100,
        state = state,
        priority = 0,
        errorMessage = null,
        speedBytesPerSecond = 0,
        createdAt = 0,
        updatedAt = 0,
        completedAt = 0,
        localPath = localPath
    )

    @Test
    fun `completed download with file and local path offers both open actions`() {
        assertEquals(
            setOf(TransferOpenAction.OPEN_IN_APP, TransferOpenAction.OPEN_WITH),
            completedTransferOpenActions(task(), fileOpenerAvailable = true)
        )
    }

    @Test
    fun `completed archive download offers open with from its local path`() {
        // Report-only record: no fileId (nothing for the id-based preview to
        // resolve), but the extracted file is on disk, so Open with… must show.
        // Since vc181 it also offers Open in Pranto Drive, routed to the
        // local-path preview instead of the id-based one.
        val actions = completedTransferOpenActions(
            task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null),
            fileOpenerAvailable = true
        )
        assertEquals(
            setOf(TransferOpenAction.OPEN_IN_APP, TransferOpenAction.OPEN_WITH),
            actions
        )
    }

    @Test
    fun `completed archive download without a local path offers no open actions`() {
        assertTrue(
            completedTransferOpenActions(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null, localPath = null),
                fileOpenerAvailable = true
            ).isEmpty()
        )
        assertTrue(
            completedTransferOpenActions(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null, localPath = "  "),
                fileOpenerAvailable = true
            ).isEmpty()
        )
    }

    // --- vc181 row-tap target matrix ---

    @Test
    fun `completed normal download row tap opens in app by file id`() {
        assertEquals(
            TransferRowTapTarget.InApp("file-1"),
            completedTransferRowTap(task())
        )
    }

    @Test
    fun `completed archive row tap opens local preview from its saved path`() {
        assertEquals(
            TransferRowTapTarget.LocalPreview(
                "/storage/emulated/0/Download/song.mp3",
                "song.mp3"
            ),
            completedTransferRowTap(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null)
            )
        )
    }

    @Test
    fun `completed archive row tap with blank or missing local path is none`() {
        assertEquals(
            TransferRowTapTarget.None,
            completedTransferRowTap(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null, localPath = null)
            )
        )
        assertEquals(
            TransferRowTapTarget.None,
            completedTransferRowTap(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null, localPath = "  ")
            )
        )
    }

    @Test
    fun `non-completed row taps are none`() {
        for (state in listOf(
            TransferState.RUNNING,
            TransferState.PAUSED,
            TransferState.FAILED,
            TransferState.QUEUED
        )) {
            assertEquals(TransferRowTapTarget.None, completedTransferRowTap(task(state = state)))
            assertEquals(
                TransferRowTapTarget.None,
                completedTransferRowTap(
                    task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null, state = state)
                )
            )
        }
    }

    @Test
    fun `completed upload row tap keeps in app route and upload without file id is none`() {
        assertEquals(
            TransferRowTapTarget.InApp("file-1"),
            completedTransferRowTap(task(type = TransferType.UPLOAD))
        )
        // An upload's localPath is its source file, not a Transfers download
        // to preview, so a file-less upload row tap stays a no-op.
        assertEquals(
            TransferRowTapTarget.None,
            completedTransferRowTap(task(type = TransferType.UPLOAD, fileId = null))
        )
    }

    @Test
    fun `open with needs a local path and a platform opener`() {
        assertTrue(
            completedTransferOpenActions(
                task(localPath = null),
                fileOpenerAvailable = true
            ).none { it == TransferOpenAction.OPEN_WITH }
        )
        // No platform opener drops OPEN_WITH, but since vc181 the archive row
        // keeps OPEN_IN_APP: the local preview needs no platform opener.
        assertEquals(
            setOf(TransferOpenAction.OPEN_IN_APP),
            completedTransferOpenActions(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null),
                fileOpenerAvailable = false
            )
        )
        assertTrue(
            completedTransferOpenActions(
                task(localPath = "  "),
                fileOpenerAvailable = true
            ).none { it == TransferOpenAction.OPEN_WITH }
        )
    }

    @Test
    fun `non-completed records offer no open actions`() {
        assertTrue(
            completedTransferOpenActions(
                task(state = TransferState.RUNNING),
                fileOpenerAvailable = true
            ).isEmpty()
        )
        assertTrue(
            completedTransferOpenActions(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null, state = TransferState.FAILED),
                fileOpenerAvailable = true
            ).isEmpty()
        )
    }

    @Test
    fun `completed upload keeps in-app open but gains no open with`() {
        // Uploads were never offered Open with… (their localPath is the source
        // file, not a download); the fix must not change that.
        assertEquals(
            setOf(TransferOpenAction.OPEN_IN_APP),
            completedTransferOpenActions(
                task(type = TransferType.UPLOAD),
                fileOpenerAvailable = true
            )
        )
    }
}
