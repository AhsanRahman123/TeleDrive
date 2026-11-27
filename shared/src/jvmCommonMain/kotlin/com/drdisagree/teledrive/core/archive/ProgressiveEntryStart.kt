package com.drdisagree.teledrive.core.archive

/**
 * When a progressively extracted in-zip entry may be handed to a player/viewer
 * (PC 1.6.27 item N4).
 *
 * Extraction of a ZIP entry is strictly in-order: the deflate stream only
 * decompresses sequentially, and TDLib's `ReadFilePart` gate only ever serves
 * the contiguous downloaded prefix (report 52), so the produced bytes grow as
 * a prefix of the entry. Most media containers (MKV, WebM, AVI, MP3, FLAC,
 * fast-start MP4) are playable from such a prefix — the player reads the
 * growing file and the rest arrives behind it. The one common exception is an
 * MP4/MOV whose `moov` index box sits AFTER `mdat`: no player can start until
 * the moov has arrived, which for an in-order stream means the whole entry.
 * That case must be reported honestly ([DelayedUntilComplete]) instead of
 * presenting a silent stall — and it must never become an excuse to download
 * the whole ZIP: only this entry's bytes are ever fetched, in order.
 */
sealed interface ProgressiveStartDecision {
    /** The produced prefix is enough for a player to start. */
    data object StartNow : ProgressiveStartDecision

    /** Not enough of the entry has been produced yet; keep fetching in order. */
    data object WaitForMore : ProgressiveStartDecision

    /**
     * This entry cannot start until it has been produced completely
     * (an MP4-family file whose `moov` box follows its `mdat` box).
     * [reason] is user-facing wording for the loading state.
     */
    data class DelayedUntilComplete(val reason: String) : ProgressiveStartDecision
}

/**
 * Pure start policy over the produced prefix of one entry. Fed incrementally
 * by [ProgressiveStartTracker]; has no I/O of its own so the exact field
 * cases (fast-start vs moov-at-end MP4, partial box headers, ZIP64-style
 * 64-bit box sizes) are unit-testable without a player or a network.
 */
object ProgressiveStartPolicy {

    /** Prefix a non-MP4 container needs before a player is handed the file. */
    const val DEFAULT_PREFIX_BYTES: Long = 512L * 1024

    /** How much of the produced prefix is retained for container sniffing. */
    const val RETAINED_PREFIX_BYTES: Int = 1024 * 1024

    private val MP4_EXTENSIONS = setOf("mp4", "m4v", "m4a", "mov", "3gp", "3g2", "mp4a")

    const val MOOV_AT_END_REASON: String =
        "This video's index (moov) is at the end of the file, so it can only " +
            "start once the whole entry has loaded."

