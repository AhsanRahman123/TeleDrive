package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.drdisagree.teledrive.domain.model.ArchiveThumbnailSize
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * PC 1.6.26 item P7 — the ZIP contents list's thumbnail size switcher,
 * driven through the REAL [DesktopArchivePane]: choosing Big / Medium /
 * Small in the header menu reports the choice through the pane's callback
 * (the production wiring persists it as `archive_thumbnail_size` — see
 * ArchiveThumbnailSizeSettingsTest) and the row thumbnail slots actually
 * resize, with the pre-switcher 56 dp as the Medium step. PC 1.6.27 (N6)
 * re-mapped the PC steps: Small / Medium stay round 40 / 56 dp slots,
 * while Big is a wide 152x112 dp preview tile. Assertions are
 * ratio-based so the test does not depend on the test runtime's density.
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveThumbnailSizeTest {

    @get:Rule
    val rule = createComposeRule()

    private val listing = listOf(
        ArchiveEntry("pic.png", 100, 90, false),
        ArchiveEntry("notes.txt", 50, 45, false)
    )

    private fun zipFile(): DriveFile = DriveFile(
        id = "thumb-zip",
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

    private val changes = CopyOnWriteArrayList<ArchiveThumbnailSize>()

    private fun setPane() {
        changes.clear()
        rule.setContent {
            MaterialTheme {
                ArchiveTestProviders {
                    var size by remember { mutableStateOf(ArchiveThumbnailSize.MEDIUM) }
                    DesktopArchivePane(
                        file = zipFile(),
                        archive = PreviewContent.Archive(listing, "ZIP", remote = false),
                        busyEntryIndex = null,
                        batchProgress = null,
                        archivePreview = null,
                        thumbnailSize = size,
                        onThumbnailSizeChange = { picked ->
                            changes += picked
                            size = picked
                        },
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
        rule.waitForIdle()
    }

    private fun thumbnailWidth(): Float =
        // The slot lives inside the row's merged semantics (combinedClickable
        // merges the row), so it only exists in the unmerged tree.
        rule.onNodeWithTag("archive-thumbnail-0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.width

    private fun thumbnailHeight(): Float =
        rule.onNodeWithTag("archive-thumbnail-0", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot.height

    private fun pickSize(name: String) {
        rule.onNodeWithContentDescription("Thumbnail size").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("$ARCHIVE_THUMBNAIL_SIZE_TAG-$name").performClick()
        rule.waitForIdle()
    }

    @Test
    fun `switcher resizes the thumbnail slots through the persisted steps`() {
        setPane()
        val medium = thumbnailWidth()
        val mediumHeight = thumbnailHeight()
        assertTrue("medium slot must have a real size", medium > 0f)
        assertEquals("medium slot is square", medium, mediumHeight, 2f)

        pickSize("big")
        val big = thumbnailWidth()
        assertEquals(
            "Big tile must be 152/56 of Medium wide",
            medium * 152f / 56f,
            big,
            2f
        )
        assertEquals(
            "Big tile must be 112/56 of Medium high",
            mediumHeight * 112f / 56f,
            thumbnailHeight(),
            2f
        )
        assertTrue("Big tile must be wider than it is tall", big > thumbnailHeight())

        pickSize("small")
        val small = thumbnailWidth()
        assertEquals(
            "Small must be 40/56 of Medium",
            medium * 40f / 56f,
            small,
            2f
        )
        assertEquals("small slot is square", small, thumbnailHeight(), 2f)

        pickSize("medium")
        assertEquals(medium, thumbnailWidth(), 2f)

        assertEquals(
            listOf(
                ArchiveThumbnailSize.BIG,
                ArchiveThumbnailSize.SMALL,
                ArchiveThumbnailSize.MEDIUM
            ),
            changes.toList()
        )
    }
}
