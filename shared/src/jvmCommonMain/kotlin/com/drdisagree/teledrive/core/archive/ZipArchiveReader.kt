package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.media.MediaByteSource
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlinx.coroutines.CancellationException

/** One central-directory record of a ZIP archive. Sizes and offsets are the true (ZIP64-resolved) values. */
data class ZipEntryInfo(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val compressedBytes: Long,
    val method: Int,
    val crc32: Long,
    val localHeaderOffset: Long,
    val isEncrypted: Boolean,
    /**
     * The entry's last-modified time from the central directory's DOS date/time fields,
     * as epoch milliseconds (UTC interpretation of the timezone-less DOS value), or 0 when
     * the archive carries no valid timestamp for this entry.
     */
    val modifiedAtMillis: Long = 0L
)

/**
 * Converts a ZIP DOS date/time pair to epoch milliseconds, reading the DOS value as UTC
 * (DOS timestamps carry no timezone; a fixed interpretation keeps ordering stable across
 * devices). Returns 0 for the all-zero "no timestamp" value and for out-of-range fields,
 * so callers can treat 0 as "unknown" and place such entries stably instead of showing a
 * garbage 1980 date.
 */
internal fun dosDateTimeToEpochMillis(dosTime: Int, dosDate: Int): Long {
    if (dosDate == 0) return 0L
    val day = dosDate and 0x1F
    val month = (dosDate shr 5) and 0x0F
    val year = ((dosDate shr 9) and 0x7F) + 1980
    val second = (dosTime and 0x1F) * 2
    val minute = (dosTime shr 5) and 0x3F
    val hour = (dosTime shr 11) and 0x1F
    if (month !in 1..12 || day !in 1..31 || minute > 59 || hour > 23 || second > 59) return 0L
    return runCatching {
        java.time.LocalDateTime.of(year, month, day, hour, minute, second)
            .toEpochSecond(java.time.ZoneOffset.UTC) * 1000L
    }.getOrDefault(0L)
}

enum class ArchiveErrorKind {
    NOT_A_ZIP,
    CORRUPT,
    MULTI_DISK,
    ENCRYPTED_ENTRY,
    UNSUPPORTED_METHOD,
    CRC_MISMATCH,
    ENTRY_NOT_FOUND,
    ENCRYPTED_FILE,
    SPLIT_FILE,
    NO_REMOTE_COPY,
    SAVE_FAILED,

    /** The temp dir cannot hold the extracted entry (vc181 B11 preflight). */
    INSUFFICIENT_SPACE
}

