package com.drdisagree.teledrive.presentation.preview

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Text extraction for the document formats a preview can honestly support with no new
 * dependencies: OOXML Office files (DOCX/XLSX/PPTX) and EPUB Tier 1 (plain-text book
 * reading). All of them are ZIPs of XML, read here with [ZipFile]; every XML parse in
 * this file goes through the ONE XXE-hardened factory below, because these are
 * untrusted files — external entities and DTDs are disabled, always.
 *
 * Legacy binary .doc/.xls/.ppt are deliberately NOT handled: there is no small honest
 * extractor for the OLE2 formats, so they keep the "Open with…" fallback.
 */

/** Own text budget for extracted documents (above the 256 KiB plain-text cap). */
const val DOCUMENT_TEXT_MAX_CHARS: Int = 1_000_000

/** Own text budget for EPUB books: books are big, so Tier 1 allows 2M chars with a truncated flag. */
const val EPUB_TEXT_MAX_CHARS: Int = 2_000_000

sealed interface DocumentExtraction {
    data class Text(val text: String, val truncated: Boolean) : DocumentExtraction
    data object DrmProtected : DocumentExtraction
    data object Failed : DocumentExtraction
}

/** Raised inside the archive-preview flow when a document entry cannot be shown. */
class DocumentPreviewException(message: String) : Exception(message)

/**
 * The single hardened [DocumentBuilderFactory] for ALL user-file XML in the app.
 * DOCTYPE is disallowed where the parser supports it; external entities/DTDs are off
 * regardless, XInclude is off and entity references are never expanded.
 */
