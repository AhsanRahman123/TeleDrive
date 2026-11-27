package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * vc183 item 4: the four field fixtures (copied into
 * `src/test/resources/viewer_fixtures/`) run through the REAL extraction
 * path the normal route uses — [extractDocumentText], the same dispatch the
 * in-zip route calls — and must yield their readable text. On vc182 the
 * minimal and the full fixture pairs failed identically on the phone (EPUB:
 * "No preview for this file type"; DOCX: blank viewer), so both pairs are
 * pinned here, not just one.
 *
 * Also pinned: extraction must not depend on the XML namespace PREFIX a
 * document happens to use. The extractors previously looked elements up by
 * prefixed qualified name (`getElementsByTagName("w:p")`), which a
 * namespace-aware DOM is not required to match — a lookup that silently
 * returns nothing turns into extracted "" text. Matching is by local name
 * now, so an alternate-prefix DOCX and the fixtures' default-namespace EPUB
 * spine both extract.
 *
 * And: an extraction with no readable content is [DocumentExtraction.Failed],
 * never a successful empty [DocumentExtraction.Text] — the empty success was
 * the blank-DOCX swallow.
 */
class DocumentFixtureExtractionTest {

    /** Copies a classpath fixture to a temp file keeping its real extension. */
    private fun fixture(resourceName: String): File {
        val stream = javaClass.getResourceAsStream("/viewer_fixtures/$resourceName")
            ?: error("missing test fixture $resourceName")
        val extension = resourceName.substringAfterLast('.')
        val file = File.createTempFile(resourceName.substringBeforeLast('.') + "-", ".$extension")
        stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        file.deleteOnExit()
        return file
    }

    private fun extractedText(resourceName: String): String {
        val out = extractDocumentText(fixture(resourceName))
        assertTrue(
            "$resourceName should extract to text, was $out",
            out is DocumentExtraction.Text
        )
        return (out as DocumentExtraction.Text).text
    }

    private fun zipFile(name: String, entries: Map<String, String>): File {
        val file = File.createTempFile("fixture", name)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((entryName, content) in entries) {
                zip.putNextEntry(ZipEntry(entryName))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        file.deleteOnExit()
        return file
    }

    // ---- The four field fixtures through the real extraction path ----

    @Test
    fun `minimal epub fixture extracts its chapters`() {
        val text = extractedText("Viewer_Test.epub")
        assertTrue(text, text.contains("Chapter 1 — It opens"))
        assertTrue(text, text.contains("the EPUB viewer works"))
        assertTrue(text, text.contains("Chapter 2 — Second chapter reached"))
    }

    @Test
    fun `minimal docx fixture extracts its paragraphs`() {
        val text = extractedText("Viewer_Test.docx")
        assertTrue(text, text.contains("Viewer Test Document"))
        assertTrue(text, text.contains("the Office viewer works"))
        assertTrue(text, text.contains("Second paragraph"))
    }

    @Test
    fun `full epub fixture extracts the essay in spine order`() {
        val text = extractedText("Classical_Conditioning.epub")
        assertTrue(text, text.contains("Classical Conditioning"))
        assertTrue(text, text.contains("Chapter 1 — What Classical Conditioning Is"))
        assertTrue(text, text.contains("Chapter 2 — How the Learning Grows, Fades and Spreads"))
        assertTrue(text, text.contains("Chapter 3 — How Classical Conditioning Helps People"))
        assertTrue(text, text.contains("Ivan Pavlov"))
        assertTrue(
            text,
            text.indexOf("Chapter 1 —") < text.indexOf("Chapter 2 —") &&
                text.indexOf("Chapter 2 —") < text.indexOf("Chapter 3 —")
        )
    }

    @Test
    fun `full docx fixture extracts the essay with its headings`() {
        val text = extractedText("Classical_Conditioning.docx")
        assertTrue(
            text,
            text.contains("Classical Conditioning — How Learning by Association Works and Helps")
        )
        assertTrue(text, text.contains("Chapter 1 — What Classical Conditioning Is"))
        assertTrue(text, text.contains("Chapter 2 — How the Learning Grows, Fades and Spreads"))
        assertTrue(text, text.contains("Chapter 3 — How Classical Conditioning Helps People"))
        assertTrue(text, text.contains("Ivan Pavlov"))
    }

    // ---- Namespace-prefix independence ----

    @Test
    fun `docx with an alternate namespace prefix still extracts`() {
        val file = zipFile(
            "alt.docx",
            mapOf(
                "word/document.xml" to
                    """<?xml version="1.0"?><x:document xmlns:x="urn:word">
                    <x:body><x:p><x:r><x:t>Alt prefix body</x:t></x:r></x:p></x:body>
                    </x:document>"""
            )
        )
        val out = extractDocumentText(file)
        assertTrue(
            "alternate-prefix DOCX should extract, was $out",
            out is DocumentExtraction.Text && out.text.contains("Alt prefix body")
        )
    }

    @Test
    fun `docx with a default namespace still extracts`() {
        val file = zipFile(
            "default.docx",
            mapOf(
                "word/document.xml" to
                    """<?xml version="1.0"?><document xmlns="urn:word">
                    <body><p><r><t>Default namespace body</t></r></p></body></document>"""
            )
        )
        val out = extractDocumentText(file)
        assertTrue(
            "default-namespace DOCX should extract, was $out",
            out is DocumentExtraction.Text && out.text.contains("Default namespace body")
        )
    }

    @Test
    fun `pptx with an alternate namespace prefix still extracts`() {
        val file = zipFile(
            "alt.pptx",
            mapOf(
                "ppt/slides/slide1.xml" to
                    """<?xml version="1.0"?><s:sld xmlns:s="urn:p" xmlns:z="urn:a">
                    <z:p><z:r><z:t>Slide words</z:t></z:r></z:p></s:sld>"""
            )
        )
        val out = extractDocumentText(file)
        assertTrue(
            "alternate-prefix PPTX should extract, was $out",
            out is DocumentExtraction.Text && out.text.contains("Slide words")
        )
    }

    // ---- No more blank successes: contentless extraction is a failure ----

    @Test
    fun `docx whose document xml does not parse fails instead of extracting blank`() {
        val file = zipFile(
            "broken.docx",
            mapOf("word/document.xml" to "this is not xml at all")
        )
        assertEquals(DocumentExtraction.Failed, extractDocumentText(file))
    }

    @Test
    fun `docx with no paragraphs fails instead of extracting blank`() {
        val file = zipFile(
            "empty.docx",
            mapOf(
                "word/document.xml" to
                    """<w:document xmlns:w="urn:x"><w:body/></w:document>"""
            )
        )
        assertEquals(DocumentExtraction.Failed, extractDocumentText(file))
    }

    @Test
    fun `xlsx with sheets but no rows fails instead of extracting headers only`() {
        val file = zipFile(
            "empty.xlsx",
            mapOf(
                "xl/worksheets/sheet1.xml" to
                    """<worksheet><sheetData></sheetData></worksheet>"""
            )
        )
        assertEquals(DocumentExtraction.Failed, extractDocumentText(file))
    }

    @Test
    fun `contentless check recognises real text and header-only text`() {
        assertTrue(documentTextIsContentless(""))
        assertTrue(documentTextIsContentless("— Sheet 1 —"))
        assertTrue(documentTextIsContentless("— Slide 1 —\n"))
        assertFalse(documentTextIsContentless("— Sheet 1 —\nAlpha\t42"))
        assertFalse(documentTextIsContentless("Hello world"))
    }
}
