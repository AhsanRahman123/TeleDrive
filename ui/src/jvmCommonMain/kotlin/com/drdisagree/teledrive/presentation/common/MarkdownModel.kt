package com.drdisagree.teledrive.presentation.common

/**
 * Pure Markdown model behind [MarkdownText]: the block classifier and the inline-span
 * parser, extracted from the Compose renderer so they are host-testable. Anything the
 * parser does not recognize stays as written (a [MarkdownBlock.Paragraph] / [MarkdownInline.Text]),
 * so no text is ever lost.
 */

sealed interface MarkdownBlock {
    data object Blank : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val depth: Int, val text: String) : MarkdownBlock
    data class Numbered(val depth: Int, val number: String, val text: String) : MarkdownBlock
    data class Quote(val text: String) : MarkdownBlock
    data class CodeFence(val language: String?, val lines: List<String>) : MarkdownBlock
    data class Paragraph(val text: String) : MarkdownBlock
}

private val HEADING_LINE = Regex("""^(#{1,6})\s+(.*)$""")
private val NUMBERED_LINE = Regex("""^(\d+)[.)]\s+(.*)$""")
private const val FENCE = "```"
private const val INDENT_UNIT_CHARS = 2
private const val MAX_INLINE_DEPTH = 6

/** Classifies one non-fence line; fence state is handled by [parseMarkdownBlocks]. */
fun classifyMarkdownLine(line: String): MarkdownBlock {
    if (line.isBlank()) return MarkdownBlock.Blank
    val trimmed = line.trimStart()
    if (trimmed.startsWith("> ")) return MarkdownBlock.Quote(trimmed.removePrefix("> "))
    val indentDepth = (line.takeWhile { it == ' ' }.length / INDENT_UNIT_CHARS).coerceAtMost(6)
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ")) {
        return MarkdownBlock.Bullet(indentDepth, trimmed.drop(2))
    }
    NUMBERED_LINE.find(trimmed)?.let { match ->
        return MarkdownBlock.Numbered(indentDepth, match.groupValues[1], match.groupValues[2])
    }
    HEADING_LINE.find(trimmed)?.let { match ->
        return MarkdownBlock.Heading(match.groupValues[1].length, match.groupValues[2])
    }
    return MarkdownBlock.Paragraph(trimmed)
}

/** Splits [text] into blocks, grouping fenced code (``` … ```) into one [MarkdownBlock.CodeFence]. */
fun parseMarkdownBlocks(text: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    var fenceLanguage: String? = null
    var fenceLines: MutableList<String>? = null
    for (line in text.lines()) {
        val open = fenceLines
        if (open != null) {
            if (line.trimStart().startsWith(FENCE)) {
                blocks += MarkdownBlock.CodeFence(fenceLanguage, open.toList())
                fenceLines = null
                fenceLanguage = null
            } else {
                open += line
            }
        } else if (line.trimStart().startsWith(FENCE)) {
            fenceLanguage = line.trimStart().removePrefix(FENCE).trim().ifEmpty { null }
            fenceLines = mutableListOf()
        } else {
            blocks += classifyMarkdownLine(line)
        }
    }
    fenceLines?.let { blocks += MarkdownBlock.CodeFence(fenceLanguage, it.toList()) }
    return blocks
}

sealed interface MarkdownInline {
    data class Text(val text: String) : MarkdownInline
    data class Bold(val children: List<MarkdownInline>) : MarkdownInline
    data class Italic(val children: List<MarkdownInline>) : MarkdownInline
    data class Strike(val children: List<MarkdownInline>) : MarkdownInline
    data class Code(val text: String) : MarkdownInline
    data class Link(val url: String, val children: List<MarkdownInline>) : MarkdownInline
}

private val INLINE_PATTERN = Regex(
    """\[([^\]]+)\]\(([^)]+)\)|\*\*(.+?)\*\*|~~(.+?)~~|`([^`]+)`|_(.+?)_|\*([^*]+)\*"""
)

/** Parses inline spans of one line; nesting recurses, code content stays literal. */
fun parseMarkdownInline(line: String, depth: Int = 0): List<MarkdownInline> {
    if (depth >= MAX_INLINE_DEPTH) return listOf(MarkdownInline.Text(line))
    val nodes = mutableListOf<MarkdownInline>()
    var cursor = 0
    for (match in INLINE_PATTERN.findAll(line)) {
        if (match.range.first > cursor) {
            nodes += MarkdownInline.Text(line.substring(cursor, match.range.first))
        }
        val (label, url, bold, strike, code, italic) = match.destructured
        val starred = match.groupValues[7]
        when {
            url.isNotEmpty() ->
                nodes += MarkdownInline.Link(url, parseMarkdownInline(label, depth + 1))
            bold.isNotEmpty() ->
                nodes += MarkdownInline.Bold(parseMarkdownInline(bold, depth + 1))
            strike.isNotEmpty() ->
                nodes += MarkdownInline.Strike(parseMarkdownInline(strike, depth + 1))
            code.isNotEmpty() -> nodes += MarkdownInline.Code(code)
            italic.isNotEmpty() ->
                nodes += MarkdownInline.Italic(parseMarkdownInline(italic, depth + 1))
            starred.isNotEmpty() ->
                nodes += MarkdownInline.Italic(parseMarkdownInline(starred, depth + 1))
        }
        cursor = match.range.last + 1
    }
    if (cursor < line.length) nodes += MarkdownInline.Text(line.substring(cursor))
    return nodes
}
