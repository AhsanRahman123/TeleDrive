package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.media.MediaByteSource
import java.io.OutputStream
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.Inflater

/**
 * Reads a zip archive through ranged reads only.
 *
 * Range discipline (the whole point of this class): [entries] reads only the file tail
 * (at most [TAIL_WINDOW] bytes), the ZIP64 records when present, and the central directory
 * range. [extract] reads only the chosen entry's local header and its compressed data range.
 * No code path ever reads the whole underlying file for a normal archive, so this works
 * against a remote copy in Telegram with no local copy of the zip present.
 */
class ZipArchiveReader(private val source: MediaByteSource) {

    suspend fun entries(): List<ZipEntryInfo> {
        val fileSize = source.size()
        if (fileSize < EOCD_MIN_LENGTH) throw ZipArchiveException.NotAZip()

        val tailLength = minOf(fileSize, TAIL_WINDOW.toLong()).toInt()
        val tailStart = fileSize - tailLength
        val tail = readExact(tailStart, tailLength, missingIsNotAZip = true)

        val eocdIndex = findEocd(tail) ?: throw ZipArchiveException.NotAZip()
        val eocdAbsolute = tailStart + eocdIndex

        var entryCount = u16(tail, eocdIndex + 10).toLong()
        var cdSize = u32(tail, eocdIndex + 12)
        var cdOffset = u32(tail, eocdIndex + 16)
        val diskNumber = u16(tail, eocdIndex + 4)
        val cdStartDisk = u16(tail, eocdIndex + 6)
        val entriesThisDisk = u16(tail, eocdIndex + 8)
        if (diskNumber != 0 || cdStartDisk != 0) throw ZipArchiveException.MultiDisk()
        if (entriesThisDisk != 0xFFFF && entriesThisDisk.toLong() != entryCount) {
            throw ZipArchiveException.MultiDisk()
        }

        val zip64Saturated = entryCount == 0xFFFFL || cdSize == SATURATED_32 || cdOffset == SATURATED_32
        if (zip64Saturated) {
            val zip64 = readZip64Eocd(eocdAbsolute, fileSize)
            entryCount = zip64.entryCount
            cdSize = zip64.cdSize
            cdOffset = zip64.cdOffset
        }

        if (entryCount < 0 || entryCount > MAX_ENTRIES) {
            throw ZipArchiveException.Corrupt("it claims $entryCount entries")
        }
        if (cdOffset < 0 || cdSize < 0 || cdOffset + cdSize > fileSize) {
            throw ZipArchiveException.Corrupt("its central directory lies outside the file")
        }
        if (cdSize < entryCount * CENTRAL_HEADER_MIN_LENGTH) {
            throw ZipArchiveException.Corrupt("its central directory is too small for its entries")
        }

        return readCentralDirectory(cdOffset, cdSize, entryCount.toInt())
    }

