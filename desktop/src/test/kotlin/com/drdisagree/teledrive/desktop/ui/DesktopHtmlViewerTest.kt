package com.drdisagree.teledrive.desktop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.6.25 item 5: the desktop safe-mode HTML reduction — scripts and styles
 * never survive as text, markup becomes readable lines, entities decode.
 */
class DesktopHtmlViewerTest {

    @Test
    fun `scripts and styles are removed whole`() {
        val text = htmlToReadableText(
            "<html><head><style>body { color: red; }</style>" +
                "<script>alert('x')</script></head>" +
                "<body><p>Hello</p></body></html>"
        )
        assertEquals("Hello", text)
        assertFalse(text.contains("alert"))
        assertFalse(text.contains("color"))
    }

    @Test
    fun `block elements become lines and entities decode`() {
        val text = htmlToReadableText(
            "<h1>Title</h1><p>Fish &amp; Chips</p><p>5 &lt; 6<br>next line</p>"
        )
        assertTrue(text.contains("Title"))
        assertTrue(text.contains("Fish & Chips"))
        assertTrue(text.contains("5 < 6"))
        assertTrue(text.lines().any { it == "next line" })
    }

    @Test
    fun `malformed markup degrades instead of failing`() {
        assertEquals("oops", htmlToReadableText("<p>oops"))
        assertEquals("", htmlToReadableText("<div></div>"))
    }
}
