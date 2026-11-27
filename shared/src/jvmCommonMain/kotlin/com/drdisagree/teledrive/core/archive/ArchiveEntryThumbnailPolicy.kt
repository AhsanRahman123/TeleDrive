package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.MimeTypes
import java.security.MessageDigest

/**
 * Pure eligibility and cache-key rules for on-demand archive-entry thumbnails
 * (vc182 item 6). Everything here is decided from data the ZIP listing already
 * carries — no byte-source reads, no I/O — so a row can be ruled out (and a cache
 * key computed) before any fetch machinery is touched.
 *
 * Scope v1: still images only. Video poster frames are deliberately NOT eligible
 * (a poster would cost a whole-entry download per row), and SVG entries are
 * skipped (they keep the generic icon).
 */
object ArchiveEntryThumbnailPolicy {

    /** Entries larger than this never get a thumbnail: the fetch downloads the whole entry. */
    const val MAX_ENTRY_BYTES: Long = 20L * 1024 * 1024

    /**
     * Decode target: thumbnails are generated at at most this many pixels per side.
     * vc183 item 6: raised 192 -> 256 alongside the row slot's 40dp -> 56dp, and
     * deliberately no higher — decode cost scales with this size and the smooth
     * vc182 scrolling baseline must not regress. Cached 192px JPEGs from vc182
     * stay valid under the same keys (they upscale marginally in the bigger
     * slot); only newly fetched thumbnails decode at the new size.
     */
    const val THUMBNAIL_SIZE_PX: Int = 256

    /** Cache subdir (under the app cache dir) holding generated archive-entry thumbnails. */
    const val CACHE_DIR_NAME: String = "archive-thumbs"

    // ZIP compression methods, mirroring ZipArchiveReader's METHOD_STORED / METHOD_DEFLATE.
    const val METHOD_STORED: Int = 0
    const val METHOD_DEFLATE: Int = 8

    private const val SVG_MIME: String = "image/svg+xml"

    /**
     * Whether [name] (a full entry path or just its last segment — only the
     * extension matters) names a still image other than SVG.
     */
    fun isImageEntryName(name: String): Boolean {
        val mimeType = MimeTypes.fromFileName(name)
        return MimeTypes.isImage(mimeType) && mimeType != SVG_MIME
    }

    /**
     * The cheap pre-check a caller can run from listing data alone (entry name +
     * uncompressed size), before opening any session: ineligible here means the
     * entry must not cause a single byte-source read.
     */
    fun isEligibleByListing(entryName: String, sizeBytes: Long, isDirectory: Boolean): Boolean =
        !isDirectory && isImageEntryName(entryName) && sizeBytes in 1..MAX_ENTRY_BYTES

    /**
     * Full eligibility from the parsed central-directory record: the listing
     * pre-check plus the fields only [ZipEntryInfo] carries (compression method,
     * encryption flag).
     */
    fun isEligible(info: ZipEntryInfo): Boolean =
        isEligibleByListing(info.name, info.sizeBytes, info.isDirectory) &&
            !info.isEncrypted &&
            (info.method == METHOD_STORED || info.method == METHOD_DEFLATE)

    /**
     * Stable cache key for one entry of one archive. The listing [entryIndex] is
     * part of the key, so duplicate-named entries (same name, different listing
     * positions) get distinct keys, while the same entry always maps to the same
     * key; any change to the entry's recorded sizes or timestamp yields a new key
     * (a replaced archive never serves a stale thumbnail under the old key).
     */
    fun cacheKey(
        archiveFileId: String,
        entryIndex: Int,
        sizeBytes: Long,
        compressedBytes: Long,
        modifiedAtMillis: Long
    ): String {
        val raw = "$archiveFileId|$entryIndex|$sizeBytes|$compressedBytes|$modifiedAtMillis"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