    suspend fun extract(entry: ZipEntryInfo, out: OutputStream) {
        if (entry.isEncrypted) throw ZipArchiveException.EncryptedEntry(entry.name)
        if (entry.method != METHOD_STORED && entry.method != METHOD_DEFLATED) {
            throw ZipArchiveException.UnsupportedMethod(entry.name, entry.method)
        }
        if (entry.compressedBytes < 0 || entry.sizeBytes < 0 || entry.localHeaderOffset < 0) {
            throw ZipArchiveException.Corrupt("entry \"${entry.name}\" has invalid sizes")
        }

        val header = readExact(entry.localHeaderOffset, LOCAL_HEADER_LENGTH)
        if (u32(header, 0) != LOCAL_HEADER_SIG) {
            throw ZipArchiveException.Corrupt("entry \"${entry.name}\" has no local header")
        }
        val nameLength = u16(header, 26)
        val extraLength = u16(header, 28)
        var position = entry.localHeaderOffset + LOCAL_HEADER_LENGTH + nameLength + extraLength

        val crc = CRC32()
        var produced = 0L
        var remaining = entry.compressedBytes

        fun accountFor(bytes: ByteArray, offset: Int, length: Int) {
            produced += length
            if (produced > entry.sizeBytes) {
                throw ZipArchiveException.Corrupt(
                    "entry \"${entry.name}\" expands past its recorded size"
                )
            }
            crc.update(bytes, offset, length)
            out.write(bytes, offset, length)
        }

        if (entry.method == METHOD_STORED) {
            while (remaining > 0) {
                val chunk = readChunk(position, remaining)
                accountFor(chunk, 0, chunk.size)
                position += chunk.size
                remaining -= chunk.size
            }
        } else {
            val inflater = Inflater(true)
            try {
                val plain = ByteArray(PLAIN_BUFFER_SIZE)
                // Exit as soon as the deflate stream finishes, whatever `remaining` says:
                // a finished inflater with unconsumed input returns 0 from inflate()
                // forever, so keying the loop on `remaining > 0` as well would busy-spin.
                while (!inflater.finished()) {
                    if (inflater.needsInput()) {
                        if (remaining <= 0) {
                            throw ZipArchiveException.Corrupt(
                                "entry \"${entry.name}\" ends before its data is complete"
                            )
                        }
                        val chunk = readChunk(position, remaining)
                        position += chunk.size
                        remaining -= chunk.size
                        inflater.setInput(chunk)
                    }
                    val count = try {
                        inflater.inflate(plain)
                    } catch (failure: Exception) {
                        throw ZipArchiveException.Corrupt(
                            "entry \"${entry.name}\" has damaged compressed data"
                        )
                    }
                    if (count > 0) {
                        accountFor(plain, 0, count)
                    } else if (!inflater.finished() && !inflater.needsInput()) {
                        // No progress and not waiting for more input (e.g. a dictionary
                        // request or a stream state inflate() can never advance): refuse
                        // instead of spinning.
                        throw ZipArchiveException.Corrupt(
                            "entry \"${entry.name}\" has damaged compressed data"
                        )
                    }
                    // count == 0 with needsInput() loops back to feed the next chunk (or
                    // fail above when none is left); count == 0 with finished() exits.
                }
                if (remaining > 0 || inflater.remaining > 0) {
                    // The stream ended before the central directory's compressed size was
                    // consumed: the recorded size overstates the real deflate stream.
                    throw ZipArchiveException.Corrupt(
                        "entry \"${entry.name}\" has damaged compressed data"
                    )
                }
            } finally {
                inflater.end()
            }
        }

        if (produced != entry.sizeBytes) {
            throw ZipArchiveException.Corrupt(
                "entry \"${entry.name}\" produced $produced of ${entry.sizeBytes} bytes"
            )
        }
        if (crc.value != entry.crc32) throw ZipArchiveException.CrcMismatch(entry.name)
    }

    // --- central directory -------------------------------------------------

    private data class Zip64Eocd(val entryCount: Long, val cdSize: Long, val cdOffset: Long)

    private suspend fun readZip64Eocd(eocdAbsolute: Long, fileSize: Long): Zip64Eocd {
        if (eocdAbsolute < ZIP64_LOCATOR_LENGTH) {
            throw ZipArchiveException.Corrupt("its ZIP64 locator is missing")
        }
        val locator = readExact(eocdAbsolute - ZIP64_LOCATOR_LENGTH, ZIP64_LOCATOR_LENGTH)
        if (u32(locator, 0) != ZIP64_LOCATOR_SIG) {
            throw ZipArchiveException.Corrupt("its ZIP64 locator is missing")
        }
        if (u32(locator, 4) != 0L) throw ZipArchiveException.MultiDisk()
        if (u32(locator, 16) > 1L) throw ZipArchiveException.MultiDisk()
        val recordOffset = u64(locator, 8)
        if (recordOffset < 0 || recordOffset + ZIP64_EOCD_MIN_LENGTH > fileSize) {
            throw ZipArchiveException.Corrupt("its ZIP64 record lies outside the file")
        }
        val record = readExact(recordOffset, ZIP64_EOCD_MIN_LENGTH)
        if (u32(record, 0) != ZIP64_EOCD_SIG) {
            throw ZipArchiveException.Corrupt("its ZIP64 record is missing")
        }
        if (u32(record, 16) != 0L || u32(record, 20) != 0L) throw ZipArchiveException.MultiDisk()
        val entriesThisDisk = u64(record, 24)
        val totalEntries = u64(record, 32)
        if (entriesThisDisk != totalEntries) throw ZipArchiveException.MultiDisk()
        return Zip64Eocd(
            entryCount = totalEntries,
            cdSize = u64(record, 40),
            cdOffset = u64(record, 48)
        )
    }

