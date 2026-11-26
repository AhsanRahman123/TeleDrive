package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 1.6.24 note 2, pane level: while one in-ZIP download is running, the pane
 * must not go dead — the per-entry Download action stays live (its tap queues
 * in the ViewModel), Download selected stays enabled whenever a selection
 * exists, and queued downloads are visibly accounted for in the header. The
 * old pane gated all three on a single `busy` flag.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveDownloadWhileBusyTest {

    @get:Rule
    val rule = createComposeRule()

    private val listing = listOf(
        ArchiveEntry("one.bin", 10, 9, false),
        ArchiveEntry("two.bin", 20, 18, false),
        ArchiveEntry("three.bin", 30, 27, false)
    )

    private fun zipFile(): DriveFile = DriveFile(
        id = "busy-zip",
        folderId = null,
        name = "test.zip",
        sizeBytes = 100,
        mimeType = "application/zip",
        category = FileCategory.ARCHIVE,
        localPath = null,
        contentHash = null,
        chatId = 1L,
        messageId = 2L,
        remoteFileId = "remote-1",
        remoteUniqueId = "unique-1",
        backupState = BackupState.BACKED_UP,
        isHidden = false,
        isArchived = false,
        isFavorite = false,
        isEncrypted = false,
        width = null,
        height = null,
        durationMs = null,
        trashedAt = null,
        createdAt = 0L,
        modifiedAt = 0L,
        addedAt = 0L
    )

    private val entryDownloads = CopyOnWriteArrayList<Int>()
    private val downloadSelection = AtomicReference<Pair<Set<Int>, Set<String>>?>(null)

    private fun setPane() {
        rule.setContent {
            MaterialTheme {
              ArchiveTestProviders {
                DesktopArchivePane(
                    file = zipFile(),
                    archive = PreviewContent.Archive(listing, "ZIP", remote = true),
                    // Entry 0 is the actually-running download; two more
                    // requests are queued behind it.
                    busyEntryIndex = 0,
                    batchProgress = null,
                    queuedDownloads = 2,
                    archivePreview = null,
                    sortField = FileSortField.NAME,
                    sortDirection = SortDirection.ASCENDING,
                    onSortChange = { _, _ -> },
                    onEntryTap = {},
                    onEntryDownload = { entryDownloads += it },
                    onDownloadSelection = { entries, folders ->
                        downloadSelection.set(entries to folders)
                    },
                    onDismissPreview = {},
                    preferredAudioLanguage = "",
                    preferredSubtitleLanguage = "",
                    onPreferredAudioLanguage = {},
                    onPreferredSubtitleLanguage = {},
                    backgroundPlayback = false,
                    onBackgroundPlaybackChange = {},
                    snackbarHostState = remember { SnackbarHostState() }
                )
              }
            }
        }
        rule.waitForIdle()
    }

    /** The drawn centre of [rowTag] in list-node-local pixels (where a user presses). */
    private fun drawnCenter(rowTag: String): Offset {
        val listBounds = rule.onAllNodesWithTag(ARCHIVE_LIST_TEST_TAG)[0]
            .fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onAllNodesWithTag(rowTag)[0]
            .fetchSemanticsNode().boundsInRoot
        return rowBounds.center - listBounds.topLeft
    }

    private fun longPress(rowTag: String) {
        val start = drawnCenter(rowTag)
        rule.onAllNodesWithTag(ARCHIVE_LIST_TEST_TAG)[0].performTouchInput {
            down(start)
            advanceEventTime(600)
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun `queued downloads are accounted for and download actions stay live while one runs`() {
        setPane()

        // Queued work is visibly accounted for in the header.
        rule.onNodeWithText("2 more downloads queued").assertIsDisplayed()

        // The running entry (0) shows its busy spinner and no Download action;
        // the other rows keep theirs, and tapping one reaches the ViewModel
        // (where it queues) instead of being swallowed by the busy gate.
        assertEquals(
            2,
            rule.onAllNodesWithContentDescription("Download").fetchSemanticsNodes().size
        )
        // Address entry 1's own Download action through its row (semantics
        // node order across rows is not something to rely on).
        rule.onNode(
            hasAnyAncestor(hasTestTag("archive-entry-1")) and hasContentDescription("Download")
        ).performClick()
        rule.waitForIdle()
        assertEquals(listOf(1), entryDownloads.toList())

        // Download selected is enabled while a download runs: select entry 2
        // and start a batch.
        longPress("archive-entry-2")
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithText("Download selected").assertIsEnabled().performClick()
        rule.waitForIdle()
        assertEquals(setOf(2) to emptySet<String>(), downloadSelection.get())
    }
}
