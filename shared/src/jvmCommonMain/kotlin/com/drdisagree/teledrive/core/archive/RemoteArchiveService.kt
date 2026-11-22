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

/**
 * Browses and extracts single files from a ZIP that exists only as a remote Telegram copy.
 *
 * Everything goes through ranged reads ([TelegramMediaByteSource]); the whole ZIP is never
 * downloaded to list or extract. Files Pranto Drive itself split or encrypted at upload are
 * refused up front, because their Telegram bytes are not one readable ZIP.
 */
class RemoteArchiveService(
    private val telegramClient: TelegramClient,
    private val filePartDao: FilePartDao,
    private val downloadWriter: DownloadWriter,
    private val folderPathResolver: FolderPathResolver
) {

    suspend fun listEntries(file: DriveFile): List<ZipEntryInfo> {
        checkReadable(file)
        val source = openSource(file)
        try {
            return ZipArchiveReader(source).entries()
        } finally {
            source.close()
        }
    }

    /**
     * Extracts the [occurrence]-th entry named [entryName] (duplicate names keep their own data)
     * and saves it through the normal download writer, under the entry's file name, in the folder
     * that mirrors the ZIP's library folder. Returns the writer's saved location.
     */
    suspend fun extractEntry(file: DriveFile, entryName: String, occurrence: Int = 0): String {
        checkReadable(file)
        val source = openSource(file)
        try {
            val reader = ZipArchiveReader(source)
            // Occurrence counts only non-directory entries with this name; the preview view
            // model counts the same way, so both sides pick the same entry.
            val matches = reader.entries().filter { it.name == entryName && !it.isDirectory }
            val entry = matches.getOrNull(occurrence)
                ?: throw ArchiveException(
                    ArchiveErrorKind.ENTRY_NOT_FOUND,
                    "\"$entryName\" is no longer in this archive. Reopen the ZIP and try again."
                )
            val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "unnamed" }
            // Extract to a temp file first: size and CRC are verified there, so a corrupt entry
            // fails before the download writer is ever called and can never leave a bad file in
            // Downloads. Only a verified entry is copied through the writer.
            val temp = java.io.File.createTempFile("zip-entry-", ".tmp")
            try {
                temp.outputStream().buffered().use { output -> reader.extract(entry, output) }
                val folderPath = folderPathResolver.pathOf(file.folderId)
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
        } finally {
            source.close()
        }
    }

    private suspend fun checkReadable(file: DriveFile) {
        if (file.isEncrypted) {
            throw ArchiveException(
                ArchiveErrorKind.ENCRYPTED_FILE,
                "This ZIP was encrypted by Pranto Drive when it was uploaded, so its contents " +
                    "cannot be browsed online. Download the whole file to open it."
            )
        }
        if (filePartDao.partsOf(file.id).isNotEmpty()) {
            throw ArchiveException(
                ArchiveErrorKind.SPLIT_FILE,
                "This file was split by Pranto Drive into multiple Telegram parts when it was " +
                    "uploaded, so it is not a single file online and cannot be browsed here. " +
                    "Download the whole file to open it."
            )
        }
        if (!file.hasRemoteCopy || file.remoteFileId == null) {
            throw ArchiveException(
                ArchiveErrorKind.NO_REMOTE_COPY,
                "This ZIP has not been uploaded yet, so there is no online copy to browse."
            )
        }
    }

    private fun openSource(file: DriveFile): MediaByteSource {
        return BufferedMediaByteSource(
            TelegramMediaByteSource(
                telegramClient = telegramClient,
                remoteFileId = file.remoteFileId!!,
                chatId = file.chatId,
                messageId = file.messageId,
                // Never prime a whole-file download from byte 0: ranged reads request the
                // exact offsets they need.
                primeFromStart = false
            )
        )
    }
}
