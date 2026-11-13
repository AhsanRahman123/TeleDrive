package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
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

internal fun composeVideoSurface(onFrame: (VideoFramePixels) -> Unit): CallbackVideoSurface {
    val bufferFormat = object : BufferFormatCallbackAdapter() {
        override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat =
            RV32BufferFormat(sourceWidth, sourceHeight)
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
            onFrame(VideoFramePixels(width, height, rowBytes, framePixels))
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
