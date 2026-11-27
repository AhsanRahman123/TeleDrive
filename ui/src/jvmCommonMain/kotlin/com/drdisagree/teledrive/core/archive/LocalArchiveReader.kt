package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.presentation.preview.ArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File
import java.io.OutputStream

/**
 * The non-ZIP archive formats this app can open from a LOCAL copy, via the
 * commons-compress copy already shipped for listing them. ZIP is deliberately
 * absent: ZIP keeps its own zip4j / [ZipArchiveReader] path (local and remote)
 * and nothing here touches it. RAR is held (junrar is a separate, deliberate
 * dependency decision), and remote browsing of these formats is held by
 * design — they have no ranged central directory, so the honest route stays
 * "download the whole archive, then open the local copy".
 */
enum class LocalArchiveFormat {
    SEVEN_Z,
    TAR,
    TAR_COMPRESSED;

    /** The [com.drdisagree.teledrive.presentation.preview.PreviewContent.Archive] format label. */
    val archiveLabel: String
        get() = when (this) {
            SEVEN_Z -> "7Z"
            TAR -> "TAR"
            TAR_COMPRESSED -> "TAR (compressed)"
        }

    companion object {
        fun fromMimeType(mimeType: String): LocalArchiveFormat? = when (mimeType) {
            "application/x-7z-compressed" -> SEVEN_Z
            "application/x-tar" -> TAR
            "application/x-compressed-tar" -> TAR_COMPRESSED
            else -> null
        }

        fun fromArchiveLabel(label: String): LocalArchiveFormat? = when (label) {
            "7Z" -> SEVEN_Z
            "TAR" -> TAR
            "TAR (compressed)" -> TAR_COMPRESSED
            else -> null
        }
    }
}

/**
 * Lists and extracts a local archive file. Identity is the LISTING INDEX —
 * the position in [list]'s result — exactly like the ZIP viewer's model, so
 * duplicate names each extract their own bytes. Implementations are stateless
 * and reopen the file per call.
 */
interface LocalArchiveReader {
    fun list(file: File): List<ArchiveEntry>

    /** Writes the bytes of the entry at [entryIndex] in [list]'s order to [output]. */
    fun extract(file: File, entryIndex: Int, output: OutputStream)
}

fun localArchiveReader(format: LocalArchiveFormat): LocalArchiveReader = when (format) {
    LocalArchiveFormat.SEVEN_Z -> SevenZLocalArchiveReader
    LocalArchiveFormat.TAR -> TarLocalArchiveReader(compressed = false)
    LocalArchiveFormat.TAR_COMPRESSED -> TarLocalArchiveReader(compressed = true)
}

object SevenZLocalArchiveReader : LocalArchiveReader {

    override fun list(file: File): List<ArchiveEntry> = open(file).use { archive ->
        archive.entries.map { header ->
            ArchiveEntry(
                name = header.name.orEmpty(),
                sizeBytes = header.size.coerceAtLeast(0L),
                // Commons Compress does not expose this 7z value publicly.
                compressedBytes = 0L,
                isDirectory = header.isDirectory
            )
        }
    }

    override fun extract(file: File, entryIndex: Int, output: OutputStream) {
        try {
            open(file).use { archive ->
                val entry = archive.entries.toList().getOrNull(entryIndex)
                    ?: throw ArchiveException(
                        ArchiveErrorKind.ENTRY_NOT_FOUND,
                        "That entry is no longer in this archive. Reopen it and try again."
                    )
                if (entry.isDirectory) {
                    throw ArchiveException(
                        ArchiveErrorKind.ENTRY_NOT_FOUND,
                        "That entry is a folder, not a file."
                    )
                }
                val stream = archive.getInputStream(entry)
                    ?: throw ArchiveException(
                        ArchiveErrorKind.CORRUPT,
                        "This 7z entry could not be read."
                    )
                stream.use { it.copyTo(output) }
            }
        } catch (failure: ArchiveException) {
            throw failure
        } catch (failure: kotlinx.coroutines.CancellationException) {
            // vc183: cancellation is not corruption (see ZipArchiveReader).
            throw failure
        } catch (failure: Exception) {
            // Header-encrypted archives fail at open, content-encrypted entries
            // and unsupported codecs/filters fail here; none of them is helped
            // by the generic archive-failed message.
            throw ArchiveException(
                ArchiveErrorKind.CORRUPT,
                "This 7z entry could not be extracted. The archive may be " +
                    "encrypted, or use a compression method this app cannot read.",
                failure
            )
        }
    }

    private fun open(file: File): SevenZFile = SevenZFile.builder()
        // Keep archive metadata parsing bounded on low-memory devices — the
        // same discipline (and value) the listing path has always used; solid
        // archives can make extraction decompress a whole block, so the cap
        // matters there too.
        .setMaxMemoryLimitKiB(128 * 1024)
        .setFile(file)
        .get()
}

class TarLocalArchiveReader(private val compressed: Boolean) : LocalArchiveReader {

    override fun list(file: File): List<ArchiveEntry> = open(file).use { archive ->
        buildList {
            while (size < MAX_TAR_ENTRIES) {
                val entry = archive.nextEntry ?: break
                add(
                    ArchiveEntry(
                        name = entry.name,
                        sizeBytes = entry.size.coerceAtLeast(0L),
                        compressedBytes = 0L,
                        isDirectory = entry.isDirectory
                    )
                )
            }
        }
    }

    override fun extract(file: File, entryIndex: Int, output: OutputStream) {
        try {
            open(file).use { archive ->
                // TAR has no index: extraction is a sequential scan to the
                // target entry, so cost is O(position). The stream bounds
                // reads to the current entry, so copyTo stops at its end.
                var index = -1
                while (true) {
                    val entry = archive.nextEntry
                        ?: throw ArchiveException(
                            ArchiveErrorKind.ENTRY_NOT_FOUND,
                            "That entry is no longer in this archive. Reopen it and try again."
                        )
                    index++
                    if (index == entryIndex) {
                        if (entry.isDirectory) {
                            throw ArchiveException(
                                ArchiveErrorKind.ENTRY_NOT_FOUND,
                                "That entry is a folder, not a file."
                            )
                        }
                        archive.copyTo(output)
                        return
                    }
                }
            }
        } catch (failure: ArchiveException) {
            throw failure
        } catch (failure: kotlinx.coroutines.CancellationException) {
            // vc183: cancellation is not corruption (see ZipArchiveReader).
            throw failure
        } catch (failure: Exception) {
            throw ArchiveException(
                ArchiveErrorKind.CORRUPT,
                "This TAR entry could not be extracted. The archive may be damaged.",
                failure
            )
        }
    }

    private fun open(file: File): TarArchiveInputStream {
        val input = file.inputStream().buffered()
        val stream = if (compressed) {
            CompressorStreamFactory().createCompressorInputStream(input)
        } else {
            input
        }
        return TarArchiveInputStream(stream)
    }

    companion object {
        /** The listing cap the TAR path has always had (ZIP's reader cap is separate). */
        const val MAX_TAR_ENTRIES = 5_000
    }
}
