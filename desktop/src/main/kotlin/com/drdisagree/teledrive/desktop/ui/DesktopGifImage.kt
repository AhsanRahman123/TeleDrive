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
import androidx.compose.ui.graphics.toComposeImageBitmap
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
            decodeGifFrames(codec, parseGifFrameRects(bytes), maxFrames, maxTotalPixels)
        } finally {
            codec.close()
        }
    } finally {
        data.close()
    }
}.getOrNull()

private fun decodeGifFrames(
    codec: Codec,
    frameRects: List<GifFrameRect>,
    maxFrames: Int,
    maxTotalPixels: Long
): GifFrames? {
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
                    // The disposal comes from Skia's own frame info (the
                    // property is `disposalMethod` in Skiko 0.150.1), but
                    // that version declares AnimationFrameInfo.frameRect
                    // `internal`, so the rectangle comes from our own GIF
                    // parse (see parseGifFrameRects); when the parse has
                    // no rectangle for the frame, fall back to the whole
                    // canvas — over-clearing beats leaving stale pixels.
                    val previous = frameInfos.getOrNull(index - 1)
                    if (required == index - 1 &&
                        previous?.disposalMethod == AnimationDisposalMode.RESTORE_BG_COLOR
                    ) {
                        val rect = frameRects.getOrNull(index - 1)
                        canvas.drawRect(
                            Rect.makeXYWH(
                                (rect?.left ?: 0).toFloat(),
                                (rect?.top ?: 0).toFloat(),
                                (rect?.width ?: width).toFloat(),
                                (rect?.height ?: height).toFloat()
                            ),
                            clearPaint
                        )
                    }
                }
                // The Codec decodes into a Bitmap, so route the canvas
                // through the scratch bitmap and back.
                check(surface.readPixels(scratch, 0, 0)) { "GIF canvas readback failed" }
                codec.readPixels(scratch, index)
                // Surface.writePixels returns Unit in Skiko 0.150.1
                // (unlike readPixels, which returns Boolean), so there
                // is no success flag to check here.
                surface.writePixels(scratch, 0, 0)
                // A raster snapshot is copy-on-write: later canvas edits
                // can never mutate a frame already handed out.
                val snapshot = surface.makeImageSnapshot()
                images += snapshot
                durations += frameInfo?.duration ?: DEFAULT_FRAME_MS
            }
            return GifFrames(
                // Compose Multiplatform 1.12.0: asComposeImageBitmap()
                // exists only for org.jetbrains.skia.Bitmap; the Skia
                // Image conversion is toComposeImageBitmap(), which
                // copies the snapshot's pixels into a Bitmap-backed
                // ImageBitmap.
                frames = images.map { it.toComposeImageBitmap() },
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

/** One frame's Image Descriptor rectangle, from [parseGifFrameRects]. */
private data class GifFrameRect(val left: Int, val top: Int, val width: Int, val height: Int)

/**
 * Walks the GIF byte stream and returns each frame's Image Descriptor
 * rectangle, in frame order. Skiko 0.150.1 (the version Compose
 * Multiplatform 1.12.0 pins) declares `AnimationFrameInfo.frameRect`
 * `internal`, so the compositing above cannot read the rectangle a
 * restore-to-background frame must clear from Skia — but the rectangle
 * is a fixed field of the Image Descriptor, so a small block walk
 * recovers it exactly. Bounds-checked throughout: a malformed stream
 * yields the rectangles found so far (possibly none) instead of
 * throwing, and the caller falls back to the full canvas for a missing
 * rectangle. Mirrors the byte-level parser in DesktopGifImageTest.
 */
private fun parseGifFrameRects(bytes: ByteArray): List<GifFrameRect> {
    if (!hasGifHeader(bytes)) return emptyList()
    var pos = GIF_HEADER_BYTES
    fun u8(): Int? = if (pos < bytes.size) bytes[pos++].toInt() and 0xFF else null
    fun u16(): Int? {
        val lo = u8() ?: return null
        val hi = u8() ?: return null
        return lo or (hi shl 8)
    }
    fun skip(count: Int): Boolean {
        if (count < 0 || pos + count > bytes.size) return false
        pos += count
        return true
    }
    fun skipSubBlocks(): Boolean {
        while (true) {
            val size = u8() ?: return false
            if (size == 0) return true
            if (!skip(size)) return false
        }
    }
    fun colourTableSize(packed: Int): Int = 3 * (1 shl ((packed and 0x07) + 1))

    // Logical Screen Descriptor: width, height, packed, background, aspect.
    if (u16() == null || u16() == null) return emptyList()
    val screenPacked = u8() ?: return emptyList()
    if (u8() == null || u8() == null) return emptyList()
    if (screenPacked and 0x80 != 0 && !skip(colourTableSize(screenPacked))) return emptyList()

    val rects = ArrayList<GifFrameRect>()
    while (pos < bytes.size) {
        when (u8()) {
            0x3B -> return rects // trailer
            0x21 -> {
                // Extension: a label, then data in sub-blocks. The
                // Graphic Control Extension's first sub-block is its
                // fixed 4-byte body; the generic sub-block walk covers
                // it and every other extension (application, comment,
                // plain text) alike.
                if (u8() == null || !skipSubBlocks()) return rects
            }
            0x2C -> {
                // Image Descriptor: left, top, width, height, packed.
                val left = u16() ?: return rects
                val top = u16() ?: return rects
                val width = u16() ?: return rects
                val height = u16() ?: return rects
                val descPacked = u8() ?: return rects
                if (descPacked and 0x80 != 0 && !skip(colourTableSize(descPacked))) return rects
                if (u8() == null || !skipSubBlocks()) return rects // LZW min code size + data
                rects += GifFrameRect(left, top, width, height)
            }
            else -> return rects // unknown block: stop rather than guess
        }
    }
    return rects
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
