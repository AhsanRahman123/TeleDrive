package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.MimeTypes
import java.io.File
import java.util.zip.ZipFile

/**
 * Text extraction for the document formats a preview can honestly support with no new
 * dependencies: OOXML Office files (DOCX/XLSX/PPTX) and EPUB Tier 1 (plain-text book
 * reading). All of them are ZIPs of XML, read here with [ZipFile].
 *
 * The XML itself is parsed by the small self-contained parser in this file
 * ([parseXmlToTree]) — deliberately NOT by `javax.xml.parsers` / `org.w3c.dom`.
 * That platform stack is a different implementation on Android (a
 * KXmlParser-based builder whose factory throws on every hardening feature
 * and attribute this code used to set) than on the desktop JDK (Xerces), so
 * the same bytes parsed on one platform and failed on the other, and every
 * host-JVM test exercised only the JDK half. The parser below is pure
 * Kotlin: identical behaviour on every platform, and XXE-safe by
 * construction — DTDs are skipped wholesale and entities are never resolved
 * against anything external; only the five predefined XML entities,
 * numeric character references and a small fixed set of common HTML named
 * entities are decoded.
 *
 * Legacy binary .doc/.xls/.ppt are deliberately NOT handled: there is no small honest
 * extractor for the OLE2 formats, so they keep the "Open with…" fallback.
 */

/** Own text budget for extracted documents (above the 256 KiB plain-text cap). */
const val DOCUMENT_TEXT_MAX_CHARS: Int = 1_000_000

/** Own text budget for EPUB books: books are big, so Tier 1 allows 2M chars with a truncated flag. */
const val EPUB_TEXT_MAX_CHARS: Int = 2_000_000

private const val TAG = "DocumentExtract"

sealed interface DocumentExtraction {
    data class Text(val text: String, val truncated: Boolean) : DocumentExtraction
    data object DrmProtected : DocumentExtraction
    data object Failed : DocumentExtraction
}

/** Raised inside the archive-preview flow when a document entry cannot be shown. */
class DocumentPreviewException(message: String) : Exception(message)

// ---------------------------------------------------------------------------
// Self-contained XML tree parser (platform-independent replacement for DOM)
// ---------------------------------------------------------------------------

/** One node of the minimal XML tree the extractors run on. */
sealed interface XmlNode

/** A run of character data (entity-decoded; CDATA content verbatim). */
class XmlTextNode(val text: String) : XmlNode

/**
 * One XML element: its namespace-free [localName] (`w:p` → `p`), its
 * attributes (qualified names as written) and its child nodes in document
 * order. Matching is by local name throughout, exactly as the extractors
 * have always required — the prefix a document happens to use never
 * matters, on any platform.
 */
class XmlElementNode(
    val localName: String,
    private val attributes: List<Pair<String, String>>,
    val children: List<XmlNode>
) : XmlNode {

    /** The attribute [name] (qualified match first, then local-name match), or "". */
    fun attribute(name: String): String =
        attributes.firstOrNull { it.first == name }?.second
            ?: attributes.firstOrNull { it.first.substringAfterLast(':') == name }?.second
            ?: ""

    /** All descendant text in document order (the DOM `textContent` equivalent). */
    val textContent: String
        get() = buildString { appendTextOf(this@XmlElementNode) }

    /** Descendant elements (self excluded) with the given local name, in document order. */
    fun descendantsByLocalName(local: String): List<XmlElementNode> {
        val out = mutableListOf<XmlElementNode>()
        fun visit(element: XmlElementNode) {
            for (child in element.children) {
                if (child is XmlElementNode) {
                    if (child.localName == local) out += child
                    visit(child)
                }
            }
        }
        visit(this)
        return out
    }

    /** This element (when it matches) plus its descendants with the given local name. */
    fun allByLocalName(local: String): List<XmlElementNode> =
        if (localName == local) {
            listOf(this) + descendantsByLocalName(local)
        } else {
            descendantsByLocalName(local)
        }
}

