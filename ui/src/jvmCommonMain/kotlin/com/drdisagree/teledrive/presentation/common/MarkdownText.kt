package com.drdisagree.teledrive.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.core.files.Urls

/** Renders the pure [MarkdownBlock]/[MarkdownInline] model; unrecognized text stays as written. */
@Composable
fun MarkdownText(
    text: String,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    textScale: Float = 1f
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val quoteColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier = modifier.fillMaxWidth()) {
        parseMarkdownBlocks(text).forEach { block ->
            MarkdownBlockView(block, textScale, linkColor, quoteColor, onOpenUrl)
        }
    }
}

/** Block markers nest, so a quote's content is rendered by recursion on its inner text. */
@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    textScale: Float,
    linkColor: Color,
    quoteColor: Color,
    onOpenUrl: (String) -> Unit,
    quoted: Boolean = false,
    depth: Int = 0
) {
    val body = MaterialTheme.typography.bodyMedium.let {
        if (quoted) it.copy(fontStyle = FontStyle.Italic, color = quoteColor) else it
    }

    when (block) {
        MarkdownBlock.Blank -> Spacer(Modifier.height(8.dp))

        is MarkdownBlock.CodeFence -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(12.dp)
        ) {
            Text(
                text = block.lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall
                    .copy(fontFamily = FontFamily.Monospace)
                    .scaledBy(textScale),
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        is MarkdownBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .width(QUOTE_BAR_WIDTH)
                    .heightIn(min = QUOTE_BAR_MIN_HEIGHT)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(10.dp))
            Column {
                parseMarkdownBlocks(block.text).forEach { inner ->
                    MarkdownBlockView(inner, textScale, linkColor, quoteColor, onOpenUrl, quoted = true, depth = depth + 1)
                }
            }
        }

        is MarkdownBlock.Bullet -> Row(
            modifier = Modifier.padding(start = 8.dp + NESTED_INDENT * block.depth)
        ) {
            Text(
                text = bulletFor(block.depth),
                style = body.scaledBy(textScale),
                color = if (quoted) quoteColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            MarkdownBlockView(
                classifyMarkdownLine(block.text), textScale, linkColor, quoteColor,
                onOpenUrl, quoted, depth + 1
            )
        }

        is MarkdownBlock.Numbered -> Row(
            modifier = Modifier.padding(start = 8.dp + NESTED_INDENT * block.depth)
        ) {
            Text(
                text = "${block.number}.",
                style = body.scaledBy(textScale),
                color = if (quoted) quoteColor else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            MarkdownBlockView(
                classifyMarkdownLine(block.text), textScale, linkColor, quoteColor,
                onOpenUrl, quoted, depth + 1
            )
        }

        is MarkdownBlock.Heading -> {
            val style = when (block.level) {
                1 -> MaterialTheme.typography.titleLarge
                2 -> MaterialTheme.typography.titleMedium
                else -> MaterialTheme.typography.titleSmall
            }
            MarkdownLine(block.text, style.scaledBy(textScale), linkColor, onOpenUrl)
        }

        is MarkdownBlock.Paragraph ->
            if (depth >= MAX_BLOCK_DEPTH) {
                MarkdownLine(block.text, body.scaledBy(textScale), linkColor, onOpenUrl)
            } else {
                // A paragraph can still carry a nested block marker (e.g. inside a
                // bullet's text): re-classify once so nesting keeps working.
                val inner = classifyMarkdownLine(block.text)
                if (inner is MarkdownBlock.Paragraph || inner is MarkdownBlock.Blank) {
                    MarkdownLine(block.text, body.scaledBy(textScale), linkColor, onOpenUrl)
                } else {
                    MarkdownBlockView(inner, textScale, linkColor, quoteColor, onOpenUrl, quoted, depth + 1)
                }
            }
    }
}

private fun bulletFor(depth: Int): String = when (depth % 3) {
    0 -> "•"
    1 -> "◦"
    else -> "▪"
}

@Composable
private fun MarkdownLine(
    line: String,
    style: TextStyle,
    linkColor: Color,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val annotated = inlineMarkdown(line, linkColor)
    val onSurface = MaterialTheme.colorScheme.onSurface
    @Suppress("DEPRECATION")
    ClickableText(
        text = annotated,
        style = style.copy(color = style.color.takeOrElse { onSurface }),
        modifier = modifier.padding(vertical = 2.dp),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset)
                .firstOrNull()
                ?.let { onOpenUrl(it.item) }
        }
    )
}

private fun Color.takeOrElse(fallback: () -> Color): Color =
    if (this == Color.Unspecified) fallback() else this

private fun inlineMarkdown(line: String, linkColor: Color): AnnotatedString =
    buildAnnotatedString { appendInline(parseMarkdownInline(line), linkColor) }

/** Styles nest, so wrapped text renders its parsed children; code stays as written. */
private fun AnnotatedString.Builder.appendInline(
    nodes: List<MarkdownInline>,
    linkColor: Color
) {
    for (node in nodes) {
        when (node) {
            is MarkdownInline.Text -> appendPlain(node.text, linkColor)
            is MarkdownInline.Link -> {
                pushStringAnnotation(URL_TAG, Urls.normalize(node.url))
                withLinkStyle(linkColor) { appendInline(node.children, linkColor) }
                pop()
            }
            is MarkdownInline.Bold ->
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    appendInline(node.children, linkColor)
                }
            is MarkdownInline.Strike ->
                withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                    appendInline(node.children, linkColor)
                }
            is MarkdownInline.Code ->
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) { append(node.text) }
            is MarkdownInline.Italic ->
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    appendInline(node.children, linkColor)
                }
        }
    }
}

private fun AnnotatedString.Builder.appendPlain(
    segment: String,
    linkColor: Color
) {
    var cursor = 0
    Urls.PATTERN.findAll(segment).forEach { match ->
        append(segment.substring(cursor, match.range.first))
        val url = match.value.trimEnd('.', ',', ';', ':')
        pushStringAnnotation(URL_TAG, Urls.normalize(url))
        withLinkStyle(linkColor) { append(url) }
        pop()
        cursor = match.range.first + url.length
    }
    append(segment.substring(cursor))
}

private inline fun AnnotatedString.Builder.withLinkStyle(
    linkColor: Color,
    block: () -> Unit
) {
    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { block() }
}

private inline fun AnnotatedString.Builder.withStyle(
    style: SpanStyle,
    block: () -> Unit
) {
    val index = pushStyle(style)
    block()
    pop(index)
}

fun TextStyle.scaledBy(scale: Float): TextStyle =
    if (scale == 1f) this else copy(fontSize = fontSize * scale, lineHeight = lineHeight * scale)

private const val MAX_BLOCK_DEPTH = 6
private val NESTED_INDENT = 16.dp
private val QUOTE_BAR_WIDTH = 3.dp
private val QUOTE_BAR_MIN_HEIGHT = 20.dp
