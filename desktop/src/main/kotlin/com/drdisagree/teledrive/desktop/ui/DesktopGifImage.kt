package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.AnimationDisposalMode
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface

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

/**
 * Decoded GIF frames with their display durations, ready to cycle.
 *
 * The Compose [frames] wrap the retained Skia [retainedImages]; [close]
 * releases those native images deterministically. The viewer closes the
 * frames when it leaves the composition (or replaces them for a new
 * model) — until then the images must stay open, they ARE the display.
 */
class GifFrames(
    val frames: List<ImageBitmap>,
    val durationsMs: List<Int>,
    private val retainedImages: List<Image> = emptyList()
) : Closeable {
    override fun close() {
        retainedImages.forEach { image -> runCatching { image.close() } }
    }
}

/**
 * Decodes up to [maxFrames] frames of the GIF in [bytes] through Skia's
 * Codec, compositing dependent frames the way the GIF model requires:
 * Skia does NOT reconstruct an animation into a blank bitmap — for a
 * frame whose [org.jetbrains.skia.FrameInfo.requiredFrame] points at an
 * earlier frame (sub-rectangle updates, disposal modes — virtually every
 * optimised GIF), the caller must present that required frame's
 * composited pixels as the decode target. So frames are decoded
 * sequentially into one retained canvas: before each frame the canvas is
 * restored to the required frame's snapshot (or cleared when the frame
 * is independent), a restore-to-background previous frame has its
 * rectangle cleared on top of that, the Codec draws the frame's delta
 * onto the canvas, and the composited canvas is snapshotted as the
 * displayed frame.
 *
 * Memory is bounded in pixels, not just frames (1.6.25 second-pass
 * MF-2): the encoded input may not exceed [maxInputBytes], a single
 * canvas larger than [maxTotalPixels] does not decode at all, and when
 * the full animation would retain more than [maxTotalPixels] across its
 * frames the decode falls back to the first frame as a static image
 * instead of retaining an unbounded animation. Null when the bytes are
 * not a decodable GIF (or exceed the input/canvas bounds) — the caller
 * treats that exactly like any other undecodable image.
 */
fun decodeGifFrames(
    bytes: ByteArray,
    maxFrames: Int = MAX_GIF_FRAMES,
    maxTotalPixels: Long = MAX_GIF_TOTAL_PIXELS,
    maxInputBytes: Int = MAX_GIF_INPUT_BYTES
): GifFrames? = runCatching {
    if (bytes.size > maxInputBytes) return null
    val data = Data.makeFromBytes(bytes)
    try {
        val codec = Codec.makeFromData(data)
        try {
            decodeGifFrames(codec, maxFrames, maxTotalPixels)
        } finally {
            codec.close()
        }
    } finally {
        data.close()
    }
}.getOrNull()