private fun StringBuilder.appendTextOf(element: XmlElementNode) {
    for (child in element.children) {
        when (child) {
            is XmlTextNode -> append(child.text)
            is XmlElementNode -> appendTextOf(child)
        }
    }
}

/** Named entities decoded beyond the five predefined XML ones (common HTML set). */
private val NAMED_ENTITIES = mapOf(
    "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
    "copy" to "©", "reg" to "®", "trade" to "™", "laquo" to "«",
    "raquo" to "»", "middot" to "·", "bull" to "•", "deg" to "°",
    "plusmn" to "±", "times" to "×", "divide" to "÷", "micro" to "µ",
    "para" to "¶", "sect" to "§", "iexcl" to "¡", "iquest" to "¿",
    "szlig" to "ß", "agrave" to "à", "aacute" to "á", "acirc" to "â",
    "atilde" to "ã", "auml" to "ä", "aring" to "å", "aelig" to "æ",
    "ccedil" to "ç", "egrave" to "è", "eacute" to "é", "ecirc" to "ê",
    "euml" to "ë", "igrave" to "ì", "iacute" to "í", "icirc" to "î",
    "iuml" to "ï", "ntilde" to "ñ", "ograve" to "ò", "oacute" to "ó",
    "ocirc" to "ô", "otilde" to "õ", "ouml" to "ö", "ugrave" to "ù",
    "uacute" to "ú", "ucirc" to "û", "uuml" to "ü", "yacute" to "ý",
    "yuml" to "ÿ", "Agrave" to "À", "Aacute" to "Á", "Acirc" to "Â",
    "Atilde" to "Ã", "Auml" to "Ä", "Aring" to "Å", "AElig" to "Æ",
    "Ccedil" to "Ç", "Egrave" to "È", "Eacute" to "É", "Ecirc" to "Ê",
    "Euml" to "Ë", "Igrave" to "Ì", "Iacute" to "Í", "Icirc" to "Î",
    "Iuml" to "Ï", "Ntilde" to "Ñ", "Ograve" to "Ò", "Oacute" to "Ó",
    "Ocirc" to "Ô", "Otilde" to "Õ", "Ouml" to "Ö", "Ugrave" to "Ù",
    "Uacute" to "Ú", "Ucirc" to "Û", "Uuml" to "Ü", "Yacute" to "Ý"
)

/**
 * Decodes entity and character references in [value]. External entities are
 * NEVER resolved (nothing is fetched or read); an unknown named entity is
 * left as its literal `&name;` text, which is honest in extracted text and
 * can never leak file or network content (the XXE contract).
 */
private fun decodeXmlEntities(value: String): String {
    if (!value.contains('&')) return value
    val out = StringBuilder(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c != '&') {
            out.append(c)
            i++
            continue
        }
        val semi = value.indexOf(';', i + 1)
        if (semi < 0 || semi - i > 12) {
            out.append(c)
            i++
            continue
        }
        val body = value.substring(i + 1, semi)
        val decoded: String? = when {
            body == "amp" -> "&"
            body == "lt" -> "<"
            body == "gt" -> ">"
            body == "quot" -> "\""
            body == "apos" -> "'"
            body.startsWith("#x") || body.startsWith("#X") ->
                body.substring(2).toIntOrNull(16)
                    ?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
                    ?.let { String(Character.toChars(it)) }
            body.startsWith("#") ->
                body.substring(1).toIntOrNull()
                    ?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }
                    ?.let { String(Character.toChars(it)) }
            else -> NAMED_ENTITIES[body]
        }
        if (decoded == null) {
            // Unknown (possibly DTD-defined) entity: keep it literal, resolve nothing.
            out.append(value, i, semi + 1)
        } else {
            out.append(decoded)
        }
        i = semi + 1
    }
    return out.toString()
}

