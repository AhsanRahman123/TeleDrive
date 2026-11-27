package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.files.MimeTypes
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

/**
 * The local (prefix-free) name of a node: `w:p` → `p`, `a:t` → `t`, `row` → `row`.
 * Namespace-aware DOM implementations are only required to match
 * `getElementsByTagName` against the qualified name as written, and Android's
 * Harmony-derived DOM does not reliably match prefixed lookups such as
 * `getElementsByTagName("w:p")` — such a lookup silently returns an empty list,
 * which turned a normal-route DOCX into extracted "" text (a blank viewer)
 * while the same file's unprefixed EPUB lookups behaved differently. Every
 * extractor below therefore matches by LOCAL name, which is identical on the
 * host JVM and on Android whatever prefix (or default namespace) a document
 * uses.
 */
fun localNameOf(node: Node): String =
    node.localName ?: node.nodeName.substringAfterLast(':')

private fun Element.descendantsByLocalName(local: String): List<Element> {
    val nodes = getElementsByTagName("*")
    return (0 until nodes.length)
        .map { nodes.item(it) as Element }
        .filter { localNameOf(it) == local }
}

private fun Document.elementsByLocalName(local: String): List<Element> {
    val nodes = getElementsByTagName("*")
    return (0 until nodes.length)
        .map { nodes.item(it) as Element }
        .filter { localNameOf(it) == local }
}

// --------------------------------------------------------------------------- DOCX

