package com.drdisagree.teledrive.desktop.media.player

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/**
 * Repairs player metadata text that arrived as mojibake through the vlcj
 * native-string seam.
 *
 * Proven mechanism (vlcj 4.12.1 / JNA 5.19.1, the versions this app ships):
 * libvlc returns meta strings as UTF-8 `char*`
 * (`libvlc_media_get_meta`). vlcj's `MetaApi.get` hands the pointer to
 * `NativeString.copyAndFreeNativeString`, which calls JNA
 * `Pointer.getString(0)` — the overload with no charset. JNA decodes with
 * `Native.getDefaultStringEncoding()`, i.e. the OS native charset
 * (`native.encoding`), which on a Western Windows install is
 * windows-1252 — not UTF-8. Every non-ASCII UTF-8 byte sequence is therefore
 * re-interpreted as windows-1252 characters: a UTF-8 BOM (EF BB BF) becomes
 * "ï»¿" and Japanese text becomes the "ã€‚ / ãƒ…" pattern seen in the
 * 1.6.23 field screenshot (the ‚ † Ÿ glyphs in it are the windows-1252-only
 * mappings for bytes 0x82 / 0x86 / 0x9F, which ISO-8859-1 would have rendered
 * as invisible control characters).
 *
 * Two halves, each doing what the other cannot:
 *
 * 1. [ensureJnaUtf8StringEncoding] stops the mangling at the source.
 *    JNA's `getDefaultStringEncoding()` re-reads the `jna.encoding`
 *    system property on every call, so setting it to UTF-8 at app
 *    startup (before any JNA/vlcj use) makes the seam decode libvlc's
 *    UTF-8 as UTF-8 — losslessly, including characters whose UTF-8
 *    bytes (0x81, 0x8D, 0x8F, 0x90, 0x9D) the windows-1252 decoder
 *    destroys outright (it maps them to U+FFFD; e.g. の is E3 81 AE).
 *    Audited side effects: jna-platform's Win32 libraries (Shell32 /
 *    Kernel32 / User32) load with UNICODE_OPTIONS and convert strings
 *    as WString, so they ignore this property; the app's own JNA
 *    interfaces carry no display strings (the one exception, POSIX
 *    `setenv` for VLC_PLUGIN_PATH, wants UTF-8 bytes anyway); and
 *    strings passed TO libvlc (file paths / MRLs) are documented
 *    UTF-8, so they gain correct non-ASCII paths rather than lose
 *    anything.
 *
 * 2. The repair below covers text that still arrives mangled — above
 *    all tags that were already written into a file as mojibake by
 *    another tool, which no decoding fix at our seam can undo.
 *    It cannot (and does not guess at) strings in which the lossy
 *    decode already destroyed a byte: those contain U+FFFD and are
 *    left exactly as-is.
 *
 * The repair re-encodes the string back to the bytes it must have come from
 * (via an explicit windows-1252 reverse table, so no JVM charset quirks are
 * involved) and strictly decodes those bytes as UTF-8. It is a strict no-op
 * unless every guard passes:
 *  - every character must belong to the windows-1252 decode image (a string
 *    containing real Japanese / emoji / etc. cannot be mojibake of this
 *    form and is returned untouched);
 *  - the recovered bytes must be valid UTF-8 end-to-end (genuine Latin-1 /
 *    Western European text such as "Café" fails this and is untouched);
 *  - the decoded text must contain non-ASCII text and no replacement or
 *    control characters.
 * Pure ASCII is returned unchanged. A leading UTF-8 BOM, surfaced as
 * "ï»¿", is stripped. The pass repeats once so double-mangled text (a
 * tagger that itself wrote mojibake as UTF-8, then mangled again by the
 * seam) also recovers; each pass is guarded independently.
 */
internal fun repairMetaText(text: String): String {
    var current = text
    repeat(MAX_REPAIR_PASSES) {
        val repaired = repairMetaTextOnce(current) ?: return current
        current = repaired
    }
    return current
}

/**
 * Points JNA's default native-string encoding at UTF-8 for the whole
 * process. Must run before the first JNA/vlcj string conversion (it is
 * the first statement of the desktop `main`), though JNA re-reads the
 * property on every conversion, so nothing caches the old value.
 */
internal fun ensureJnaUtf8StringEncoding() {
    System.setProperty("jna.encoding", "UTF-8")
}

private const val MAX_REPAIR_PASSES = 2

private fun repairMetaTextOnce(text: String): String? {
    if (text.isEmpty()) return null
    val bytes = ByteArray(text.length)
    var hasNonAsciiByte = false
    for (index in text.indices) {
        val byte = windows1252ByteFor(text[index]) ?: return null
        bytes[index] = byte
        if (byte < 0) hasNonAsciiByte = true
    }
    if (!hasNonAsciiByte) return null

    val decoded = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull() ?: return null

    if (decoded == text) return null
    if (decoded.none { it.code > 0x7F }) return null
    if (decoded.any { char ->
            char == '\uFFFD' ||
                (char.code < 0x20 && char != '\t' && char != '\n' && char != '\r') ||
                char.code in 0x7F..0x9F
        }
    ) {
        return null
    }
    val stripped = decoded.removePrefix("\uFEFF")
    return stripped.takeIf { it.isNotEmpty() }
}

/**
 * The byte a character must have come from if it was produced by decoding
 * bytes as windows-1252, or null when the character cannot have been
 * produced that way. Covers ASCII, the U+00A0..U+00FF identity range, the
 * windows-1252 punctuation block for bytes 0x80..0x9F, and C1 controls
 * (some decoders surface bytes 0x80..0x9F as U+0080..U+009F instead).
 * U+FFFD is deliberately unmappable: it marks a byte the decoder itself
 * could not map (the undefined windows-1252 bytes), so the original byte
 * is unrecoverable and the string must be left as-is.
 */
private fun windows1252ByteFor(char: Char): Byte? = when (char.code) {
    in 0x00..0x7F -> char.code.toByte()
    in 0xA0..0xFF -> char.code.toByte()
    in 0x80..0x9F -> char.code.toByte()
    else -> WINDOWS_1252_PUNCTUATION[char]
}

private val WINDOWS_1252_PUNCTUATION: Map<Char, Byte> = mapOf(
    '€' to 0x80.toByte(),
    '‚' to 0x82.toByte(),
    'ƒ' to 0x83.toByte(),
    '„' to 0x84.toByte(),
    '…' to 0x85.toByte(),
    '†' to 0x86.toByte(),
    '‡' to 0x87.toByte(),
    'ˆ' to 0x88.toByte(),
    '‰' to 0x89.toByte(),
    'Š' to 0x8A.toByte(),
    '‹' to 0x8B.toByte(),
    'Œ' to 0x8C.toByte(),
    'Ž' to 0x8E.toByte(),
    '‘' to 0x91.toByte(),
    '’' to 0x92.toByte(),
    '“' to 0x93.toByte(),
    '”' to 0x94.toByte(),
    '•' to 0x95.toByte(),
    '–' to 0x96.toByte(),
    '—' to 0x97.toByte(),
    '˜' to 0x98.toByte(),
    '™' to 0x99.toByte(),
    'š' to 0x9A.toByte(),
    '›' to 0x9B.toByte(),
    'œ' to 0x9C.toByte(),
    'ž' to 0x9E.toByte(),
    'Ÿ' to 0x9F.toByte()
)
