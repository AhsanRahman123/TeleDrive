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
     */
    suspend fun extractEntry(file: DriveFile, entryName: String, occurrence: Int = 0): String {
        checkBrowsable(file)
        val source = byteSourceFactory(file)
        try {
            val reader = ZipArchiveReader(source)
            val matches = reader.entries().filter { it.name == entryName && !it.isDirectory }
            val entry = matches.getOrNull(occurrence)
                ?: throw ZipArchiveException.EntryNotFound(entryName)

            val temp = File.createTempFile("teledrive-zip-entry-", ".tmp")
            try {
                temp.outputStream().buffered().use { output -> reader.extract(entry, output) }
                val entryFileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                    .ifBlank { "file" }
                val folderPath = folderPathResolver.pathOf(file.folderId)
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
        } finally {
            source.close()
        }
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
