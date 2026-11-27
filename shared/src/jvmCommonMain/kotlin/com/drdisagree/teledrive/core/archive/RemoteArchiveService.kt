package com.drdisagree.teledrive.core.archive

import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.DownloadWriter
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.BufferedMediaByteSource
import com.drdisagree.teledrive.core.media.MediaByteSource
import com.drdisagree.teledrive.core.media.TelegramMediaByteSource
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.repository.FolderPathResolver
import com.drdisagree.teledrive.domain.model.DriveFile
import kotlinx.coroutines.sync.withLock

/**
 * Bounded, credential-free detail for a failure: exception class + TelegramException
 * code + message (+ cause detail). Used by [RemoteArchiveService.describe] for SafeLog
 * and by the preview UI, so an archive failure reaching the user always names its cause
 * instead of a bare "unknown error" (1.6.23, report 52).
 */
fun archiveFailureDetail(failure: Throwable): String {
    fun one(error: Throwable): String {
        val code = (error as? com.drdisagree.teledrive.core.telegram.TelegramException)?.code
        return "${error::class.simpleName}" + (code?.let { " code=$it" } ?: "") +
            " msg=${error.message?.take(300)}"
    }
    val cause = failure.cause
    return one(failure) + if (cause != null && cause !== failure) " cause=${one(cause)}" else ""
}

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

    /**
     * Opens a thumbnail session over [file] (PC 1.6.25 item 6, ported from
     * Android vc182): one byte source whose central directory is parsed once
     * and shared by every per-row thumbnail extraction. Caller closes it.
     */
    suspend fun openThumbnailSession(file: DriveFile): ArchiveThumbnailSession {
        checkReadable(file)
        return ArchiveThumbnailSession(openSource(file))
    }

    suspend fun listEntries(file: DriveFile): List<ZipEntryInfo> {
        try {
            checkReadable(file)
        } catch (failure: Exception) {
            warnLog("stage=checkReadable failed file=${file.name}: ${describe(failure)}", failure)
            throw wrapFailure("checkReadable", failure)
        }
        val source = openSource(file)
        try {
            val entries = ZipArchiveReader(source).entries()
            SafeLog.d(TAG, "stage=list ok entries=${entries.size} file=${file.name}")
            return entries
        } catch (failure: Exception) {
            warnLog("stage=list failed file=${file.name}: ${describe(failure)}", failure)
            throw wrapFailure("list", failure)
        } finally {
            source.close()
        }
    }

    /**
     * Extracts the [occurrence]-th entry named [entryName] (duplicate names keep their own data)
     * and saves it through the normal download writer, under the entry's file name, in the folder
     * that mirrors the ZIP's library folder. Returns the writer's saved location.
     */
    /**
     * [subFolderPath], when given, is appended to the ZIP's library folder so a download
     * that belongs to a folder inside the ZIP recreates that folder structure under
     * Downloads — the same tree recreation the normal folder download performs.
     */
    suspend fun extractEntry(
        file: DriveFile,
        entryName: String,
        occurrence: Int = 0,
        subFolderPath: String? = null,
        onProgress: ((transferredBytes: Long) -> Unit)? = null
    ): String {
        // Progress covers the extraction (network) phase only: the temp -> Downloads
        // copy below is a local file copy and is deliberately unreported.
        val temp = extractEntryToTempFile(file, entryName, occurrence, onProgress)
        try {
            val fileName = entryName.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "unnamed" }
            val libraryPath = folderPathResolver.pathOf(file.folderId)
            val folderPath = listOf(libraryPath, subFolderPath.orEmpty())
                .filter { it.isNotBlank() }
                .joinToString("/")
            val savedPath = try {
                downloadWriter.write(
                    fileName = fileName,
                    mimeType = MimeTypes.fromFileName(fileName),
                    folderPath = folderPath
                ) { output ->
                    temp.inputStream().buffered().use { input -> input.copyTo(output) }
                }
            } catch (failure: Exception) {
                warnLog("stage=save failed file=${file.name}: ${describe(failure)}", failure)
                throw wrapFailure("save", failure)
            }
            return savedPath ?: throw ArchiveException(
                ArchiveErrorKind.SAVE_FAILED,
                "The file could not be saved to Downloads."
            )
        } finally {
            temp.delete()
        }
    }

    /**
     * Extracts the [occurrence]-th entry named [entryName] to a fresh temp file in the
     * system temp/cache directory, with the same produced-length and CRC-32 verification
     * as a download: [ZipArchiveReader.extract] only returns once both checks pass, so a
     * returned file is always the verified entry bytes. The file keeps the entry's own
     * name (plus a uniqueness marker) so image loaders and media players see the right
     * extension. The CALLER owns the returned file and must delete it when done. Nothing
     * is written to Downloads here — this is the preview/playback path.
     */
    suspend fun extractEntryToTempFile(
        file: DriveFile,
        entryName: String,
        occurrence: Int = 0,
        onProgress: ((transferredBytes: Long) -> Unit)? = null
    ): java.io.File {
        try {
            checkReadable(file)
        } catch (failure: Exception) {
            warnLog("stage=checkReadable failed file=${file.name}: ${describe(failure)}", failure)
            throw wrapFailure("checkReadable", failure)
        }
        SafeLog.d(TAG, "stage=resolve ok file=${file.name} entry=$entryName occurrence=$occurrence")
        val source = openSource(file)
        try {
            val reader = ZipArchiveReader(source)
            // Occurrence counts only non-directory entries with this name; the preview view
            // model counts the same way, so both sides pick the same entry.
            val matches = try {
                reader.entries().filter { it.name == entryName && !it.isDirectory }
            } catch (failure: Exception) {
                warnLog("stage=list failed file=${file.name}: ${describe(failure)}", failure)
                throw wrapFailure("list", failure)
            }
            SafeLog.d(TAG, "stage=list ok matches=${matches.size} entry=$entryName")
            val entry = matches.getOrNull(occurrence)
                ?: throw ArchiveException(
                    ArchiveErrorKind.ENTRY_NOT_FOUND,
                    "\"$entryName\" is no longer in this archive. Reopen the ZIP and try again."
                )
            val fileName = entry.name.substringAfterLast('/').substringAfterLast('\\')
                .ifBlank { "unnamed" }
            val temp = createEntryTempFile(fileName)
            try {
                SafeLog.d(
                    TAG,
                    "stage=data start entry=${entry.name} compressed=${entry.compressedBytes} size=${entry.sizeBytes}"
                )
                temp.outputStream().buffered().use { output ->
                    if (onProgress == null) {
                        reader.extract(entry, output)
                    } else {
                        reader.extract(entry, CountingOutputStream(output, onProgress))
                    }
                }
                SafeLog.d(TAG, "stage=writer ok entry=${entry.name} temp=${temp.name}")
            } catch (failure: Exception) {
                warnLog("stage=data/writer failed entry=${entry.name}: ${describe(failure)}", failure)
                temp.delete()
                throw wrapFailure("data", failure)
            }
            return temp
        } finally {
            source.close()
        }
    }

    private fun createEntryTempFile(fileName: String): java.io.File {
        val extension = fileName.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_LENGTH }
            ?.let { ".$it" } ?: ".tmp"
        val base = fileName.removeSuffix(extension).ifBlank { "entry" }
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .take(MAX_TEMP_BASE_LENGTH)
        // createTempFile inserts its random marker between prefix and suffix, so the
        // result is "<base>-<marker><ext>": unique, and the entry name stays readable.
        return java.io.File.createTempFile("$TEMP_PREFIX$base-", extension)
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

    /**
     * Counts the bytes the extractor produces and reports the running total after each write.
     * Extends OutputStream directly with an explicit delegate: FilterOutputStream's
     * write(byte[], off, len) loops through write(int) on some platforms, which would
     * count those bytes twice.
     */
    private class CountingOutputStream(
        private val delegate: java.io.OutputStream,
        private val onProgress: (transferredBytes: Long) -> Unit
    ) : java.io.OutputStream() {
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

    /** SafeLog.w that can never throw into the extraction path it only observes. */
    private fun warnLog(message: String, failure: Throwable) {
        runCatching { SafeLog.w(TAG, message, failure) }
    }

    /** Exception class + TDLib error code, so a field recurrence names its own stage and cause. */
    private fun describe(failure: Exception): String = archiveFailureDetail(failure)

    /**
     * Every failure leaving this service must carry its stage + [describe] detail in the
     * exception the UI receives (1.6.23, report 52): non-[ArchiveException] failures are
     * wrapped as [ArchiveErrorKind.REMOTE_FAILED]; ArchiveExceptions keep their kind but
     * gain the underlying detail where a cause exists. Cancellation is never wrapped.
     */
    private fun wrapFailure(stage: String, failure: Exception): Exception = when {
        failure is kotlinx.coroutines.CancellationException -> failure
        failure is ArchiveException -> {
            val cause = failure.cause
            if (cause != null && failure.message?.contains("cause=") != true) {
                ArchiveException(
                    failure.kind,
                    "${failure.message} [stage=$stage detail: ${archiveFailureDetail(cause)}]",
                    cause
                )
            } else {
                failure
            }
        }
        else -> ArchiveException(
            ArchiveErrorKind.REMOTE_FAILED,
            "Archive stage $stage failed: ${archiveFailureDetail(failure)}",
            failure
        )
    }

    private companion object {
        const val TAG = "RemoteArchiveService"
        const val TEMP_PREFIX = "zip-entry-"
        const val MAX_EXTENSION_LENGTH = 16
        const val MAX_TEMP_BASE_LENGTH = 48
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

/**
 * One open byte source over a remote ZIP, shared by every thumbnail fetch for that
 * archive (Android vc182 item 6, ported to PC 1.6.25). The central directory is
 * parsed lazily on the first [entries] call and then cached for the session's
 * lifetime, so N thumbnail extractions cost ONE directory parse — unlike
 * [RemoteArchiveService.extractEntryToTempFile], which re-parses per call and is
 * therefore forbidden on the per-row path.
 *
 * All reads are serialized on an internal mutex: the shared byte source keeps a
 * single read buffer, so concurrent extractions must not interleave on it.
 */
class ArchiveThumbnailSession internal constructor(
    private val source: MediaByteSource
) : AutoCloseable {

    private val reader = ZipArchiveReader(source)
    private val mutex = kotlinx.coroutines.sync.Mutex()
    private var cachedEntries: List<ZipEntryInfo>? = null

    @Volatile
    private var closed = false

    /** The archive's entries, parsed from the central directory on first call only. */
    suspend fun entries(): List<ZipEntryInfo> = mutex.withLock {
        check(!closed) { "Archive thumbnail session is closed." }
        cachedEntries ?: reader.entries().also { cachedEntries = it }
    }

    /**
     * Extracts [info] (an entry previously returned by [entries]) to [out], with the
     * reader's usual produced-length and CRC-32 verification. The caller owns [out].
     */
    suspend fun extractEntry(info: ZipEntryInfo, out: java.io.File) {
        mutex.withLock {
            check(!closed) { "Archive thumbnail session is closed." }
            out.outputStream().buffered().use { output -> reader.extract(info, output) }
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            source.close()
        }
    }
}
