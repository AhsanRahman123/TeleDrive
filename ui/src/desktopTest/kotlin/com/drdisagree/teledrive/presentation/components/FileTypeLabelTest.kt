package com.drdisagree.teledrive.presentation.components

import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypeLabelTest {

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
