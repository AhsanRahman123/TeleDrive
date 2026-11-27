package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * vc183 item 4: the normal-route preview dispatch ([previewDispatch]) and the
 * auto-fetch gate must classify EPUB/OOXML files to their document viewer
 * exactly as the in-zip route does — by file NAME when the stored MIME cannot
 * be trusted. Inside a ZIP these same files previewed (vc181 B8/B9) because
 * `archiveEntryTapAction` keys off `MimeTypes.fromFileName(entry.name)`
 * alone; on the normal route the stored MIME is whatever was declared when
 * the file was indexed, and EPUB/OOXML are ZIP containers, so a declared
 * `application/zip` (or another container-ish value) used to capture the
 * file into the archive/text branches before the document branch — EPUB
 * landed on "No preview for this file type" and the document viewer was
 * never reached.
 */
class PreviewDispatchTest {

    private companion object {
        const val MIB = 1024L * 1024L
        const val EPUB_MIME = "application/epub+zip"
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        const val ZIP_MIME = "application/zip"
    }

    // ---- The document types classify to their viewer on the normal route ----

    @Test
    fun `epub and ooxml classify to the document viewer by canonical mime`() {
        assertEquals(
            PreviewDispatch.DOCUMENT,
            previewDispatch(EPUB_MIME, FileCategory.DOCUMENT, "novel.epub")
        )
        assertEquals(
            PreviewDispatch.DOCUMENT,
            previewDispatch(DOCX_MIME, FileCategory.DOCUMENT, "report.docx")
        )
        assertEquals(
            PreviewDispatch.DOCUMENT,
            previewDispatch(XLSX_MIME, FileCategory.DOCUMENT, "sheet.xlsx")
        )
        assertEquals(
            PreviewDispatch.DOCUMENT,
            previewDispatch(PPTX_MIME, FileCategory.DOCUMENT, "deck.pptx")
        )
    }

    @Test
    fun `epub and ooxml classify to the document viewer when the stored mime is the zip container type`() {
        // The field failure class: the file IS a zip container, so a declared
        // application/zip MIME plus an ARCHIVE category is a realistic stored
        // state for it. The name must win, like it does inside a ZIP.
        for (name in listOf("novel.epub", "report.docx", "sheet.xlsx", "deck.pptx")) {
            assertEquals(
                "stored zip MIME must not archive-route $name",
                PreviewDispatch.DOCUMENT,
                previewDispatch(ZIP_MIME, FileCategory.ARCHIVE, name)
            )
        }
    }

    @Test
    fun `epub and ooxml classify to the document viewer when the stored mime is generic or xml`() {
        for (name in listOf("novel.epub", "report.docx", "sheet.xlsx", "deck.pptx")) {
            assertEquals(
                PreviewDispatch.DOCUMENT,
                previewDispatch("application/octet-stream", FileCategory.OTHER, name)
            )
            assertEquals(
                PreviewDispatch.DOCUMENT,
                previewDispatch("application/xml", FileCategory.DOCUMENT, name)
            )
        }
    }

    @Test
    fun `document preview type is recognised by name alone`() {
        assertTrue(isDocumentPreviewType("application/octet-stream", "novel.epub"))
        assertTrue(isDocumentPreviewType(ZIP_MIME, "report.docx"))
        assertTrue(isDocumentPreviewType(EPUB_MIME, "no-extension"))
        assertFalse(isDocumentPreviewType(ZIP_MIME, "archive.zip"))
        assertFalse(isDocumentPreviewType("application/msword", "legacy.doc"))
        assertFalse(isDocumentPreviewType("text/plain", "notes.txt"))
    }

    // ---- The auto-fetch gate follows the same classification ----

    @Test
    fun `remote epub and ooxml with a container mime still auto-fetch within the document cap`() {
        assertTrue(
            isAutoFetchable(ZIP_MIME, FileCategory.ARCHIVE, "novel.epub", 10 * MIB)
        )
        assertTrue(
            isAutoFetchable(
                "application/octet-stream", FileCategory.OTHER, "report.docx", 10 * MIB
            )
        )
        assertFalse(
            isAutoFetchable(ZIP_MIME, FileCategory.ARCHIVE, "novel.epub", 21 * MIB)
        )
    }

    // ---- No regressions for the other viewer types ----

    @Test
    fun `other types keep their dispatch`() {
        assertEquals(
            PreviewDispatch.PDF,
            previewDispatch("application/pdf", FileCategory.DOCUMENT, "book.pdf")
        )
        assertEquals(
            PreviewDispatch.ARCHIVE,
            previewDispatch(ZIP_MIME, FileCategory.ARCHIVE, "archive.zip")
        )
        assertEquals(
            PreviewDispatch.ARCHIVE,
            previewDispatch(
                "application/vnd.comicbook+zip", FileCategory.ARCHIVE, "comic.cbz"
            )
        )
        assertEquals(
            PreviewDispatch.ARCHIVE,
            previewDispatch(
                "application/x-7z-compressed", FileCategory.ARCHIVE, "archive.7z"
            )
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("text/plain", FileCategory.DOCUMENT, "notes.txt")
        )
        assertEquals(
            PreviewDispatch.HTML,
            previewDispatch("text/html", FileCategory.DOCUMENT, "page.html")
        )
        assertEquals(
            PreviewDispatch.IMAGE,
            previewDispatch("image/png", FileCategory.IMAGE, "photo.png")
        )
        assertEquals(
            PreviewDispatch.VIDEO,
            previewDispatch("video/mp4", FileCategory.VIDEO, "movie.mp4")
        )
        assertEquals(
            PreviewDispatch.AUDIO,
            previewDispatch("audio/mpeg", FileCategory.AUDIO, "song.mp3")
        )
        assertEquals(
            PreviewDispatch.APK,
            previewDispatch(
                "application/vnd.android.package-archive", FileCategory.OTHER, "app.apk"
            )
        )
    }

    @Test
    fun `legacy office and unknown types stay unsupported`() {
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/msword", FileCategory.DOCUMENT, "legacy.doc")
        )
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/vnd.ms-excel", FileCategory.DOCUMENT, "legacy.xls")
        )
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch(
                "application/vnd.ms-powerpoint", FileCategory.DOCUMENT, "legacy.ppt"
            )
        )
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "blob.bin")
        )
    }
}
