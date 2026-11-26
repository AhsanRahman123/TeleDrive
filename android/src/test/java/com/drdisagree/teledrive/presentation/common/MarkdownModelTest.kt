package com.drdisagree.teledrive.presentation.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownModelTest {

    @Test
    fun `blocks classify headings bullets quotes and paragraphs`() {
        assertEquals(MarkdownBlock.Heading(2, "Title"), classifyMarkdownLine("## Title"))
        assertEquals(MarkdownBlock.Bullet(0, "item"), classifyMarkdownLine("- item"))
        assertEquals(MarkdownBlock.Bullet(1, "nested"), classifyMarkdownLine("  - nested"))
        assertEquals(MarkdownBlock.Quote("words"), classifyMarkdownLine("> words"))
        assertEquals(MarkdownBlock.Paragraph("plain"), classifyMarkdownLine("plain"))
        assertEquals(MarkdownBlock.Blank, classifyMarkdownLine("   "))
    }

    @Test
    fun `numbered lists are blocks now`() {
        assertEquals(MarkdownBlock.Numbered(0, "1", "first"), classifyMarkdownLine("1. first"))
        assertEquals(MarkdownBlock.Numbered(0, "3", "third"), classifyMarkdownLine("3) third"))
        // A year at line start is not a list item without the marker spacing rule failing:
        // "2024 was" has no '.' or ')' marker, so it stays a paragraph.
        assertEquals(MarkdownBlock.Paragraph("2024 was a year"), classifyMarkdownLine("2024 was a year"))
    }

    @Test
    fun `fenced code blocks group lines and keep them literal`() {
        val blocks = parseMarkdownBlocks("before\n```kotlin\nval x = 1\n# not a heading\n```\nafter")
        assertEquals(3, blocks.size)
        val fence = blocks[1] as MarkdownBlock.CodeFence
        assertEquals("kotlin", fence.language)
        assertEquals(listOf("val x = 1", "# not a heading"), fence.lines)
        // An unclosed fence still yields its lines rather than losing them.
        val open = parseMarkdownBlocks("```\ncode")
        assertTrue(open.single() is MarkdownBlock.CodeFence)
    }

    @Test
    fun `inline spans parse with nesting and literal code`() {
        val nodes = parseMarkdownInline("a **bold _it_** and `**code**` and [x](https://e.com)")
        assertEquals(MarkdownInline.Text("a "), nodes[0])
        val bold = nodes[1] as MarkdownInline.Bold
        assertTrue(bold.children.any { it is MarkdownInline.Italic })
        val code = nodes.filterIsInstance<MarkdownInline.Code>().single()
        assertEquals("**code**", code.text)
        val link = nodes.filterIsInstance<MarkdownInline.Link>().single()
        assertEquals("https://e.com", link.url)
        assertEquals(listOf(MarkdownInline.Text("plain")), parseMarkdownInline("plain"))
    }
}