    private suspend fun readCentralDirectory(
        cdOffset: Long,
        cdSize: Long,
        expectedCount: Int
    ): List<ZipEntryInfo> {
        val reader = SequentialReader(source, cdOffset, cdOffset + cdSize)
        val entries = ArrayList<ZipEntryInfo>(expectedCount)
        repeat(expectedCount) {
            val fixed = reader.readBytes(CENTRAL_HEADER_MIN_LENGTH)
            if (u32(fixed, 0) != CENTRAL_HEADER_SIG) {
                throw ZipArchiveException.Corrupt("a central directory record is damaged")
            }
            val flags = u16(fixed, 8)
            val method = u16(fixed, 10)
            val modifiedAtMillis = dosDateTimeToEpochMillis(u16(fixed, 12), u16(fixed, 14))
            val crc = u32(fixed, 16)
            var compressed = u32(fixed, 20)
            var uncompressed = u32(fixed, 24)
            val nameLength = u16(fixed, 28)
            val extraLength = u16(fixed, 30)
            val commentLength = u16(fixed, 32)
            val diskStart = u16(fixed, 34)
            var localHeaderOffset = u32(fixed, 42)
            if (diskStart != 0 && diskStart != 0xFFFF) throw ZipArchiveException.MultiDisk()

            val nameBytes = reader.readBytes(nameLength)
            val extra = reader.readBytes(extraLength)
            reader.skip(commentLength)

            // ZIP64 extra field: values appear, in order, for each saturated field.
            var extraCursor = 0
            var zip64DiskStart = -1L
            while (extraCursor + 4 <= extra.size) {
                val fieldId = u16(extra, extraCursor)
                val fieldSize = u16(extra, extraCursor + 2)
                val valueStart = extraCursor + 4
                if (valueStart + fieldSize > extra.size) {
                    throw ZipArchiveException.Corrupt("an extra field is damaged")
                }
                if (fieldId == EXTRA_ZIP64) {
                    var p = valueStart
                    val end = valueStart + fieldSize
                    fun next64(): Long {
                        if (p + 8 > end) {
                            throw ZipArchiveException.Corrupt("a ZIP64 extra field is damaged")
                        }
                        val value = u64(extra, p)
                        p += 8
                        return value
                    }
                    if (uncompressed == SATURATED_32) uncompressed = next64()
                    if (compressed == SATURATED_32) compressed = next64()
                    if (localHeaderOffset == SATURATED_32) localHeaderOffset = next64()
                    if (diskStart == 0xFFFF) {
                        if (p + 4 > end) {
                            throw ZipArchiveException.Corrupt("a ZIP64 extra field is damaged")
                        }
                        zip64DiskStart = u32(extra, p)
                    }
                }
                extraCursor = valueStart + fieldSize
            }
            if (diskStart == 0xFFFF) {
                // Missing field first: zip64DiskStart stays -1 when the saturated disk
                // start has no ZIP64 value, and -1 != 0 would otherwise misclassify it
                // as MultiDisk before the Corrupt branch could ever run.
                if (zip64DiskStart < 0) {
                    throw ZipArchiveException.Corrupt("an entry is missing its ZIP64 disk number")
                }
                if (zip64DiskStart != 0L) throw ZipArchiveException.MultiDisk()
            }
            val charset = if (flags and FLAG_UTF8 != 0) Charsets.UTF_8 else CP437
            val name = String(nameBytes, charset)
            entries += ZipEntryInfo(
                name = name,
                isDirectory = name.endsWith('/') || name.endsWith('\\'),
                sizeBytes = uncompressed,
                compressedBytes = compressed,
                method = method,
                crc32 = crc,
                localHeaderOffset = localHeaderOffset,
                isEncrypted = flags and FLAG_ENCRYPTED != 0,
                modifiedAtMillis = modifiedAtMillis
            )
        }
        return entries
    }

    // --- low-level reading ---------------------------------------------------

    /** Reads one chunk of at most [CHUNK_SIZE] bytes; never returns empty while bytes remain. */
    private suspend fun readChunk(position: Long, remaining: Long): ByteArray {
        val wanted = minOf(remaining, CHUNK_SIZE.toLong()).toInt()
        val builder = ByteCollector(wanted)
        var filled = 0
        while (filled < wanted) {
            val piece = source.read(position + filled, wanted - filled)
            if (piece.isEmpty()) {
                throw ZipArchiveException.Corrupt("the file ends earlier than the archive records")
            }
            builder.append(piece)
            filled += piece.size
        }
        return builder.toByteArray()
    }

    /** Reads exactly [count] bytes at [position], looping over short reads. */
    private suspend fun readExact(position: Long, count: Int, missingIsNotAZip: Boolean = false): ByteArray {
        val builder = ByteCollector(count)
        var filled = 0
        while (filled < count) {
            val piece = source.read(position + filled, count - filled)
            if (piece.isEmpty()) {
                if (missingIsNotAZip) throw ZipArchiveException.NotAZip()
                throw ZipArchiveException.Corrupt("the file ends earlier than the archive records")
            }
            builder.append(piece)
            filled += piece.size
        }
        return builder.toByteArray()
    }

