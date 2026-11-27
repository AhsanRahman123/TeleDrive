package com.drdisagree.teledrive.core.archive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.27 item N4: the progressive-start policy. Pure decisions over a
 * produced prefix — the cases that decide whether an in-zip video starts in
 * a second or waits for a 1 GB entry: non-MP4 prefix threshold, fast-start
 * MP4 (moov before mdat), moov-at-end MP4 (honest delay, never a stall
 * presented as loading-forever), partial box headers, and 64-bit box sizes.
 */
class ProgressiveEntryStartTest {

    /** One MP4 box: 32-bit size + 4-char type + zero payload. */
    private fun box(type: String, payloadBytes: Int): ByteArray {
        val size = 8 + payloadBytes
        val out = ByteArray(size)
        out[0] = (size ushr 24).toByte()
        out[1] = (size ushr 16).toByte()
        out[2] = (size ushr 8).toByte()
        out[3] = size.toByte()
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        return out
    }

    /** One MP4 box with a 64-bit largesize header (size32 == 1). */
    private fun largeBox(type: String, payloadBytes: Int): ByteArray {
        val size = 16L + payloadBytes
        val out = ByteArray(size.toInt())
        out[3] = 1
        type.toByteArray(Charsets.US_ASCII).copyInto(out, 4)
        for (i in 0 until 8) {
            out[8 + i] = (size ushr (56 - 8 * i)).toByte()
        }
        return out
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var at = 0
        for (part in parts) {
            part.copyInto(out, at)
            at += part.size
        }
        return out
    }

    @Test
    fun `non-mp4 starts at the prefix threshold, not at 100 percent`() {
        val size = 40L * 1024 * 1024
        assertEquals(
            ProgressiveStartDecision.WaitForMore,
            ProgressiveStartPolicy.evaluate("movie.mkv", ByteArray(1024), 100_000L, size)
        )
        assertEquals(
            ProgressiveStartDecision.StartNow,
            ProgressiveStartPolicy.evaluate(
                "movie.mkv", ByteArray(1024),
                ProgressiveStartPolicy.DEFAULT_PREFIX_BYTES, size
            )
        )
    }

    @Test
    fun `non-mp4 smaller than the threshold starts when complete`() {
        val data = ByteArray(50_000) { 7 }
        assertEquals(
            ProgressiveStartDecision.WaitForMore,
            ProgressiveStartPolicy.evaluate("clip.webm", data, 10_000L, data.size.toLong())
        )
        assertEquals(
            ProgressiveStartDecision.StartNow,
            ProgressiveStartPolicy.evaluate("clip.webm", data, data.size.toLong(), data.size.toLong())
        )
    }

    @Test
    fun `fast-start mp4 starts once the whole moov has been produced`() {
        val ftyp = box("ftyp", 16)
        val moov = box("moov", 2_000)
        val mdatHeader = box("mdat", 0)
        val prefix = concat(ftyp, moov, mdatHeader)
        val entrySize = 30L * 1024 * 1024
        val moovEnd = (ftyp.size + moov.size).toLong()
        // moov only half produced: a player parsing a truncated moov would
        // error, so this must still wait.
        assertEquals(
            ProgressiveStartDecision.WaitForMore,
            ProgressiveStartPolicy.evaluate("movie.mp4", prefix, moovEnd - 500L, entrySize)
        )
        assertEquals(
            ProgressiveStartDecision.StartNow,
            ProgressiveStartPolicy.evaluate("movie.mp4", prefix, moovEnd, entrySize)
        )
    }

    @Test
    fun `moov-at-end mp4 is honestly delayed, then startable when complete`() {
        val ftyp = box("ftyp", 16)
        val mdat = box("mdat", 600_000)
        val moov = box("moov", 64)
        val whole = concat(ftyp, mdat, moov)
        val decision = ProgressiveStartPolicy.evaluate(
            "movie.mp4", whole.copyOfRange(0, 4_096), 4_096L, whole.size.toLong()
        )
        assertTrue(decision is ProgressiveStartDecision.DelayedUntilComplete)
        assertEquals(
            ProgressiveStartDecision.StartNow,
            ProgressiveStartPolicy.evaluate(
                "movie.mp4", whole, whole.size.toLong(), whole.size.toLong()
            )
        )
    }

