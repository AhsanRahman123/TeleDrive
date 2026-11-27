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
import com.drdisagree.teledrive.resources.preview_epub_drm
import com.drdisagree.teledrive.resources.preview_fetch_failed
import com.drdisagree.teledrive.core.archive.ArchiveErrorKind
import com.drdisagree.teledrive.core.archive.ArchiveException
import com.drdisagree.teledrive.core.archive.LocalArchiveFormat
import com.drdisagree.teledrive.core.archive.RemoteArchiveService
import com.drdisagree.teledrive.core.archive.localArchiveReader
import com.drdisagree.teledrive.core.files.formatPreviewText
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
                if (content is PreviewContent.Archive &&
                    (content.format == "ZIP" || content.format == "CBZ") &&
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
        // never starts unasked. PDF and EPUB/OOXML documents get their own larger caps
        // (see isAutoFetchable) so a remote copy previews exactly like an in-zip entry.
        val autoFetchable = isAutoFetchable(
            mimeType = file.mimeType,
            category = file.category,
            fileName = file.name,
            sizeBytes = file.sizeBytes
        )
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

    /**
     * Preview an arbitrary saved local file (a completed Transfers row with
     * no drive file id, e.g. an in-ZIP download — PC 1.6.26 P6, ported from
     * Android vc181). The mime/category are derived from [displayName]
     * alone, then the exact same local dispatch as the Files preview runs.
     * A stale path whose file was deleted fails cleanly with
     * [Res.string.preview_no_copy_available] instead of crashing or hanging
     * in a renderer.
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

    private fun captureVideoFrame(file: DriveFile, parts: List<MediaPart>) {
        if (!file.isEncrypted || file.category != FileCategory.VIDEO) return
        scope.launch {
            runCatching { thumbnailStore.storeFromStream(file.id, parts, encrypted = true) }
        }
    }

    private fun isZipFile(file: DriveFile): Boolean {
        // An EPUB/OOXML file is a ZIP container on the inside, and a stored or
        // Telegram-declared MIME of application/zip for one must NOT route it
        // to the archive browser: it is a document, exactly as the in-zip
        // route treats a same-named archive entry (vc183 item 4 semantics).
        if (isDocumentPreviewType(file.mimeType, file.name)) return false
        return file.mimeType == "application/zip" ||
            file.extension.equals("zip", ignoreCase = true) ||
            isCbzFile(file)
    }

    /** A CBZ is a ZIP of comic pages: it browses and extracts through the ZIP machinery. */
    private fun isCbzFile(file: DriveFile): Boolean =
        file.mimeType == "application/vnd.comicbook+zip" ||
            file.extension.equals("cbz", ignoreCase = true)

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
        PreviewContent.Archive(entries, if (isCbzFile(file)) "CBZ" else "ZIP", remote = true)
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

    private fun fromLocal(file: DriveFile, path: String): PreviewContent =
        fromLocal(
            mimeType = file.mimeType,
            category = file.category,
            fileName = file.name,
            path = path
        )

    // The dispatch itself is the pure [previewDispatch] seam below; this
    // function only maps each kind to its content builder.
    private fun fromLocal(
        mimeType: String,
        category: FileCategory,
        fileName: String,
        path: String
    ): PreviewContent = when (previewDispatch(mimeType, category, fileName)) {
        PreviewDispatch.IMAGE -> PreviewContent.Image(File(path))
        PreviewDispatch.VIDEO -> PreviewContent.LocalMedia(path, isAudio = false)
        PreviewDispatch.AUDIO -> PreviewContent.LocalMedia(path, isAudio = true)
        PreviewDispatch.PDF -> PreviewContent.Pdf(path)
        PreviewDispatch.HTML -> readHtml(path)
        PreviewDispatch.DOCUMENT -> readDocumentText(path)
        PreviewDispatch.TEXT -> readText(path, mimeType)
        PreviewDispatch.ARCHIVE -> readArchive(mimeType, path)
        PreviewDispatch.APK -> readApk(path)
        PreviewDispatch.UNSUPPORTED ->
            PreviewContent.Unsupported(Res.string.preview_no_preview_for_type, path)
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

    /** Reads up to [limit] bytes of [path] as UTF-8 text; the flag marks a longer file. */
    private fun readTextBytes(path: String, limit: Int = TEXT_LIMIT): Pair<String, Boolean> {
        val target = File(path)
        val truncated = target.length() > limit
        val bytes = target.inputStream().use { stream ->
            val buffer = ByteArray(limit)
            var read = 0
            while (read < limit) {
                val count = stream.read(buffer, read, limit - read)
                if (count <= 0) break
                read += count
            }
            buffer.copyOf(read)
        }
        return String(bytes, Charsets.UTF_8) to truncated
    }

    private fun readText(path: String, mimeType: String): PreviewContent = runCatching {
        val (raw, truncated) = readTextBytes(path)
        PreviewContent.PlainText(formatPreviewText(raw, mimeType), truncated)
    }.getOrElse { PreviewContent.Failed(Res.string.preview_read_failed) }

    /**
     * EPUB (Tier 1) and OOXML files extract to plain text on this IO flow. A DRM'd
     * EPUB gets its own honest message; an unparseable document keeps "Open with…".
     * Legacy .doc/.xls/.ppt never reach here — they are not OOXML and stay Unsupported.
     */
    private fun readDocumentText(path: String): PreviewContent =
        when (val extraction = extractDocumentText(File(path))) {
            is DocumentExtraction.Text ->
                PreviewContent.PlainText(extraction.text, extraction.truncated)
            DocumentExtraction.DrmProtected ->
                PreviewContent.Failed(Res.string.preview_epub_drm)
            DocumentExtraction.Failed ->
                PreviewContent.Unsupported(Res.string.preview_no_preview_for_type, path)
        }

    /**
     * HTML goes to the safe viewer as a raw string — no formatting transform applies.
     *
     * Round 3 A10: HTML gets its own read budget instead of the plain-text
     * [TEXT_LIMIT]. A real-world HTML page carries its CSS and scripts inline, so
     * 256 KiB cut a long checklist page off part-way down — the viewer faithfully
     * rendered everything it was handed, and everything past the cap never existed
     * as far as it was concerned. The budget is [HTML_PREVIEW_LIMIT] (10 MiB, the
     * same value as the remote auto-fetch gate for text, so a remote HTML file is
     * never fetched at one size and read at a smaller one); past it the viewer
     * still shows the truncated notice via [PreviewContent.Html.truncated].
     */
    private fun readHtml(path: String): PreviewContent = runCatching {
        val (raw, truncated) = readTextBytes(path, HTML_LIMIT)
        PreviewContent.Html(raw, truncated)
    }.getOrElse { PreviewContent.Failed(Res.string.preview_read_failed) }

    private fun readLocalZip(path: String, format: String = "ZIP"): PreviewContent =
        runCatching {
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
            PreviewContent.Archive(entries, format)
        }.getOrElse { PreviewContent.Failed(Res.string.preview_archive_failed) }

    private fun readArchive(mimeType: String, path: String): PreviewContent =
        when (mimeType) {
            "application/zip" -> readLocalZip(path)

            // A CBZ is listed by the exact ZIP lister; only the format label
            // differs, which is what switches the viewer to the comic pager.
            "application/vnd.comicbook+zip" -> readLocalZip(path, format = "CBZ")

            // Listing delegates to the local readers (Android vc181 B10 port):
            // the entries produced are exactly the ones the open-coded listing
            // produced, and the same readers now also extract for preview.
            "application/x-7z-compressed" -> runCatching {
                val entries = localArchiveReader(LocalArchiveFormat.SEVEN_Z).list(File(path))
                PreviewContent.Archive(entries, LocalArchiveFormat.SEVEN_Z.archiveLabel)
            }.getOrElse { PreviewContent.Failed(Res.string.preview_archive_failed) }

            "application/x-tar" -> readTar(path, compressed = false)
            "application/x-compressed-tar" -> readTar(path, compressed = true)

            else -> PreviewContent.Unsupported(Res.string.preview_only_zip, path)
        }

    private fun readTar(path: String, compressed: Boolean): PreviewContent = runCatching {
        val format = if (compressed) {
            LocalArchiveFormat.TAR_COMPRESSED
        } else {
            LocalArchiveFormat.TAR
        }
        PreviewContent.Archive(
            entries = localArchiveReader(format).list(File(path)),
            format = format.archiveLabel
        )
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
            // A non-empty cache file is only reused when it is the WHOLE file:
            // a truncated copy left by an interrupted earlier fetch otherwise
            // stayed cached forever and every later preview extracted from
            // the wrong (short) content object — an EPUB whose container.xml
            // was cut off read as "No preview for this file type" and a DOCX
            // with a cut document.xml extracted to blank text (vc183 item 4).
            if (file.sizeBytes <= 0 || target.length() == file.sizeBytes) {
                cacheDao.touch(target.absolutePath, System.currentTimeMillis())
                return target
            }
            target.delete()
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

        // Public (not private) only so the top-level pure isAutoFetchable seam
        // in this file — and its host tests — can use the same values.
        const val AUTO_PREVIEW_LIMIT = 10L * 1024 * 1024

        /**
         * Largest remote PDF fetched on open: 50 MiB. Deliberately equal to the
         * in-zip cap ARCHIVE_PDF_PREVIEW_MAX_BYTES (ArchiveSelection.kt), as a
         * literal with this parity note — the same way the in-zip cap itself is
         * defined — rather than a new cross-reference.
         */
        const val PDF_AUTO_PREVIEW_LIMIT = 50L * 1024 * 1024

        /**
         * Largest remote EPUB/OOXML document fetched on open: 20 MiB.
         * Deliberately equal to the in-zip cap
         * ARCHIVE_DOCUMENT_PREVIEW_MAX_BYTES (ArchiveSelection.kt), as a
         * literal with this parity note, for the same reason as
         * [PDF_AUTO_PREVIEW_LIMIT].
         */
        const val DOCUMENT_AUTO_PREVIEW_LIMIT = 20L * 1024 * 1024

        private const val TEXT_LIMIT = 256 * 1024

        /**
         * Largest HTML document read whole for the viewer: 10 MiB, deliberately
         * equal to [AUTO_PREVIEW_LIMIT] (the remote auto-fetch cap that already
         * applies to `text/html` through `MimeTypes.isText`) as a literal with
         * this parity note, the same way [PDF_AUTO_PREVIEW_LIMIT] is defined.
         * Public only so host tests can pin the value, like the other limits.
         */
        const val HTML_PREVIEW_LIMIT = 10L * 1024 * 1024

        private const val HTML_LIMIT = 10 * 1024 * 1024
    }
}

/** The viewer a normal-route local file is dispatched to (see [previewDispatch]). */
enum class PreviewDispatch {
    IMAGE, VIDEO, AUDIO, PDF, HTML, DOCUMENT, TEXT, ARCHIVE, APK, UNSUPPORTED
}

/**
 * Whether a file is an EPUB/OOXML document for preview purposes, by its stored
 * MIME **or by its file name** — the same name-based classification the in-zip
 * route has always used (`archiveEntryTapAction` keys off
 * `MimeTypes.fromFileName(entry.name)` alone).
 *
 * vc183 item 4 semantics, ported to PC with 1.6.25 item 5: the normal route
 * previously trusted the stored MIME alone, but that MIME is not reliable for
 * these formats — `MimeTypes.resolve` prefers a declared MIME over the
 * filename, and EPUB/OOXML are ZIP containers, so a Telegram- or
 * client-declared `application/zip` (or another container-ish value) is
 * exactly what such a file can carry. With the stored MIME alone, the normal
 * dispatch then sent the file to the archive branch (or to Unsupported) and
 * the document viewer was never reached, while the identical file inside a
 * ZIP — classified by name — previewed fine.
 */
fun isDocumentPreviewType(mimeType: String, fileName: String): Boolean {
    if (MimeTypes.isEpub(mimeType) || MimeTypes.isOoxml(mimeType)) return true
    val byName = MimeTypes.fromFileName(fileName)
    return MimeTypes.isEpub(byName) || MimeTypes.isOoxml(byName)
}

/**
 * Whether a file is a text file for preview purposes, by its stored MIME
 * **or by its file name** — the same name-based classification the in-zip
 * route has always used (`archiveEntryTapAction` keys off
 * `MimeTypes.fromFileName(entry.name)` alone) and the same pattern as
 * [isDocumentPreviewType].
 *
 * R3 item X1: the normal route previously trusted the stored MIME alone
 * for text, but that MIME is not reliable for text files either —
 * `MimeTypes.resolve` prefers a declared MIME over the filename, and a
 * README.md declared by Telegram (or a sync manifest) as a generic or
 * container type then missed the text branch entirely and landed on
 * "No preview for this file type", while the identical file inside a
 * ZIP — classified by name — previewed fine. Extension-less text files
 * (README, LICENSE, …) have no name-derived MIME at all, so they are
 * recognised by their conventional names
 * ([MimeTypes.isExtensionlessTextName]).
 */
fun isTextPreviewType(mimeType: String, fileName: String): Boolean =
    MimeTypes.isText(mimeType) ||
        MimeTypes.isText(MimeTypes.fromFileName(fileName)) ||
        MimeTypes.isExtensionlessTextName(fileName)

/**
 * The normal-route preview dispatch, pure so host tests can pin it.
 *
 * Branch order: image -> video -> audio -> pdf -> html -> document -> text ->
 * archive -> apk -> unsupported. The DOCUMENT branch deliberately sits
 * BEFORE text and archive (vc183 item 4): an EPUB's MIME ends in `+zip` and
 * an OOXML file is a ZIP of XML, so under the old order (text -> archive ->
 * document) any stored MIME that read as text or archive captured the file
 * first and the document branch was unreachable for it — a remote EPUB
 * landed on "No preview for this file type" and a DOCX that was captured by
 * the text branch (or extracted to an empty string) opened blank. Every
 * other type keeps its original relative order and outcome: a real `.zip`
 * (name-derived MIME `application/zip`, not a document type) still archives,
 * a CBZ still archives, and legacy `.doc`/`.xls`/`.ppt` are not OOXML and
 * stay unsupported.
 */
fun previewDispatch(
    mimeType: String,
    category: FileCategory,
    fileName: String
): PreviewDispatch = when {
    MimeTypes.isImage(mimeType) || category == FileCategory.IMAGE -> PreviewDispatch.IMAGE
    MimeTypes.isVideo(mimeType) || category == FileCategory.VIDEO -> PreviewDispatch.VIDEO
    MimeTypes.isAudio(mimeType) || category == FileCategory.AUDIO -> PreviewDispatch.AUDIO
    MimeTypes.isPdf(mimeType) -> PreviewDispatch.PDF
    MimeTypes.isHtml(mimeType) -> PreviewDispatch.HTML
    isDocumentPreviewType(mimeType, fileName) -> PreviewDispatch.DOCUMENT
    isTextPreviewType(mimeType, fileName) -> PreviewDispatch.TEXT
    MimeTypes.isArchive(mimeType) || category == FileCategory.ARCHIVE -> PreviewDispatch.ARCHIVE
    MimeTypes.isApk(mimeType, fileName) -> PreviewDispatch.APK
    else -> PreviewDispatch.UNSUPPORTED
}

/**
 * Whether a remote-only file is fetched automatically on open instead of
 * showing the Download-to-view gate (PreviewContent.RequiresDownload).
 *
 * The original rule is unchanged: images, text and APKs fetch only up to
 * [PreviewContentResolver.AUTO_PREVIEW_LIMIT] (10 MiB). The document types
 * that already preview in place inside a ZIP get parity: a PDF fetches up
 * to [PreviewContentResolver.PDF_AUTO_PREVIEW_LIMIT] and an EPUB or OOXML
 * document up to [PreviewContentResolver.DOCUMENT_AUTO_PREVIEW_LIMIT] —
 * exactly the in-zip caps — so the same file is not gated on the
 * normal-file route while being previewable in-zip. Everything else
 * (unsupported types, images/text/APKs over 10 MiB, documents over their
 * caps) keeps the gate exactly as before. Pure and side-effect free so
 * host tests can pin the boundaries.
 */
fun isAutoFetchable(
    mimeType: String,
    category: FileCategory,
    fileName: String,
    sizeBytes: Long
): Boolean =
    (sizeBytes <= PreviewContentResolver.AUTO_PREVIEW_LIMIT &&
        (MimeTypes.isImage(mimeType) ||
            category == FileCategory.IMAGE ||
            isTextPreviewType(mimeType, fileName) ||
            MimeTypes.isApk(mimeType, fileName))) ||
        (MimeTypes.isPdf(mimeType) && sizeBytes <= PreviewContentResolver.PDF_AUTO_PREVIEW_LIMIT) ||
        (isDocumentPreviewType(mimeType, fileName) &&
            sizeBytes <= PreviewContentResolver.DOCUMENT_AUTO_PREVIEW_LIMIT)
