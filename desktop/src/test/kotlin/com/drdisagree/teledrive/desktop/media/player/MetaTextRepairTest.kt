package com.drdisagree.teledrive.desktop.media.player

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetaTextRepairTest {

    /**
     * The exact conversion the vlcj/JNA seam performs on libvlc's UTF-8
     * meta bytes: JNA `Pointer.getString(0)` is
     * `new String(bytes, Native.getDefaultStringEncoding())`, and the
     * default encoding is the OS native charset — windows-1252 on the
     * field PC. Reproduced here with that charset so the proof runs on
     * any CI machine.
     */
    private fun seamMangle(original: String): String =
        String(original.toByteArray(Charsets.UTF_8), Charset.forName("windows-1252"))

    @Test
    fun `seam proof - utf8 bytes decoded as windows-1252 reproduce the screenshot pattern`() {
        // The 1.6.23 field screenshot's title line starts with the UTF-8
        // BOM rendered as ï»¿ and contains ã€‚ (for 。) and ã€Ž (for 『).
        assertEquals("ï»¿", seamMangle("\uFEFF"))
        assertEquals("ã€‚", seamMangle("。"))
        assertEquals("ã€Ž", seamMangle("『"))
        assertTrue(seamMangle("\uFEFFタイトル").startsWith("ï»¿"))
    }

    @Test
    fun `screenshot pattern - bom plus japanese title is recovered without the bom`() {
        val original = "\uFEFFタイトル「テスト。"
        val mangled = seamMangle(original)
        assertEquals("タイトル「テスト。", repairMetaText(mangled))
    }

    @Test
    fun `japanese title artist and album survive the seam`() {
        assertEquals("桜・音楽", repairMetaText(seamMangle("桜・音楽")))
        assertEquals("音楽ユニット", repairMetaText(seamMangle("音楽ユニット")))
        assertEquals("タイトル", repairMetaText(seamMangle("タイトル")))
    }

    @Test
    fun `korean tags survive the seam`() {
        assertEquals("한국어 노래", repairMetaText(seamMangle("한국어 노래")))
    }

    @Test
    fun `mixed japanese and ascii is recovered as one string`() {
        val original = "タイトル / Title 17 - MV"
        assertEquals(original, repairMetaText(seamMangle(original)))
    }

    @Test
    fun `double mangled text is recovered`() {
        val original = "音楽ユニット"
        assertEquals(original, repairMetaText(seamMangle(seamMangle(original))))
    }

    @Test
    fun `bytes the lossy decode destroys are not guessed at`() {
        // の is E3 81 AE in UTF-8; byte 0x81 is undefined in windows-1252
        // and Java's decoder surfaces it as U+FFFD, so after the old seam
        // the byte is gone and no repair can know which character it was.
        // The repair leaves such strings untouched rather than guessing —
        // the startup encoding fix (next test) is what prevents the loss.
        val mangled = seamMangle("桜の音楽")
        assertTrue(mangled.contains('\uFFFD'))
        assertEquals(mangled, repairMetaText(mangled))
    }

    @Test
    fun `startup encoding makes jna decode libvlc strings as utf-8`() {
        val previous = System.getProperty("jna.encoding")
        try {
            ensureJnaUtf8StringEncoding()
            // The exact lookup vlcj's string conversion consults on
            // every Pointer.getString(0) call.
            assertEquals("UTF-8", com.sun.jna.Native.getDefaultStringEncoding())
            // Observable equivalent at the seam: libvlc's UTF-8 bytes now
            // decode back to the original — including の, whose byte
            // 0x81 the windows-1252 decode destroyed in the test above.
            val original = "桜の音楽"
            val decoded = String(
                original.toByteArray(Charsets.UTF_8),
                Charset.forName(com.sun.jna.Native.getDefaultStringEncoding())
            )
            assertEquals(original, decoded)
        } finally {
            if (previous == null) {
                System.clearProperty("jna.encoding")
            } else {
                System.setProperty("jna.encoding", previous)
            }
        }
    }

    @Test
    fun `clean ascii is unchanged`() {
        val ascii = "Track 17 - Final Mix (2024)"
        assertEquals(ascii, repairMetaText(ascii))
    }

    @Test
    fun `already correct japanese and korean are unchanged`() {
        assertEquals("タイトル「テスト」。", repairMetaText("タイトル「テスト」。"))
        assertEquals("한국어 노래", repairMetaText("한국어 노래"))
    }

    @Test
    fun `already correct mixed unicode is unchanged`() {
        val mixed = "タイトル feat. Café 🎵"
        assertEquals(mixed, repairMetaText(mixed))
    }

    @Test
    fun `genuine western european text is unchanged`() {
        // As correct Unicode…
        assertEquals("Café", repairMetaText("Café"))
        assertEquals("Björk — Homogenic", repairMetaText("Björk — Homogenic"))
        assertEquals("Señor Über alles", repairMetaText("Señor Über alles"))
        assertEquals("À la carte, très bien", repairMetaText("À la carte, très bien"))
        // …and as a string that was itself decoded from Latin-1 bytes
        // (the same characters, so the same verdict must hold).
        val latin1 = String(
            "Café del Mar".toByteArray(Charset.forName("ISO-8859-1")),
            Charset.forName("ISO-8859-1")
        )
        assertEquals("Café del Mar", repairMetaText(latin1))
    }

    @Test
    fun `empty and blank strings are unchanged`() {
        assertEquals("", repairMetaText(""))
        assertEquals("   ", repairMetaText("   "))
    }

    @Test
    fun `text with an unrecoverable replacement character is left as-is`() {
        // U+FFFD marks a byte the seam's decoder could not map at all;
        // the original byte is gone, so guessing would corrupt further.
        val damaged = "Title � test"
        assertEquals(damaged, repairMetaText(damaged))
    }
}
