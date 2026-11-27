package com.drdisagree.teledrive.core.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Pure text transforms applied where preview text is produced (normal files in
 * PreviewContentResolver, archive entries in PreviewViewModel), so the viewer itself
 * never changes: formatted/cleaned text is still shown by the existing text view.
 * Every transform is total — on any parse problem it returns its input unchanged,
 * because a preview must never fail just because formatting did.
 */

private val prettyJson = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

/** Pretty-prints [raw] JSON with two-space indent; returns [raw] unchanged when it does not parse. */
fun prettyPrintJson(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return raw
    return try {
        val element: JsonElement = Json.parseToJsonElement(trimmed)
        prettyJson.encodeToString(element)
    } catch (failure: Exception) {
        raw
    }
}

private sealed interface XmlToken {
    data class Misc(val text: String) : XmlToken // declaration, comment, CDATA, doctype
    data class Open(val raw: String, val name: String, val selfClosing: Boolean) : XmlToken
    data class Close(val name: String) : XmlToken
    data class Text(val text: String) : XmlToken
}

private fun tokenizeXml(raw: String): List<XmlToken>? {
    val tokens = mutableListOf<XmlToken>()
    var index = 0
    while (index < raw.length) {
        if (raw[index] == '<') {
            when {
                raw.startsWith("<!--", index) -> {
                    val end = raw.indexOf("-->", index + 4)
                    if (end < 0) return null
                    tokens += XmlToken.Misc(raw.substring(index, end + 3))
                    index = end + 3
                }
                raw.startsWith("<![CDATA[", index) -> {
                    val end = raw.indexOf("]]>", index + 9)
                    if (end < 0) return null
                    tokens += XmlToken.Misc(raw.substring(index, end + 3))
                    index = end + 3
                }
                raw.startsWith("<?", index) -> {
                    val end = raw.indexOf("?>", index + 2)
                    if (end < 0) return null
                    tokens += XmlToken.Misc(raw.substring(index, end + 2).trim())
                    index = end + 2
                }
                raw.startsWith("<!", index) -> {
                    val end = raw.indexOf('>', index + 2)
                    if (end < 0) return null
                    tokens += XmlToken.Misc(raw.substring(index, end + 1))
                    index = end + 1
                }
                raw.startsWith("</", index) -> {
                    val end = raw.indexOf('>', index + 2)
                    if (end < 0) return null
                    tokens += XmlToken.Close(raw.substring(index + 2, end).trim())
                    index = end + 1
                }
                else -> {
                    val end = raw.indexOf('>', index + 1)
                    if (end < 0) return null
                    val inside = raw.substring(index + 1, end)
                    val selfClosing = inside.trimEnd().endsWith('/')
                    val name = inside.trim().trimEnd('/').split(' ', '\t', '\n', '\r').firstOrNull()
                        ?: return null
                    if (name.isEmpty()) return null
                    tokens += XmlToken.Open("<$inside>", name, selfClosing)
                    index = end + 1
                }
            }
        } else {
            val next = raw.indexOf('<', index)
            val end = if (next < 0) raw.length else next
            tokens += XmlToken.Text(raw.substring(index, end))
            index = end
        }
    }
    return tokens
}

/**
 * Indents [raw] XML with two spaces per depth level. Text-only elements stay on one
 * line (`<a>text</a>`); comments, CDATA, declarations and self-closing tags keep their
 * own line. Returns [raw] unchanged when the markup cannot be tokenized.
 */
fun prettyPrintXml(raw: String): String {
    val tokens = tokenizeXml(raw) ?: return raw
    if (tokens.none { it is XmlToken.Open }) return raw
    val lines = mutableListOf<String>()
    var depth = 0
    var i = 0
    fun indent(): String = "  ".repeat(depth.coerceAtLeast(0))
    while (i < tokens.size) {
        when (val token = tokens[i]) {
            is XmlToken.Misc -> {
                lines += indent() + token.text.trim()
                i++
            }
            is XmlToken.Text -> {
                val text = token.text.trim()
                if (text.isNotEmpty()) lines += indent() + text
                i++
            }
            is XmlToken.Close -> {
                depth--
                lines += indent() + "</${token.name}>"
                i++
            }
            is XmlToken.Open -> {
                if (token.selfClosing) {
                    lines += indent() + token.raw
                    i++
                } else {
                    val next = tokens.getOrNull(i + 1)
                    val after = tokens.getOrNull(i + 2)
                    if (next is XmlToken.Text && after is XmlToken.Close && after.name == token.name) {
                        // Text-only element: keep it on one line, text unbroken.
                        lines += indent() + token.raw + next.text.trim() + "</${token.name}>"
                        i += 3
                    } else if (next is XmlToken.Close && next.name == token.name) {
                        lines += indent() + token.raw + "</${token.name}>"
                        i += 2
                    } else {
                        lines += indent() + token.raw
                        depth++
                        i++
                    }
                }
            }
        }
    }
    return lines.joinToString("\n")
}

/** True for the MIME types whose preview text gets pretty-printed as JSON. */
fun isJsonMime(mimeType: String): Boolean = mimeType == "application/json"

/** True for the MIME types whose preview text gets pretty-printed as XML (HTML excluded: it has its own viewer). */
fun isXmlMime(mimeType: String): Boolean =
    (mimeType == "application/xml" || mimeType.endsWith("+xml")) &&
        mimeType != "application/xhtml+xml"

/**
 * The one dispatch every text-preview producer runs: pretty-print JSON/XML, clean
 * subtitles, pass everything else through untouched. A formatted result that balloons
 * far past its source (pathological nesting) falls back to the raw text.
 */
