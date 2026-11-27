package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Animated-GIF support for the desktop image viewers (1.6.25 item 5).
 *
 * Coil is the app's image stack, but Coil 3's `coil-gif` artifact is
 * Android-only (it wraps Android's AnimatedImageDecoder/GifDecoder), so on
 * desktop there is no Coil decoder to register for GIF. The desktop
 * surfaces therefore route GIF models here instead of to the Coil painter:
 * frames are decoded with Skia's own Codec (Skiko is already the desktop
 * rendering engine under Compose) and cycled on the encoded per-frame
 * durations. Everything else — SVG included, via Coil's multiplatform
 * `coil-svg` — stays on the normal Coil path.
 */

/** True when [model] is a GIF: a `.gif` file, or bytes starting with a GIF header. */
fun isGifImageModel(model: Any): Boolean = when (model) {
    is File -> model.extension.equals("gif", ignoreCase = true) || runCatching {
        model.inputStream().use { stream ->
            val header = ByteArray(GIF_HEADER_BYTES)
            val read = stream.read(header)
            read == GIF_HEADER_BYTES && hasGifHeader(header)
        }
    }.getOrDefault(false)
    is ByteArray -> hasGifHeader(model)
    else -> false
}

/** True when [bytes] start with the GIF87a/GIF89a signature. */
fun hasGifHeader(bytes: ByteArray): Boolean {
    if (bytes.size < GIF_HEADER_BYTES) return false
    return bytes[0] == 'G'.code.toByte() &&
        bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() &&
        bytes[3] == '8'.code.toByte() &&
        (bytes[4] == '7'.code.toByte() || bytes[4] == '9'.code.toByte()) &&
        bytes[5] == 'a'.code.toByte()
}

private const val GIF_HEADER_BYTES = 6

/** Decoded GIF frames with their display durations, ready to cycle. */
class GifFrames(val frames: List<ImageBitmap>, val durationsMs: List<Int>)

/**
 * Decodes up to [maxFrames] frames of the GIF in [bytes] through Skia's
 * Codec, which composites each frame (disposal modes applied) — a frame
 * bitmap is the full picture at that point in the animation. Null when the
 * bytes are not a decodable GIF.
 */
fun decodeGifFrames(bytes: ByteArray, maxFrames: Int = MAX_GIF_FRAMES): GifFrames? =
    runCatching {
        val codec = Codec.makeFromData(Data.makeFromBytes(bytes))
        try {
            val count = minOf(codec.frameCount, maxFrames)
            if (count <= 0) return null
            val frames = ArrayList<ImageBitmap>(count)
            val durations = ArrayList<Int>(count)
            val frameInfos = codec.framesInfo
            for (index in 0 until count) {
                val bitmap = Bitmap()
                bitmap.allocPixels(
                    ImageInfo.makeN32Premul(codec.imageInfo.width, codec.imageInfo.height)
                )
                codec.readPixels(bitmap, index)
                frames += Image.makeFromBitmap(bitmap).asComposeImageBitmap()
                durations += frameInfos.getOrNull(index)?.duration ?: DEFAULT_FRAME_MS
            }
            GifFrames(frames, durations)
        } finally {
            codec.close()
        }
    }.getOrNull()

private const val MAX_GIF_FRAMES = 300
private const val DEFAULT_FRAME_MS = 100

/**
 * Plays the GIF behind [model] (a [File] or [ByteArray], as the image
 * viewers already carry) at fit scale, with the caller's rotate/mirror
 * transform applied. A single-frame or undecodable GIF shows its first
 * frame (or nothing, letting the caller's error state take over via
 * [onUndecodable]).
 */
@Composable
fun AnimatedGifImage(
    model: Any,
    contentDescription: String,
    imageRotationDegrees: Int,
    mirrorImageHorizontally: Boolean,
    mirrorImageVertically: Boolean,
    onToggleChrome: () -> Unit,
    onUndecodable: () -> Unit,
    modifier: Modifier = Modifier
) {
    var decoded by remember(model) { mutableStateOf<GifFrames?>(null) }
    var failed by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) {
        val result = withContext(Dispatchers.IO) {
            val bytes = when (model) {
                is File -> runCatching { model.readBytes() }.getOrNull()
                is ByteArray -> model
                else -> null
            }
            bytes?.let { decodeGifFrames(it) }
        }
        if (result == null || result.frames.isEmpty()) {
            failed = true
        } else {
            decoded = result
        }
    }
    LaunchedEffect(failed) {
        if (failed) onUndecodable()
    }
    val gif = decoded ?: return
    var frameIndex by remember(model) { mutableIntStateOf(0) }
    LaunchedEffect(model, gif) {
        if (gif.frames.size <= 1) return@LaunchedEffect
        while (true) {
            val duration = gif.durationsMs.getOrElse(frameIndex) { DEFAULT_FRAME_MS }
            delay(duration.coerceAtLeast(MIN_FRAME_MS).toLong())
            frameIndex = (frameIndex + 1) % gif.frames.size
        }
    }
    Image(
        bitmap = gif.frames[frameIndex.coerceIn(0, gif.frames.lastIndex)],
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { onToggleChrome() }
            }
            .graphicsLayer {
                rotationZ = imageRotationDegrees.toFloat()
                scaleX = if (mirrorImageHorizontally) -1f else 1f
                scaleY = if (mirrorImageVertically) -1f else 1f
            }
    )
}

private const val MIN_FRAME_MS = 20
