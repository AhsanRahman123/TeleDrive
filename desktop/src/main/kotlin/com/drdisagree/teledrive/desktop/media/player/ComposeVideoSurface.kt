package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.drdisagree.teledrive.core.common.SafeLog
import java.nio.ByteBuffer
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallbackAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat

/**
 * VLC invokes [RenderCallback.display] on a native render thread. Every ImageBitmap must own
 * immutable pixel storage: Skia retains the byte array passed to installPixels for the lifetime
 * of the bitmap. Reusing one mutable byte array between frames lets VLC overwrite pixels that
 * Compose is still drawing, which can show partial/green frames when media dimensions change.
 */
internal data class VideoFramePixels(
    val width: Int,
    val height: Int,
    val rowBytes: Int,
    val pixels: ByteArray
) {
    fun toImageBitmap(): ImageBitmap? {
        if (width <= 0 || height <= 0 || rowBytes < width * BYTES_PER_PIXEL) return null
        val bitmap = Bitmap()
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        if (!bitmap.installPixels(info, pixels, rowBytes)) return null
        return bitmap.asComposeImageBitmap()
    }
}

/**
 * Records the buffer dimensions libVLC negotiates with the callback
 * surface (PC 1.6.27 N3 diagnostics).
 *
 * The viewer's first play of a video can render heavily pixelated at the
 * wrong resolution until the file is left and replayed. Every app-side
 * part of the pipeline is identical between a first play and a replay —
 * same instance arguments, same surface, same start call, same source
 * bytes — except ONE native step the app cannot see from code: the
 * dimensions libVLC hands this surface's format callback when it creates
 * the video output for a start. If a first play negotiates a smaller
 * buffer than a replay of the same file, every frame of that play is
 * scaled into the smaller buffer (blocky picture at a wrong resolution,
 * cured by the replay's renegotiation). This tracker makes that step
 * observable in the log: [record] returns true exactly when the
 * negotiated size changes, so the surface logs each negotiation once.
 */
internal class NegotiatedVideoFormat {
    @Volatile
    private var lastWidth: Int = 0

    @Volatile
    private var lastHeight: Int = 0

    /** The last negotiated size, or null before the first valid one. */
    fun current(): Pair<Int, Int>? =
        if (lastWidth > 0 && lastHeight > 0) lastWidth to lastHeight else null

    /**
     * Records one negotiation. Returns true when the size differs from
     * the previously recorded one (including the first valid size);
     * invalid sizes are ignored and never recorded.
     */
    @Synchronized
    fun record(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        if (width == lastWidth && height == lastHeight) return false
        lastWidth = width
        lastHeight = height
        return true
    }
}

internal fun composeVideoSurface(
    onFrame: (MediaPlayer, VideoFramePixels) -> Unit
): CallbackVideoSurface {
    val negotiatedFormat = NegotiatedVideoFormat()
    val bufferFormat = object : BufferFormatCallbackAdapter() {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
            // The surface always requests the buffer at the FULL size
            // libVLC reports for the source — it never asks for a
            // downscale. The negotiation is logged (N3) so a field log
            // shows the size each start actually negotiated: a first
            // play that reports less than the file's true size proves
            // the divergence is inside libVLC's native negotiation,
            // while a full-size report on a blocky first play points
            // below the surface (decoder/output), not at this seam.
            if (negotiatedFormat.record(sourceWidth, sourceHeight)) {
                SafeLog.d(
                    TAG,
                    "VLC negotiated video buffer ${sourceWidth}x$sourceHeight"
                )
            }
            return RV32BufferFormat(sourceWidth, sourceHeight)
        }
    }

    val render = object : RenderCallback {
        override fun lock(mediaPlayer: MediaPlayer) = Unit

        override fun unlock(mediaPlayer: MediaPlayer) = Unit

        override fun display(
            mediaPlayer: MediaPlayer,
            nativeBuffers: Array<ByteBuffer>,
            bufferFormat: BufferFormat,
            displayWidth: Int,
            displayHeight: Int
        ) {
            val width = bufferFormat.width
            val height = bufferFormat.height
            if (width <= 0 || height <= 0) return

            val rowBytes = bufferFormat.pitches.firstOrNull() ?: (width * BYTES_PER_PIXEL)
            if (rowBytes < width * BYTES_PER_PIXEL) return
            val byteCount = rowBytes.toLong() * height
            if (byteCount <= 0L || byteCount > Int.MAX_VALUE) return

            val source = nativeBuffers.firstOrNull() ?: return
            // Never publish a partial frame. A truncated copy is exactly the sort of
            // green/black strip that can appear during a resolution or media transition.
            val framePixels = copyVideoFramePixels(source, rowBytes, height) ?: return

            // Keep native callback work bounded. Skia bitmap construction happens after the
            // conflated handoff on the Compose dispatcher, so superseded frames are never decoded.
            // The emitting player travels with the frame: during an appearance
            // instance swap two players share this surface shape, and the
            // viewer accepts frames only from the player that is currently
            // live (see PlayerEventGate), never from a swap candidate.
            onFrame(mediaPlayer, VideoFramePixels(width, height, rowBytes, framePixels))
        }
    }

    return CallbackVideoSurface(
        bufferFormat,
        render,
        true,
        VideoSurfaceAdapters.getVideoSurfaceAdapter()
    )
}

/**
 * Copies one complete VLC plane into storage owned by the published Compose frame.
 * The returned array is never reused by later frames because Skia retains it.
 */
internal fun copyVideoFramePixels(
    source: ByteBuffer,
    rowBytes: Int,
    height: Int
): ByteArray? {
    if (rowBytes <= 0 || height <= 0) return null
    val byteCount = rowBytes.toLong() * height
    if (byteCount <= 0L || byteCount > Int.MAX_VALUE) return null

    val readable = source.duplicate().apply { position(0) }
    if (readable.remaining() < byteCount.toInt()) return null

    return ByteArray(byteCount.toInt()).also { readable.get(it) }
}

private const val BYTES_PER_PIXEL = 4
private const val TAG = "ComposeVideoSurface"
