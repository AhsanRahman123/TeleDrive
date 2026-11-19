package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The curated subtitle font library (PC-10): the picker offers the list,
 * never a raw enumeration of the machine's installed fonts.
 */
class SubtitleFontLibraryTest {

    @Test
    fun `core windows fonts are always offered even when enumeration finds nothing`() {
        val library = resolveSubtitleFontLibrary(
            installedFamilyNames = emptyList(),
            currentSelection = "Segoe UI"
        )
        assertEquals(SUBTITLE_FONT_CORE, library)
    }

    @Test
    fun `extended fonts are offered only when installed, spelled as the system reports them`() {
        val library = resolveSubtitleFontLibrary(
            installedFamilyNames = listOf("roboto", "Noto Sans Bengali", "Baloo Da 2"),
            currentSelection = "Segoe UI"
        )
        // Installed spelling/casing is what freetype gets handed.
        assertTrue(library.contains("roboto"))
        assertTrue(library.contains("Noto Sans Bengali"))
        // Not installed -> not offered, even though they are in the extended list.
        assertFalse(library.contains("Open Sans"))
        assertFalse(library.contains("Montserrat"))
        // A random installed font never leaks into the library.
        assertFalse(library.contains("Baloo Da 2"))
    }

    @Test
    fun `a saved selection outside the library is kept so it is never silently dropped`() {
        val library = resolveSubtitleFontLibrary(
            installedFamilyNames = emptyList(),
            currentSelection = "Baloo Da 2"
        )
        assertEquals(SUBTITLE_FONT_CORE + "Baloo Da 2", library)
    }

    @Test
    fun `a saved selection inside the library is not duplicated`() {
        val library = resolveSubtitleFontLibrary(
            installedFamilyNames = listOf("Roboto"),
            currentSelection = "roboto"
        )
        assertEquals(1, library.count { it.equals("roboto", ignoreCase = true) })
    }

    @Test
    fun `the library stays a short curated list`() {
        assertTrue(SUBTITLE_FONT_CORE.size in 12..16)
        assertTrue(SUBTITLE_FONT_EXTENDED.size in 4..10)
    }
}
