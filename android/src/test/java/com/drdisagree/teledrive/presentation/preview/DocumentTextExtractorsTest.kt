package com.drdisagree.teledrive.presentation.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextExtractorsTest {

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

    // ---- B9 DOCX ----

    @Test
    fun `docx joins runs per paragraph`() {
        val xml = """<w:document xmlns:w="urn:x"><w:body>
            <w:p><w:r><w:t>Hello </w:t></w:r><w:r><w:t>world</w:t></w:r></w:p>
            <w:p><w:r><w:t>Second</w:t></w:r></w:p></w:body></w:document>"""
        assertEquals("Hello world\nSecond", docxToText(xml.toByteArray()))
    }

    @Test
    fun `docx file extracts through the zip`() {
        val file = zipFile(
            "a.docx",
            mapOf(
                "word/document.xml" to
                    """<w:document xmlns:w="urn:x"><w:p><w:r><w:t>Body</w:t></w:r></w:p></w:document>"""
            )
        )
        val out = extractDocumentText(file)
        assertTrue(out is DocumentExtraction.Text && out.text == "Body")
    }

    // ---- B9 XLSX ----

    @Test
    fun `xlsx resolves shared strings and tabs cells`() {
        val shared = """<sst><si><t>Alpha</t></si><si><t>Beta</t></si></sst>"""
        val sheet = """<worksheet><sheetData>
            <row><c t="s"><v>0</v></c><c><v>42</v></c></row>
            <row><c t="s"><v>1</v></c><c t="inlineStr"><is><t>in</t></is></c></row>
            </sheetData></worksheet>"""
        val text = xlsxToText(shared.toByteArray(), listOf("Data" to sheet.toByteArray()))
        assertEquals("— Data —\nAlpha\t42\nBeta\tin", text)
    }

    // ---- B9 PPTX ----

    @Test
    fun `pptx orders slides numerically with separators`() {
        fun slide(text: String) =
            """<p:sld xmlns:p="urn:y" xmlns:a="urn:x"><a:p><a:r><a:t>$text</a:t></a:r></a:p></p:sld>"""
        val text = pptxToText(listOf(2 to slide("Two").toByteArray(), 1 to slide("One").toByteArray()))
        assertEquals("— Slide 1 —\nOne\n\n— Slide 2 —\nTwo", text)
    }

    // ---- B8 EPUB ----

    @Test
    fun `epub container opf spine chain resolves chapter paths`() {
        val container = """<container><rootfiles>
            <rootfile full-path="OEBPS/content.opf"/></rootfiles></container>"""
        assertEquals("OEBPS/content.opf", epubRootFilePath(container.toByteArray()))
        val opf = """<package><manifest>
            <item id="c1" href="ch1.xhtml"/><item id="c2" href="text/ch2.xhtml"/>
            </manifest><spine><itemref idref="c2"/><itemref idref="c1"/></spine></package>"""
        assertEquals(
            listOf("OEBPS/text/ch2.xhtml", "OEBPS/ch1.xhtml"),
            epubSpinePaths(opf.toByteArray(), "OEBPS/content.opf")
        )
    }

    @Test
    fun `xhtml becomes plain text with block breaks`() {
        val xhtml = """<html><body><h1>Title</h1><p>One <b>two</b></p><p>Three</p></body></html>"""
        assertEquals("Title\nOne two\nThree", xhtmlToPlainText(xhtml.toByteArray()))
    }

    @Test
    fun `epub file reads in spine order and drm is reported`() {
        val book = zipFile(
            "book.epub",
            mapOf(
                "META-INF/container.xml" to
                    """<container><rootfiles><rootfile full-path="content.opf"/></rootfiles></container>""",
                "content.opf" to
                    """<package><manifest><item id="a" href="a.xhtml"/><item id="b" href="b.xhtml"/></manifest><spine><itemref idref="a"/><itemref idref="b"/></spine></package>""",
                "a.xhtml" to "<html><body><p>Chapter one</p></body></html>",
                "b.xhtml" to "<html><body><p>Chapter two</p></body></html>"
            )
        )
        val out = extractDocumentText(book)
        assertTrue(
            out is DocumentExtraction.Text &&
                out.text == "Chapter one\n\nChapter two"
        )
        val drm = zipFile(
            "drm.epub",
            mapOf("META-INF/encryption.xml" to "<encryption/>")
        )
        assertEquals(DocumentExtraction.DrmProtected, extractDocumentText(drm))
    }

    @Test
    fun `xxe payload is not expanded`() {
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <w:document xmlns:w="urn:x"><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:document>"""
        val text = docxToText(xxe.toByteArray())
        assertTrue(!text.contains("root:"), text)
    }

    @Test
    fun `legacy office and unknown files fail honestly`() {
        val legacy = zipFile("old.doc", mapOf("x" to "y"))
        assertEquals(DocumentExtraction.Failed, extractDocumentText(legacy))
    }
}
