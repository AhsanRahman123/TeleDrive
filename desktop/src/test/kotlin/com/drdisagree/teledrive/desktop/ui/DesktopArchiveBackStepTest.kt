package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
import com.drdisagree.teledrive.presentation.preview.ArchiveEntryPreview
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 1.6.24 note 1: one Back press inside a ZIP must step up ONE folder level at
 * a time and exit only at the zip root — the desktop pane previously had no
 * back step at all, so the preview screen's back button left the whole viewer
 * from any depth. The pane now registers the same step ladder Android's
 * ArchiveList uses (shared `archiveBackStep` decision): preview -> search ->
 * selection -> collapse deepest expanded folder -> exit.
 *
 * The required sequence is pinned here against the REAL pane: nested folder
 * -> back -> parent -> back -> root -> back -> exits (handler returns false).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveBackStepTest {

    @get:Rule
    val rule = createComposeRule()

    private val listing = listOf(
        ArchiveEntry("a/top.txt", 10, 9, false),
        ArchiveEntry("a/b/mid.txt", 20, 18, false),
        ArchiveEntry("a/b/c/deep.txt", 30, 27, false),
        ArchiveEntry("root.txt", 40, 36, false)
    )

    private fun zipFile(): DriveFile = DriveFile(
        id = "back-zip",
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

    private val backHandler = AtomicReference<(() -> Boolean)?>(null)
    private val exits = AtomicInteger(0)
    private val dismissals = AtomicInteger(0)

    private fun setPane(withPreview: Boolean = false) {
        rule.setContent {
            MaterialTheme {
              ArchiveTestProviders {
                var preview by remember {
                    mutableStateOf<ArchiveEntryPreview?>(
                        if (withPreview) {
                            ArchiveEntryPreview.Text("deep.txt", "hello from inside the zip")
                        } else {
                            null
                        }
                    )
                }
                DesktopArchivePane(
                    file = zipFile(),
                    archive = PreviewContent.Archive(listing, "ZIP", remote = true),
                    busyEntryIndex = null,
                    batchProgress = null,
                    archivePreview = preview,
                    onRegisterBackStep = { handler -> backHandler.set(handler) },
                    onExitViewer = { exits.incrementAndGet() },
                    sortField = FileSortField.NAME,
                    sortDirection = SortDirection.ASCENDING,
                    onSortChange = { _, _ -> },
                    onEntryTap = {},
                    onEntryDownload = {},
                    onDownloadSelection = { _, _ -> },
                    onDismissPreview = {
                        dismissals.incrementAndGet()
                        preview = null
                    },
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

    private fun back(): Boolean {
        val handler = backHandler.get()
        assertTrue("pane must register a back-step handler", handler != null)
        return handler!!.invoke().also { rule.waitForIdle() }
    }

    private fun displayed(text: String): Boolean =
        rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `back steps up one folder level at a time and exits only at the zip root`() {
        setPane()

        // At the root with nothing expanded, back is NOT consumed: it exits.
        assertFalse(back())

        // Descend: a -> a/b -> a/b/c.
        rule.onNodeWithTag("archive-folder-a").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("archive-folder-a/b").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("archive-folder-a/b/c").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("deep.txt").assertIsDisplayed()
        rule.onNodeWithText("mid.txt").assertIsDisplayed()
        rule.onNodeWithText("top.txt").assertIsDisplayed()

        // Back 1: only the deepest folder (a/b/c) collapses.
        assertTrue(back())
        assertFalse("deep.txt must be hidden after one back", displayed("deep.txt"))
        assertTrue("mid.txt must still show after one back", displayed("mid.txt"))
        assertTrue("top.txt must still show after one back", displayed("top.txt"))

        // Back 2: a/b collapses; a stays open.
        assertTrue(back())
        assertFalse("mid.txt must be hidden after two backs", displayed("mid.txt"))
        assertTrue("top.txt must still show after two backs", displayed("top.txt"))

        // Back 3: a collapses; the viewer is back at the zip root.
        assertTrue(back())
        assertFalse("top.txt must be hidden at the root", displayed("top.txt"))
        assertTrue("root.txt must show at the root", displayed("root.txt"))

        // Back 4: at the root, back is not consumed — it exits the viewer.
        assertFalse(back())
    }

    @Test
    fun `back closes an open in-zip preview before touching folders`() {
        setPane(withPreview = true)
        rule.onNodeWithText("hello from inside the zip").assertIsDisplayed()

        assertTrue(back())
        assertEquals(1, dismissals.get())
        assertFalse(displayed("hello from inside the zip"))

        // Preview closed and nothing else active: the next back exits.
        assertFalse(back())
    }

    @Test
    fun `keyboard backspace runs the same ladder and exits at the root`() {
        setPane()

        rule.onNodeWithTag("archive-folder-a").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("archive-folder-a/b").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("mid.txt").assertIsDisplayed()

        // Backspace on a focused row collapses the deepest folder (a/b).
        rule.onNodeWithTag("archive-entry-1").performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertFalse("mid.txt must be hidden after keyboard back", displayed("mid.txt"))
        assertTrue("top.txt must still show after keyboard back", displayed("top.txt"))
        assertEquals(0, exits.get())

        // One more keyboard back collapses the remaining folder (a)...
        rule.onNodeWithTag("archive-entry-3").performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertFalse("top.txt must be hidden after second keyboard back", displayed("top.txt"))
        assertEquals(0, exits.get())

        // ...and only at the zip root does keyboard back leave the viewer.
        rule.onNodeWithTag("archive-entry-3").performKeyInput { pressKey(Key.Backspace) }
        rule.waitForIdle()
        assertEquals(1, exits.get())
    }
}
