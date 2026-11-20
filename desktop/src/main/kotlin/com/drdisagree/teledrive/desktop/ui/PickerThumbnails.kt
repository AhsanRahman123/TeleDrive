package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * Real thumbnails for the upload picker's image files (PC-17).
 *
 * The Android thumbnail warm-up disaster (report 19) set the rules:
 * decode ONLY what is on screen (each row/cell loads its own thumbnail
 * when composed — the lazy lists never compose off-screen items), decode
 * downscaled at the source (ImageIO subsampling, never a full-res decode
 * then shrink), and keep a bounded in-memory cache. Nothing here ever
 * scans a folder ahead of display.
 *
 * Video files deliberately keep the generic file icon: the only video
 * machinery in this stack is VLC itself, and spinning up player
 * instances to grab frames for a folder listing is exactly the
 * mass-work pattern that made the phone lag. No duration badge either —
 * no cheap metadata reader exists in the stack.
 */

/**
 * Image extensions the picker decodes thumbnails for (name-based, no
 * I/O). Exactly the formats a stock ImageIO decodes — WebP is absent
 * because the JDK has no WebP reader; a WebP file keeps the file icon.
 */
private val PICKER_THUMBNAIL_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "bmp")

internal fun isPickerThumbnailCandidate(file: File): Boolean =
    file.extension.lowercase() in PICKER_THUMBNAIL_EXTENSIONS

/**
 * Power-of-two source subsampling factor for decoding a [width]×[height]
 * image down toward [maxDim]: the largest factor that still leaves both
 * dimensions at or above [maxDim] after one more halving would drop
 * below it — the standard sampled-decode rule, so the decoder skips
 * pixels instead of decoding full size and shrinking afterwards.
 */
internal fun pickerThumbnailSubsample(width: Int, height: Int, maxDim: Int): Int {
    if (width <= 0 || height <= 0 || maxDim <= 0) return 1
    var factor = 1
    while (width / (factor * 2) >= maxDim && height / (factor * 2) >= maxDim) {
        factor *= 2
    }
    return factor
}

/**
 * Bounded access-order LRU for decoded thumbnails. The key carries the
 * file's path, length, last-modified time and the requested decode size,
 * so a file that changes on disk (or is shown at the other size class)
 * never serves a stale bitmap. Generic over the value so the eviction
 * policy is testable without a graphics stack.
 */
internal class PickerThumbnailCache<V>(private val capacity: Int = 240) {
    private val entries = object : LinkedHashMap<String, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean =
            size > capacity
    }

    @Synchronized
    fun get(file: File, maxDim: Int): V? = entries[key(file, maxDim)]

    @Synchronized
    fun put(file: File, maxDim: Int, value: V) {
        entries[key(file, maxDim)] = value
    }

    @Synchronized
    fun size(): Int = entries.size

    private fun key(file: File, maxDim: Int): String =
        file.absolutePath + "|" + file.length() + "|" + file.lastModified() + "|" + maxDim
}

private val pickerThumbnailCache = PickerThumbnailCache<ImageBitmap>()

/** Longest-edge decode targets: list rows render at 40.dp, grid cells at ~84.dp tall. */
private const val LIST_THUMBNAIL_MAX_DIM = 160
private const val GRID_THUMBNAIL_MAX_DIM = 480

/**
 * Decodes [file] downscaled so its longest edge is at most [maxDim].
 * Pure AWT/ImageIO (headless-safe), null on any failure — the caller
 * falls back to the file icon.
 */
internal fun decodePickerThumbnailImage(file: File, maxDim: Int): BufferedImage? =
    runCatching {
        ImageIO.createImageInputStream(file)?.use { stream ->
            val reader = ImageIO.getImageReaders(stream).next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val param: ImageReadParam = reader.defaultReadParam.apply {
                    val factor = pickerThumbnailSubsample(width, height, maxDim)
                    if (factor > 1) setSourceSubsampling(factor, factor, 0, 0)
                }
                val sampled = reader.read(0, param)
                fitPickerThumbnail(sampled, maxDim)
            } finally {
                reader.dispose()
            }
        }
    }.getOrNull()

/** Scales [image] (never up) so its longest edge is at most [maxDim]. */
internal fun fitPickerThumbnail(image: BufferedImage, maxDim: Int): BufferedImage {
    val longest = maxOf(image.width, image.height)
    if (longest <= maxDim) return image
    val scale = maxDim.toDouble() / longest
    val targetWidth = (image.width * scale).toInt().coerceAtLeast(1)
    val targetHeight = (image.height * scale).toInt().coerceAtLeast(1)
    val type = if (image.type == BufferedImage.TYPE_CUSTOM) {
        BufferedImage.TYPE_INT_ARGB
    } else {
        image.type
    }
    val scaled = BufferedImage(targetWidth, targetHeight, type)
    val graphics = scaled.createGraphics()
    try {
        graphics.setRenderingHint(
            RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null)
    } finally {
        graphics.dispose()
    }
    return scaled
}

private fun loadPickerThumbnail(file: File, maxDim: Int): ImageBitmap? {
    pickerThumbnailCache.get(file, maxDim)?.let { return it }
    val decoded = decodePickerThumbnailImage(file, maxDim) ?: return null
    val bitmap = runCatching {
        val bytes = ByteArrayOutputStream().use { out ->
            check(ImageIO.write(decoded, "png", out)) { "No PNG writer" }
            out.toByteArray()
        }
        SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
    }.getOrNull() ?: return null
    pickerThumbnailCache.put(file, maxDim, bitmap)
    return bitmap
}

/**
 * The picker's per-entry visual: folder icon for directories, a decoded
 * thumbnail for image files (generic file icon while it loads and if
 * decoding fails), the file icon for everything else — videos included
 * (see the file header). Loads happen per composed item only.
 */
@Composable
internal fun PickerEntryThumbnail(entry: File, grid: Boolean) {
    if (entry.isDirectory) {
        Icon(
            imageVector = Icons.Filled.Folder,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        return
    }
    if (!isPickerThumbnailCandidate(entry)) {
        Icon(
            imageVector = Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
        return
    }
    val maxDim = if (grid) GRID_THUMBNAIL_MAX_DIM else LIST_THUMBNAIL_MAX_DIM
    var bitmap by remember(entry.absolutePath, maxDim) {
        mutableStateOf(pickerThumbnailCache.get(entry, maxDim))
    }
    LaunchedEffect(entry.absolutePath, maxDim) {
        if (bitmap == null) {
            bitmap = withContext(Dispatchers.IO) { loadPickerThumbnail(entry, maxDim) }
        }
    }
    val loaded = bitmap
    if (loaded != null) {
        Image(
            bitmap = loaded,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = if (grid) {
                Modifier.fillMaxWidth().height(84.dp)
            } else {
                Modifier.size(40.dp)
            }
        )
    } else {
        Icon(
            imageVector = Icons.Filled.InsertDriveFile,
            contentDescription = null,
            modifier = Modifier.size(40.dp)
        )
    }
}