/** Decodes raw XML [bytes] to a string honouring the BOM / declared encoding. */
private fun decodeXmlBytes(bytes: ByteArray): String {
    if (bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
    ) {
        return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
    }
    if (bytes.size >= 2 && bytes[0] == '<'.code.toByte() && bytes[1] == 0.toByte()) {
        return String(bytes, Charsets.UTF_16LE)
    }
    if (bytes.size >= 2 && bytes[0] == 0.toByte() && bytes[1] == '<'.code.toByte()) {
        return String(bytes, Charsets.UTF_16BE)
    }
    // No BOM: sniff the encoding named by the XML declaration (ASCII-safe head).
    val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.ISO_8859_1)
    val declared = Regex("""<\?xml[^>]*?encoding\s*=\s*["']([^"']+)["']""")
        .find(head)?.groupValues?.get(1)?.lowercase()
    val charset = when (declared) {
        "utf-16", "utf-16le" -> Charsets.UTF_16LE
        "utf-16be" -> Charsets.UTF_16BE
        "iso-8859-1", "windows-1252" -> Charsets.ISO_8859_1
        "us-ascii" -> Charsets.US_ASCII
        else -> Charsets.UTF_8
    }
    return String(bytes, charset).removePrefix("\uFEFF")
}

private const val MAX_XML_DEPTH = 512
private const val MAX_XML_ELEMENTS = 500_000

private class XmlBuilder(
    val qualifiedName: String,
    val localName: String,
    val attributes: List<Pair<String, String>>
) {
    val children = mutableListOf<XmlNode>()
    fun build(): XmlElementNode = XmlElementNode(localName, attributes, children.toList())
}

/**
 * Parses [bytes] as XML into the minimal [XmlElementNode] tree; null on any
 * malformed input (the same contract the DOM parse had: unparseable XML is
 * a failed extraction, never partial text). Pure Kotlin — the result does
 * not depend on the platform's XML stack in any way.
 */
