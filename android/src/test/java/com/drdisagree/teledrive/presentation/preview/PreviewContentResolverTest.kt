package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the normal-file route's auto-fetch gate (vc182 items 5 + 7-gates):
 * a remote PDF, EPUB or OOXML document previews on open up to exactly the
 * same size cap it gets inside a ZIP, instead of hitting the
 * Download-to-view gate that images/text/APKs get at 10 MiB. Everything
 * outside the listed types and caps keeps the gate exactly as before.
 * The resolver itself needs Telegram/storage collaborators, so the tests
 * target the pure [isAutoFetchable] predicate its resolve() uses.
 */
class PreviewContentResolverTest {

    private companion object {
        const val KIB = 1024L
        const val MIB = 1024L * 1024L

        const val PDF_MIME = "application/pdf"
        const val EPUB_MIME = "application/epub+zip"
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        const val PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    }

    // ---- Parity: the normal-file caps ARE the in-zip caps ----

    @Test
    fun `auto preview document caps equal the in-zip caps`() {
        assertEquals(
            ARCHIVE_PDF_PREVIEW_MAX_BYTES,
            PreviewContentResolver.PDF_AUTO_PREVIEW_LIMIT
        )
        assertEquals(
            ARCHIVE_DOCUMENT_PREVIEW_MAX_BYTES,
            PreviewContentResolver.DOCUMENT_AUTO_PREVIEW_LIMIT
        )
    }

    // ---- PDF (vc182) ----

    @Test
    fun `remote PDF fetches on open up to the in-zip cap`() {
        assertTrue(
            isAutoFetchable(PDF_MIME, FileCategory.DOCUMENT, "tiny.pdf", 5 * KIB)
        )
        assertTrue(
            isAutoFetchable(PDF_MIME, FileCategory.DOCUMENT, "book.pdf", 49 * MIB)
        )
        // The cap is inclusive, exactly like archiveEntryPdfPreviewable.
        assertTrue(
            isAutoFetchable(
                PDF_MIME, FileCategory.DOCUMENT, "book.pdf",
                PreviewContentResolver.PDF_AUTO_PREVIEW_LIMIT
            )
        )
    }

    @Test
    fun `remote PDF over the in-zip cap keeps the download gate`() {
        assertFalse(
            isAutoFetchable(PDF_MIME, FileCategory.DOCUMENT, "book.pdf", 51 * MIB)
        )
        assertFalse(
            isAutoFetchable(
                PDF_MIME, FileCategory.DOCUMENT, "book.pdf",
                PreviewContentResolver.PDF_AUTO_PREVIEW_LIMIT + 1
            )
        )
    }

    // ---- EPUB / OOXML (vc182) ----

    @Test
    fun `remote EPUB and OOXML fetch on open up to the in-zip document cap`() {
        assertTrue(
            isAutoFetchable(EPUB_MIME, FileCategory.DOCUMENT, "novel.epub", 10 * MIB)
        )
        assertTrue(
            isAutoFetchable(
                EPUB_MIME, FileCategory.DOCUMENT, "novel.epub",
                PreviewContentResolver.DOCUMENT_AUTO_PREVIEW_LIMIT
            )
        )
        assertTrue(
            isAutoFetchable(DOCX_MIME, FileCategory.DOCUMENT, "report.docx", 10 * MIB)
        )
        assertTrue(
            isAutoFetchable(XLSX_MIME, FileCategory.DOCUMENT, "sheet.xlsx", 10 * MIB)
        )
        assertTrue(
            isAutoFetchable(PPTX_MIME, FileCategory.DOCUMENT, "deck.pptx", 10 * MIB)
        )
    }

    @Test
    fun `remote EPUB and OOXML over the in-zip cap keep the download gate`() {
        assertFalse(
            isAutoFetchable(EPUB_MIME, FileCategory.DOCUMENT, "novel.epub", 21 * MIB)
        )
        assertFalse(
            isAutoFetchable(DOCX_MIME, FileCategory.DOCUMENT, "report.docx", 21 * MIB)
        )
        assertFalse(
            isAutoFetchable(
                EPUB_MIME, FileCategory.DOCUMENT, "novel.epub",
                PreviewContentResolver.DOCUMENT_AUTO_PREVIEW_LIMIT + 1
            )
        )
    }

    // ---- Unchanged rules ----

    @Test
    fun `image over the original 10 MiB limit still requires download`() {
        assertTrue(
            isAutoFetchable("image/png", FileCategory.IMAGE, "photo.png", 10 * MIB)
        )
        assertFalse(
            isAutoFetchable("image/png", FileCategory.IMAGE, "photo.png", 11 * MIB)
        )
    }

    @Test
    fun `text and apk keep the original 10 MiB limit`() {
        assertTrue(
            isAutoFetchable("text/plain", FileCategory.DOCUMENT, "notes.txt", 10 * MIB)
        )
        assertFalse(
            isAutoFetchable("text/plain", FileCategory.DOCUMENT, "notes.txt", 11 * MIB)
        )
        assertTrue(
            isAutoFetchable(
                "application/vnd.android.package-archive",
                FileCategory.OTHER, "app.apk", 10 * MIB
            )
        )
        assertFalse(
            isAutoFetchable(
                "application/vnd.android.package-archive",
                FileCategory.OTHER, "app.apk", 11 * MIB
            )
        )
    }

    @Test
    fun `unsupported small type still requires download`() {
        assertFalse(
            isAutoFetchable(
                "application/octet-stream", FileCategory.OTHER, "blob.bin", 5 * KIB
            )
        )
        // Streamed media is routed before this gate in resolve(); the
        // predicate itself never auto-fetches it.
        assertFalse(
            isAutoFetchable("video/mp4", FileCategory.VIDEO, "movie.mp4", 5 * KIB)
        )
    }
}