fun formatPreviewText(raw: String, mimeType: String): String {
    val formatted = when {
        MimeTypes.isSubtitle(mimeType) -> subtitleToCleanText(raw)
        isJsonMime(mimeType) -> prettyPrintJson(raw)
        isXmlMime(mimeType) -> prettyPrintXml(raw)
        else -> return raw
    }
    return if (formatted.length > raw.length * 4 + 1024 && formatted.length > 64 * 1024) raw else formatted
}

private val SUBTITLE_TAG_PATTERN = Regex("<[^>]+>")
private val VTT_BLOCK_HEADER = Regex("^(NOTE|STYLE|REGION)(\\s.*)?$")

/**
 * Turns raw SRT/VTT subtitle source into clean readable text: the WEBVTT header and
 * NOTE/STYLE/REGION blocks are dropped, cue-index and timing (`-->`) lines are dropped,
 * inline tags are stripped, and cues are separated by exactly one blank line.
 */
fun subtitleToCleanText(raw: String): String {
    val cues = mutableListOf<String>()
    var current = mutableListOf<String>()
    fun flush() {
        if (current.isNotEmpty()) {
            cues += current.joinToString("\n")
            current = mutableListOf()
        }
    }
    var inVttBlock = false
    // True once the current cue's timing line has been seen: lines before it in a
    // block are a VTT cue identifier (or header metadata) and are not cue text.
    var inCue = false
    for (lineRaw in raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
        val line = lineRaw.trim()
        when {
            line.startsWith("WEBVTT") -> Unit
            VTT_BLOCK_HEADER.matches(line) -> {
                flush()
                inCue = false
                inVttBlock = true
            }
            line.isEmpty() -> {
                inVttBlock = false
                inCue = false
                flush()
            }
            inVttBlock -> Unit
            line.contains("-->") -> {
                // A timing line starts a new cue (and ends any cue whose file
                // omitted the blank-line separator).
                flush()
                inCue = true
            }
            line.all { it.isDigit() } -> Unit // SRT cue index
            !inCue -> Unit // VTT cue identifier / header metadata before the timing line
            else -> current += SUBTITLE_TAG_PATTERN.replace(line, "").trim()
        }
    }
    flush()
    return cues.filter { it.isNotBlank() }.joinToString("\n\n")
}

/**
 * B2 safe-WebView navigation policy: no navigation is ever allowed out of the loaded
 * document — only the initial about:blank / data load itself passes. Everything else
 * (http, https, file, intent, …) is refused.
 *
 * Round 3 A10: same-document fragment navigations (`about:blank#section`, produced by
 * tapping an in-page anchor such as a checklist's table-of-contents link) are also
 * allowed — they never leave the loaded document, they only scroll within it. Before
 * this, every anchor tap was classified as a forbidden external navigation.
 */
fun htmlNavigationAllowed(url: String?): Boolean {
    val value = url?.trim()?.lowercase() ?: return false
    return value == "about:blank" || value.startsWith("about:blank#") ||
        value.startsWith("data:text/html")
}

/**
 * Round 3 A10 subresource policy for the HTML viewer: the only subresources a preview
 * page may load are ones already inside the document itself — `data:` URLs (inline
 * images/styles the file carries) and `about:` placeholders. Every http/https/file/
 * content subresource is refused (the Android client answers it with an empty
 * response), so a previewed file cannot reach the network or the filesystem through
 * an `<img>`, `<link>`, `<script src>` or CSS `url(...)` even though scripts now run.
 */
fun htmlSubresourceAllowed(url: String?): Boolean {
    val value = url?.trim()?.lowercase() ?: return false
    return value.startsWith("data:") || value.startsWith("about:")
}

/**
 * Round 3 A10: the HTML viewer's WebView settings, as pure values so host tests can
 * pin the security posture (the settings themselves are applied by the Android
 * `HtmlPreview`, which is not host-testable).
 *
 * What changed from the vc181 safe mode and why: JavaScript and DOM storage are ON,
 * because the field failure was a real-world checklist page whose items are rendered
 * and whose checkboxes are driven by its inline script — with scripts off only the
 * static top of such a page ever appears and its checkboxes are dead. Everything
 * that gives a page a route OUT of the WebView stays OFF: no file access, no content
 * access, no file-URL access in either direction, network loads blocked, no
 * JavaScript interface is ever registered (there is no field for one here on
 * purpose), windows cannot be opened, and navigation/subresources stay governed by
 * [htmlNavigationAllowed] / [htmlSubresourceAllowed]. A script can therefore only
 * rearrange its own page; it cannot read files, reach the network, or call app code.
 */
object HtmlWebViewPolicy {
    const val JAVA_SCRIPT_ENABLED = true
    const val DOM_STORAGE_ENABLED = true
    const val ALLOW_FILE_ACCESS = false
    const val ALLOW_CONTENT_ACCESS = false
    const val ALLOW_FILE_ACCESS_FROM_FILE_URLS = false
    const val ALLOW_UNIVERSAL_ACCESS_FROM_FILE_URLS = false
    const val BLOCK_NETWORK_LOADS = true
    const val JAVA_SCRIPT_CAN_OPEN_WINDOWS_AUTOMATICALLY = false
    const val SUPPORT_MULTIPLE_WINDOWS = false
    const val MEDIA_PLAYBACK_REQUIRES_USER_GESTURE = true
    const val LOAD_WITH_OVERVIEW_MODE = true
    const val USE_WIDE_VIEW_PORT = true
    const val SUPPORT_ZOOM = false
}