private fun decodeGifFrames(codec: Codec, maxFrames: Int, maxTotalPixels: Long): GifFrames? {
    val width = codec.imageInfo.width
    val height = codec.imageInfo.height
    if (width <= 0 || height <= 0) return null
    val canvasPixels = width.toLong() * height.toLong()
    // One frame alone busting the pixel budget can never be shown safely.
    if (canvasPixels > maxTotalPixels) return null
    val available = minOf(codec.frameCount, maxFrames)
    if (available <= 0) return null
    // Retaining the whole animation would bust the pixel budget: fall
    // back to the first frame as a static image rather than OOM.
    val count = if (canvasPixels * available > maxTotalPixels) 1 else available
    val frameInfos = codec.framesInfo

    val surface = Surface.makeRasterN32Premul(width, height)
    try {
        val scratch = Bitmap()
        scratch.allocPixels(ImageInfo.makeN32Premul(width, height))
        val srcPaint = Paint().apply { blendMode = BlendMode.SRC }
        val clearPaint = Paint().apply {
            blendMode = BlendMode.SRC
            color = 0
        }
        val images = ArrayList<Image>(count)
        try {
            val canvas = surface.canvas
            val durations = ArrayList<Int>(count)
            for (index in 0 until count) {
                val frameInfo = frameInfos.getOrNull(index)
                val required = frameInfo?.requiredFrame ?: NO_REQUIRED_FRAME
                canvas.clear(0)
                if (required in 0 until index) {
                    // Restore the required frame's composited pixels; the
                    // Codec draws this frame's delta on top of them.
                    canvas.drawImage(images[required], 0f, 0f, srcPaint)
                    // A restore-to-background previous frame clears its
                    // own rectangle out of the frame it was drawn on
                    // before the next frame is drawn (GIF89a disposal 2).
                    val previous = frameInfos.getOrNull(index - 1)
                    if (required == index - 1 &&
                        previous?.disposalMode == AnimationDisposalMode.RESTORE_BG_COLOR
                    ) {
                        val rect = previous.frameRect
                        canvas.drawRect(
                            Rect.makeXYWH(
                                rect.left.toFloat(),
                                rect.top.toFloat(),
                                rect.width.toFloat(),
                                rect.height.toFloat()
                            ),
                            clearPaint
                        )
                    }
                }
                // The Codec decodes into a Bitmap, so route the canvas
                // through the scratch bitmap and back.
                check(surface.readPixels(scratch, 0, 0)) { "GIF canvas readback failed" }
                codec.readPixels(scratch, index)
                check(surface.writePixels(scratch, 0, 0)) { "GIF canvas writeback failed" }
                // A raster snapshot is copy-on-write: later canvas edits
                // can never mutate a frame already handed out.
                val snapshot = surface.makeImageSnapshot()
                images += snapshot
                durations += frameInfo?.duration ?: DEFAULT_FRAME_MS
            }
            return GifFrames(
                frames = images.map { it.asComposeImageBitmap() },
                durationsMs = durations,
                retainedImages = images
            )
        } catch (t: Throwable) {
            // A half-decoded animation is never handed out; its snapshots
            // are released here instead of leaking with the failure.
            images.forEach { image -> runCatching { image.close() } }
            throw t
        } finally {
            srcPaint.close()
            clearPaint.close()
            scratch.close()
        }
    } finally {
        surface.close()
    }
}

/** Skia's "this frame needs no earlier frame" marker in FrameInfo.requiredFrame. */
private const val NO_REQUIRED_FRAME = -1

private const val MAX_GIF_FRAMES = 300
private const val DEFAULT_FRAME_MS = 100

/**
 * Largest encoded GIF the viewer will load: 20 MiB, the same order of cap
 * the app already puts on its other in-app previews (20 MiB in-archive
 * document extraction, 20 MiB thumbnail entries; PDFs get 50 MiB because
 * PDFBox streams pages instead of retaining decoded frames).
 */
const val MAX_GIF_INPUT_BYTES: Int = 20 * 1024 * 1024

/**
 * Total decoded pixels retained across all frames of one GIF:
 * 33,554,432 px ≈ 128 MiB of N32 bitmaps at the cap — a hard ceiling a
 * single preview can never turn into the ~2.5 GB a 300-frame 1080p GIF
 * would otherwise retain on an 8 GB machine.
 */
const val MAX_GIF_TOTAL_PIXELS: Long = 33_554_432L

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
    // The decoded frames' native images are released deterministically
    // when this viewer leaves the composition or a new model's frames
    // replace them, never left to finalisation.
    DisposableEffect(decoded) {
        val gif = decoded
        onDispose { gif?.close() }
    }
    LaunchedEffect(model) {
        val result = withContext(Dispatchers.IO) {
            val bytes = when (model) {
                is File -> runCatching {
                    // Bounded read: never pull an unbounded file into
                    // memory for a preview (MF-2).
                    if (model.length() > MAX_GIF_INPUT_BYTES) null else model.readBytes()
                }.getOrNull()
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
