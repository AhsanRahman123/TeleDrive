package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.preview_archive_corrupt
import com.drdisagree.teledrive.resources.preview_archive_encrypted
import com.drdisagree.teledrive.resources.preview_archive_failed
import com.drdisagree.teledrive.resources.preview_archive_multi_disk
import com.drdisagree.teledrive.resources.preview_archive_not_zip
import com.drdisagree.teledrive.resources.preview_archive_split
import com.drdisagree.teledrive.resources.preview_fetch_failed
import com.drdisagree.teledrive.core.archive.ArchiveErrorKind
import com.drdisagree.teledrive.core.archive.ArchiveException
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.archive.ZipArchiveReader
import com.drdisagree.teledrive.core.media.BufferedMediaByteSource
import com.drdisagree.teledrive.core.media.LocalFileByteSource
import com.drdisagree.teledrive.resources.preview_no_copy_available
import com.drdisagree.teledrive.resources.preview_no_preview_for_type
import com.drdisagree.teledrive.resources.preview_only_zip
import com.drdisagree.teledrive.resources.preview_read_failed
import com.drdisagree.teledrive.core.common.SafeLog
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.dispatchers.DispatcherProvider
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.media.ApkIconExtractor
import com.drdisagree.teledrive.core.media.MediaPart
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.CacheDao
import com.drdisagree.teledrive.data.local.dao.FilePartDao
import com.drdisagree.teledrive.data.local.entity.CacheEntryEntity
import com.drdisagree.teledrive.data.local.entity.CacheEntryType
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import net.lingala.zip4j.ZipFile
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.CompressorStreamFactory
import java.io.File

