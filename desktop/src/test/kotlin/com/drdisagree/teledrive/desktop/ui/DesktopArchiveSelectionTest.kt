package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.components.WindowSelectAllRegistry
import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Selection-parity tests for the desktop ZIP viewer (1.6.21 defect 2): the archive
 * list mirrors the desktop Files section's selection — one selection state from
 * which checkbox ticks, row highlight and the "N selected" count are all derived,
 * the same checkbox presentation, Ctrl+A through the window-level registry Files
 * uses, Escape/Space through the Files keyboard collection, drag paint over the
 * list's real padding, folder rows selected by exact path, and row heights that do
 * not change when selection mode starts. The field failure this regresses: ticked
 * checkboxes, row highlight and the count disagreed, and rows changed height when
 * selection mode started.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveSelectionTest {

    @get:Rule
    val rule = createComposeRule()

    private val listing = listOf(
        ArchiveEntry("docs/readme.txt", 10, 9, false),
        ArchiveEntry("docs/guide.txt", 20, 18, false),
        ArchiveEntry("pic.png", 30, 27, false),
        ArchiveEntry("song.mp3", 40, 36, false)
    )

    private fun zipFile(id: String): DriveFile = DriveFile(
        id = id,
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

    private val downloadSelection = AtomicReference<Pair<Set<Int>, Set<String>>?>(null)
    private val taps = mutableListOf<Int>()

    private fun setPane(
        fileId: String = "sel-zip",
        modifier: Modifier = Modifier,
        tagSuffix: String = ""
    ) {
        rule.setContent {
            MaterialTheme {
              ArchiveTestProviders {
                DesktopArchivePane(
                    file = zipFile(fileId),
                    archive = PreviewContent.Archive(listing, "ZIP", remote = true),
                    busyEntryIndex = null,
                    batchProgress = null,
                    archivePreview = null,
                    sortField = FileSortField.NAME,
                    sortDirection = SortDirection.ASCENDING,
                    onSortChange = { _, _ -> },
                    onEntryTap = { taps += it },
                    onEntryDownload = {},
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
                    snackbarHostState = remember { SnackbarHostState() },
                    modifier = modifier.testTag("pane$tagSuffix")
                )
              }
            }
        }
        rule.waitForIdle()
    }

    /** The drawn centre of [rowTag] in list-node-local pixels (where a user presses). */
    private fun drawnCenter(rowTag: String, listIndex: Int = 0): Offset {
        val listBounds = rule.onAllNodesWithTag(ARCHIVE_LIST_TEST_TAG)[listIndex]
            .fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onAllNodesWithTag(rowTag)[listIndex]
            .fetchSemanticsNode().boundsInRoot
        return rowBounds.center - listBounds.topLeft
    }

    private fun rowHeight(rowTag: String): Float =
        rule.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot.height

    private fun selectedCheckboxCount(): Int =
        rule.onAllNodesWithContentDescription("Selected").fetchSemanticsNodes().size

    /** One single-call long-press (optionally dragging to [toTag]) on the archive list. */
    private fun longPress(fromTag: String, toTag: String? = null) {
        val start = drawnCenter(fromTag)
        val end = toTag?.let { drawnCenter(it) }
        rule.onNodeWithTag(ARCHIVE_LIST_TEST_TAG).performTouchInput {
            down(start)
            advanceEventTime(LONG_PRESS_ADVANCE_MS)
            if (end != null) moveBy(Offset(0f, end.y - start.y))
            up()
        }
        rule.waitForIdle()
    }

    /** Clicks the checkbox at the start of the row with [rowTag] (48.dp target). */
    private fun clickCheckbox(rowTag: String) {
        val listBounds = rule.onNodeWithTag(ARCHIVE_LIST_TEST_TAG)
            .fetchSemanticsNode().boundsInRoot
        val rowBounds = rule.onNodeWithTag(rowTag).fetchSemanticsNode().boundsInRoot
        val point = Offset(
            x = rowBounds.left - listBounds.left + 24f,
            y = rowBounds.center.y - listBounds.top
        )
        rule.onNodeWithTag(ARCHIVE_LIST_TEST_TAG).performTouchInput { click(point) }
        rule.waitForIdle()
    }

    @Test
    fun `drag across rows selects exactly the crossed rows and ticks count and height agree`() {
        setPane()
        val heightBefore = rowHeight("archive-entry-2")
        // Collapsed rows: [docs folder, pic.png, song.mp3]. Drag pic -> song.
        longPress("archive-entry-2", "archive-entry-3")
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        assertEquals(2, selectedCheckboxCount())
        assertEquals(
            "row height changed when selection mode started",
            heightBefore,
            rowHeight("archive-entry-2")
        )
        // Download payload proves the selected identities are exactly pic + song.
        rule.onNodeWithText("Download selected").performClick()
        assertEquals(setOf(2, 3) to emptySet<String>(), downloadSelection.get())
    }

    @Test
    fun `checkbox toggle selects exactly that row`() {
        setPane()
        longPress("archive-entry-2")
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        assertEquals(1, selectedCheckboxCount())
        clickCheckbox("archive-entry-3")
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        assertEquals(2, selectedCheckboxCount())
        clickCheckbox("archive-entry-3")
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        assertEquals(1, selectedCheckboxCount())
        rule.onNodeWithText("Download selected").performClick()
        assertEquals(setOf(2) to emptySet<String>(), downloadSelection.get())
    }

    @Test
    fun `window ctrl+a toggles select all and escape clears`() {
        setPane()
        var dispatched = false
        rule.runOnIdle { dispatched = WindowSelectAllRegistry.dispatchSelectAll(false) }
        assertTrue("window Ctrl+A was not routed to the archive pane", dispatched)
        rule.waitForIdle()
        // Collapsed rows: docs folder + pic + song.
        rule.onNodeWithText("3 selected").assertIsDisplayed()
        assertEquals(3, selectedCheckboxCount())
        rule.runOnIdle { WindowSelectAllRegistry.dispatchSelectAll(false) }
        rule.waitForIdle()
        assertEquals(0, selectedCheckboxCount())
        assertTrue(rule.onAllNodesWithText("3 selected").fetchSemanticsNodes().isEmpty())

        rule.runOnIdle { WindowSelectAllRegistry.dispatchSelectAll(false) }
        rule.waitForIdle()
        rule.onNodeWithText("3 selected").assertIsDisplayed()
        rule.onNodeWithTag("archive-entry-2").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(0, selectedCheckboxCount())
        assertTrue(rule.onAllNodesWithText("3 selected").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `space toggles the focused row through the keyboard collection`() {
        setPane()
        rule.runOnIdle { WindowSelectAllRegistry.dispatchSelectAll(false) }
        rule.waitForIdle()
        rule.onNodeWithText("3 selected").assertIsDisplayed()
        // Clicking pic focuses its row and toggles it off (selection-mode click).
        rule.onNodeWithText("pic.png").performClick()
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        // Space on the focused row toggles it back on.
        rule.onNodeWithTag("archive-entry-2").performKeyInput { pressKey(Key.Spacebar) }
        rule.waitForIdle()
        rule.onNodeWithText("3 selected").assertIsDisplayed()
        assertEquals(3, selectedCheckboxCount())
    }

    @Test
    fun `folder row selects by exact path and reaches the download payload`() {
        setPane()
        // Expanding the synthesized folder row reveals its children.
        rule.onNodeWithText("docs").performClick()
        rule.onNodeWithText("readme.txt").assertIsDisplayed()
        rule.onNodeWithText("guide.txt").assertIsDisplayed()
        // Enter selection mode via a file, then tick the folder's checkbox.
        longPress("archive-entry-2")
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        clickCheckbox("archive-folder-docs")
        rule.onNodeWithText("2 selected").assertIsDisplayed()
        assertEquals(2, selectedCheckboxCount())
        rule.onNodeWithText("Download selected").performClick()
        assertEquals(setOf(2) to setOf("docs"), downloadSelection.get())
    }

    @Test
    fun `selection is keyed per zip and does not leak between panes`() {
        rule.setContent {
            MaterialTheme {
              ArchiveTestProviders {
                Column {
                    Box(Modifier.height(340.dp)) {
                        DesktopArchivePane(
                            file = zipFile("zip-a"),
                            archive = PreviewContent.Archive(listing, "ZIP", remote = true),
                            busyEntryIndex = null,
                            batchProgress = null,
                            archivePreview = null,
                            sortField = FileSortField.NAME,
                            sortDirection = SortDirection.ASCENDING,
                            onSortChange = { _, _ -> },
                            onEntryTap = {},
                            onEntryDownload = {},
                            onDownloadSelection = { _, _ -> },
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
                    Box(Modifier.height(340.dp)) {
                        DesktopArchivePane(
                            file = zipFile("zip-b"),
                            archive = PreviewContent.Archive(listing, "ZIP", remote = true),
                            busyEntryIndex = null,
                            batchProgress = null,
                            archivePreview = null,
                            sortField = FileSortField.NAME,
                            sortDirection = SortDirection.ASCENDING,
                            onSortChange = { _, _ -> },
                            onEntryTap = {},
                            onEntryDownload = {},
                            onDownloadSelection = { _, _ -> },
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
            }
        }
        rule.waitForIdle()
        // Long-press pic in the FIRST pane only (index 0 of the duplicated tags).
        val start = drawnCenter("archive-entry-2", listIndex = 0)
        rule.onAllNodesWithTag(ARCHIVE_LIST_TEST_TAG)[0].performTouchInput {
            down(start)
            advanceEventTime(LONG_PRESS_ADVANCE_MS)
            up()
        }
        rule.waitForIdle()
        // Exactly one pane reports a selection.
        assertEquals(
            1,
            rule.onAllNodesWithText("1 selected").fetchSemanticsNodes().size
        )
        assertEquals(1, selectedCheckboxCount())
    }

    private companion object {
        const val LONG_PRESS_ADVANCE_MS = 1_000L
    }
}