fun parseXmlToTree(bytes: ByteArray): XmlElementNode? {
    val raw = decodeXmlBytes(bytes)
    var pos = 0
    val length = raw.length
    val stack = ArrayDeque<XmlBuilder>()
    var root: XmlElementNode? = null
    var elementCount = 0

    fun attach(node: XmlNode) {
        val parent = stack.lastOrNull()
        if (parent != null) {
            parent.children += node
        }
        // Character data outside the root element carries no content; dropped.
    }

    fun completeElement(builder: XmlBuilder) {
        val element = builder.build()
        val parent = stack.lastOrNull()
        if (parent != null) {
            parent.children += element
        } else if (root == null) {
            root = element
        }
    }

    while (pos < length) {
        if (raw[pos] != '<') {
            val next = raw.indexOf('<', pos)
            val end = if (next < 0) length else next
            if (stack.isNotEmpty()) {
                attach(XmlTextNode(decodeXmlEntities(raw.substring(pos, end))))
            }
            pos = end
            continue
        }
        when {
            raw.startsWith("<!--", pos) -> {
                val end = raw.indexOf("-->", pos + 4)
                if (end < 0) return null
                pos = end + 3
            }
            raw.startsWith("<![CDATA[", pos) -> {
                val end = raw.indexOf("]]>", pos + 9)
                if (end < 0) return null
                if (stack.isNotEmpty()) {
                    attach(XmlTextNode(raw.substring(pos + 9, end)))
                }
                pos = end + 3
            }
            raw.startsWith("<?", pos) -> {
                val end = raw.indexOf("?>", pos + 2)
                if (end < 0) return null
                pos = end + 2
            }
            raw.startsWith("<!", pos) -> {
                // DOCTYPE or another declaration: skipped wholesale (with
                // internal-subset brackets balanced), so nothing it declares
                // — entities included — is ever processed.
                var i = pos + 2
                var bracketDepth = 0
                var quote: Char? = null
                var closed = false
                while (i < length) {
                    val c = raw[i]
                    when {
                        quote != null -> if (c == quote) quote = null
                        c == '"' || c == '\'' -> quote = c
                        c == '[' -> bracketDepth++
                        c == ']' -> bracketDepth--
                        c == '>' && bracketDepth <= 0 -> {
                            closed = true
                            break
                        }
                    }
                    i++
                }
                if (!closed) return null
                pos = i + 1
            }
            raw.startsWith("</", pos) -> {
                val end = raw.indexOf('>', pos + 2)
                if (end < 0) return null
                val name = raw.substring(pos + 2, end).trim()
                // Close the matching open element; anything still open above
                // it is closed with it (lenient toward hand-made files), and
                // a stray end tag with no open match is ignored.
                val matchIndex = stack.indexOfLast { it.qualifiedName == name }
                if (matchIndex >= 0) {
                    while (stack.size > matchIndex) {
                        completeElement(stack.removeLast())
                    }
                }
                pos = end + 1
            }
            else -> {
                // Start tag: name, then attributes until '>' or '/>'.
                var i = pos + 1
                val nameStart = i
                while (i < length && !raw[i].isWhitespace() && raw[i] != '>' && raw[i] != '/') i++
                if (i == nameStart) return null
                val qualifiedName = raw.substring(nameStart, i)
                val attributes = mutableListOf<Pair<String, String>>()
                var selfClosing = false
                var tagClosed = false
                while (i < length && !tagClosed) {
                    while (i < length && raw[i].isWhitespace()) i++
                    if (i >= length) break
                    when {
                        raw.startsWith("/>", i) -> {
                            selfClosing = true
                            tagClosed = true
                            i += 2
                        }
                        raw[i] == '>' -> {
                            tagClosed = true
                            i += 1
                        }
                        else -> {
                            val attrStart = i
                            while (i < length && !raw[i].isWhitespace() &&
                                raw[i] != '=' && raw[i] != '>' && raw[i] != '/'
                            ) i++
                            val attrName = raw.substring(attrStart, i)
                            while (i < length && raw[i].isWhitespace()) i++
                            var attrValue = ""
                            if (i < length && raw[i] == '=') {
                                i++
                                while (i < length && raw[i].isWhitespace()) i++
                                if (i < length && (raw[i] == '"' || raw[i] == '\'')) {
                                    val quoteChar = raw[i]
                                    val valueStart = ++i
                                    val valueEnd = raw.indexOf(quoteChar, i)
                                    if (valueEnd < 0) return null
                                    attrValue = raw.substring(valueStart, valueEnd)
                                    i = valueEnd + 1
                                } else {
                                    val valueStart = i
                                    while (i < length && !raw[i].isWhitespace() && raw[i] != '>') i++
                                    attrValue = raw.substring(valueStart, i)
                                }
                            }
                            if (attrName.isNotEmpty()) {
                                attributes += attrName to decodeXmlEntities(attrValue)
                            } else if (i == attrStart) {
                                // No name and no value consumed (a stray
                                // character): skip it so the loop always
                                // makes progress.
                                i++
                            }
                        }
                    }
                }
                if (!tagClosed) return null
                elementCount++
                if (elementCount > MAX_XML_ELEMENTS) return null
                val builder = XmlBuilder(
                    qualifiedName = qualifiedName,
                    localName = qualifiedName.substringAfterLast(':'),
                    attributes = attributes
                )
                if (stack.size >= MAX_XML_DEPTH) return null
                if (selfClosing) {
                    if (stack.isEmpty() && root != null) return null
                    completeElement(builder)
                } else {
                    if (stack.isEmpty() && root != null) return null
                    stack.addLast(builder)
                }
                pos = i
            }
        }
    }
    if (stack.isNotEmpty()) return null
    return root
}

// --------------------------------------------------------------------------- DOCX