    @Test
    fun `mdat extending to end of file before any moov is delayed`() {
        val ftyp = box("ftyp", 16)
        // mdat header with size field 0 = extends to EOF.
        val mdatHeader = ByteArray(8)
        System.arraycopy("mdat".toByteArray(Charsets.US_ASCII), 0, mdatHeader, 4, 4)
        val prefix = concat(ftyp, mdatHeader, ByteArray(1_000))
        val decision = ProgressiveStartPolicy.evaluate(
            "movie.mov", prefix, prefix.size.toLong(), 5_000_000L
        )
        assertTrue(decision is ProgressiveStartDecision.DelayedUntilComplete)
    }

    @Test
    fun `partial box header waits for more data`() {
        val decision = ProgressiveStartPolicy.evaluate(
            "movie.mp4", ByteArray(4), 4L, 5_000_000L
        )
        assertEquals(ProgressiveStartDecision.WaitForMore, decision)
    }

    @Test
    fun `large free box before moov does not start before the moov is produced`() {
        // MF-1 regression: ftyp -> free(2 MiB) -> moov. The retained prefix
        // (1 MiB cap) ends inside the free box, so the walk runs past it
        // without ever seeing the moov. Even with writtenBytes past the
        // free box, the verdict must not be StartNow until the moov is
        // fully produced (or the entry completes).
        val ftyp = box("ftyp", 16)
        val free = box("free", 2 * 1024 * 1024)
        val moov = box("moov", 2_000)
        val whole = concat(ftyp, free, moov)
        val entrySize = whole.size.toLong() + 5_000_000L
        val retained = whole.copyOf(1024 * 1024)
        val pastFree = (ftyp.size + free.size).toLong()
        assertEquals(
            ProgressiveStartDecision.WaitForMore,
            ProgressiveStartPolicy.evaluate("movie.mp4", retained, pastFree, entrySize)
        )
        // Once the whole moov has been produced it may start.
        val moovEnd = pastFree + moov.size
        val retainedThroughMoov = whole.copyOf(moovEnd.toInt())
        assertEquals(
            ProgressiveStartDecision.StartNow,
            ProgressiveStartPolicy.evaluate("movie.mp4", retainedThroughMoov, moovEnd, entrySize)
        )
    }

    @Test
    fun `mp4 with 64-bit largesize moov starts when that moov is produced`() {
        val ftyp = box("ftyp", 16)
        val moov = largeBox("moov", 3_000)
        val prefix = concat(ftyp, moov)
        val entrySize = 30L * 1024 * 1024
        assertEquals(
            ProgressiveStartDecision.StartNow,
            ProgressiveStartPolicy.evaluate(
                "movie.mp4", prefix, (ftyp.size + moov.size).toLong(), entrySize
            )
        )
        assertEquals(
            ProgressiveStartDecision.WaitForMore,
            ProgressiveStartPolicy.evaluate(
                "movie.mp4", prefix, (ftyp.size + 100).toLong(), entrySize
            )
        )
    }

    @Test
    fun `tracker latches the first decisive verdict and counts produced bytes`() {
        val tracker = ProgressiveStartTracker("movie.mkv", 10L * 1024 * 1024)
        val chunk = ByteArray(64 * 1024) { 3 }
        var decision: ProgressiveStartDecision? = null
        repeat(8) {
            val verdict = tracker.onChunk(chunk, 0, chunk.size)
            if (verdict != null) {
                assertNull("verdict must fire exactly once", decision)
                decision = verdict
            }
        }
        assertEquals(ProgressiveStartDecision.StartNow, decision)
        assertEquals(8L * 64 * 1024, tracker.producedBytes)
        // Later chunks never re-decide.
        assertNull(tracker.onChunk(chunk, 0, chunk.size))
        assertEquals(ProgressiveStartDecision.StartNow, tracker.decision)
    }

    @Test
    fun `tracker retains only the retained-prefix window`() {
        val tracker = ProgressiveStartTracker("movie.mkv", 100L * 1024 * 1024)
        val chunk = ByteArray(ProgressiveStartPolicy.RETAINED_PREFIX_BYTES + 100) { 1 }
        tracker.onChunk(chunk, 0, chunk.size)
        assertEquals(chunk.size.toLong(), tracker.producedBytes)
        // Producing past the window must not throw or lose the count.
        tracker.onChunk(chunk, 0, 50)
        assertEquals(chunk.size + 50L, tracker.producedBytes)
    }
}
