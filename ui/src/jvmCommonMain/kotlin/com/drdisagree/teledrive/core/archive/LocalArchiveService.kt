package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.DriveFile
import java.io.File
import java.io.OutputStream

/**
 * Extracts single entries out of a LOCAL 7z / TAR archive — the sibling of
 * [RemoteArchiveService] for the formats that can only be browsed from a
 * local copy. Same contracts as the remote service: extraction for preview
 * goes to a caller-owned temp file (caller deletes), extraction for download
 * is saved through the normal download writer in the archive's library
 * folder. The ZIP path is not involved in any way.
 */
class LocalArchiveService(
    private val downloadWriter: DownloadWriter,
    private val folderPathResolver: FolderPathResolver
) {

    /**
     * Extracts the entry at [entryIndex] (listing-index identity) to a fresh
     * temp file that keeps the entry's extension, so image loaders and media
     * players sniff it correctly. The CALLER owns the returned file and must
     * delete it when done. A failed extraction deletes its own partial temp.
     */
    fun extractEntryToTempFile(
        localPath: String,
        format: LocalArchiveFormat,
        entryIndex: Int,
        entryName: String,
        onProgress: ((transferredBytes: Long) -> Unit)? = null
    ): File {
        val temp = createEntryTempFile(entryName)
        try {
            temp.outputStream().buffered().use { output ->
                val target = if (onProgress == null) {
                    output
                } else {
                    CountingOutputStream(output, onProgress)
                }
                localArchiveReader(format).extract(File(localPath), entryIndex, target)
            }
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
        return temp
    }

    /**
     * Extracts the entry at [entryIndex] and saves it through the normal
     * download writer, under the entry's file name, in the folder that
     * mirrors the archive's library folder. [subFolderPath], when given, is
     * appended so a folder download recreates the in-archive structure —
     * the same destination rule [RemoteArchiveService.extractEntry] applies.
     */
    fun extractEntry(
        file: DriveFile,
        localPath: String,
        format: LocalArchiveFormat,
        entryIndex: Int,
        entryName: String,
        subFolderPath: String? = null,
        onProgress: ((transferredBytes: Long) -> Unit)? = null
    ): String {
        val temp = extractEntryToTempFile(localPath, format, entryIndex, entryName, onProgress)
        try {
            val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "unnamed" }
            val libraryPath = folderPathResolver.pathOf(file.folderId)
            val folderPath = listOf(libraryPath, subFolderPath.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("/")
            val savedPath = downloadWriter.write(
                fileName = fileName,
                mimeType = MimeTypes.fromFileName(fileName),
                folderPath = folderPath
            ) { output ->
                temp.inputStream().buffered().use { input -> input.copyTo(output) }
            }
            return savedPath ?: throw ArchiveException(
                ArchiveErrorKind.SAVE_FAILED,
                "The file could not be saved to Downloads."
            )
        } finally {
            temp.delete()
        }
    }

    private fun createEntryTempFile(entryName: String): File {
        val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')
            .ifBlank { "unnamed" }
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH }
            ?.let { ".$it" } ?: ".tmp"
        val base = fileName.removeSuffix(extension).ifBlank { "entry" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_TEMP_BASE_LENGTH)
        return File.createTempFile("$TEMP_PREFIX$base-", extension)
    }

    /** Same counting wrapper as the remote service's, for the same reason. */
    private class CountingOutputStream(
        private val delegate: OutputStream,
        private val onProgress: (transferredBytes: Long) -> Unit
    ) : OutputStream() {
        private var total = 0L

        override fun write(b: Int) {
            delegate.write(b)
            total += 1
            onProgress(total)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            total += len
            onProgress(total)
        }

        override fun write(b: ByteArray) {
            write(b, 0, b.size)
        }

        override fun flush() {
            delegate.flush()
        }

        override fun close() {
            delegate.close()
        }
    }

    private companion object {
        const val TEMP_PREFIX = "local-entry-"
        const val MAX_EXTENSION_LENGTH = 16
        const val MAX_TEMP_BASE_LENGTH = 48
    }
}
