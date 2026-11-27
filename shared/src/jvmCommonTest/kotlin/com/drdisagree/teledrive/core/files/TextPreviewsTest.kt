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

    // ---- Round 3 A10: same-document fragment taps stay inside the page ----

    @Test
    fun htmlNavigationAllowsSameDocumentFragmentsOnly() {
        // A checklist's table-of-contents tap resolves to about:blank#item-52:
        // it scrolls within the loaded document and must not be refused.
        assertTrue(htmlNavigationAllowed("about:blank#item-52"))
        assertTrue(htmlNavigationAllowed("about:blank#"))
        // Anything that would leave the document is still refused, fragments
        // on foreign origins included.
        assertFalse(htmlNavigationAllowed("https://example.com/page#item-52"))
        assertFalse(htmlNavigationAllowed("about:blanket#item-52"))
        assertFalse(htmlNavigationAllowed("intent://scan#Intent;scheme=zxing;end"))
        assertFalse(htmlNavigationAllowed("tel:+123"))
        assertFalse(htmlNavigationAllowed(""))
    }

    // ---- Round 3 A10: subresource policy ----

    @Test
    fun htmlSubresourcesOnlyWhatTheDocumentCarries() {
        assertTrue(htmlSubresourceAllowed("data:image/png;base64,iVBORw0KGgo="))
        assertTrue(htmlSubresourceAllowed("data:text/css;base64,Ym9keXt9"))
        assertTrue(htmlSubresourceAllowed("about:blank"))
        assertFalse(htmlSubresourceAllowed("https://example.com/tracker.png"))
        assertFalse(htmlSubresourceAllowed("http://example.com/style.css"))
        assertFalse(htmlSubresourceAllowed("file:///data/data/com.example/secret"))
        assertFalse(htmlSubresourceAllowed("content://com.example.provider/file"))
        assertFalse(htmlSubresourceAllowed(null))
        assertFalse(htmlSubresourceAllowed(""))
    }

    // ---- Round 3 A10: WebView settings posture ----

    @Test
    fun htmlWebViewPolicyRunsPageScriptsButKeepsEveryRouteOutClosed() {
        // On, so script-rendered / script-driven pages (checklists) render
        // completely and their checkboxes work.
        assertTrue(HtmlWebViewPolicy.JAVA_SCRIPT_ENABLED)
        assertTrue(HtmlWebViewPolicy.DOM_STORAGE_ENABLED)
        // Off/blocked: no route from the page to files, content providers,
        // the network, other windows, or autoplay.
        assertFalse(HtmlWebViewPolicy.ALLOW_FILE_ACCESS)
        assertFalse(HtmlWebViewPolicy.ALLOW_CONTENT_ACCESS)
        assertFalse(HtmlWebViewPolicy.ALLOW_FILE_ACCESS_FROM_FILE_URLS)
        assertFalse(HtmlWebViewPolicy.ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS)
        assertTrue(HtmlWebViewPolicy.BLOCK_NETWORK_LOADS)
        assertFalse(HtmlWebViewPolicy.JAVA_SCRIPT_CAN_OPEN_WINDOWS_AUTOMATICALLY)
        assertFalse(HtmlWebViewPolicy.SUPPORT_MULTIPLE_WINDOWS)
        assertTrue(HtmlWebViewPolicy.MEDIA_PLAYBACK_REQUIRES_USER_GESTURE)
    }
}