    /** Sequential reader over one byte range, refilling in [CHUNK_SIZE] blocks. */
    private class SequentialReader(
        private val source: MediaByteSource,
        start: Long,
        private val end: Long
    ) {
        private var buffer = ByteArray(0)
        private var bufferPosition = start
        private var cursor = start

        suspend fun readBytes(count: Int): ByteArray {
            val out = ByteArray(count)
            var filled = 0
            while (filled < count) {
                if (cursor >= end) {
                    throw ZipArchiveException.Corrupt("the central directory is truncated")
                }
                if (cursor < bufferPosition || cursor >= bufferPosition + buffer.size) refill()
                val available = (bufferPosition + buffer.size - cursor).toInt()
                val take = minOf(available, count - filled)
                buffer.copyInto(out, filled, (cursor - bufferPosition).toInt(), (cursor - bufferPosition).toInt() + take)
                filled += take
                cursor += take
            }
            return out
        }

        suspend fun skip(count: Int) {
            if (count < 0 || cursor + count > end) {
                throw ZipArchiveException.Corrupt("the central directory is truncated")
            }
            cursor += count
        }

        private suspend fun refill() {
            val wanted = minOf(CHUNK_SIZE.toLong(), end - cursor).toInt()
            val builder = ByteCollector(wanted)
            var filled = 0
            while (filled < wanted) {
                val piece = source.read(cursor + filled, wanted - filled)
                if (piece.isEmpty()) {
                    throw ZipArchiveException.Corrupt("the central directory is truncated")
                }
                builder.append(piece)
                filled += piece.size
            }
            buffer = builder.toByteArray()
            bufferPosition = cursor
        }
    }

    /** Small growable accumulator so short source reads don't force quadratic copying. */
    private class ByteCollector(expected: Int) {
        private var bytes = ByteArray(maxOf(expected, 1))
        private var size = 0

        fun append(piece: ByteArray) {
            if (size + piece.size > bytes.size) {
                bytes = bytes.copyOf(maxOf(bytes.size * 2, size + piece.size))
            }
            piece.copyInto(bytes, size)
            size += piece.size
        }

        fun toByteArray(): ByteArray = bytes.copyOf(size)
    }

    companion object {
        private const val EOCD_SIG = 0x06054b50L
        private const val ZIP64_EOCD_SIG = 0x06064b50L
        private const val ZIP64_LOCATOR_SIG = 0x07064b50L
        private const val CENTRAL_HEADER_SIG = 0x02014b50L
        private const val LOCAL_HEADER_SIG = 0x04034b50L

        private const val EOCD_MIN_LENGTH = 22
        private const val CENTRAL_HEADER_MIN_LENGTH = 46
        private const val LOCAL_HEADER_LENGTH = 30
        private const val ZIP64_LOCATOR_LENGTH = 20
        private const val ZIP64_EOCD_MIN_LENGTH = 56

        /** 22-byte EOCD + 65,535-byte maximum comment + slack, per the task's tail bound. */
        private const val TAIL_WINDOW = 65_600
        private const val MAX_ENTRIES = 200_000
        private const val CHUNK_SIZE = 512 * 1024
        private const val PLAIN_BUFFER_SIZE = 64 * 1024

        private const val SATURATED_32 = 0xFFFFFFFFL
        private const val EXTRA_ZIP64 = 0x0001
        private const val FLAG_ENCRYPTED = 0x0001
        private const val FLAG_UTF8 = 0x0800

        private const val METHOD_STORED = 0
        private const val METHOD_DEFLATED = 8

        /**
         * CP437 lives in the `jdk.charsets` module, which a packaged runtime can lack;
         * resolve it lazily and fall back to ISO-8859-1 (a standard charset, always
         * present) for non-UTF-8 names rather than failing the whole listing.
         */
        private val CP437: Charset by lazy {
            runCatching { Charset.forName("IBM437") }.getOrElse { Charsets.ISO_8859_1 }
        }

        internal fun u16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        internal fun u32(bytes: ByteArray, offset: Int): Long =
            (bytes[offset].toLong() and 0xFF) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 8) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 24)

        internal fun u64(bytes: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 7 downTo 0) {
                value = (value shl 8) or (bytes[offset + i].toLong() and 0xFF)
            }
            return value
        }

        /** Finds the EOCD record whose comment length lands exactly on the end of the tail. */
        internal fun findEocd(tail: ByteArray): Int? {
            var index = tail.size - EOCD_MIN_LENGTH
            while (index >= 0) {
                if (u32(tail, index) == EOCD_SIG &&
                    index + EOCD_MIN_LENGTH + u16(tail, index + 20) == tail.size
                ) {
                    return index
                }
                index--
            }
            return null
        }
    }
}
