package com.drdisagree.teledrive.desktop.media

import com.drdisagree.teledrive.desktop.media.player.VlcPlayback
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.drdisagree.teledrive.core.crypto.CryptoKeys
import com.drdisagree.teledrive.core.crypto.StreamCrypto
import com.drdisagree.teledrive.core.crypto.WrappedKeyRepository
import com.drdisagree.teledrive.core.files.AppStoragePaths
import com.drdisagree.teledrive.core.files.MimeTypes
import com.drdisagree.teledrive.core.files.Urls
import com.drdisagree.teledrive.core.media.ThumbnailStore
import com.drdisagree.teledrive.core.media.ThumbnailChangeNotifier
import com.drdisagree.teledrive.data.local.dao.ThumbnailPolicyDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailJobDao
import com.drdisagree.teledrive.data.local.entity.ThumbnailPolicyEntity
import com.drdisagree.teledrive.data.local.entity.ThumbnailJobEntity
import com.drdisagree.teledrive.domain.model.ThumbnailPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import com.drdisagree.teledrive.core.telegram.TelegramClient
import com.drdisagree.teledrive.core.telegram.TelegramDownloadEvent
import com.drdisagree.teledrive.data.local.dao.FileDao
import com.drdisagree.teledrive.data.local.dao.ThumbnailDao
import com.drdisagree.teledrive.data.local.entity.FileEntity
import com.drdisagree.teledrive.data.local.entity.ThumbnailEntity
import com.drdisagree.teledrive.domain.repository.SettingsRepository
import java.awt.Color
import java.awt.Image
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import com.drdisagree.teledrive.core.media.ApkIconExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Cache files are sealed when thumbnail encryption is on; non-images fall back to the thumbnail
 * Telegram holds.
 */