/** Original local files are always plaintext; only remote copies and caches are ever encrypted. */
class PreviewContentResolver(
    private val storagePaths: AppStoragePaths,
    private val telegramClient: TelegramClient,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val cacheDao: CacheDao,
    private val filePartDao: FilePartDao,
    private val settingsRepository: SettingsRepository,
    private val dispatchers: DispatcherProvider,
    private val remoteArchiveService: RemoteArchiveService
) {

    fun resolve(file: DriveFile): Flow<PreviewContent> = flow {
        emit(PreviewContent.Loading)
        val localPath = file.localPath?.takeIf { File(it).exists() }
        if (localPath != null) {
            val content = fromLocal(file, localPath)
            // A ZIP with BOTH copies keeps its (identical) local listing, but is marked
            // remote: entry extraction re-lists the online copy and picks by name +
            // occurrence in the same central-directory order, so its rows are tappable.
            // A local-ONLY zip stays remote = false with the existing local-only message.
            emit(
                if (content is PreviewContent.Archive && content.format == "ZIP" &&
                    file.hasRemoteCopy
                ) {
                    content.copy(remote = true)
                } else {
                    content
                }
            )
            return@flow
        }

        val remoteFileId = file.remoteFileId
        if (!file.hasRemoteCopy || remoteFileId == null) {
            emit(PreviewContent.Failed(Res.string.preview_no_copy_available))
            return@flow
        }

        // A ZIP with no local copy is browsed straight from its online copy with ranged reads;
        // the whole archive is never downloaded just to list it.
        if (isZipFile(file)) {
            emit(remoteArchive(file))
            return@flow
        }

        val prefs = settingsRepository.preferences.first()
        val isMedia = file.category == FileCategory.VIDEO || file.category == FileCategory.AUDIO
        val parts = if (isMedia) filePartDao.partsOf(file.id) else emptyList()

        if (prefs.streamBeforeDownload && isMedia && parts.isNotEmpty()) {
            val mediaParts = parts.mapNotNull { part ->
                part.remoteFileId?.let {
                    MediaPart(it, part.plainOffset, part.plainSize, part.chatId, part.messageId)
                }
            }
            if (mediaParts.size == parts.size &&
                (!file.isEncrypted || wrappedKeyRepository.exists(CryptoKeys.CONTENT))
            ) {
                emit(
                    PreviewContent.StreamedMedia(
                        remoteFileId = remoteFileId,
                        isAudio = file.category == FileCategory.AUDIO,
                        parts = mediaParts,
                        encrypted = file.isEncrypted,
                        chatId = file.chatId,
                        messageId = file.messageId
                    )
                )
                return@flow
            }
        }

        if (prefs.streamBeforeDownload && isMedia && parts.isEmpty()) {
            if (!file.isEncrypted) {
                emit(
                    PreviewContent.StreamedMedia(
                        remoteFileId = remoteFileId,
                        isAudio = file.category == FileCategory.AUDIO,
                        chatId = file.chatId,
                        messageId = file.messageId
                    )
                )
                return@flow
            }

            if (wrappedKeyRepository.exists(CryptoKeys.CONTENT)) {
                emit(
                    PreviewContent.StreamedMedia(
                        remoteFileId = remoteFileId,
                        isAudio = file.category == FileCategory.AUDIO,
                        parts = listOf(
                            MediaPart(
                                remoteFileId,
                                0,
                                file.sizeBytes,
                                file.chatId,
                                file.messageId
                            )
                        ),
                        encrypted = true,
                        chatId = file.chatId,
                        messageId = file.messageId
                    )
                )
                return@flow
            }
        }

        // Only small files are fetched on open, so browsing stays instant and a large transfer
        // never starts unasked.
        val autoFetchable = file.sizeBytes <= AUTO_PREVIEW_LIMIT &&
                (MimeTypes.isImage(file.mimeType) ||
                        file.category == FileCategory.IMAGE ||
                        MimeTypes.isText(file.mimeType) ||
                        MimeTypes.isApk(file.mimeType, file.name))
        if (!autoFetchable) {
            emit(PreviewContent.RequiresDownload(file.sizeBytes))
            return@flow
        }

        val cached = fetchToCache(file) { transferred, total ->
            emit(PreviewContent.DownloadProgress(transferred, total))
        }
        if (cached == null) {
            emit(PreviewContent.Failed(Res.string.preview_fetch_failed))
        } else {
            emit(fromLocal(file, cached.absolutePath))
        }
    }
        .catch { failure ->
            SafeLog.w(TAG, "Preview failed", failure)
            emit(PreviewContent.Failed(Res.string.preview_fetch_failed))
        }
        .flowOn(dispatchers.io)

    private fun isZipFile(file: DriveFile): Boolean =
        file.mimeType == "application/zip" || file.extension.equals("zip", ignoreCase = true)

    private suspend fun remoteArchive(file: DriveFile): PreviewContent = try {
        val entries = remoteArchiveService.listEntries(file).map { entry ->
            ArchiveEntry(
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                compressedBytes = entry.compressedBytes,
                isDirectory = entry.isDirectory,
                modifiedAtMillis = entry.modifiedAtMillis
            )
        }
        PreviewContent.Archive(entries, "ZIP", remote = true)
    } catch (failure: ArchiveException) {
        when (failure.kind) {
            ArchiveErrorKind.ENCRYPTED_FILE ->
                PreviewContent.Failed(Res.string.preview_archive_encrypted)
            ArchiveErrorKind.SPLIT_FILE ->
                PreviewContent.Failed(Res.string.preview_archive_split)
            ArchiveErrorKind.NO_REMOTE_COPY ->
                PreviewContent.Failed(Res.string.preview_no_copy_available)
            ArchiveErrorKind.NOT_A_ZIP ->
                PreviewContent.Failed(Res.string.preview_archive_not_zip)
            ArchiveErrorKind.MULTI_DISK ->
                PreviewContent.Failed(Res.string.preview_archive_multi_disk)
            ArchiveErrorKind.CORRUPT ->
                PreviewContent.Failed(Res.string.preview_archive_corrupt)
            else -> PreviewContent.Failed(
                Res.string.preview_archive_failed,
                messageText = failure.message
            )
        }
    } catch (failure: Exception) {
        SafeLog.w(TAG, "Could not list remote ZIP", failure)
        PreviewContent.Failed(Res.string.preview_archive_failed)
    }

    /**
     * Preview an arbitrary saved local file (vc181: a completed Transfers row
     * with no drive file id, e.g. an in-zip download). The mime/category are
     * derived from [displayName] alone, then the exact same local dispatch as
     * the Files preview runs. A stale path whose file was deleted fails
     * cleanly with [Res.string.preview_no_copy_available] instead of crashing
     * or hanging in a renderer.
     */
    fun resolveLocalPath(path: String, displayName: String): Flow<PreviewContent> = flow {
        emit(PreviewContent.Loading)
        if (!File(path).exists()) {
            emit(PreviewContent.Failed(Res.string.preview_no_copy_available))
            return@flow
        }
        val mimeType = MimeTypes.fromFileName(displayName)
        emit(
            fromLocal(
                mimeType = mimeType,
                category = FileCategory.fromMimeType(mimeType),
                fileName = displayName,
                path = path
            )
        )
    }
        .catch { failure ->
            SafeLog.w(TAG, "Local preview failed", failure)
            emit(PreviewContent.Failed(Res.string.preview_fetch_failed))
        }
        .flowOn(dispatchers.io)

    private suspend fun fromLocal(file: DriveFile, path: String): PreviewContent =
        fromLocal(
            mimeType = file.mimeType,
            category = file.category,
            fileName = file.name,
            path = path
        )

    // Branch order is load-bearing and unchanged from the original
    // DriveFile-keyed dispatch: image -> video -> audio -> pdf -> text ->
    // archive -> apk -> unsupported.
    private suspend fun fromLocal(
        mimeType: String,
        category: FileCategory,
        fileName: String,
        path: String
    ): PreviewContent = when {
        MimeTypes.isImage(mimeType) || category == FileCategory.IMAGE ->
            PreviewContent.Image(File(path))
        MimeTypes.isVideo(mimeType) || category == FileCategory.VIDEO ->
            PreviewContent.LocalMedia(path, isAudio = false)
        MimeTypes.isAudio(mimeType) || category == FileCategory.AUDIO ->
            PreviewContent.LocalMedia(path, isAudio = true)
        MimeTypes.isPdf(mimeType) -> PreviewContent.Pdf(path)
        MimeTypes.isText(mimeType) -> readText(path)
        MimeTypes.isArchive(mimeType) || category == FileCategory.ARCHIVE ->
            readArchive(mimeType, path)
        MimeTypes.isApk(mimeType, fileName) -> readApk(path)
        else -> PreviewContent.Unsupported(Res.string.preview_no_preview_for_type, path)
    }

    private fun readApk(path: String): PreviewContent = runCatching {
        val file = File(path)
        val iconBytes = ApkIconExtractor.extractIconBytes(file)
        if (iconBytes != null) {
            PreviewContent.Image(iconBytes)
        } else {
            PreviewContent.Unsupported(Res.string.preview_no_preview_for_type, path)
        }
    }.getOrElse { PreviewContent.Unsupported(Res.string.preview_no_preview_for_type, path) }

    private fun readText(path: String): PreviewContent = runCatching {
        val target = File(path)
        val truncated = target.length() > TEXT_LIMIT
        val bytes = target.inputStream().use { stream ->
            val buffer = ByteArray(TEXT_LIMIT)
            var read = 0
            while (read < TEXT_LIMIT) {
                val count = stream.read(buffer, read, TEXT_LIMIT - read)
                if (count <= 0) break
                read += count
            }
            buffer.copyOf(read)
        }
        PreviewContent.PlainText(String(bytes, Charsets.UTF_8), truncated)
    }.getOrElse { PreviewContent.Failed(Res.string.preview_read_failed) }

    private suspend fun readLocalZip(path: String): PreviewContent {
        // zip4j first, exactly as before: every archive it can list today keeps the
        // identical listing, including its timestamp handling.
        val zip4jListing = runCatching {
            ZipFile(path).fileHeaders.map { header ->
                ArchiveEntry(
                    name = header.fileName,
                    sizeBytes = header.uncompressedSize,
                    compressedBytes = header.compressedSize,
                    isDirectory = header.isDirectory,
                    // zip4j exposes the entry time as Java epoch millis; anything
                    // non-positive is "no timestamp" for sorting purposes.
                    modifiedAtMillis = header.lastModifiedTime.coerceAtLeast(0L)
                )
            }
        }.getOrNull()
        if (zip4jListing != null) return PreviewContent.Archive(zip4jListing, "ZIP")

        // Fallback to the app's own ranged reader. zip4j refuses to even LIST an
        // archive when any entry uses a compression method it does not implement —
        // it throws ZipException("Unknown compression method") while parsing the
        // central directory — even though the archive is perfectly valid. That hits
        // ZIPs containing Deflate64, BZip2, LZMA or PPMd entries, all of which
        // ZArchiver's 7-Zip engine offers when creating ZIP archives, so one such
        // entry used to make the whole archive unopenable here. ZipArchiveReader
        // records the method per entry and only refuses such an entry when it is
        // actually extracted, so the archive opens and its other entries stay usable.
        return try {
            PreviewContent.Archive(localZipEntriesViaReader(path), "ZIP")
        } catch (failure: ArchiveException) {
            when (failure.kind) {
                ArchiveErrorKind.NOT_A_ZIP ->
                    PreviewContent.Failed(Res.string.preview_archive_not_zip)
                ArchiveErrorKind.MULTI_DISK ->
                    PreviewContent.Failed(Res.string.preview_archive_multi_disk)
                ArchiveErrorKind.CORRUPT ->
                    PreviewContent.Failed(Res.string.preview_archive_corrupt)
                else -> PreviewContent.Failed(
                    Res.string.preview_archive_failed,
                    messageText = failure.message
                )
            }
        } catch (failure: Exception) {
            SafeLog.w(TAG, "Could not list local ZIP", failure)
            PreviewContent.Failed(Res.string.preview_archive_failed)
        }
    }

    private suspend fun readArchive(mimeType: String, path: String): PreviewContent =
        when (mimeType) {
            "application/zip" -> readLocalZip(path)

            "application/x-7z-compressed" -> runCatching {
                val entries = SevenZFile.builder()
                    // Keep archive metadata parsing bounded on low-memory devices.
                    .setMaxMemoryLimitKiB(128 * 1024)
                    .setFile(File(path))
                    .get()
                    .use { archive ->
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
                PreviewContent.Archive(entries, "7Z")
            }.getOrElse { PreviewContent.Failed(Res.string.preview_archive_failed) }

            "application/x-tar" -> readTar(path, compressed = false)
            "application/x-compressed-tar" -> readTar(path, compressed = true)

            else -> PreviewContent.Unsupported(Res.string.preview_only_zip, path)
        }

    private fun readTar(path: String, compressed: Boolean): PreviewContent = runCatching {
        val input = File(path).inputStream().buffered()
        val tarInput = if (compressed) {
            CompressorStreamFactory().createCompressorInputStream(input)
        } else {
            input
        }
        TarArchiveInputStream(tarInput).use { archive ->
            val entries = buildList {
                while (size < MAX_ARCHIVE_ENTRIES) {
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
            PreviewContent.Archive(
                entries = entries,
                format = if (compressed) "TAR (compressed)" else "TAR"
            )
        }
    }.getOrElse {
        SafeLog.w(TAG, "Could not read TAR archive metadata", it)
        PreviewContent.Failed(Res.string.preview_archive_failed)
    }

    private suspend fun fetchToCache(
        file: DriveFile,
        onProgress: suspend (Long, Long) -> Unit
    ): File? {
        val previewDir = File(storagePaths.cacheDir, "previews").apply { mkdirs() }
        val target = File(previewDir, "${file.id}-${file.name}")
        if (target.exists() && target.length() > 0) {
            cacheDao.touch(target.absolutePath, System.currentTimeMillis())
            return target
        }

        var tdlibPath: String? = null
        telegramClient.downloadDocument(
            remoteFileId = file.remoteFileId ?: return null,
            chatId = file.chatId,
            messageId = file.messageId
        ).collect { event ->
                when (event) {
                    is TelegramDownloadEvent.Progress ->
                        onProgress(event.transferredBytes, event.totalBytes)

                    is TelegramDownloadEvent.Completed -> tdlibPath = event.localPath
                }
            }
        val source = tdlibPath?.let(::File)?.takeIf { it.exists() } ?: return null

        val success = runCatching {
            if (file.isEncrypted) {
                val key = wrappedKeyRepository.get(CryptoKeys.CONTENT)
                    ?: error("Encryption key missing")
                source.inputStream().buffered().use { input ->
                    target.outputStream().buffered().use { output ->
                        streamCrypto.decryptStream(key, input, output)
                    }
                }
            } else {
                source.copyTo(target, overwrite = true)
            }
        }.isSuccess

        if (!success) {
            target.delete()
            return null
        }
        runCatching { telegramClient.deleteLocalCopy(file.remoteFileId ?: return@runCatching) }
        val now = System.currentTimeMillis()
        cacheDao.upsert(
            CacheEntryEntity(
                path = target.absolutePath,
                fileId = file.id,
                type = CacheEntryType.PREVIEW,
                sizeBytes = target.length(),
                encrypted = false,
                createdAt = now,
                lastAccessAt = now
            )
        )
        return target
    }

    companion object {
        private const val TAG = "PreviewContent"
        private const val AUTO_PREVIEW_LIMIT = 10L * 1024 * 1024
        private const val TEXT_LIMIT = 256 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 5_000
    }
}

/**
 * Lists a local ZIP file with the app's own ranged [ZipArchiveReader] over a
 * [LocalFileByteSource], instead of zip4j. Only the file tail and the central
 * directory are read, so even a very large archive lists fast. Unlike zip4j, the
 * reader does not reject an archive over an entry's compression method: the
 * method is recorded per entry and only an actual extraction of an unsupported
 * entry is refused (with the specific unsupported-method message). Throws
 * [ArchiveException] with the same kinds the remote listing path produces.
 */
suspend fun localZipEntriesViaReader(path: String): List<ArchiveEntry> {
    val source = BufferedMediaByteSource(LocalFileByteSource(File(path)))
    try {
        return ZipArchiveReader(source).entries().map { entry ->
            ArchiveEntry(
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                compressedBytes = entry.compressedBytes,
                isDirectory = entry.isDirectory,
                modifiedAtMillis = entry.modifiedAtMillis
            )
        }
    } finally {
        source.close()
    }
}