fun hardenedDocumentBuilderFactory(): DocumentBuilderFactory {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    runCatching {
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.onFailure {
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        runCatching {
            factory.setFeature(
                "http://apache.org/xml/features/nonvalidating/load-external-dtd", false
            )
        }
    }
    runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
    runCatching { factory.setAttribute("http://javax.xml.XMLConstants/property/accessExternalSchema", "") }
    return factory
}

/** Parses [bytes] as XML through the hardened factory; null on any failure. */
fun parseXmlHardened(bytes: ByteArray): Document? = runCatching {
    val builder = hardenedDocumentBuilderFactory().newDocumentBuilder()
    // Belt and braces: even if a DOCTYPE slipped through, no external entity is fetched.
    builder.setEntityResolver { _, _ -> InputSource(ByteArrayInputStream(ByteArray(0))) }
    builder.parse(ByteArrayInputStream(bytes))
}.getOrNull()

private fun Element.descendantsByTag(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

private fun Document.elementsByTag(tag: String): List<Element> {
    val nodes = getElementsByTagName(tag)
    return (0 until nodes.length).map { nodes.item(it) as Element }
}

// --------------------------------------------------------------------------- DOCX

/** DOCX `word/document.xml` → text: `w:t` runs concatenated, one line per `w:p`. */
fun docxToText(documentXml: ByteArray): String {
    val document = parseXmlHardened(documentXml) ?: return ""
    val paragraphs = document.elementsByTag("w:p")
    return paragraphs.joinToString("\n") { paragraph ->
        buildString {
            fun walk(node: Node) {
                when (node.nodeName) {
                    "w:t" -> append(node.textContent)
                    "w:tab" -> append('\t')
                    "w:br" -> append('\n')
                }
                val children = node.childNodes
                for (i in 0 until children.length) walk(children.item(i))
            }
            walk(paragraph)
        }
    }
}

// --------------------------------------------------------------------------- XLSX

/** XLSX `xl/sharedStrings.xml` → the shared string table (`s:t` per `si`, runs joined). */
fun xlsxSharedStrings(sharedStringsXml: ByteArray?): List<String> {
    if (sharedStringsXml == null) return emptyList()
    val document = parseXmlHardened(sharedStringsXml) ?: return emptyList()
    return document.elementsByTag("si").map { si ->
        si.descendantsByTag("t").joinToString("") { it.textContent }
    }
}

/** One XLSX sheet → one line per row, cells tab-separated, shared strings resolved. */
fun xlsxSheetToLines(sheetXml: ByteArray, sharedStrings: List<String>): List<String> {
    val document = parseXmlHardened(sheetXml) ?: return emptyList()
    return document.elementsByTag("row").mapNotNull { row ->
        val cells = row.descendantsByTag("c").map { cell ->
            val type = cell.getAttribute("t")
            val value = cell.descendantsByTag("v").firstOrNull()?.textContent.orEmpty()
            when (type) {
                "s" -> value.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: value
                "inlineStr" -> cell.descendantsByTag("t").joinToString("") { it.textContent }
                else -> value
            }
        }
        if (cells.all { it.isEmpty() }) null else cells.joinToString("\t").trimEnd()
    }
}

/** XLSX → text with a header line per sheet. Numbers/dates appear as stored. */
fun xlsxToText(
    sharedStringsXml: ByteArray?,
    sheets: List<Pair<String, ByteArray>>
): String {
    val shared = xlsxSharedStrings(sharedStringsXml)
    return sheets.joinToString("\n\n") { (name, sheetXml) ->
        buildString {
            append("— ").append(name).append(" —")
            val lines = xlsxSheetToLines(sheetXml, shared)
            if (lines.isNotEmpty()) append('\n').append(lines.joinToString("\n"))
        }
    }
}

// --------------------------------------------------------------------------- PPTX

/** One PPTX slide → its `a:t` runs, one line per `a:p` paragraph. */
fun pptxSlideToText(slideXml: ByteArray): String {
    val document = parseXmlHardened(slideXml) ?: return ""
    return document.elementsByTag("a:p").joinToString("\n") { paragraph ->
        paragraph.descendantsByTag("a:t").joinToString("") { it.textContent }
    }
}

/** PPTX → text, slides in numeric order with `— Slide N —` separators. */
fun pptxToText(slides: List<Pair<Int, ByteArray>>): String =
    slides.sortedBy { it.first }.joinToString("\n\n") { (number, slideXml) ->
        "— Slide $number —\n" + pptxSlideToText(slideXml)
    }

// --------------------------------------------------------------------------- EPUB

/** EPUB `META-INF/container.xml` → the OPF package document's full path. */
fun epubRootFilePath(containerXml: ByteArray): String? {
    val document = parseXmlHardened(containerXml) ?: return null
    return document.elementsByTag("rootfile")
        .firstOrNull()?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
}

/**
 * EPUB OPF → the spine's chapter paths in reading order: manifest id→href joined with
 * the spine's idrefs, hrefs resolved against the OPF's own directory inside the zip.
 */
fun epubSpinePaths(opfXml: ByteArray, opfPath: String): List<String> {
    val document = parseXmlHardened(opfXml) ?: return emptyList()
    val manifest = HashMap<String, String>()
    document.elementsByTag("item").forEach { item ->
        val id = item.getAttribute("id")
        val href = item.getAttribute("href")
        if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
    }
    val opfDir = opfPath.substringBeforeLast('/', "")
    return document.elementsByTag("itemref").mapNotNull { ref ->
        val href = manifest[ref.getAttribute("idref")] ?: return@mapNotNull null
        resolveZipPath(opfDir, href)
    }
}

/** Resolves a relative zip path (with %-escapes and ./ ../ segments) against [baseDir]. */
fun resolveZipPath(baseDir: String, href: String): String {
    val decoded = percentDecode(href.substringBefore('#'))
    val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
    val segments = mutableListOf<String>()
    for (segment in combined.split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

private fun percentDecode(value: String): String {
    if (!value.contains('%')) return value
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%' && i + 2 < value.length) {
            val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.write(hex)
                i += 3
                continue
            }
        }
        bytes.write(c.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return bytes.toByteArray().toString(Charsets.UTF_8)
}

private val XHTML_BLOCK_TAGS = setOf(
    "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "tr", "section",
    "article", "blockquote", "title", "pre", "table"
)

/** XHTML chapter → plain text: block elements become line breaks, all markup dropped. */
fun xhtmlToPlainText(xhtml: ByteArray): String {
    val document = parseXmlHardened(xhtml) ?: return ""
    val out = StringBuilder()
    fun walk(node: Node) {
        val local = node.localName ?: node.nodeName.substringAfterLast(':')
        if (node.nodeType == Node.TEXT_NODE) {
            out.append(node.nodeValue)
            return
        }
        if (local == "br") {
            out.append('\n')
            return
        }
        val isBlock = local in XHTML_BLOCK_TAGS
        if (isBlock) out.append('\n')
        val children = node.childNodes
        for (i in 0 until children.length) walk(children.item(i))
        if (isBlock) out.append('\n')
    }
    walk(document.documentElement)
    return out.lines()
        .map { it.replace(Regex("[ \t]+"), " ").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

// ------------------------------------------------------------------- file entry

private fun truncateText(text: String, maxChars: Int): DocumentExtraction.Text =
    if (text.length > maxChars) {
        DocumentExtraction.Text(text.substring(0, maxChars), truncated = true)
    } else {
        DocumentExtraction.Text(text, truncated = false)
    }

/** Extracts readable text from an OOXML file on disk (DOCX/XLSX/PPTX). */
fun extractOfficeText(file: File): DocumentExtraction {
    return runCatching {
        ZipFile(file).use { zip ->
            fun entryBytes(name: String): ByteArray? =
                zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).readBytes() }
            when (file.name.substringAfterLast('.', "").lowercase()) {
                "docx" -> {
                    val xml = entryBytes("word/document.xml")
                        ?: return DocumentExtraction.Failed
                    truncateText(docxToText(xml), DOCUMENT_TEXT_MAX_CHARS)
                }
                "xlsx" -> {
                    val sheetEntries = zip.entries().toList()
                        .filter {
                            Regex("""xl/worksheets/sheet\d+\.xml""").matches(it.name)
                        }
                        .sortedBy {
                            it.name.substringAfterLast("sheet").substringBefore(".").toIntOrNull()
                                ?: Int.MAX_VALUE
                        }
                    if (sheetEntries.isEmpty()) return DocumentExtraction.Failed
                    val sheetNames = entryBytes("xl/workbook.xml")?.let { workbook ->
                        parseXmlHardened(workbook)?.elementsByTag("sheet")
                            ?.map { it.getAttribute("name") }.orEmpty()
                    }.orEmpty()
                    val sheets = sheetEntries.mapIndexed { index, entry ->
                        (sheetNames.getOrNull(index)?.takeIf { it.isNotBlank() }
                            ?: "Sheet ${index + 1}") to
                            zip.getInputStream(entry).readBytes()
                    }
                    truncateText(
                        xlsxToText(entryBytes("xl/sharedStrings.xml"), sheets),
                        DOCUMENT_TEXT_MAX_CHARS
                    )
                }
                "pptx" -> {
                    val slides = zip.entries().toList()
                        .mapNotNull { entry ->
                            val match = Regex("""ppt/slides/slide(\d+)\.xml""")
                                .find(entry.name) ?: return@mapNotNull null
                            (match.groupValues[1].toIntOrNull() ?: return@mapNotNull null) to
                                zip.getInputStream(entry).readBytes()
                        }
                    if (slides.isEmpty()) return DocumentExtraction.Failed
                    truncateText(pptxToText(slides), DOCUMENT_TEXT_MAX_CHARS)
                }
                else -> DocumentExtraction.Failed
            }
        }
    }.getOrElse { DocumentExtraction.Failed }
}

/** EPUB Tier 1: container → OPF → spine → chapter plain text, in reading order. */
fun extractEpubText(file: File): DocumentExtraction {
    return runCatching {
        ZipFile(file).use { zip ->
            fun entryBytes(name: String): ByteArray? =
                zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).readBytes() }
            // A book carrying an encryption file is DRM-protected (or at best
            // font-obfuscated); Tier 1 says so honestly instead of showing garbage.
            if (zip.getEntry("META-INF/encryption.xml") != null) {
                return DocumentExtraction.DrmProtected
            }
            val container = entryBytes("META-INF/container.xml")
                ?: return DocumentExtraction.Failed
            val opfPath = epubRootFilePath(container) ?: return DocumentExtraction.Failed
            val opf = entryBytes(opfPath) ?: return DocumentExtraction.Failed
            val chapters = epubSpinePaths(opf, opfPath)
            if (chapters.isEmpty()) return DocumentExtraction.Failed
            val out = StringBuilder()
            var truncated = false
            for (chapterPath in chapters) {
                val xhtml = entryBytes(chapterPath) ?: continue
                val text = xhtmlToPlainText(xhtml)
                if (text.isBlank()) continue
                if (out.isNotEmpty()) out.append("\n\n")
                if (out.length + text.length > EPUB_TEXT_MAX_CHARS) {
                    out.append(text, 0, (EPUB_TEXT_MAX_CHARS - out.length).coerceAtLeast(0))
                    truncated = true
                    break
                }
                out.append(text)
            }
            if (out.isEmpty()) return DocumentExtraction.Failed
            DocumentExtraction.Text(out.toString(), truncated)
        }
    }.getOrElse { DocumentExtraction.Failed }
}

/** The one dispatch for extracted/local document files: EPUB vs OOXML by extension. */
fun extractDocumentText(file: File): DocumentExtraction =
    when (file.name.substringAfterLast('.', "").lowercase()) {
        "epub" -> extractEpubText(file)
        "docx", "xlsx", "pptx" -> extractOfficeText(file)
        else -> DocumentExtraction.Failed
    }
