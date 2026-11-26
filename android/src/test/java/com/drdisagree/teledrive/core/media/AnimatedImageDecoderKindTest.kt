package com.drdisagree.teledrive.core.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decoder-branch seam for GIF support: the ImageLoader itself needs an Android
 * Context and a real image source, so it cannot be built in a host unit test — but the
 * SDK-level decision that registers AnimatedImageDecoder vs GifDecoder in MediaModule
 * is pure, and this pins it. API 28 is the boundary (ImageDecoder exists from 28);
 * the app's minSdk is 26, so 26/27 must take the Movie-backed GifDecoder.
 */
class AnimatedImageDecoderKindTest {

    @Test
    fun belowApi28UsesGifDecoder() {
        assertEquals(AnimatedImageDecoderKind.GIF_DECODER, animatedImageDecoderKind(26))
        assertEquals(AnimatedImageDecoderKind.GIF_DECODER, animatedImageDecoderKind(27))
    }

    @Test
    fun api28AndAboveUsesAnimatedImageDecoder() {
        assertEquals(
            AnimatedImageDecoderKind.ANIMATED_IMAGE_DECODER,
            animatedImageDecoderKind(28)
        )
        assertEquals(
            AnimatedImageDecoderKind.ANIMATED_IMAGE_DECODER,
            animatedImageDecoderKind(35)
        )
    }
}
