package com.drdisagree.teledrive.desktop.media.player

import java.nio.charset.Charset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC 1.6.26 P1 — in-zip audio player showed the PREVIOUS track's tags.
 *
 * Field sequence replayed at the loader level (the exact seam AudioStage
 * uses): the native player still holds entry A's media while entry B's
 * stage is already polling, because B's start command is queued behind
 * stop+start on the session's serial executor. The old ungated poll
 * accepted A's non-blank tags on its first read and never re-read, so the
 * display lagged exactly one entry. The loader must reject every snapshot
 * whose current-media mrl is not the loaded entry's, across an A→B→C
 * chain, and must keep the mojibake repair on accepted snapshots.
 */
class AudioMetaLoaderTest {

    private val mrlA = """C:\Users\ahsan\AppData\Local\Temp\teledrive-entryA.mp3"""
    private val mrlB = """C:\Users\ahsan\AppData\Local\Temp\teledrive-entryB.mp3"""
    private val mrlC = """C:\Users\ahsan\AppData\Local\Temp\teledrive-entryC.mp3"""

    private fun snapshot(mrl: String?, title: String, artist: String, album: String = "") =
        MediaMetaSnapshot(currentMrl = mrl, title = title, artist = artist, album = album)

    /** A snapshot supplier that replays [script] then keeps returning its last entry. */
    private fun scripted(script: List<MediaMetaSnapshot?>): () -> MediaMetaSnapshot? {
        var index = 0
        return {
            val next = if (index < script.size) script[index] else script.lastOrNull()
            index++
            next
        }
    }

    private fun load(expectedMrl: String, script: List<MediaMetaSnapshot?>): AudioMeta? =
        runBlocking {
            AudioMetaLoader(
                expectedMrl = expectedMrl,
                snapshot = scripted(script),
                maxAttempts = 12,
                pollDelayMs = 0
            ).load()
        }

    @Test
    fun `previous track snapshots are rejected until the loaded entry's own media appears`() {
        // B's stage polls while the player still holds A (twice), then B lands.
        val meta = load(
            mrlB,
            listOf(
                snapshot(mrlA, "Song A", "Artist A", "Album A"),
                snapshot(mrlA, "Song A", "Artist A", "Album A"),
                snapshot(mrlB, "Song B", "Artist B", "Album B")
            )
        )
        assertEquals(AudioMeta("Song B", "Artist B", "Album B"), meta)
    }

    @Test
    fun `a to b to c chain - each entry displays its own tags, never the previous one`() {
        // Land on A first (player empty at first: null snapshots, then A).
        val metaA = load(
            mrlA,
            listOf(null, snapshot(mrlA, "Song A", "Artist A"))
        )
        assertEquals(AudioMeta("Song A", "Artist A", ""), metaA)

        // Switch A -> B: A's snapshot is still served first.
        val metaB = load(
            mrlB,
            listOf(
                snapshot(mrlA, "Song A", "Artist A"),
                snapshot(mrlB, "Song B", "Artist B")
            )
        )
        assertEquals(AudioMeta("Song B", "Artist B", ""), metaB)

        // Switch B -> C: B's snapshot is still served first.
        val metaC = load(
            mrlC,
            listOf(
                snapshot(mrlB, "Song B", "Artist B"),
                snapshot(mrlC, "Song C", "Artist C")
            )
        )
        assertEquals(AudioMeta("Song C", "Artist C", ""), metaC)
    }

    @Test
    fun `only the previous track ever served - nothing is accepted`() {
        // If the new media never lands, the loader must NOT fall back to the
        // previous track's tags: null means the caller shows the entry name.
        val meta = load(mrlB, listOf(snapshot(mrlA, "Song A", "Artist A")))
        assertNull(meta)
    }

    @Test
    fun `matching media with tags arriving late - blank snapshots keep polling`() {
        val meta = load(
            mrlB,
            listOf(
                snapshot(mrlB, "", ""),
                snapshot(mrlB, "", ""),
                snapshot(mrlB, "Song B", "Artist B")
            )
        )
        assertEquals(AudioMeta("Song B", "Artist B", ""), meta)
    }

    @Test
    fun `matching media with no tags at all - returns the blank matching meta, not another track's`() {
        val meta = load(
            mrlB,
            listOf(
                snapshot(mrlA, "Song A", "Artist A"),
                snapshot(mrlB, "", "")
            )
        )
        assertEquals(AudioMeta("", "", ""), meta)
    }

    /** The vlcj/JNA seam mangle, reproduced exactly as in MetaTextRepairTest. */
    private fun seamMangle(original: String): String =
        String(original.toByteArray(Charsets.UTF_8), Charset.forName("windows-1252"))

    @Test
    fun `mojibake repair still runs on accepted snapshots`() {
        val meta = load(
            mrlB,
            listOf(
                snapshot(
                    mrlB,
                    seamMangle("タイトル「テスト。"),
                    seamMangle("音楽ユニット"),
                    seamMangle("桜・音楽")
                )
            )
        )
        assertEquals(
            AudioMeta("タイトル「テスト。", "音楽ユニット", "桜・音楽"),
            meta
        )
    }

    @Test
    fun `libvlc uri form of the loaded path matches the plain path`() {
        // libVLC reports the current media as a file:// URI with forward
        // slashes and percent-escapes; the app passed a plain Windows path.
        val plain = """C:\Users\ahsan\AppData\Local\Temp\my song B.mp3"""
        val uriForm = "file:///C:/Users/ahsan/AppData/Local/Temp/my%20song%20B.mp3"
        assertTrue(mediaMrlMatches(uriForm, plain))
        assertTrue(mediaMrlMatches(plain, plain))
        assertFalse(mediaMrlMatches(uriForm.replace("B.mp3", "A.mp3"), plain))
        assertFalse(mediaMrlMatches(null, plain))
        assertFalse(mediaMrlMatches("", plain))
    }

    @Test
    fun `loopback stream url matches itself and not another file's url`() {
        val one = "http://127.0.0.1:51234/media/First%20Song.mp3"
        val two = "http://127.0.0.1:51234/media/Second%20Song.mp3"
        assertTrue(mediaMrlMatches(one, one))
        assertFalse(mediaMrlMatches(two, one))
    }

    @Test
    fun `normaliser converges path spellings of the same file`() {
        val expected = normalizeMediaMrl("""C:\Music\My Song.mp3""")
        assertEquals(expected, normalizeMediaMrl("file:///C:/Music/My%20Song.mp3"))
        assertEquals(expected, normalizeMediaMrl("C:/Music/My Song.mp3"))
        assertEquals(expected, normalizeMediaMrl("""c:\music\my song.mp3"""))
    }
}
