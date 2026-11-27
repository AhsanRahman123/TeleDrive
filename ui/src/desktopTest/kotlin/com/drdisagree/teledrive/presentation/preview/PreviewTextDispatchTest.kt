package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 item X1: README / text files must reach the PC text viewer through
 * the production dispatch ([previewDispatch] — the seam
 * PreviewContentResolver.fromLocal routes through) and the production
 * auto-fetch gate ([isAutoFetchable] — the seam that decides whether a
 * remote copy opens at all or stops at Download-to-view).
 *
 * The baseline failure: the TEXT branch trusted the stored MIME alone,
 * while the in-zip route classifies by file name alone. A README.md
 * whose stored MIME was generic (or a container type declared by
 * Telegram / a sync manifest, which `MimeTypes.resolve` prefers over
 * the filename) missed the branch; an extension-less README has no
 * usable MIME from any source and was Unsupported under every stored
 * state.
 */
class PreviewTextDispatchTest {

    private val mib = 1024L * 1024L

    @Test
    fun `readme variants dispatch to the text viewer`() {
        // Extension-less README: generic stored MIME, OTHER category —
        // the state every source produces for it.
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "README")
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "readme")
        )
        // README.md with its proper stored MIME (already worked).
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("text/markdown", FileCategory.DOCUMENT, "README.md")
        )
        // README.md whose stored MIME is generic or a declared container
        // type: the name must win, like it does inside a ZIP.
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "README.md")
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/zip", FileCategory.ARCHIVE, "README.md")
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "README.txt")
        )
    }

    @Test
    fun `txt and md files dispatch to text even with a wrong stored mime`() {
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("text/plain", FileCategory.DOCUMENT, "notes.txt")
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "notes.txt")
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "guide.md")
        )
        assertEquals(
            PreviewDispatch.TEXT,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "data.log")
        )
    }

    @Test
    fun `extension-less conventional text names dispatch to text`() {
        for (name in listOf("LICENSE", "LICENCE", "COPYING", "NOTICE", "AUTHORS", "CHANGELOG", "TODO")) {
            assertEquals(
                "$name must open as text",
                PreviewDispatch.TEXT,
                previewDispatch("application/octet-stream", FileCategory.OTHER, name)
            )
        }
    }

    @Test
    fun `non-text files are not captured by the name-based text rule`() {
        // A real archive still archives; an unknown binary stays
        // unsupported; an extension-less name that is NOT a conventional
        // text name stays unsupported.
        assertEquals(
            PreviewDispatch.ARCHIVE,
            previewDispatch("application/zip", FileCategory.ARCHIVE, "archive.zip")
        )
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "blob.bin")
        )
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "firmware")
        )
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "README.bin")
        )
        // A dotted name never matches the extension-less rule.
        assertEquals(
            PreviewDispatch.UNSUPPORTED,
            previewDispatch("application/octet-stream", FileCategory.OTHER, "README.bin")
        )
    }

    @Test
    fun `isTextPreviewType follows stored mime, name mime, then conventional names`() {
        assertTrue(isTextPreviewType("text/plain", "whatever.bin"))
        assertTrue(isTextPreviewType("application/octet-stream", "notes.txt"))
        assertTrue(isTextPreviewType("application/octet-stream", "README"))
        assertFalse(isTextPreviewType("application/octet-stream", "blob.bin"))
        assertFalse(isTextPreviewType("application/zip", "archive.zip"))
    }

    @Test
    fun `remote readme and text files auto-fetch within the text cap`() {
        assertTrue(
            isAutoFetchable("application/octet-stream", FileCategory.OTHER, "README", 12_345)
        )
        assertTrue(
            isAutoFetchable("application/octet-stream", FileCategory.OTHER, "README.md", 12_345)
        )
        assertTrue(
            isAutoFetchable("application/zip", FileCategory.ARCHIVE, "notes.txt", 12_345)
        )
        // Over the 10 MiB text cap the Download-to-view gate still applies.
        assertFalse(
            isAutoFetchable("application/octet-stream", FileCategory.OTHER, "README", 11 * mib)
        )
        // And an unknown binary of any small size still does not auto-fetch.
        assertFalse(
            isAutoFetchable("application/octet-stream", FileCategory.OTHER, "blob.bin", 12_345)
        )
    }
}
