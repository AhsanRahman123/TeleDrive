package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.core.media.ThumbnailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
import com.drdisagree.teledrive.resources.preview_no_copy_available
import com.drdisagree.teledrive.resources.preview_no_preview_for_type
import com.drdisagree.teledrive.resources.preview_only_zip
import com.drdisagree.teledrive.resources.preview_read_failed
import com.drdisagree.teledrive.resources.preview_zip_corrupt
import com.drdisagree.teledrive.resources.preview_zip_encrypted
import com.drdisagree.teledrive.resources.preview_zip_multi_disk
import com.drdisagree.teledrive.resources.preview_zip_not_a_zip
import com.drdisagree.teledrive.resources.preview_zip_split
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
    private val thumbnailStore: ThumbnailStore,
    private val dispatchers: DispatcherProvider,
    private val remoteArchiveService: RemoteArchiveService
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.io)

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
                captureVideoFrame(file, mediaParts)
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
                val single = listOf(MediaPart(remoteFileId, 0, file.sizeBytes))
                captureVideoFrame(file, single)
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
            if (file.isEncrypted && MimeTypes.isImage(file.mimeType)) {
                runCatching { thumbnailStore.storeFromDecrypted(file.id, cached) }
            }
        }
    }
        .catch { failure ->
            SafeLog.w(TAG, "Preview failed", failure)
            emit(PreviewContent.Failed(Res.string.preview_fetch_failed))
        }
        .flowOn(dispatchers.io)

    private fun captureVideoFrame(file: DriveFile, parts: List<MediaPart>) {
        if (!file.isEncrypted || file.category != FileCategory.VIDEO) return
        scope.launch {
            runCatching { thumbnailStore.storeFromStream(file.id, parts, encrypted = true) }
        }
    }

    private fun isZipFile(file: DriveFile): Boolean =
        file.mimeType == "application/zip" || file.extension.equals("zip", ignoreCase = true)

    private suspend fun remoteArchive(file: DriveFile): PreviewContent = try {
        val entries = remoteArchiveService.listEntries(file).map { entry ->
            ArchiveEntry(
                name = entry.name,
                sizeBytes = entry.sizeBytes,
                compressedBytes = entry.compressedBytes,
                isDirectory = entry.isDirectory,
                modifiedAtMillis = normalizeArchiveEntryModifiedAt(entry.modifiedAtMillis)
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
        PreviewContent.Failed(
            Res.string.preview_archive_failed,
            messageText = com.drdisagree.teledrive.core.archive.archiveFailureDetail(failure)
        )
    }

    private fun fromLocal(file: DriveFile, path: String): PreviewContent = when {
        MimeTypes.isImage(file.mimeType) || file.category == FileCategory.IMAGE ->
            PreviewContent.Image(File(path))
        MimeTypes.isVideo(file.mimeType) || file.category == FileCategory.VIDEO ->
            PreviewContent.LocalMedia(path, isAudio = false)
        MimeTypes.isAudio(file.mimeType) || file.category == FileCategory.AUDIO ->
            PreviewContent.LocalMedia(path, isAudio = true)
        MimeTypes.isPdf(file.mimeType) -> PreviewContent.Pdf(path)
        MimeTypes.isText(file.mimeType) -> readText(path)
        MimeTypes.isArchive(file.mimeType) || file.category == FileCategory.ARCHIVE ->
            readArchive(file, path)
        MimeTypes.isApk(file.mimeType, file.name) -> readApk(path)
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

    private fun readArchive(file: DriveFile, path: String): PreviewContent =
        when (file.mimeType) {
            "application/zip" -> runCatching {
                val entries = ZipFile(path).fileHeaders.map { header ->
                    ArchiveEntry(
                        name = header.fileName,
                        sizeBytes = header.uncompressedSize,
                        compressedBytes = header.compressedSize,
                        isDirectory = header.isDirectory,
                        // zip4j yields 0 for a dateless entry but the packed DOS
                        // dword itself as "millis" for a DOS-dated one (January 1970
                        // when read as a date — the field defect). Decode it back to
                        // the real date; see archiveEntryModifiedAtFromZip4j.
                        modifiedAtMillis = archiveEntryModifiedAtFromZip4j(
                            header.lastModifiedTime
                        )
                    )
                }
                PreviewContent.Archive(entries, "ZIP")
            }.getOrElse { PreviewContent.Failed(Res.string.preview_archive_failed) }

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