/** DOCX `word/document.xml` → text: `w:t` runs concatenated, one line per `w:p`. */
fun docxToText(documentXml: ByteArray): String {
    val document = parseXmlHardened(documentXml) ?: return ""
    val paragraphs = document.elementsByLocalName("p")
    return paragraphs.joinToString("\n") { paragraph ->
        buildString {
            fun walk(node: Node) {
                when (localNameOf(node)) {
                    "t" -> append(node.textContent)
                    "tab" -> append('\t')
                    "br" -> append('\n')
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
    return document.elementsByLocalName("si").map { si ->
        si.descendantsByLocalName("t").joinToString("") { it.textContent }
    }
}

/** One XLSX sheet → one line per row, cells tab-separated, shared strings resolved. */
fun xlsxSheetToLines(sheetXml: ByteArray, sharedStrings: List<String>): List<String> {
    val document = parseXmlHardened(sheetXml) ?: return emptyList()
    return document.elementsByLocalName("row").mapNotNull { row ->
        val cells = row.descendantsByLocalName("c").map { cell ->
            val type = cell.getAttribute("t")
            val value = cell.descendantsByLocalName("v").firstOrNull()?.textContent.orEmpty()
            when (type) {
                "s" -> value.toIntOrNull()?.let { sharedStrings.getOrNull(it) } ?: value
                "inlineStr" -> cell.descendantsByLocalName("t").joinToString("") { it.textContent }
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
    return document.elementsByLocalName("p").joinToString("\n") { paragraph ->
        paragraph.descendantsByLocalName("t").joinToString("") { it.textContent }
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
    return document.elementsByLocalName("rootfile")
        .firstOrNull()?.getAttribute("full-path")?.takeIf { it.isNotBlank() }
}

/**
 * EPUB OPF → the spine's chapter paths in reading order: manifest id→href joined with
 * the spine's idrefs, hrefs resolved against the OPF's own directory inside the zip.
 */
fun epubSpinePaths(opfXml: ByteArray, opfPath: String): List<String> {
    val document = parseXmlHardened(opfXml) ?: return emptyList()
    val manifest = HashMap<String, String>()
    document.elementsByLocalName("item").forEach { item ->
        val id = item.getAttribute("id")
        val href = item.getAttribute("href")
        if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
    }
    val opfDir = opfPath.substringBeforeLast('/', "")
    return document.elementsByLocalName("itemref").mapNotNull { ref ->
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

/**
 * True when [text] carries no readable content: blank, or — for the XLSX/PPTX
 * formats whose converters always emit a `— name —` header line per sheet /
 * slide — only those header lines. An extraction that yields no readable
 * content is a FAILURE, never a successful empty text: before vc183 the DOCX
 * branch wrapped a parse failure's "" in [DocumentExtraction.Text], so the
 * normal-route viewer opened to a blank screen instead of the honest
 * "No preview for this file type" fallback the EPUB branch already produced
 * for the same failure.
 */
fun documentTextIsContentless(text: String): Boolean =
    text.lines().all { line ->
        val trimmed = line.trim()
        trimmed.isEmpty() || (trimmed.startsWith("— ") && trimmed.endsWith(" —"))
    }

/** Extracts readable text from an OOXML file on disk (DOCX/XLSX/PPTX). */
fun extractOfficeText(file: File): DocumentExtraction =
    when (val kind = documentKindForExtension(file.name.substringAfterLast('.', ""))) {
        DocumentKind.DOCX, DocumentKind.XLSX, DocumentKind.PPTX ->
            extractOfficeText(file, kind)
        else -> DocumentExtraction.Failed
    }

/** Extracts readable text from an OOXML file on disk as the given [kind]. */
fun extractOfficeText(file: File, kind: DocumentKind): DocumentExtraction {
    return runCatching {
        ZipFile(file).use { zip ->
            fun entryBytes(name: String): ByteArray? =
                zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).readBytes() }
            when (kind) {
                DocumentKind.DOCX -> {
                    val xml = entryBytes("word/document.xml")
                        ?: return DocumentExtraction.Failed
                    val text = docxToText(xml)
                    if (documentTextIsContentless(text)) return DocumentExtraction.Failed
                    truncateText(text, DOCUMENT_TEXT_MAX_CHARS)
                }
                DocumentKind.XLSX -> {
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
                        parseXmlHardened(workbook)?.elementsByLocalName("sheet")
                            ?.map { it.getAttribute("name") }.orEmpty()
                    }.orEmpty()
                    val sheets = sheetEntries.mapIndexed { index, entry ->
                        (sheetNames.getOrNull(index)?.takeIf { it.isNotBlank() }
                            ?: "Sheet ${index + 1}") to
                            zip.getInputStream(entry).readBytes()
                    }
                    val text = xlsxToText(entryBytes("xl/sharedStrings.xml"), sheets)
                    if (documentTextIsContentless(text)) return DocumentExtraction.Failed
                    truncateText(text, DOCUMENT_TEXT_MAX_CHARS)
                }
                DocumentKind.PPTX -> {
                    val slides = zip.entries().toList()
                        .mapNotNull { entry ->
                            val match = Regex("""ppt/slides/slide(\d+)\.xml""")
                                .find(entry.name) ?: return@mapNotNull null
                            (match.groupValues[1].toIntOrNull() ?: return@mapNotNull null) to
                                zip.getInputStream(entry).readBytes()
                        }
                    if (slides.isEmpty()) return DocumentExtraction.Failed
                    val text = pptxToText(slides)
                    if (documentTextIsContentless(text)) return DocumentExtraction.Failed
                    truncateText(text, DOCUMENT_TEXT_MAX_CHARS)
                }
                DocumentKind.EPUB -> DocumentExtraction.Failed
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

/** The document formats the preview extractors support. */
enum class DocumentKind { EPUB, DOCX, XLSX, PPTX }

fun documentKindForExtension(extension: String): DocumentKind? =
    when (extension.lowercase()) {
        "epub" -> DocumentKind.EPUB
        "docx" -> DocumentKind.DOCX
        "xlsx" -> DocumentKind.XLSX
        "pptx" -> DocumentKind.PPTX
        else -> null
    }

fun documentKindForMimeType(mimeType: String): DocumentKind? = when (mimeType) {
    "application/epub+zip" -> DocumentKind.EPUB
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
        DocumentKind.DOCX
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
        DocumentKind.XLSX
    "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
        DocumentKind.PPTX
    else -> null
}

/**
 * Last-resort format detection from the ZIP's own table of contents: each
 * supported format has a marker entry no plain ZIP carries. Only consulted
 * when neither the MIME type nor any file name identifies the format, and a
 * plain ZIP (no marker entry) still resolves to null — never to a document.
 */
fun documentKindByContent(file: File): DocumentKind? = runCatching {
    ZipFile(file).use { zip ->
        fun has(name: String) = zip.getEntry(name) != null
        when {
            has("META-INF/container.xml") -> DocumentKind.EPUB
            has("word/document.xml") -> DocumentKind.DOCX
            has("xl/workbook.xml") -> DocumentKind.XLSX
            has("ppt/presentation.xml") -> DocumentKind.PPTX
            else -> null
        }
    }
}.getOrNull()

/**
 * Resolves which document format [file] is, in trust order: the caller's
 * declared [mimeType], the caller's [displayName] extension, the on-disk
 * file's own extension, and finally the ZIP content markers.
 *
 * vc184 A1: the production Files-pager route classifies by the DriveFile's
 * stored MIME and name (see `previewDispatch`), but until now extraction
 * re-derived the format from the ON-DISK file's extension alone and the
 * caller classification was dropped at this seam. The byte source the
 * resolver hands over is not always named after the DriveFile — the preview
 * cache synthesises its name, and local copies keep whatever name their
 * producer (managed import, MediaStore, TDLib, staging) gave them — so a
 * file the route had correctly classified as EPUB/OOXML could still extract
 * as [DocumentExtraction.Failed] here and render "No preview for this file
 * type". The classification the route already computed now decides; the
 * on-disk name is only a fallback, exactly as it was the sole signal before.
 */
fun resolveDocumentKind(file: File, mimeType: String, displayName: String): DocumentKind? =
    documentKindForMimeType(mimeType)
        ?: documentKindForExtension(displayName.substringAfterLast('.', ""))
        ?: documentKindForExtension(file.name.substringAfterLast('.', ""))
        ?: documentKindByContent(file)

/** The one dispatch for extracted/local document files whose name is trusted. */
fun extractDocumentText(file: File): DocumentExtraction =
    extractDocumentText(file, MimeTypes.fromFileName(file.name), file.name)

/**
 * Extracts readable text from a document [file] the caller has already
 * classified: [mimeType] and [displayName] are the DriveFile's stored MIME
 * and name on the normal route (the same pair `previewDispatch` dispatched
 * on), so extraction can never disagree with the dispatch that sent the
 * file here.
 */
fun extractDocumentText(file: File, mimeType: String, displayName: String): DocumentExtraction =
    when (val kind = resolveDocumentKind(file, mimeType, displayName)) {
        DocumentKind.EPUB -> extractEpubText(file)
        DocumentKind.DOCX, DocumentKind.XLSX, DocumentKind.PPTX ->
            extractOfficeText(file, kind)
        null -> DocumentExtraction.Failed
    }
