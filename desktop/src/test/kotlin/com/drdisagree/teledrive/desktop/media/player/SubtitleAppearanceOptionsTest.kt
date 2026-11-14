package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAppearanceOptionsTest {

    @Test
    fun `VLC defaults do not add custom style options at the default position`() {
        assertTrue(SubtitleAppearance().toVlcMediaOptions(videoHeight = 1080).isEmpty())
    }

    @Test
    fun `custom appearance maps supported settings to VLC media options`() {
        val options = SubtitleAppearance(
            useSystemDefaults = false,
            fontFamily = "Consolas",
            textScale = 1.5f,
            textColor = 0xFFFFFF00.toInt(),
            textOpacity = 0.5f,
            backgroundColor = 0xFF000000.toInt(),
            backgroundOpacity = 0.5f,
            edgeType = EDGE_OUTLINE,
            edgeColor = 0xFFFFFFFF.toInt(),
            edgeOpacity = 0.8f,
            verticalPosition = 0.75f
        ).toVlcMediaOptions(videoHeight = 1_000)

        assertTrue(options.contains(":freetype-font=Consolas"))
        assertTrue(options.contains(":freetype-rel-fontsize=24"))
        assertTrue(options.contains(":freetype-color=0xFFFF00"))
        assertTrue(options.contains(":freetype-opacity=128"))
        assertTrue(options.contains(":freetype-outline-thickness=4"))
        assertTrue(options.contains(":freetype-outline-opacity=204"))
        assertTrue(options.contains(":sub-margin=-130"))
    }

    @Test
    fun `appearance values are clamped before building media options`() {
        val normalized = SubtitleAppearance(
            textScale = 4f,
            textOpacity = -1f,
            backgroundOpacity = 2f,
            windowOpacity = -0.5f,
            edgeType = 99,
            edgeOpacity = 2f,
            verticalPosition = 1f
        ).normalized()

        assertEquals(2.5f, normalized.textScale)
        assertEquals(0f, normalized.textOpacity)
        assertEquals(1f, normalized.backgroundOpacity)
        assertEquals(0f, normalized.windowOpacity)
        assertEquals(EDGE_DEPRESSED, normalized.edgeType)
        assertEquals(1f, normalized.edgeOpacity)
        assertEquals(0.92f, normalized.verticalPosition)
    }
}
