package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The PC-27 proof media: a malformed WAV would fail every candidate
 * proof and silently restore the pre-PC-27 behaviour through the
 * session's fallback, so the file's structure is pinned byte for byte —
 * RIFF/WAVE framing, a PCM fmt chunk, and a data chunk whose declared
 * sizes agree with the actual byte count.
 */
class SwapProofMediaTest {

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, Charsets.US_ASCII)

    private fun int32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun int16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `proof media is a well-formed WAV`() {
        val bytes = SwapProofMedia.wavBytes()

        assertEquals("RIFF", ascii(bytes, 0, 4))
        assertEquals("WAVE", ascii(bytes, 8, 4))
        assertEquals("fmt ", ascii(bytes, 12, 4))
        assertEquals("data", ascii(bytes, 36, 4))

        // RIFF chunk size covers everything after its own 8 bytes.
        assertEquals(bytes.size - 8, int32(bytes, 4))
        // PCM, mono.
        assertEquals(1, int16(bytes, 20))
        assertEquals(1, int16(bytes, 22))
        // The data chunk's declared size is the actual payload.
        assertEquals(bytes.size - 44, int32(bytes, 40))
        // 16-bit samples: payload is whole samples, and non-empty but tiny.
        assertEquals(0, (bytes.size - 44) % 2)
        assertTrue(bytes.size in 1_000..16_000)
    }

    @Test
    fun `proof media bytes are deterministic`() {
        assertTrue(SwapProofMedia.wavBytes().contentEquals(SwapProofMedia.wavBytes()))
    }
}
