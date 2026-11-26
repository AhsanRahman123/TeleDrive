package com.drdisagree.teledrive.core.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextPreviewsTest {

    // ---- B6 JSON ----

    @Test
    fun jsonMinifiedIsPrettyPrinted() {
        val out = prettyPrintJson("""{"a":1,"b":[1,2],"c":{"d":true}}""")
        assertTrue(out.contains("\n  \"a\": 1"), out)
        assertTrue(out.contains("\"b\": [\n    1,\n    2\n  ]"), out)
    }

    @Test
    fun jsonInvalidIsIdentity() {
        val raw = "{not json"
        assertEquals(raw, prettyPrintJson(raw))
        assertEquals("", prettyPrintJson(""))
    }

    @Test
    fun jsonTopLevelArrayAndScalar() {
        assertTrue(prettyPrintJson("[1,2]").contains("\n"))
        assertEquals("42", prettyPrintJson("42"))
    }

    // ---- B6 XML ----

    @Test
    fun xmlNestingIndentsAndTextOnlyStaysInline() {
        val out = prettyPrintXml("<root><a>hello</a><b><c>deep</c></b></root>")
        assertEquals(
            "<root>\n  <a>hello</a>\n  <b>\n    <c>deep</c>\n  </b>\n</root>",
            out
        )
    }

    @Test
    fun xmlSelfClosingCommentCdataAndDeclaration() {
        val out = prettyPrintXml(
            """<?xml version="1.0"?><root><!-- note --><empty/><![CDATA[<raw>]]></root>"""
        )
        val lines = out.lines()
        assertEquals("""<?xml version="1.0"?>""", lines[0])
        assertTrue(lines.any { it.trim() == "<!-- note -->" }, out)
        assertTrue(lines.any { it.trim() == "<empty/>" }, out)
        assertTrue(lines.any { it.trim() == "<![CDATA[<raw>]]>" }, out)
    }

    @Test
    fun xmlBrokenIsIdentity() {
        val raw = "<root><unclosed"
        assertEquals(raw, prettyPrintXml(raw))
        assertEquals("plain text", prettyPrintXml("plain text"))
    }

    @Test
    fun formatDispatchOnlyForJsonAndXml() {
        assertTrue(formatPreviewText("""{"a":1}""", "application/json").contains("\n"))
        assertTrue(formatPreviewText("<a><b>x</b></a>", "application/xml").contains("\n  <b>x</b>"))
        assertTrue(formatPreviewText("<a><b>x</b></a>", "image/svg+xml").contains("\n"))
        assertEquals("hello", formatPreviewText("hello", "text/plain"))
        // XHTML is HTML, not XML-pretty-printed.
        assertEquals("<a><b>x</b></a>", formatPreviewText("<a><b>x</b></a>", "application/xhtml+xml"))
    }

    // ---- B7 subtitles ----

    @Test
    fun srtCleaned() {
        val srt = "1\n00:00:01,000 --> 00:00:02,500\nHello <i>world</i>\n\n" +
            "2\n00:00:03,000 --> 00:00:04,000\nSecond line one\nsecond line two\n"
        assertEquals("Hello world\n\nSecond line one\nsecond line two", subtitleToCleanText(srt))
    }

    @Test
    fun vttHeaderNoteAndSettingsDropped() {
        val vtt = "WEBVTT\n\nNOTE this is a note\nspanning lines\n\n" +
            "cue-1\n00:00:01.000 --> 00:00:02.000 align:start position:0%\n<v Alice>Hi</v>\n"
        assertEquals("Hi", subtitleToCleanText(vtt))
    }

    @Test
    fun subtitleMimeAndTextRouting() {
        assertEquals("application/x-subrip", MimeTypes.fromFileName("a/b.SRT"))
        assertEquals("text/vtt", MimeTypes.fromFileName("a/b.vtt"))
        assertTrue(MimeTypes.isSubtitle(MimeTypes.fromFileName("x.srt")))
        assertTrue(MimeTypes.isText(MimeTypes.fromFileName("x.srt")))
        assertTrue(MimeTypes.isText(MimeTypes.fromFileName("x.vtt")))
    }

    // ---- B2 navigation policy ----

    @Test
    fun htmlNavigationRefusedExceptInitialLoad() {
        assertFalse(htmlNavigationAllowed("https://example.com"))
        assertFalse(htmlNavigationAllowed("http://example.com"))
        assertFalse(htmlNavigationAllowed("file:///etc/passwd"))
        assertFalse(htmlNavigationAllowed(null))
        assertTrue(htmlNavigationAllowed("about:blank"))
    }
}