/** DOCX `word/document.xml` → text: `w:t` runs concatenated, one line per `w:p`. */
fun docxToText(documentXml: ByteArray): String {
    val document = parseXmlToTree(documentXml) ?: return ""
    val paragraphs = document.allByLocalName("p")
    return paragraphs.joinToString("\n") { paragraph ->
        buildString {
            fun walk(node: XmlNode) {
                if (node !is XmlElementNode) return
                when (node.localName) {
                    "t" -> {
                        append(node.textContent)
                        return
                    }
                    "tab" -> append('\t')
                    "br" -> append('\n')
                }
                node.children.forEach(::walk)
            }
            walk(paragraph)
        }
    }
}

// --------------------------------------------------------------------------- XLSX

/** XLSX `xl/sharedStrings.xml` → the shared string table (`s:t` per `si`, runs joined). */
fun xlsxSharedStrings(sharedStringsXml: ByteArray?): List<String> {
    if (sharedStringsXml == null) return emptyList()
    val document = parseXmlToTree(sharedStringsXml) ?: return emptyList()
    return document.allByLocalName("si").map { si ->
        si.descendantsByLocalName("t").joinToString("") { it.textContent }
    }
}

/** One XLSX sheet → one line per row, cells tab-separated, shared strings resolved. */
fun xlsxSheetToLines(sheetXml: ByteArray, sharedStrings: List<String>): List<String> {
    val document = parseXmlToTree(sheetXml) ?: return emptyList()
    return document.allByLocalName("row").mapNotNull { row ->
        val cells = row.descendantsByLocalName("c").map { cell ->
            val type = cell.attribute("t")
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
    val document = parseXmlToTree(slideXml) ?: return ""
    return document.allByLocalName("p").joinToString("\n") { paragraph ->
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
    val document = parseXmlToTree(containerXml) ?: return null
    return document.allByLocalName("rootfile")
        .firstOrNull()?.attribute("full-path")?.takeIf { it.isNotBlank() }
}

/**
 * EPUB OPF → the spine's chapter paths in reading order: manifest id→href joined with
 * the spine's idrefs, hrefs resolved against the OPF's own directory inside the zip.
 */
fun epubSpinePaths(opfXml: ByteArray, opfPath: String): List<String> {
    val document = parseXmlToTree(opfXml) ?: return emptyList()
    val manifest = HashMap<String, String>()
    document.allByLocalName("item").forEach { item ->
        val id = item.attribute("id")
        val href = item.attribute("href")
        if (id.isNotBlank() && href.isNotBlank()) manifest[id] = href
    }
    val opfDir = opfPath.substringBeforeLast('/', "")
    return document.allByLocalName("itemref").mapNotNull { ref ->
        val href = manifest[ref.attribute("idref")] ?: return@mapNotNull null
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
    val document = parseXmlToTree(xhtml) ?: return ""
    val out = StringBuilder()
    fun walk(node: XmlNode) {
        if (node is XmlTextNode) {
            out.append(node.text)
            return
        }
        if (node !is XmlElementNode) return
        val local = node.localName
        if (local == "br") {
            out.append('\n')
            return
        }
        val isBlock = local in XHTML_BLOCK_TAGS
        if (isBlock) out.append('\n')
        node.children.forEach(::walk)
        if (isBlock) out.append('\n')
    }
    walk(document)
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
                        parseXmlToTree(workbook)?.allByLocalName("sheet")
                            ?.map { it.attribute("name") }.orEmpty()
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
    }.getOrElse { failure ->
        // Extraction failures used to be swallowed silently, which is why two
        // field rounds could not see WHERE the reader failed. Log the cause.
        SafeLog.w(TAG, "Office extraction failed for ${file.name}", failure)
        DocumentExtraction.Failed
    }
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
    }.getOrElse { failure ->
        SafeLog.w(TAG, "EPUB extraction failed for ${file.name}", failure)
        DocumentExtraction.Failed
    }
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