class DesktopThumbnailStore(
    private val storagePaths: AppStoragePaths,
    private val fileDao: FileDao,
    private val thumbnailDao: ThumbnailDao,
    private val telegramClient: TelegramClient,
    private val streamCrypto: StreamCrypto,
    private val wrappedKeyRepository: WrappedKeyRepository,
    private val settingsRepository: SettingsRepository,
    private val thumbnailPolicyDao: ThumbnailPolicyDao,
    private val thumbnailJobDao: ThumbnailJobDao
) : ThumbnailStore {

    private val generationSemaphore = Semaphore(3)
    private val videoThumbnailMutex = Mutex()
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobWakeups = Channel<Unit>(Channel.CONFLATED)

    init {
        jobScope.launch {
            for (ignored in jobWakeups) processPendingJobs()
        }
        jobWakeups.trySend(Unit)
    }

    override suspend fun thumbnailBytes(fileId: String): ByteArray? {
        if (!settingsRepository.preferences.first().linkPreviews && isNote(fileId)) {
            thumbnailDao.delete(fileId)
            return null
        }
        readCachedThumbnail(fileId)?.let { return it }
        if (effectivePolicy(fileId) != ThumbnailPolicy.AUTOMATIC) return null
        return generationSemaphore.withPermit { generate(fileId) }
    }

    override suspend fun generateNow(fileId: String): ByteArray? {
        if (!settingsRepository.preferences.first().linkPreviews && isNote(fileId)) {
            return null
        }
        readCachedThumbnail(fileId)?.let { return it }
        return generationSemaphore.withPermit { generate(fileId) }
    }

    override suspend fun enqueueAfterUpload(fileId: String) {
        val inherited = thumbnailPolicyDao.effectivePolicy(fileId)
            ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
        if (inherited == ThumbnailPolicy.MANUAL_ONLY) return
        val now = System.currentTimeMillis()
        thumbnailPolicyDao.upsert(
            ThumbnailPolicyEntity(
                targetType = TARGET_FILE,
                targetId = fileId,
                policy = ThumbnailPolicy.AUTOMATIC.name,
                changedAt = now
            )
        )
        thumbnailJobDao.enqueue(
            ThumbnailJobEntity(fileId = fileId, queuedAt = now, force = false)
        )
        jobWakeups.trySend(Unit)
    }

    override suspend fun enqueueManual(fileId: String) {
        thumbnailJobDao.enqueue(
            ThumbnailJobEntity(
                fileId = fileId,
                queuedAt = System.currentTimeMillis(),
                force = true
            )
        )
        jobWakeups.trySend(Unit)
    }

    override suspend fun enqueueManual(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        fileIds.distinct().chunked(JOB_INSERT_BATCH_SIZE).forEach { batch ->
            thumbnailJobDao.enqueue(
                batch.map { id ->
                    ThumbnailJobEntity(
                        fileId = id,
                        queuedAt = timestamp,
                        force = true
                    )
                }
            )
            jobWakeups.trySend(Unit)
        }
    }

    override suspend fun enqueueAutomatic(fileId: String) {
        thumbnailJobDao.enqueue(
            ThumbnailJobEntity(
                fileId = fileId,
                queuedAt = System.currentTimeMillis(),
                force = false
            )
        )
        jobWakeups.trySend(Unit)
    }

    override suspend fun enqueueAutomatic(fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        fileIds.distinct().chunked(JOB_INSERT_BATCH_SIZE).forEach { batch ->
            thumbnailJobDao.enqueue(
                batch.map { id ->
                    ThumbnailJobEntity(
                        fileId = id,
                        queuedAt = timestamp,
                        force = false
                    )
                }
            )
            jobWakeups.trySend(Unit)
        }
    }

    private suspend fun readCachedThumbnail(fileId: String): ByteArray? {
        val cached = thumbnailDao.byFileId(fileId) ?: return null
        val file = File(cached.path)
        if (!file.exists()) {
            thumbnailDao.delete(fileId)
            return null
        }
        thumbnailDao.touch(fileId, System.currentTimeMillis())
        val raw = file.readBytes()
        return if (cached.encrypted) {
            runCatching {
                streamCrypto.decryptBytes(thumbnailKey(), raw)
            }.getOrNull()
        } else raw
    }

    private suspend fun effectivePolicy(fileId: String): ThumbnailPolicy =
        thumbnailPolicyDao.effectivePolicy(fileId)
            ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
            ?: ThumbnailPolicy.MANUAL_ONLY

    private suspend fun processPendingJobs() {
        while (true) {
            val job = thumbnailJobDao.next(1).firstOrNull() ?: return
            val entity = fileDao.byId(job.fileId)
            if (entity == null || entity.trashedAt != null) {
                thumbnailJobDao.delete(job.fileId)
                continue
            }
            val cached = thumbnailDao.byFileId(job.fileId)?.let { File(it.path).exists() } == true
            if (cached || (!job.force && effectivePolicy(job.fileId) != ThumbnailPolicy.AUTOMATIC)) {
                thumbnailJobDao.delete(job.fileId)
                continue
            }
            val bytes = runCatching {
                generationSemaphore.withPermit { generate(job.fileId) }
            }.getOrNull()
            if (bytes != null) {
                thumbnailJobDao.delete(job.fileId)
                continue
            }
            thumbnailJobDao.recordAttempt(job.fileId)
            if (job.attempts + 1 >= MAX_BACKGROUND_ATTEMPTS) {
                thumbnailJobDao.delete(job.fileId)
                continue
            }
            delay(BACKGROUND_RETRY_DELAY_MS)
            jobWakeups.trySend(Unit)
            return
        }
    }

    override suspend fun cacheUploadedThumbnail(
        fileId: String,
        temporaryPath: String
    ): Boolean {
        val existing = thumbnailDao.byFileId(fileId)
        if (existing != null) {
            if (File(existing.path).exists()) return true
            thumbnailDao.delete(fileId)
        }

        val inherited = thumbnailPolicyDao.effectivePolicy(fileId)
            ?.let { stored -> runCatching { ThumbnailPolicy.valueOf(stored) }.getOrNull() }
        if (inherited == ThumbnailPolicy.MANUAL_ONLY) return false

        val temporary = File(temporaryPath)
        if (!temporary.isFile || temporary.length() == 0L) return false
        val jpegBytes = runCatching { temporary.readBytes() }.getOrNull() ?: return false
        val encrypt = settingsRepository.preferences.first().encryptThumbnails
        val stored = if (encrypt) {
            streamCrypto.encryptBytes(thumbnailKey(), jpegBytes)
        } else jpegBytes
        val dir = File(storagePaths.cacheDir, "thumbnails").apply { mkdirs() }
        val target = File(dir, "$fileId.thumb")
        if (!runCatching { target.writeBytes(stored) }.isSuccess) return false

        val now = System.currentTimeMillis()
        thumbnailDao.upsert(
            ThumbnailEntity(
                fileId = fileId,
                path = target.absolutePath,
                encrypted = encrypt,
                width = MAX_DIMENSION,
                height = MAX_DIMENSION,
                sizeBytes = stored.size.toLong(),
                generatedAt = now,
                lastAccessAt = now
            )
        )
        thumbnailPolicyDao.upsert(
            ThumbnailPolicyEntity(
                targetType = TARGET_FILE,
                targetId = fileId,
                policy = ThumbnailPolicy.AUTOMATIC.name,
                changedAt = now
            )
        )
        ThumbnailChangeNotifier.notifyChanged(fileId, stored.size.toLong(), now, encrypt)
        return true
    }

    override suspend fun uploadThumbnailFile(fileId: String): File? {
        if (!settingsRepository.preferences.first().linkPreviews && isNote(fileId)) return null
        val bytes = readCachedThumbnail(fileId)
            ?: generationSemaphore.withPermit { generate(fileId, cacheResult = false) }
            ?: return null
        val dir = File(storagePaths.cacheDir, "upload-thumbs").apply { mkdirs() }
        val target = File(dir, "$fileId.jpg")
        return runCatching {
            target.writeBytes(bytes)
            target
        }.getOrNull()
    }

    private suspend fun isNote(fileId: String): Boolean =
        fileDao.byId(fileId)?.let { MimeTypes.isText(it.mimeType) } == true

    private suspend fun generate(fileId: String, cacheResult: Boolean = true): ByteArray? {
        val entity = fileDao.byId(fileId) ?: return null
        val local = entity.localPath?.let { path ->
            val file = File(path)
            when {
                !file.exists() -> null
                MimeTypes.isText(entity.mimeType) -> linkThumbnail(file)
                MimeTypes.isImage(entity.mimeType) -> decodeImageThumbnail(file)
                MimeTypes.isVideo(entity.mimeType) -> decodeVideoThumbnail(file)
                MimeTypes.isApk(entity.mimeType, file.name) -> decodeApkIcon(file)
                else -> null
            }
        }

        val jpegBytes = if (local != null) {
            toJpeg(local)
        } else {
            val iconFileId = entity.iconFileId
            if (iconFileId != null) {
                fetchIconBytes(entity, iconFileId) ?: fetchRemoteThumbnail(entity)
            } else {
                fetchRemoteThumbnail(entity)
            }
        } ?: return null

        if (cacheResult) {
            val encrypt = settingsRepository.preferences.first().encryptThumbnails
            val stored = if (encrypt) {
                streamCrypto.encryptBytes(thumbnailKey(), jpegBytes)
            } else jpegBytes

            val dir = File(storagePaths.cacheDir, "thumbnails").apply { mkdirs() }
            val target = File(dir, "$fileId.thumb")
            runCatching { target.writeBytes(stored) }.onFailure { return jpegBytes }

            val now = System.currentTimeMillis()
            thumbnailDao.upsert(
                ThumbnailEntity(
                    fileId = fileId,
                    path = target.absolutePath,
                    encrypted = encrypt,
                    width = MAX_DIMENSION,
                    height = MAX_DIMENSION,
                    sizeBytes = stored.size.toLong(),
                    generatedAt = now,
                    lastAccessAt = now
                )
            )
            ThumbnailChangeNotifier.notifyChanged(fileId, stored.size.toLong(), now, encrypt)
        }
        return jpegBytes
    }

    private suspend fun linkThumbnail(file: File): BufferedImage? {
        if (file.length() > LINK_NOTE_LIMIT) return null
        if (!settingsRepository.preferences.first().linkPreviews) return null
        val url = Urls.sole(file.readText()) ?: return null
        val imagePath = telegramClient
            .linkPreview(Urls.normalize(url), withImage = true)
            ?.imagePath
            ?: return null
        return File(imagePath).takeIf { it.exists() }?.let(::decodeImageThumbnail)
    }

    /**
     * Uses the already bundled/system libVLC to extract one frame from a local video.
     * Only one extraction runs at a time so opening a gallery with many videos stays bounded.
     */
    private suspend fun decodeVideoThumbnail(file: File): BufferedImage? =
        videoThumbnailMutex.withLock {
            val factory = VlcPlayback.factory ?: return@withLock null
            val target = File(
                File(storagePaths.cacheDir, "thumbnail-frames").apply { mkdirs() },
                "${file.nameWithoutExtension}-${System.nanoTime()}.png"
            )
            val frameReady = CompletableDeferred<Unit>()
            val snapshotReady = CompletableDeferred<String?>()
            val player = factory.mediaPlayers().newEmbeddedMediaPlayer()
            try {
                player.videoSurface().set(thumbnailVideoSurface {
                    frameReady.complete(Unit)
                })
                player.audio().isMute = true
                player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                    override fun snapshotTaken(mediaPlayer: MediaPlayer, filename: String) {
                        snapshotReady.complete(filename)
                    }

                    override fun error(mediaPlayer: MediaPlayer) {
                        frameReady.completeExceptionally(
                            IllegalStateException("VLC could not decode video thumbnail")
                        )
                    }

                    override fun finished(mediaPlayer: MediaPlayer) {
                        if (!frameReady.isCompleted) {
                            frameReady.completeExceptionally(
                                IllegalStateException("Video ended before a frame was rendered")
                            )
                        }
                    }
                })

                if (!player.media().play(file.absolutePath)) return@withLock null
                val rendered = withTimeoutOrNull(VIDEO_FRAME_TIMEOUT_MS) {
                    try {
                        frameReady.await()
                        true
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        false
                    }
                } ?: false
                if (!rendered) return@withLock null

                delay(VIDEO_SNAPSHOT_DELAY_MS)
                if (!player.snapshots().save(target, MAX_DIMENSION, 0)) return@withLock null

                val snapshotPath = withTimeoutOrNull(VIDEO_SNAPSHOT_TIMEOUT_MS) {
                    snapshotReady.await()
                } ?: target.absolutePath.takeIf { target.exists() }
                snapshotPath?.let { path ->
                    runCatching { ImageIO.read(File(path)) }.getOrNull()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                runCatching { player.controls().stop() }
                runCatching { player.release() }
                target.delete()
            }
        }

    private fun thumbnailVideoSurface(onFrame: () -> Unit): CallbackVideoSurface {
        val format = object : BufferFormatCallbackAdapter() {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
                RV32BufferFormat(sourceWidth, sourceHeight)
        }
        val render = object : RenderCallback {
            override fun lock(mediaPlayer: MediaPlayer) = Unit
            override fun unlock(mediaPlayer: MediaPlayer) = Unit
            override fun display(
                mediaPlayer: MediaPlayer,
                nativeBuffers: Array<java.nio.ByteBuffer>,
                bufferFormat: BufferFormat,
                displayWidth: Int,
                displayHeight: Int
            ) {
                onFrame()
            }
        }
        return CallbackVideoSurface(
            format,
            render,
            true,
            VideoSurfaceAdapters.getVideoSurfaceAdapter()
        )
    }

    private fun decodeApkIcon(file: File): BufferedImage? {
        val bytes = ApkIconExtractor.extractIconBytes(file) ?: return null
        return runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
    }

    private fun decodeImageThumbnail(file: File): BufferedImage? = runCatching {
        val original = ImageIO.read(file) ?: return null
        if (original.width <= MAX_DIMENSION && original.height <= MAX_DIMENSION) {
            return@runCatching original
        }
        val scale = minOf(
            MAX_DIMENSION.toDouble() / original.width,
            MAX_DIMENSION.toDouble() / original.height
        )
        val targetW = (original.width * scale).toInt().coerceAtLeast(1)
        val targetH = (original.height * scale).toInt().coerceAtLeast(1)
        val scaled = original.getScaledInstance(targetW, targetH, IMAGE_SCALE_MODE)
        BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB).also { copy ->
            val graphics = copy.createGraphics()
            graphics.drawImage(scaled, 0, 0, Color.WHITE, null)
            graphics.dispose()
        }
    }.getOrNull()

    private fun toJpeg(image: BufferedImage): ByteArray {
        val rgb = if (image.type == BufferedImage.TYPE_INT_RGB) {
            image
        } else {
            BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_RGB).also { copy ->
                val graphics = copy.createGraphics()
                graphics.drawImage(image, 0, 0, Color.WHITE, null)
                graphics.dispose()
            }
        }
        return ByteArrayOutputStream().use { output ->
            ImageIO.write(rgb, "jpg", output)
            output.toByteArray()
        }
    }

    private suspend fun fetchIconBytes(entity: FileEntity, iconFileId: String): ByteArray? {
        var readyPath: String? = null
        val rawRemoteFileId = iconFileId.substringBefore(":")
        runCatching {
            telegramClient.downloadDocument(rawRemoteFileId).collect { event ->
                if (event is TelegramDownloadEvent.Completed) {
                    readyPath = event.localPath
                }
            }
        }
        val path = readyPath ?: return null
        val rawBytes = runCatching { File(path).readBytes() }.getOrNull() ?: return null
        return if (entity.isEncrypted) {
            val key = wrappedKeyRepository.get(CryptoKeys.CONTENT) ?: return null
            runCatching { streamCrypto.decryptBytes(key, rawBytes) }.getOrNull()
        } else {
            rawBytes
        }
    }

    private suspend fun fetchRemoteThumbnail(entity: FileEntity): ByteArray? {
        val chatId = entity.chatId ?: return null
        val messageId = entity.messageId ?: return null
        if (MimeTypes.isText(entity.mimeType) &&
            !settingsRepository.preferences.first().linkPreviews
        ) return null
        return runCatching { telegramClient.fetchThumbnail(chatId, messageId) }.getOrNull()
    }

    private fun thumbnailKey(): ByteArray =
        wrappedKeyRepository.getOrCreate(CryptoKeys.THUMBNAIL)

    private companion object {
        const val TARGET_FILE = "FILE"
        const val MAX_BACKGROUND_ATTEMPTS = 3
        const val JOB_INSERT_BATCH_SIZE = 250
        const val BACKGROUND_RETRY_DELAY_MS = 5_000L
        const val LINK_NOTE_LIMIT = 8L * 1024
        const val MAX_DIMENSION = 512
        const val VIDEO_FRAME_TIMEOUT_MS = 8_000L
        const val VIDEO_SNAPSHOT_TIMEOUT_MS = 5_000L
        const val VIDEO_SNAPSHOT_DELAY_MS = 100L
        const val IMAGE_SCALE_MODE = Image.SCALE_SMOOTH
    }
}
