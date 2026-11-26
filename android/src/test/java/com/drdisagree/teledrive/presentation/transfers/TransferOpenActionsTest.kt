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
        // Report-only record: no fileId (nothing for the in-app preview to
        // resolve), but the extracted file is on disk, so Open with… must show.
        val actions = completedTransferOpenActions(
            task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null),
            fileOpenerAvailable = true
        )
        assertEquals(setOf(TransferOpenAction.OPEN_WITH), actions)
    }

    @Test
    fun `open with needs a local path and a platform opener`() {
        assertTrue(
            completedTransferOpenActions(
                task(localPath = null),
                fileOpenerAvailable = true
            ).none { it == TransferOpenAction.OPEN_WITH }
        )
        assertTrue(
            completedTransferOpenActions(
                task(type = TransferType.ARCHIVE_DOWNLOAD, fileId = null),
                fileOpenerAvailable = false
            ).isEmpty()
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
