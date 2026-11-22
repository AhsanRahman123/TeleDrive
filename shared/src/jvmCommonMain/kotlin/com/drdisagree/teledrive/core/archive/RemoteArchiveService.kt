package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.BufferedMediaByteSource
import com.drdisagree.teledrive.core.media.MediaByteSource
import com.drdisagree.teledrive.core.media.TelegramMediaByteSource
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.DriveFile
import java.io.File

/**
 * Browses and extracts single files from a zip whose only copy lives in Telegram.
 *
 * Everything goes through ranged reads ([TelegramMediaByteSource]); the whole zip is never
 * downloaded to list or extract. Gates are checked in a fixed order and refuse with plain
 * messages instead of falling back to a whole-file download.
 */
class RemoteArchiveService(
    private val telegramClient: TelegramClient,
    private val filePartDao: FilePartDao,
    private val downloadWriter: DownloadWriter,
    private val folderPathResolver: FolderPathResolver,
    private val byteSourceFactory: (DriveFile) -> MediaByteSource = { file ->
        BufferedMediaByteSource(
            TelegramMediaByteSource(
                telegramClient,
                file.remoteFileId ?: error("Remote file id missing"),
                file.chatId,
                file.messageId,
                // Range-only contract: never prime a whole-file download from offset 0;
                // each ranged read requests exactly its own window via awaitAvailable().
                primeFromStart = false
            )
        )
    }
) {

    suspend fun listEntries(file: DriveFile): List<ZipEntryInfo> {
        checkBrowsable(file)
        val source = byteSourceFactory(file)
        try {
            return ZipArchiveReader(source).entries()
        } finally {
            source.close()
        }
    }

    /**
     * Extracts the [occurrence]-th entry named [entryName] (duplicate names are legal in zip;
     * occurrence follows central-directory order) and saves it through [DownloadWriter] under
     * the entry's own file name, in the same downloads folder the zip itself would land in.
     * Returns the saved path.
     *
     * [subFolderPath], when given, is appended to the zip's library folder so a download
     * that belongs to a folder inside the zip recreates that folder structure under the
     * downloads folder — the same tree recreation the normal folder download performs.
     */
    suspend fun extractEntry(
        file: DriveFile,
        entryName: String,
        occurrence: Int = 0,
        subFolderPath: String? = null
    ): String {
        val temp = extractEntryToTempFile(file, entryName, occurrence)
        try {
            val entryFileName = entryName.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "file" }
            val libraryPath = folderPathResolver.pathOf(file.folderId)
            val folderPath = listOf(libraryPath, subFolderPath.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("/")
            return downloadWriter.write(
                fileName = entryFileName,
                mimeType = MimeTypes.fromFileName(entryFileName),
                folderPath = folderPath
            ) { output ->
                temp.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw RemoteArchiveRefusalException(
                "The file was extracted but could not be saved to your downloads folder."
            )
        } finally {
            temp.delete()
        }
    }

    /**
     * Extracts the [occurrence]-th entry named [entryName] to a fresh temp file, with the
     * same produced-length and CRC-32 verification as a download: [ZipArchiveReader.extract]
     * only returns once both checks pass, so a returned file is always the verified entry
     * bytes. The file keeps the entry's own name (plus a uniqueness marker) so image loaders
     * and media players see the right extension. The CALLER owns the returned file and must
     * delete it when done. Nothing is written to Downloads here — this is the
     * preview/playback path. On failure the temp file is deleted before the error propagates.
     */
    suspend fun extractEntryToTempFile(
        file: DriveFile,
        entryName: String,
        occurrence: Int = 0
    ): File {
        checkBrowsable(file)
        val source = byteSourceFactory(file)
        try {
            val reader = ZipArchiveReader(source)
            val matches = reader.entries().filter { it.name == entryName && !it.isDirectory }
            val entry = matches.getOrNull(occurrence)
                ?: throw ZipArchiveException.EntryNotFound(entryName)
            val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "unnamed" }
            val temp = createEntryTempFile(fileName)
            try {
                temp.outputStream().buffered().use { output -> reader.extract(entry, output) }
            } catch (failure: Exception) {
                temp.delete()
                throw failure
            }
            return temp
        } finally {
            source.close()
        }
    }

    private fun createEntryTempFile(fileName: String): File {
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH }
            ?.let { ".$it" } ?: ".tmp"
        val base = fileName.removeSuffix(extension).ifBlank { "entry" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_TEMP_BASE_LENGTH)
        // createTempFile inserts its random marker between prefix and suffix, so the
        // result is "<base>-<marker><ext>": unique, and the entry name stays readable.
        return File.createTempFile("teledrive-zip-entry-$base-", extension)
    }

    private companion object {
        const val MAX_EXTENSION_LENGTH = 16
        const val MAX_TEMP_BASE_LENGTH = 48
    }

    private suspend fun checkBrowsable(file: DriveFile) {
        if (file.isEncrypted) {
            throw RemoteArchiveRefusalException(
                "This file's online copy is encrypted by Pranto Drive, so the zip can't be " +
                    "browsed here. Download it to open it locally."
            )
        }
        if (filePartDao.partsOf(file.id).isNotEmpty()) {
            throw RemoteArchiveRefusalException(
                "This file was split by Pranto Drive into multiple parts when it was uploaded, " +
                    "so it isn't a single file online and can't be browsed here. " +
                    "Download it to open it locally."
            )
        }
        if (!file.hasRemoteCopy || file.remoteFileId == null) {
            throw RemoteArchiveRefusalException(
                "This file hasn't been uploaded yet, so there is no online copy to browse."
            )
        }
    }
}