    fun isMp4Family(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in MP4_EXTENSIONS

    /**
     * Decides from the produced [prefix] (the first `prefix.size` bytes of the
     * entry, possibly shorter than [writtenBytes] once the retained window is
     * full), how many bytes have been produced so far, and the entry's total
     * size.
     */
    fun evaluate(
        fileName: String,
        prefix: ByteArray,
        writtenBytes: Long,
        entrySize: Long
    ): ProgressiveStartDecision {
        if (entrySize <= 0L || writtenBytes >= entrySize) return ProgressiveStartDecision.StartNow
        if (!isMp4Family(fileName)) {
            return if (writtenBytes >= minOf(entrySize, DEFAULT_PREFIX_BYTES)) {
                ProgressiveStartDecision.StartNow
            } else {
                ProgressiveStartDecision.WaitForMore
            }
        }
        return evaluateMp4(prefix, writtenBytes, entrySize)
    }

    /**
     * Walks the top-level MP4 boxes visible in [prefix]. Box headers beyond
     * the retained prefix are judged by [writtenBytes] only when their start
     * offset is known exactly from an unbroken walk.
     */
    private fun evaluateMp4(
        prefix: ByteArray,
        writtenBytes: Long,
        entrySize: Long
    ): ProgressiveStartDecision {
        var offset = 0L
        while (true) {
            val remainingInPrefix = prefix.size - offset
            if (remainingInPrefix < 8) {
                // The next box header is not fully in hand yet: the walk ran
                // past the retained prefix (e.g. a large `free` box before
                // the moov). The moov has NOT been seen, so starting now
                // would hand the player an index-less file — WaitForMore.
                // This cannot stall: evaluate() returns StartNow
                // unconditionally once writtenBytes >= entrySize, so the
                // worst case degrades to wait-for-complete (MF-1).
                return ProgressiveStartDecision.WaitForMore
            }
            val headerAt = offset.toInt()
            val size32 = u32be(prefix, headerAt)
            val type = boxType(prefix, headerAt + 4)
            val headerSize: Long
            val boxSize: Long
            when {
                size32 == 1L -> {
                    if (remainingInPrefix < 16) return ProgressiveStartDecision.WaitForMore
                    boxSize = u64be(prefix, headerAt + 8)
                    headerSize = 16L
                }
                size32 == 0L -> {
                    // Box extends to end of file.
                    boxSize = entrySize - offset
                    headerSize = 8L
                }
                else -> {
                    boxSize = size32
                    headerSize = 8L
                }
            }
            if (boxSize < headerSize) {
                // Not a plausible box walk (damaged or not really MP4): let
                // the player judge the prefix instead of stalling on a parse.
                return ProgressiveStartDecision.StartNow
            }
            when (type) {
                "moov" -> {
                    // The index box has begun. Starting is only safe once the
                    // whole moov is on disk: a player parsing a truncated moov
                    // errors out instead of waiting for the growing file.
                    return if (writtenBytes >= offset + boxSize) {
                        ProgressiveStartDecision.StartNow
                    } else {
                        ProgressiveStartDecision.WaitForMore
                    }
                }
                "mdat" -> return ProgressiveStartDecision.DelayedUntilComplete(MOOV_AT_END_REASON)
            }
            val next = offset + boxSize
            if (next <= offset) return ProgressiveStartDecision.StartNow
            if (next > writtenBytes && next > prefix.size) {
                // This box (ftyp/free/skip/wide/…) is still arriving and the
                // following box's header cannot be located yet.
                return ProgressiveStartDecision.WaitForMore
            }
            if (next >= entrySize) return ProgressiveStartDecision.StartNow
            offset = next
        }
    }

    private fun u32be(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 4) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun u64be(bytes: ByteArray, at: Int): Long {
        var value = 0L
        for (i in 0 until 8) value = (value shl 8) or (bytes[at + i].toLong() and 0xFF)
        return value
    }

    private fun boxType(bytes: ByteArray, at: Int): String =
        String(bytes, at, 4, Charsets.US_ASCII)
}

/**
 * Incremental tracker fed with every chunk an extractor produces, in order.
 * Retains the first [ProgressiveStartPolicy.RETAINED_PREFIX_BYTES] produced
 * bytes for container sniffing and latches the first decisive verdict
 * ([ProgressiveStartDecision.StartNow] or
 * [ProgressiveStartDecision.DelayedUntilComplete]): once a player has been
 * handed the file (or the user has been told start is delayed), later chunks
 * never re-decide.
 */
class ProgressiveStartTracker(
    private val fileName: String,
    private val entrySize: Long
) {
    private var retained = ByteArray(0)
    private var produced = 0L
    private var latched: ProgressiveStartDecision? = null

    /** Total bytes produced so far. */
    val producedBytes: Long get() = produced

    /** The latched decisive verdict, or null while more data is needed. */
    val decision: ProgressiveStartDecision? get() = latched

    /**
     * Records one produced chunk ([bytes] at [offset] for [length], in
     * production order) and returns the decisive verdict if this chunk
     * produced it, else null.
     */
    fun onChunk(bytes: ByteArray, offset: Int, length: Int): ProgressiveStartDecision? {
        if (length > 0 && retained.size < ProgressiveStartPolicy.RETAINED_PREFIX_BYTES) {
            val take = minOf(length, ProgressiveStartPolicy.RETAINED_PREFIX_BYTES - retained.size)
            if (take > 0) {
                val grown = ByteArray(retained.size + take)
                retained.copyInto(grown)
                bytes.copyInto(grown, retained.size, offset, offset + take)
                retained = grown
            }
        }
        produced += length
        return evaluateNow()
    }

    /** Re-evaluates without new bytes (e.g. at end of extraction). */
    fun evaluateNow(): ProgressiveStartDecision? {
        if (latched != null) return null
        val verdict = ProgressiveStartPolicy.evaluate(fileName, retained, produced, entrySize)
        if (verdict == ProgressiveStartDecision.WaitForMore) return null
        latched = verdict
        return verdict
    }
}
