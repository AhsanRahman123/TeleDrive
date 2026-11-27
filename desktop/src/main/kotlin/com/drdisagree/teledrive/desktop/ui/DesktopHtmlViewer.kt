package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.presentation.common.scaledBy
import com.drdisagree.teledrive.presentation.components.DesktopVerticalScrollbar
import com.drdisagree.teledrive.presentation.preview.textPreviewScaleZoomIn
import com.drdisagree.teledrive.presentation.preview.textPreviewScaleZoomOut
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_html_safe_mode
import com.drdisagree.teledrive.resources.preview_truncated_download_view
import org.jetbrains.compose.resources.stringResource

/**
 * The desktop safe-mode HTML viewer (1.6.25 item 5). Desktop has no WebView,
 * so safe mode here is stricter than Android's: nothing is executed or
 * fetched at all — the document is reduced to its readable text (scripts,
 * styles and markup dropped, block elements become line breaks) under the
 * same safe-mode caption Android shows. Script-driven pages therefore read
 * as their static text only, which is the safe mode working, and the
 * caption says so instead of leaving the user to discover it.
 */
@Composable
fun DesktopHtmlViewer(
    html: String,
    truncated: Boolean,
    modifier: Modifier = Modifier,
    // Text zoom (PC 1.6.26 item P5): the same shared scale and controls as
    // every other PC text viewer, shown when a scale-change callback is
    // wired (the preview screen always wires it; tests may omit it).
    textScale: Float = 1f,
    onTextScaleChange: ((Float) -> Unit)? = null
) {
    val text = remember(html) { htmlToReadableText(html) }
    val scrollState = rememberScrollState()
    val rootModifier = if (onTextScaleChange != null) {
        modifier.fillMaxSize().textZoomWheel(textScale, onTextScaleChange)
    } else {
        modifier.fillMaxSize()
    }
    Column(modifier = rootModifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(Res.string.preview_html_safe_mode),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
            if (onTextScaleChange != null) {
                TextZoomControls(
                    scale = textScale,
                    onZoomIn = { onTextScaleChange(textPreviewScaleZoomIn(textScale)) },
                    onZoomOut = { onTextScaleChange(textPreviewScaleZoomOut(textScale)) },
                    onReset = { onTextScaleChange(1f) },
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
        }
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(end = 10.dp)
                    .padding(24.dp)
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall
                        .copy(fontFamily = FontFamily.Monospace)
                        .copy(color = MaterialTheme.colorScheme.onSurface)
                        .scaledBy(textScale)
                )
                if (truncated) {
                    Text(
                        text = stringResource(Res.string.preview_truncated_download_view),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            DesktopVerticalScrollbar(
                state = scrollState,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
            )
        }
    }
}

private val HTML_SCRIPT_OR_STYLE = Regex(
    "<(script|style)\\b[^>]*>.*?</\\1\\s*>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)
private val HTML_BLOCK_BREAK = Regex(
    "<(br\\s*/?|/p|/div|/li|/tr|/h[1-6]|/section|/article|/blockquote|/pre|/table)>",
    RegexOption.IGNORE_CASE
)
private val HTML_ANY_TAG = Regex("<[^>]+>")
private val HTML_WHITESPACE_RUN = Regex("[ \\t\\x0B\\f]+")
private val HTML_BLANK_LINES = Regex("\\n{3,}")

/**
 * Reduces an HTML document to readable plain text: script/style bodies are
 * removed whole, block-level closers and `<br>` become line breaks, every
 * remaining tag is dropped, the common entities are decoded, and whitespace
 * is normalised. Total by construction — malformed markup degrades to
 * plainer text, never to a failure.
 */
fun htmlToReadableText(html: String): String {
    val withoutScripts = HTML_SCRIPT_OR_STYLE.replace(html, " ")
    val withBreaks = HTML_BLOCK_BREAK.replace(withoutScripts, "\n")
    val withoutTags = HTML_ANY_TAG.replace(withBreaks, "")
    val decoded = withoutTags
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&amp;", "&")
    return decoded.lines()
        .joinToString("\n") { HTML_WHITESPACE_RUN.replace(it, " ").trim() }
        .replace(HTML_BLANK_LINES, "\n\n")
        .trim()
}
