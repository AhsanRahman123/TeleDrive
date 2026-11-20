package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-27 pinning, one control at a time: every control in the subtitle
 * settings dialog must change EXACTLY its own LibVLC factory option(s)
 * in the argument list the live session's instance is built with — no
 * control may be inert (changing it changes nothing) and none may leak
 * into another control's option. Together with the swap tests, this is
 * the "settings → option set" half of the Apply path; the "option set →
 * live player" half is the PlayerSession swap itself.
 */
class SubtitleAppearanceDeltaTest {

    private val base = SubtitleAppearance(useSystemDefaults = false)

    private fun optionsOf(appearance: SubtitleAppearance): Map<String, String> =
        appearance.toVlcFactoryArguments().associate { argument ->
            val name = vlcOptionName(argument)
            name to argument.removePrefix("--$name").removePrefix("=")
        }

    /** Option names whose presence or value differs between two appearances. */
    private fun changedOptions(
        before: SubtitleAppearance,
        after: SubtitleAppearance
    ): Set<String> {
        val a = optionsOf(before)
        val b = optionsOf(after)
        return (a.keys + b.keys).filter { key -> a[key] != b[key] }.toSet()
    }

    @Test
    fun `text size changes only the relative font size`() {
        assertEquals(
            setOf("freetype-rel-fontsize"),
            changedOptions(base, base.copy(textScale = 1.5f))
        )
    }

    @Test
    fun `font changes only the freetype font`() {
        assertEquals(
            setOf("freetype-font"),
            changedOptions(base, base.copy(fontFamily = "Georgia"))
        )
    }

    @Test
    fun `text colour changes only the freetype colour`() {
        assertEquals(
            setOf("freetype-color"),
            changedOptions(base, base.copy(textColor = 0xFFFFFF00.toInt()))
        )
    }

    @Test
    fun `text opacity changes only the freetype opacity`() {
        assertEquals(
            setOf("freetype-opacity"),
            changedOptions(base, base.copy(textOpacity = 0.5f))
        )
    }

    @Test
    fun `background colour changes only the background colour`() {
        assertEquals(
            setOf("freetype-background-color"),
            changedOptions(base, base.copy(backgroundColor = 0xFF0000FF.toInt()))
        )
    }

    @Test
    fun `background opacity changes only the background opacity`() {
        // With a fully transparent window layer beneath, the composited
        // RGB is the background colour itself at any opacity — only the
        // composited alpha moves.
        assertEquals(
            setOf("freetype-background-opacity"),
            changedOptions(base, base.copy(backgroundOpacity = 0.3f))
        )
    }

    @Test
    fun `vertical position changes only the sub-margin`() {
        assertEquals(
            setOf("sub-margin"),
            changedOptions(base, base.copy(verticalPosition = 0.5f))
        )
    }

    @Test
    fun `edge type changes only the outline controls`() {
        assertEquals(
            setOf("freetype-outline-thickness", "freetype-outline-opacity"),
            changedOptions(base, base.copy(edgeType = EDGE_NONE))
        )
    }

    @Test
    fun `switching from VLC defaults to Custom adds the renderer and the full style set`() {
        val defaults = SubtitleAppearance(useSystemDefaults = true)
        val changed = changedOptions(defaults, base)
        assertTrue("text-renderer" in changed)
        assertTrue("freetype-font" in changed)
        assertTrue("freetype-color" in changed)
        assertTrue("freetype-background-color" in changed)
        // At the default position, the margin option stays absent in
        // both modes — the mode switch must not invent it. (Custom mode
        // does always carry rel-fontsize, at VLC's own default of 16
        // for a 100% size, so that option legitimately appears.)
        assertTrue("sub-margin" !in changed)
    }

    @Test
    fun `in VLC defaults mode the size still changes only the relative font size`() {
        val defaults = SubtitleAppearance(useSystemDefaults = true)
        assertEquals(
            setOf("freetype-rel-fontsize"),
            changedOptions(defaults, defaults.copy(textScale = 0.75f))
        )
    }

    @Test
    fun `in VLC defaults mode the position still changes only the sub-margin`() {
        val defaults = SubtitleAppearance(useSystemDefaults = true)
        assertEquals(
            setOf("sub-margin"),
            changedOptions(defaults, defaults.copy(verticalPosition = 0.3f))
        )
    }
}
