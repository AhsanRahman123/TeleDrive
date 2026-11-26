package com.drdisagree.teledrive.core.media

import java.io.File
import java.io.RandomAccessFile

/**
 * [MediaByteSource] over a plain local file: exact ranged reads through a
 * [RandomAccessFile], never a whole-file load, so [com.drdisagree.teledrive.core.archive.ZipArchiveReader]
 * can list a local archive with the same tail + central-directory discipline it uses
 * for remote copies. Reads are serialized on the file handle because
 * [RandomAccessFile] seek+read is not atomic; callers are sequential listings, so
 * this costs nothing in practice.
 */
class LocalFileByteSource(file: File) : MediaByteSource {

    private val randomAccess = RandomAccessFile(file, "r")
    private val length: Long = randomAccess.length()

    override suspend fun size(): Long = length

    override suspend fun read(position: Long, count: Int): ByteArray {
        if (count <= 0 || position >= length) return ByteArray(0)
        val toRead = minOf(count.toLong(), length - position).toInt()
        val result = ByteArray(toRead)
        synchronized(randomAccess) {
            randomAccess.seek(position)
            // Bounded by the file length above, so readFully cannot hit EOF early.
            randomAccess.readFully(result)
        }
        return result
    }

    override fun close() {
        runCatching { randomAccess.close() }
    }
}
