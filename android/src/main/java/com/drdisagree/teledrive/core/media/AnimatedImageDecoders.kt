package com.drdisagree.teledrive.core.media

import android.annotation.SuppressLint
import android.os.Build
import coil3.ComponentRegistry
import coil3.gif.AnimatedImageDecoder
import coil3.gif.GifDecoder

/**
 * Which animated-image decoder the shared Coil loader uses. Coil's default registry only
 * has the static bitmap decoder, so without one of these a GIF (or animated WebP/HEIF)
 * renders as its first frame only — in every viewer that uses the singleton loader.
 */
enum class AnimatedImageDecoderKind { ANIMATED_IMAGE_DECODER, GIF_DECODER }

/**
 * The Coil GIF docs' split: the ImageDecoder-backed decoder is faster and also covers
 * animated WebP/HEIF, but needs API 28+; the Movie-backed GIF decoder covers the rest
 * (this app's minSdk is 26). Pure on the SDK level so the branch is host-testable.
 */
fun animatedImageDecoderKind(sdkInt: Int): AnimatedImageDecoderKind =
    if (sdkInt >= Build.VERSION_CODES.P) {
        AnimatedImageDecoderKind.ANIMATED_IMAGE_DECODER
    } else {
        AnimatedImageDecoderKind.GIF_DECODER
    }

/**
 * Registers the animated-image decoder on the shared loader. The decoder factories only
 * claim sources whose headers are actually GIF / animated WebP / animated HEIF and
 * delegate everything else, so still images keep the exact decode path they had before,
 * and list thumbnails are untouched: the thumbnail fetcher serves pre-rendered JPEG
 * bytes, which these decoders never claim.
 */
@SuppressLint("NewApi")
fun ComponentRegistry.Builder.addAnimatedImageDecoder(
    sdkInt: Int = Build.VERSION.SDK_INT
) {
    when (animatedImageDecoderKind(sdkInt)) {
        AnimatedImageDecoderKind.ANIMATED_IMAGE_DECODER -> add(AnimatedImageDecoder.Factory())
        AnimatedImageDecoderKind.GIF_DECODER -> add(GifDecoder.Factory())
    }
}
