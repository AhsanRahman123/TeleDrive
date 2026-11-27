package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.drdisagree.teledrive.domain.model.BackupState
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
import com.drdisagree.teledrive.presentation.preview.PreviewContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * R3 item P-A9 (PC side of Android A9): the type-label derivation the PC
 * archive contents rows use for their no-thumbnail placeholder. The
 * cases mirror the shared FileTypeLabelTest on r3/a4-shell one-for-one
 * (same inputs, same expected labels), so the desktop mirror and the
 * shared helper cannot drift apart silently; the compose test pins the
 * label's presence in the REAL [DesktopArchivePane] row.
 */
class DesktopFileTypeLabelTest {

    @Test
    fun `extension wins and is lower-cased`() {
        assertEquals("jpg", fileTypeLabel("photo.JPG", "image/jpeg", FileCategory.IMAGE))
        assertEquals("png", fileTypeLabel("icon.png", "image/png", FileCategory.IMAGE))
        assertEquals("pdf", fileTypeLabel("report.PDF", "application/pdf", FileCategory.DOCUMENT))
        assertEquals("mp4", fileTypeLabel("clip.mp4", "video/mp4", FileCategory.VIDEO))
        assertEquals("mp3", fileTypeLabel("song.mp3", "audio/mpeg", FileCategory.AUDIO))
        assertEquals("opus", fileTypeLabel("voice.opus", "audio/ogg", FileCategory.AUDIO))
    }

    @Test
    fun `last extension of a multi-dot name is used`() {
        assertEquals("gz", fileTypeLabel("backup.tar.gz", "application/gzip", FileCategory.ARCHIVE))
    }

    @Test
    fun `path segments do not leak into the extension`() {
        assertEquals("png", fileTypeLabel("folder/sub/photo.png", null, FileCategory.IMAGE))
    }

    @Test
    fun `mime subtype is used when the name has no extension`() {
        assertEquals("pdf", fileTypeLabel("noext", "application/pdf", FileCategory.DOCUMENT))
        assertEquals("mp4", fileTypeLabel(null, "video/mp4", FileCategory.VIDEO))
        assertEquals("svg", fileTypeLabel(null, "image/svg+xml", FileCategory.IMAGE))
    }

    @Test
    fun `mime spellings are aliased to the labels users expect`() {
        assertEquals("jpg", fileTypeLabel(null, "image/jpeg", FileCategory.IMAGE))
        assertEquals("mp3", fileTypeLabel(null, "audio/mpeg", FileCategory.AUDIO))
        assertEquals("txt", fileTypeLabel(null, "text/plain", FileCategory.DOCUMENT))
        assertEquals("txt", fileTypeLabel(null, "text/plain; charset=utf-8", FileCategory.DOCUMENT))
    }

    @Test
    fun `kind word is used when neither extension nor mime gives a label`() {
        assertEquals("image", fileTypeLabel(null, null, FileCategory.IMAGE))
        assertEquals("video", fileTypeLabel("trailing.", null, FileCategory.VIDEO))
        assertEquals("audio", fileTypeLabel(null, "", FileCategory.AUDIO))
        assertEquals("doc", fileTypeLabel(null, "application/vnd.ms-excel", FileCategory.DOCUMENT))
        assertEquals(
            "doc",
            fileTypeLabel(
                null,
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                FileCategory.DOCUMENT
            )
        )
        assertEquals("archive", fileTypeLabel(null, null, FileCategory.ARCHIVE))
    }

    @Test
    fun `unknown files fall back to file`() {
        assertEquals("file", fileTypeLabel(null, null, null))
        assertEquals("file", fileTypeLabel(".hidden", "", FileCategory.OTHER))
        assertEquals("file", fileTypeLabel("noext", "application/octet-stream", FileCategory.OTHER))
    }

    @Test
    fun `an unusable extension falls through to the mime label`() {
        assertEquals(
            "mp4",
            fileTypeLabel("a.verylongextension", "video/mp4", FileCategory.VIDEO)
        )
    }
}

@OptIn(ExperimentalTestApi::class)
class DesktopArchiveTypeLabelComposeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `archive rows without thumbnails show their type label`() {
        val zipFile = DriveFile(
            id = "label-zip",
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
        rule.setContent {
            MaterialTheme {
                ArchiveTestProviders {
                    DesktopArchivePane(
                        file = zipFile,
                        archive = PreviewContent.Archive(
                            listOf(
                                ArchiveEntry("report.pdf", 100, 90, false),
                                ArchiveEntry("song.mp3", 50, 45, false)
                            ),
                            "ZIP",
                            remote = false
                        ),
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
        rule.waitForIdle()
        // The labels are exactly "pdf" / "mp3" — distinct from the row
        // names ("report.pdf" / "song.mp3") they sit under the icon of.
        rule.onNodeWithText("pdf").assertIsDisplayed()
        rule.onNodeWithText("mp3").assertIsDisplayed()
    }
}
