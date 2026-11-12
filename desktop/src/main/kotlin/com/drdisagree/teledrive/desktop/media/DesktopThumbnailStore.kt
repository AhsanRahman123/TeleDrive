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
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.Volatile
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
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import com.drdisagree.teledrive.core.media.ApkIconExtractor
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.joinAll

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

    // The image decoder downsamples during decode, so each image worker uses much less heap.
    // Keep two cores free on larger CPUs, while allowing up to six bounded workers on desktops.
    private val maxParallelGenerations = minOf(
        MAX_PARALLEL_GENERATIONS,
        when (Runtime.getRuntime().availableProcessors()) {
            in Int.MIN_VALUE..2 -> 1
            in 3..4 -> 3
            else -> (Runtime.getRuntime().availableProcessors() * 3 / 4).coerceIn(4, 6)
        },
        (Runtime.getRuntime().maxMemory() / MIN_HEAP_PER_WORKER_BYTES).toInt().coerceAtLeast(1)
    )
    private val generationSemaphore = Semaphore(maxParallelGenerations)
    // Each video gets its own embedded VLC player. Two simultaneous decoders improve batches
    // without allowing a large batch to overwhelm CPU, disk or native VLC resources.
    private val videoThumbnailSemaphore = Semaphore(2)
    private val schedulerMutex = Mutex()
    @Volatile private var schedulingPaused = false
    private val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobWakeups = Channel<Unit>(Channel.CONFLATED)
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val processingIds = ConcurrentHashMap.newKeySet<String>()
    private var retryWakeupJob: Job? = null
    private val _activeJobs = MutableStateFlow<Set<String>>(emptySet())

    override fun observeActiveJobs(): Flow<Set<String>> = _activeJobs.asStateFlow()

    init {
        jobScope.launch {
            for (ignored in jobWakeups) {
                try {
                    processPendingJobs()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    com.drdisagree.teledrive.core.common.SafeLog.w(
                        "DesktopThumbnailStore",
                        "Thumbnail queue scheduler recovered from an error",
                        error
                    )
                    delay(BACKGROUND_RETRY_DELAY_MS)
                    jobWakeups.trySend(Unit)
                }
            }
        }
        jobWakeups.trySend(Unit)
    }

    override suspend fun cancelJobs(fileIds: List<String>) {
        schedulerMutex.withLock {
            val jobs = fileIds.distinct().mapNotNull(runningJobs::get)
            jobs.forEach {
                it.cancel(CancellationException("Thumbnail job cancelled by user"))
            }
            jobs.joinAll()
        }
        jobWakeups.trySend(Unit)
    }

    override suspend fun retryJobs(fileIds: List<String>) {
        schedulingPaused = false
            jobWakeups.trySend(Unit)
    }

    override suspend fun pauseJobs(fileIds: List<String>) {
        schedulerMutex.withLock {
            val jobs = fileIds.distinct().mapNotNull(runningJobs::get)
            jobs.forEach { it.cancel(CancellationException("Thumbnail job paused by user")) }
            jobs.joinAll()
        }
        jobWakeups.trySend(Unit)
    }

    override suspend fun pauseAllJobs() {
        schedulerMutex.withLock {
            schedulingPaused = true
            val jobs = runningJobs.values.toList()
            jobs.forEach { it.cancel(CancellationException("Thumbnail queue paused by user")) }
            jobs.joinAll()
            runningJobs.clear()
            processingIds.clear()
            _activeJobs.value = emptySet()
        }
    }

    override suspend fun resumeQueue() {
        schedulingPaused = false
        jobWakeups.trySend(Unit)
    }


    override suspend fun cancelAllJobs() {
        schedulerMutex.withLock {
            schedulingPaused = true
            val jobs = runningJobs.values.toList()
            jobs.forEach {
                it.cancel(CancellationException("All thumbnail jobs cancelled by user"))
            }
            jobs.joinAll()
            runningJobs.clear()
            processingIds.clear()
            _activeJobs.value = emptySet()
        }
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

        // Only create a file-level Automatic rule when this upload has no inherited rule.
        // If its folder is already Automatic, preserve that inheritance instead of
        // converting every newly uploaded file into an explicit override.
        if (inherited == null) {
            thumbnailPolicyDao.upsert(
                ThumbnailPolicyEntity(
                    targetType = TARGET_FILE,
                    targetId = fileId,
                    policy = ThumbnailPolicy.AUTOMATIC.name,
                    changedAt = System.currentTimeMillis()
                )
            )
        }
        thumbnailJobDao.enqueue(
            ThumbnailJobEntity(
                fileId = fileId,
                queuedAt = System.currentTimeMillis(),
                force = false
            )
        )
        schedulingPaused = false
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
        schedulingPaused = false
            jobWakeups.trySend(Unit)
    }

    override suspend fun enqueueManual(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        ids.chunked(JOB_INSERT_BATCH_SIZE).forEach { batch ->
            thumbnailJobDao.enqueue(
                batch.map { id ->
                    ThumbnailJobEntity(
                        fileId = id,
                        queuedAt = timestamp,
                        force = true
                    )
                }
            )
        }
        schedulingPaused = false
        jobWakeups.trySend(Unit)
    }

    override suspend fun enqueueAutomatic(fileId: String) {
        thumbnailJobDao.enqueue(
            ThumbnailJobEntity(
                fileId = fileId,
                queuedAt = System.currentTimeMillis(),
                force = false
            )
        )
        schedulingPaused = false
            jobWakeups.trySend(Unit)
    }

    override suspend fun enqueueAutomatic(fileIds: List<String>) {
        val ids = fileIds.distinct()
        if (ids.isEmpty()) return
        val timestamp = System.currentTimeMillis()
        ids.chunked(JOB_INSERT_BATCH_SIZE).forEach { batch ->
            thumbnailJobDao.enqueue(
                batch.map { id ->
                    ThumbnailJobEntity(
                        fileId = id,
                        queuedAt = timestamp,
                        force = false
                    )
                }
            )
        }
        schedulingPaused = false
        jobWakeups.trySend(Unit)
    }

    private suspend fun readCachedThumbnail(fileId: String): ByteArray? {
        val cached = thumbnailDao.byFileId(fileId) ?: return null
        val file = File(cached.path)
        if (!file.isFile || file.length() <= 0L) {
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

    /**
     * Schedule up to MAX_PARALLEL_GENERATIONS independent jobs. The database remains the
     * durable source of truth; in-memory jobs are only the currently executing subset.
     */
    private suspend fun processPendingJobs() = schedulerMutex.withLock {
        if (schedulingPaused || thumbnailPolicyDao.isQueuePaused()) return@withLock
        val available = (maxParallelGenerations - runningJobs.size).coerceAtLeast(0)
        if (available == 0) return@withLock
        val now = System.currentTimeMillis()
        // Include already-running rows in the result window, then skip them in memory.
        // Fetching only "available" rows could waste every slot on the active jobs at the
        // head of the durable queue and leave a worker slot idle.
        val ready = thumbnailJobDao.nextReady(
            limit = maxParallelGenerations,
            now = now,
            maxAttempts = MAX_BACKGROUND_ATTEMPTS
        )
        if (ready.isEmpty()) {
            val nextAt = thumbnailJobDao.nextQueuedAt(MAX_BACKGROUND_ATTEMPTS)
            if (runningJobs.isEmpty() && nextAt != null && nextAt > now) {
                if (retryWakeupJob?.isActive != true) {
                    retryWakeupJob = jobScope.launch {
                        delay((nextAt - System.currentTimeMillis()).coerceAtLeast(1L))
                        jobWakeups.trySend(Unit)
                    }
                }
            }
            return@withLock
        }
        retryWakeupJob?.cancel()
        retryWakeupJob = null
        var slots = available
        ready.forEach { job ->
            if (slots <= 0) return@forEach
            if (!processingIds.add(job.fileId)) return@forEach
            slots--
            val worker = jobScope.launch(start = CoroutineStart.LAZY) {
                _activeJobs.update { it + job.fileId }
                try {
                    processSingleJob(job)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    com.drdisagree.teledrive.core.common.SafeLog.w(
                        "DesktopThumbnailStore",
                        "Thumbnail preparation failed for ${job.fileId}",
                        error
                    )
                    scheduleRetry(job)
                } finally {
                    runningJobs.remove(job.fileId)
                    processingIds.remove(job.fileId)
                    _activeJobs.update { it - job.fileId }
                    jobWakeups.trySend(Unit)
                }
            }
            runningJobs[job.fileId] = worker
            worker.start()
        }
    }

    private suspend fun processSingleJob(job: ThumbnailJobEntity) {
        // A manual request may replace an automatic queue row just before this worker starts.
        // Read the durable row again so the current force flag and retry counter are honored.
        val currentJob = thumbnailJobDao.byFileId(job.fileId) ?: return
        val entity = fileDao.byId(currentJob.fileId)
        if (entity == null || entity.trashedAt != null) {
            thumbnailJobDao.delete(currentJob.fileId)
            return
        }
        val cachedEntry = thumbnailDao.byFileId(currentJob.fileId)
        val cachedFile = cachedEntry?.let { File(it.path) }
        val cached = cachedFile?.let { it.isFile && it.length() > 0L } == true
        if (cached || (!currentJob.force &&
                effectivePolicy(currentJob.fileId) != ThumbnailPolicy.AUTOMATIC)
        ) {
            thumbnailJobDao.delete(currentJob.fileId)
            return
        }
        // Remove stale/empty cache metadata so it cannot make an ungenerated file look complete.
        if (cachedEntry != null) thumbnailDao.delete(currentJob.fileId)
        // A non-null byte array alone is not proof that the cache was saved:
        // generate() can return usable bytes for a viewer even when disk writing fails.
        // Keep the durable queue row until both the metadata row and the file exist.
        generationSemaphore.withPermit { generate(currentJob.fileId) }
        currentCoroutineContext().ensureActive()
        val saved = thumbnailDao.byFileId(currentJob.fileId)?.let { cached ->
            val target = File(cached.path)
            target.isFile && target.length() > 0L
        } == true
        if (saved) {
            thumbnailJobDao.delete(currentJob.fileId)
        } else {
            scheduleRetry(currentJob)
        }
    }

    private suspend fun scheduleRetry(job: ThumbnailJobEntity) {
        val attempts = job.attempts + 1
        val retryAt = if (attempts >= MAX_BACKGROUND_ATTEMPTS) {
            Long.MAX_VALUE
        } else {
            System.currentTimeMillis() + BACKGROUND_RETRY_DELAY_MS
        }
        thumbnailJobDao.recordAttempt(job.fileId, retryAt)
        // The scheduler reads the earliest persisted retry time and owns one wakeup.
        // This also restores the wakeup after an app restart.
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

        currentCoroutineContext().ensureActive()
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
        videoThumbnailSemaphore.withPermit {
            val factory = VlcPlayback.factory ?: return@withPermit null
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

                if (!player.media().play(file.absolutePath)) return@withPermit null
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
                if (!rendered) return@withPermit null

                delay(VIDEO_SNAPSHOT_DELAY_MS)
                if (!player.snapshots().save(target, MAX_DIMENSION, 0)) return@withPermit null

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
        val imageInput = ImageIO.createImageInputStream(file) ?: return null
        imageInput.use { input ->
            val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: return null
            try {
                reader.input = input
                val sourceWidth = reader.getWidth(0)
                val sourceHeight = reader.getHeight(0)

                // Decode only enough source pixels to produce a 512px preview. ImageIO.read()
                // first expands the full original image, which is wasteful for large photos.
                val sample = maxOf(1, maxOf(sourceWidth, sourceHeight) / MAX_DIMENSION)
                val readParam = reader.defaultReadParam
                if (sample > 1) readParam.setSourceSubsampling(sample, sample, 0, 0)
                val decoded = reader.read(0, readParam) ?: return null

                if (decoded.width <= MAX_DIMENSION && decoded.height <= MAX_DIMENSION) {
                    decoded
                } else {
                    val scale = minOf(
                        MAX_DIMENSION.toDouble() / decoded.width,
                        MAX_DIMENSION.toDouble() / decoded.height
                    )
                    val targetW = (decoded.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (decoded.height * scale).toInt().coerceAtLeast(1)
                    BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB).also { copy ->
                        val graphics = copy.createGraphics()
                        graphics.drawImage(decoded, 0, 0, targetW, targetH, Color.WHITE, null)
                        graphics.dispose()
                    }
                }
            } finally {
                reader.dispose()
            }
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
        val writer = ImageIO.getImageWritersByFormatName("jpg").asSequence().firstOrNull()
            ?: return ByteArrayOutputStream().use { output ->
                ImageIO.write(rgb, "jpg", output)
                output.toByteArray()
            }
        return ByteArrayOutputStream().use { output ->
            try {
                ImageIO.createImageOutputStream(output).use { imageOutput ->
                    writer.output = imageOutput
                    val parameters = writer.defaultWriteParam
                    if (parameters.canWriteCompressed()) {
                        parameters.compressionMode = javax.imageio.ImageWriteParam.MODE_EXPLICIT
                        parameters.compressionQuality = THUMBNAIL_JPEG_QUALITY
                    }
                    writer.write(null, IIOImage(rgb, null, null), parameters)
                }
                output.toByteArray()
            } finally {
                writer.dispose()
            }
        }
    }

    private suspend fun fetchIconBytes(entity: FileEntity, iconFileId: String): ByteArray? {
        var readyPath: String? = null
        val rawRemoteFileId = iconFileId.substringBefore(":")
        try {
            telegramClient.downloadDocument(rawRemoteFileId).collect { event ->
                if (event is TelegramDownloadEvent.Completed) {
                    readyPath = event.localPath
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
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
        return try {
            telegramClient.fetchThumbnail(chatId, messageId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    private fun thumbnailKey(): ByteArray =
        wrappedKeyRepository.getOrCreate(CryptoKeys.THUMBNAIL)

    private companion object {
        const val TARGET_FILE = "FILE"
        const val MAX_BACKGROUND_ATTEMPTS = 5
        const val MAX_PARALLEL_GENERATIONS = 6
        const val MIN_HEAP_PER_WORKER_BYTES = 384L * 1024L * 1024L
        const val JOB_INSERT_BATCH_SIZE = 250
        const val BACKGROUND_RETRY_DELAY_MS = 5_000L
        const val LINK_NOTE_LIMIT = 8L * 1024
        const val MAX_DIMENSION = 768
        const val THUMBNAIL_JPEG_QUALITY = 0.86f
        const val VIDEO_FRAME_TIMEOUT_MS = 8_000L
        const val VIDEO_SNAPSHOT_TIMEOUT_MS = 5_000L
        const val VIDEO_SNAPSHOT_DELAY_MS = 100L
        const val IMAGE_SCALE_MODE = Image.SCALE_SMOOTH
    }
}