/** A failure the UI can show to the user verbatim; [message] is deliberately plain and specific. */
class ArchiveException(
    val kind: ArchiveErrorKind,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

/**
 * Reads a ZIP archive through ranged reads only.
 *
 * Listing touches just the file tail (the End Of Central Directory) and the central directory
 * itself; extraction touches just one entry's local header and its compressed range. No code path
 * here ever reads the whole archive, which is what makes browsing a multi-gigabyte remote ZIP in
 * Telegram cheap. There is intentionally no whole-file fallback.
 */
class ZipArchiveReader(
    private val source: MediaByteSource
) {

    suspend fun entries(): List<ZipEntryInfo> {
        val fileSize = source.size()
        if (fileSize < EOCD_MIN_SIZE) {
            throw ArchiveException(ArchiveErrorKind.NOT_A_ZIP, "This file is not a ZIP archive.")
        }

        val tailSize = minOf(fileSize, TAIL_WINDOW_BYTES)
        val tailStart = fileSize - tailSize
        val tail = readExactly(tailStart, tailSize.toInt())
        val eocdIndex = findEocd(tail)
            ?: throw ArchiveException(ArchiveErrorKind.NOT_A_ZIP, "This file is not a ZIP archive.")
        val eocdPosition = tailStart + eocdIndex
        val eocd = LeReader(tail, eocdIndex)

        val diskNumber = eocd.u16(4)
        val cdDisk = eocd.u16(6)
        val entriesThisDisk = eocd.u16(8)
        var totalEntries = eocd.u16(10).toLong()
        var cdSize = eocd.u32(12)
        var cdOffset = eocd.u32(16)
        if (diskNumber != 0 || cdDisk != 0) throw multiDisk()

        if (totalEntries == SATURATED_U16.toLong() || cdSize == SATURATED_U32 || cdOffset == SATURATED_U32) {
            val zip64 = readZip64Eocd(tail, tailStart, eocdPosition)
            totalEntries = zip64.totalEntries
            cdSize = zip64.cdSize
            cdOffset = zip64.cdOffset
        } else if (entriesThisDisk.toLong() != totalEntries) {
            throw multiDisk()
        }

        if (totalEntries > MAX_ENTRIES) {
            throw ArchiveException(
                ArchiveErrorKind.CORRUPT,
                "This ZIP archive has too many entries to open here ($totalEntries)."
            )
        }
        if (cdOffset < 0 || cdSize < 0 || cdSize > MAX_CD_BYTES || cdOffset > fileSize - cdSize) {
            throw corrupt("The ZIP central directory points outside the file.")
        }

        val directory = readExactly(cdOffset, cdSize.toInt())
        return parseCentralDirectory(directory, totalEntries.toInt())
    }

    suspend fun extract(entry: ZipEntryInfo, out: OutputStream) {
        if (entry.isEncrypted) {
            throw ArchiveException(
                ArchiveErrorKind.ENCRYPTED_ENTRY,
                "\"${entry.name}\" is password-protected, so it cannot be extracted here."
            )
        }
        if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATE) {
            throw ArchiveException(
                ArchiveErrorKind.UNSUPPORTED_METHOD,
                "\"${entry.name}\" uses an unsupported compression method (${entry.method}), so it cannot be extracted here."
            )
        }
        if (entry.isDirectory) {
            throw corrupt("\"${entry.name}\" is a folder, not a file.")
        }
        val fileSize = source.size()
        if (entry.localHeaderOffset < 0 || entry.localHeaderOffset + LOCAL_HEADER_SIZE > fileSize) {
            throw corrupt("The local header of \"${entry.name}\" is outside the file.")
        }
        val header = LeReader(readExactly(entry.localHeaderOffset, LOCAL_HEADER_SIZE), 0)
        if (header.u32(0) != LOCAL_HEADER_SIG) {
            throw corrupt("The local header of \"${entry.name}\" is damaged.")
        }
        val dataStart = entry.localHeaderOffset + LOCAL_HEADER_SIZE + header.u16(26) + header.u16(28)
        if (dataStart + entry.compressedBytes > fileSize) {
            throw corrupt("\"${entry.name}\" extends past the end of the archive.")
        }

        val crc = CRC32()
        var produced = 0L
        val inflater = if (entry.method == METHOD_DEFLATE) Inflater(true) else null
        try {
            var position = dataStart
            var remaining = entry.compressedBytes
            val outBuffer = ByteArray(OUTPUT_BUFFER_SIZE)

            fun emit(count: Int) {
                out.write(outBuffer, 0, count)
                crc.update(outBuffer, 0, count)
                produced += count
                if (produced > entry.sizeBytes) {
                    throw corrupt("\"${entry.name}\" expands beyond its recorded size.")
                }
            }

            // Bounded decoding: every loop below either consumes input, produces output, reaches
            // the end of the deflate stream, or throws. A corrupt stream can never spin it.
            while (remaining > 0) {
                if (inflater != null && inflater.finished()) {
                    // The deflate stream ended before the recorded compressed size was consumed:
                    // the central directory and the data disagree, so the entry is corrupt.
                    throw corrupt(
                        "The compressed data of \"${entry.name}\" ended before its recorded size was used."
                    )
                }
                val want = minOf(CHUNK_BYTES.toLong(), remaining).toInt()
                val chunk = source.read(position, want)
                if (chunk.isEmpty()) {
                    throw corrupt("The archive ended unexpectedly while reading \"${entry.name}\".")
                }
                val consumed = minOf(chunk.size.toLong(), remaining).toInt()
                position += consumed
                remaining -= consumed
                if (inflater == null) {
                    out.write(chunk, 0, consumed)
                    crc.update(chunk, 0, consumed)
                    produced += consumed
                } else {
                    inflater.setInput(chunk, 0, consumed)
                    // An inflate() that produces nothing is NOT by itself corruption: when a
                    // chunk ends mid-symbol or mid-block-header, the inflater simply needs the
                    // next chunk. That case breaks to the outer loop, which either feeds the
                    // next chunk (input remains) or, after the loop, reports a truncated entry
                    // (no input remains and the stream never finished). Only a no-progress
                    // inflate that neither needs input nor finished is genuine damage.
                    while (!inflater.finished()) {
                        val count = inflater.inflate(outBuffer)
                        when {
                            count > 0 -> emit(count)
                            inflater.needsDictionary() -> throw corrupt(
                                "\"${entry.name}\" needs a preset dictionary and cannot be extracted."
                            )
                            inflater.finished() -> break
                            inflater.needsInput() -> break
                            else -> throw corrupt(
                                "The compressed data of \"${entry.name}\" is damaged and cannot be extracted."
                            )
                        }
                    }
                }
                if (produced > entry.sizeBytes) {
                    throw corrupt("\"${entry.name}\" expands beyond its recorded size.")
                }
            }
            if (inflater != null) {
                // Drain anything the inflater still holds after the last input chunk. Each pass
                // must produce, finish, or run out of input - otherwise it is a typed error.
                while (!inflater.finished() && !inflater.needsInput()) {
                    val count = inflater.inflate(outBuffer)
                    when {
                        count > 0 -> emit(count)
                        inflater.finished() -> break
                        else -> throw corrupt(
                            "The compressed data of \"${entry.name}\" is truncated or damaged."
                        )
                    }
                }
                if (!inflater.finished()) {
                    throw corrupt("The compressed data of \"${entry.name}\" is truncated or damaged.")
                }
            }
        } catch (failure: CancellationException) {
            // Genuine cancellation (a Transfers stop, teardown) is NOT
            // corruption (vc183): rethrow untouched. Wrapping it as
            // ArchiveException made a user stop look like archive damage to
            // every caller, bypassed every CancellationException branch
            // downstream, and — for a batch step running as an async child —
            // delivered the ArchiveException to the parent scope's uncaught
            // handler, which on Android kills the process (the vc182 field
            // force-close on repeated Cancel).
            throw failure
        } catch (failure: ArchiveException) {
            throw failure
        } catch (failure: kotlinx.coroutines.CancellationException) {
            // vc183 false-corruption fix: cancellation is NOT corruption. A
            // superseded pager step cancels its extraction; the suspending
            // source read then throws CancellationException inside the loop
            // above, and wrapping it as CORRUPT made the switch path report
            // "archive is corrupted" for entries that open fine individually
            // (individual open is never cancelled by a successor). Rethrow
            // unwrapped so callers' CancellationException handling applies —
            // the same pattern TelegramMediaByteSource already uses.
            throw failure
        } catch (failure: Exception) {
            throw ArchiveException(
                ArchiveErrorKind.CORRUPT,
                "The compressed data of \"${entry.name}\" is damaged and cannot be extracted.",
                failure
            )
        } finally {
            inflater?.end()
        }

        if (produced != entry.sizeBytes) {
            throw corrupt(
                "\"${entry.name}\" produced $produced bytes but the archive records ${entry.sizeBytes}."
            )
        }
        if (crc.value != entry.crc32) {
            throw ArchiveException(
                ArchiveErrorKind.CRC_MISMATCH,
                "\"${entry.name}\" failed its integrity check (CRC mismatch), so the saved copy would be damaged. Nothing was saved."
            )
        }
        out.flush()
    }

    private fun parseCentralDirectory(directory: ByteArray, expected: Int): List<ZipEntryInfo> {
        val entries = ArrayList<ZipEntryInfo>(expected)
        var offset = 0
        while (entries.size < expected) {
            if (offset + CENTRAL_HEADER_SIZE > directory.size) {
                throw corrupt("The ZIP central directory is truncated.")
            }
            val header = LeReader(directory, offset)
            if (header.u32(0) != CENTRAL_HEADER_SIG) {
                throw corrupt("The ZIP central directory is damaged.")
            }
            val flags = header.u16(8)
            val method = header.u16(10)
            val modifiedAtMillis = dosDateTimeToEpochMillis(header.u16(12), header.u16(14))
            val crc = header.u32(16)
            var compressed = header.u32(20)
            var size = header.u32(24)
            val nameLength = header.u16(28)
            val extraLength = header.u16(30)
            val commentLength = header.u16(32)
            val diskStart = header.u16(34)
            var localOffset = header.u32(42)
            if (diskStart != 0) throw multiDisk()

            val nameStart = offset + CENTRAL_HEADER_SIZE
            val extraStart = nameStart + nameLength
            val recordEnd = extraStart + extraLength + commentLength
            if (recordEnd > directory.size) {
                throw corrupt("The ZIP central directory is truncated.")
            }
            val nameBytes = directory.copyOfRange(nameStart, extraStart)

            // ZIP64 extra field 0x0001 carries the saturated values in a fixed order:
            // uncompressed size, compressed size, local header offset, disk number.
            var cursor = extraStart
            val extraEnd = extraStart + extraLength
            while (cursor + 4 <= extraEnd) {
                val fieldId = LeReader(directory, cursor).u16(0)
                val fieldSize = LeReader(directory, cursor).u16(2)
                val valueStart = cursor + 4
                if (valueStart + fieldSize > extraEnd) throw corrupt("A ZIP64 extra field is truncated.")
                if (fieldId == EXTRA_ZIP64) {
                    var value = valueStart
                    val valueEnd = valueStart + fieldSize
                    if (size == SATURATED_U32) {
                        if (value + 8 > valueEnd) throw corrupt("A ZIP64 extra field is truncated.")
                        size = LeReader(directory, value).u64(0); value += 8
                    }
                    if (compressed == SATURATED_U32) {
                        if (value + 8 > valueEnd) throw corrupt("A ZIP64 extra field is truncated.")
                        compressed = LeReader(directory, value).u64(0); value += 8
                    }
                    if (localOffset == SATURATED_U32) {
                        if (value + 8 > valueEnd) throw corrupt("A ZIP64 extra field is truncated.")
                        localOffset = LeReader(directory, value).u64(0); value += 8
                    }
                    if (diskStart == SATURATED_U16) {
                        if (value + 4 > valueEnd) throw corrupt("A ZIP64 extra field is truncated.")
                        if (LeReader(directory, value).u32(0) != 0L) throw multiDisk()
                    }
                }
                cursor = valueStart + fieldSize
            }

            val charset: Charset = if (flags and FLAG_UTF8 != 0) Charsets.UTF_8 else ibm437
            val name = String(nameBytes, charset)
            entries += ZipEntryInfo(
                name = name,
                isDirectory = name.endsWith('/') || name.endsWith('\\'),
                sizeBytes = size,
                compressedBytes = compressed,
                method = method,
                crc32 = crc,
                localHeaderOffset = localOffset,
                isEncrypted = flags and FLAG_ENCRYPTED != 0,
                modifiedAtMillis = modifiedAtMillis
            )
            offset = recordEnd
        }
        return entries
    }

    private suspend fun readZip64Eocd(tail: ByteArray, tailStart: Long, eocdPosition: Long): Zip64Eocd {
        val locatorPosition = eocdPosition - ZIP64_LOCATOR_SIZE
        if (locatorPosition < 0) throw corrupt("The ZIP64 end record is missing.")
        val locatorBytes = if (locatorPosition >= tailStart) {
            tail.copyOfRange((locatorPosition - tailStart).toInt(), (locatorPosition - tailStart).toInt() + ZIP64_LOCATOR_SIZE)
        } else {
            readExactly(locatorPosition, ZIP64_LOCATOR_SIZE)
        }
        val locator = LeReader(locatorBytes, 0)
        if (locator.u32(0) != ZIP64_LOCATOR_SIG) throw corrupt("The ZIP64 end record is missing.")
        if (locator.u32(4) != 0L) throw multiDisk()
        if (locator.u32(16) > 1) throw multiDisk()
        val zip64Offset = locator.u64(8)

        val record = LeReader(readExactly(zip64Offset, ZIP64_EOCD_MIN_SIZE), 0)
        if (record.u32(0) != ZIP64_EOCD_SIG) throw corrupt("The ZIP64 end record is damaged.")
        if (record.u32(16) != 0L || record.u32(20) != 0L) throw multiDisk()
        val entriesThisDisk = record.u64(24)
        val totalEntries = record.u64(32)
        if (entriesThisDisk != totalEntries) throw multiDisk()
        return Zip64Eocd(totalEntries, record.u64(40), record.u64(48))
    }

    private fun findEocd(tail: ByteArray): Int? {
        var index = tail.size - EOCD_MIN_SIZE
        while (index >= 0) {
            if (tail[index] == 0x50.toByte() && tail[index + 1] == 0x4B.toByte() &&
                tail[index + 2] == 0x05.toByte() && tail[index + 3] == 0x06.toByte()
            ) {
                val commentLength = LeReader(tail, index).u16(20)
                if (index + EOCD_MIN_SIZE + commentLength == tail.size) return index
            }
            index--
        }
        return null
    }

    /** MediaByteSource may answer with short reads; loop until the range is whole or the data runs out. */
    private suspend fun readExactly(position: Long, count: Int): ByteArray {
        if (count <= 0) return ByteArray(0)
        val result = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val piece = source.read(position + filled, count - filled)
            if (piece.isEmpty()) throw corrupt("The archive is truncated.")
            val copied = minOf(piece.size, count - filled)
            piece.copyInto(result, filled, 0, copied)
            filled += copied
        }
        return result
    }

    private data class Zip64Eocd(val totalEntries: Long, val cdSize: Long, val cdOffset: Long)

    /** Bounds-checked little-endian reads over one fetched range; any overrun is corruption, not a crash. */
    private class LeReader(private val bytes: ByteArray, private val base: Int) {
        private fun at(offset: Int, width: Int): Int {
            val index = base + offset
            if (index < 0 || index + width > bytes.size) {
                throw ArchiveException(ArchiveErrorKind.CORRUPT, "The ZIP archive is truncated or damaged.")
            }
            return index
        }

        fun u16(offset: Int): Int {
            val i = at(offset, 2)
            return (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
        }

        fun u32(offset: Int): Long {
            val i = at(offset, 4)
            return (bytes[i].toLong() and 0xFF) or
                ((bytes[i + 1].toLong() and 0xFF) shl 8) or
                ((bytes[i + 2].toLong() and 0xFF) shl 16) or
                ((bytes[i + 3].toLong() and 0xFF) shl 24)
        }

        fun u64(offset: Int): Long {
            val i = at(offset, 8)
            var value = 0L
            for (shift in 0 until 8) {
                value = value or ((bytes[i + shift].toLong() and 0xFF) shl (shift * 8))
            }
            return value
        }
    }

    companion object {
        private const val LOCAL_HEADER_SIG = 0x04034b50L
        private const val CENTRAL_HEADER_SIG = 0x02014b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val LOCAL_HEADER_SIZE = 30
        private const val CENTRAL_HEADER_SIZE = 46
        private const val EOCD_MIN_SIZE = 22
        private const val ZIP64_LOCATOR_SIZE = 20
        private const val ZIP64_EOCD_MIN_SIZE = 56
        private const val TAIL_WINDOW_BYTES = 65_600L
        private const val MAX_ENTRIES = 200_000
        // 200,000 entries at ~160 bytes of central-directory record each is ≲32 MB, so
        // 64 MB leaves generous headroom while keeping the in-memory cap proportionate.
        private const val MAX_CD_BYTES = 64L * 1024 * 1024
        private const val CHUNK_BYTES = 512 * 1024
        private const val OUTPUT_BUFFER_SIZE = 64 * 1024
        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATE = 8
        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_UTF8 = 0x0800
        private const val EXTRA_ZIP64 = 0x0001
        private const val SATURATED_U16 = 0xFFFF
        private const val SATURATED_U32 = 0xFFFF_FFFFL

        /**
         * Resolved lazily and defensively: a platform without IBM437 must not fail a whole
         * listing over name decoding - ISO-8859-1 is the fallback for non-UTF-8 names.
         */
        private val ibm437: Charset by lazy {
            runCatching { Charset.forName("IBM437") }.getOrElse { Charsets.ISO_8859_1 }
        }

        private fun corrupt(message: String) = ArchiveException(ArchiveErrorKind.CORRUPT, message)
        private fun multiDisk() = ArchiveException(
            ArchiveErrorKind.MULTI_DISK,
            "This is a multi-disk (split) ZIP set, which cannot be opened from a single file. " +
                "Join the parts into one ZIP first, or download the whole archive."
        )
    }
}
