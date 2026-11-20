package com.drdisagree.teledrive.desktop.media.player

import java.io.File

/**
 * The tiny local media an appearance-swap candidate proves itself on
 * (PC-27).
 *
 * Before PC-27 the candidate proved itself by starting the session's own
 * media, muted, while the live player kept playing it. For a LOCAL file
 * that works — both players read the file directly. For a STREAMED file
 * it cannot: the stream is served from one byte source over TDLib's
 * single ranged-download window for the file, and every read the
 * candidate makes at an uncovered position re-seeks that window and then
 * blocks behind the live player's reads (and vice versa), so the
 * candidate never reaches Playing inside the proof timeout and every
 * Apply silently rolled back — subtitle customization was inert for
 * everything opened from the Gallery (streamed), while the same build's
 * local-file swaps flipped fine.
 *
 * The proof therefore uses this file instead: a quarter second of
 * silence as a plain WAV, written once per process into the system temp
 * directory. It exercises the candidate instance end to end (factory,
 * player, demux, decode, output) with zero network and zero contention,
 * for local and streamed sessions alike. The real media is only started
 * after the flip, when the candidate is the stream's only consumer (see
 * [PlayerSession]).
 */
internal object SwapProofMedia {

    private const val FILE_NAME = "teledrive-swap-proof.wav"
    private const val SAMPLE_RATE = 8_000
    private const val DURATION_MS = 250

    @Volatile
    private var cachedPath: String? = null

    /** Absolute path of the proof file, writing it first if needed. */
    fun mrl(): String {
        cachedPath?.let { path ->
            if (File(path).isFile) return path
        }
        val target = File(System.getProperty("java.io.tmpdir"), FILE_NAME)
        val bytes = wavBytes()
        val current = runCatching {
            if (target.isFile && target.length() == bytes.size.toLong()) target else null
        }.getOrNull()
        val resolved = current ?: runCatching {
            target.writeBytes(bytes)
            target
        }.getOrNull()
        val path = (resolved ?: target).absolutePath
        cachedPath = path
        return path
    }

    /**
     * A minimal valid WAV: 8 kHz 16-bit mono silence. Pure and pinned by
     * tests — a malformed proof file would fail every proof and quietly
     * restore the pre-PC-27 behaviour through the session's fallback.
     */
    internal fun wavBytes(): ByteArray {
        val sampleCount = SAMPLE_RATE * DURATION_MS / 1000
        val dataSize = sampleCount * 2 // 16-bit mono: 2 bytes per sample
        val bytes = ByteArray(44 + dataSize)

        fun ascii(offset: Int, text: String) {
            text.forEachIndexed { index, char -> bytes[offset + index] = char.code.toByte() }
        }

        fun int32(offset: Int, value: Int) {
            for (i in 0 until 4) bytes[offset + i] = (value ushr (8 * i)).toByte()
        }

        fun int16(offset: Int, value: Int) {
            for (i in 0 until 2) bytes[offset + i] = (value ushr (8 * i)).toByte()
        }

        ascii(0, "RIFF")
        int32(4, 36 + dataSize)
        ascii(8, "WAVE")
        ascii(12, "fmt ")
        int32(16, 16) // PCM fmt chunk size
        int16(20, 1) // PCM
        int16(22, 1) // mono
        int32(24, SAMPLE_RATE)
        int32(28, SAMPLE_RATE * 2) // byte rate
        int16(32, 2) // block align
        int16(34, 16) // bits per sample
        ascii(36, "data")
        int32(40, dataSize)
        // The data itself is left as zeroes: silence.
        return bytes
    }
}
