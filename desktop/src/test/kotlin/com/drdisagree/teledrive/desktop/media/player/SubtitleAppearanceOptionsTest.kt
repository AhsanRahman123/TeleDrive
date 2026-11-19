package com.drdisagree.teledrive.desktop.media.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAppearanceOptionsTest {

    @Test
    fun `VLC defaults do not add custom renderer arguments at the default position`() {
        assertTrue(SubtitleAppearance().toVlcFactoryArguments(videoHeight = 1080).isEmpty())
    }

    @Test
    fun `custom appearance maps supported settings to VLC factory arguments`() {
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
        ).toVlcFactoryArguments(videoHeight = 1_000)

        assertTrue(options.contains("--freetype-font=Consolas"))
        assertTrue(options.contains("--freetype-rel-fontsize=24"))
        assertTrue(options.contains("--freetype-color=16776960"))
        assertTrue(options.contains("--freetype-opacity=128"))
        assertTrue(options.contains("--freetype-background-opacity=128"))
        assertTrue(options.contains("--freetype-outline-thickness=4"))
        assertTrue(options.contains("--freetype-outline-opacity=204"))
        assertTrue(options.contains("--sub-margin=-130"))
    }

    @Test
    fun `custom appearance forces the freetype text rendering path`() {
        val options = SubtitleAppearance(
            useSystemDefaults = false
        ).toVlcFactoryArguments(videoHeight = 1080)

        assertTrue(options.contains("--text-renderer=freetype"))
    }

    @Test
    fun `no appearance ever emits an option outside the verified VLC 3-0-21 set`() {
        // Regression guard for the "--no-libass" failure: that string names
        // no config item in VLC 3.0.21, libvlc_new treats unknown instance
        // options as fatal, and every custom appearance fell back to default
        // rendering behind a permanent warning banner. Every option emitted
        // for any appearance must be one verified in the bundled binaries.
        val appearances = listOf(
            SubtitleAppearance(),
            SubtitleAppearance(useSystemDefaults = false),
            SubtitleAppearance(
                useSystemDefaults = false,
                fontFamily = "Consolas",
                textScale = 2.5f,
                edgeType = EDGE_DEPRESSED,
                verticalPosition = 0.08f
            ),
            SubtitleAppearance(
                useSystemDefaults = false,
                edgeType = EDGE_SHADOW,
                verticalPosition = 0.92f
            ),
            SubtitleAppearance(verticalPosition = 0.5f)
        )
        val emitted = appearances.flatMap { appearance ->
            appearance.toVlcFactoryArguments(videoHeight = 1080)
        }
        assertTrue(emitted.isNotEmpty())
        emitted.forEach { argument ->
            assertTrue(
                "Unverified VLC option emitted: $argument",
                vlcOptionName(argument) in VERIFIED_VLC_FACTORY_OPTIONS
            )
        }
        assertTrue(
            "The non-existent libass switch must never come back",
            emitted.none { vlcOptionName(it) == "libass" || vlcOptionName(it) == "no-libass" }
        )
    }

    @Test
    fun `appearance values are clamped before building factory arguments`() {
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

    @Test
    fun `VLC defaults with a moved position emit only the margin argument`() {
        val options = SubtitleAppearance(verticalPosition = 0.5f)
            .toVlcFactoryArguments(videoHeight = 1080)

        assertEquals(listOf("--sub-margin=-410"), options)
    }

    @Test
    fun `margin is clamped to the supported range at both ends`() {
        val top = SubtitleAppearance(verticalPosition = 0.08f)
            .toVlcFactoryArguments(videoHeight = 1080)
        val bottom = SubtitleAppearance(verticalPosition = 0.92f)
            .toVlcFactoryArguments(videoHeight = 1080)

        // (0.08 - 0.88) * 1080 = -864, clamped to -1080 / 2 = -540.
        assertEquals(listOf("--sub-margin=-540"), top)
        // (0.92 - 0.88) * 1080 = 43.2 -> 43.
        assertEquals(listOf("--sub-margin=43"), bottom)
    }

    @Test
    fun `relative font size is clamped to the freetype range`() {
        val small = SubtitleAppearance(useSystemDefaults = false, textScale = 0.5f)
            .toVlcFactoryArguments(videoHeight = 1080)
        val large = SubtitleAppearance(useSystemDefaults = false, textScale = 2.5f)
            .toVlcFactoryArguments(videoHeight = 1080)

        assertTrue(small.contains("--freetype-rel-fontsize=8"))
        assertTrue(large.contains("--freetype-rel-fontsize=40"))
    }

    @Test
    fun `composite background blends the window colour under the background colour`() {
        // Half-opaque black over half-opaque white: alpha 0.75 -> 191, grey 0x55.
        val (rgb, alpha) = compositeSubtitleBackground(
            foregroundColor = 0xFF000000.toInt(),
            foregroundOpacity = 0.5f,
            backgroundColor = 0xFFFFFFFF.toInt(),
            backgroundOpacity = 0.5f
        )

        assertEquals(0x555555, rgb)
        assertEquals(191, alpha)
    }

    @Test
    fun `fully transparent layers composite to transparent black`() {
        val (rgb, alpha) = compositeSubtitleBackground(
            foregroundColor = 0xFF112233.toInt(),
            foregroundOpacity = 0f,
            backgroundColor = 0xFF445566.toInt(),
            backgroundOpacity = 0f
        )

        assertEquals(0x000000, rgb)
        assertEquals(0, alpha)
    }

    @Test
    fun `edge types map to outline and shadow arguments`() {
        fun optionsFor(edgeType: Int) = SubtitleAppearance(
            useSystemDefaults = false,
            edgeType = edgeType,
            edgeOpacity = 1f
        ).toVlcFactoryArguments(videoHeight = 1080)

        val none = optionsFor(EDGE_NONE)
        assertTrue(none.contains("--freetype-outline-thickness=0"))
        assertTrue(none.contains("--freetype-outline-opacity=0"))
        assertTrue(none.contains("--freetype-shadow-opacity=0"))

        val outline = optionsFor(EDGE_OUTLINE)
        assertTrue(outline.contains("--freetype-outline-thickness=4"))
        assertTrue(outline.contains("--freetype-outline-opacity=255"))
        assertTrue(outline.contains("--freetype-shadow-opacity=0"))

        val shadow = optionsFor(EDGE_SHADOW)
        assertTrue(shadow.contains("--freetype-outline-thickness=0"))
        assertTrue(shadow.contains("--freetype-outline-opacity=0"))
        assertTrue(shadow.contains("--freetype-shadow-opacity=160"))

        val raised = optionsFor(EDGE_RAISED)
        assertTrue(raised.contains("--freetype-outline-thickness=4"))
        assertTrue(raised.contains("--freetype-outline-opacity=255"))
        assertTrue(raised.contains("--freetype-shadow-opacity=160"))

        val depressed = optionsFor(EDGE_DEPRESSED)
        assertTrue(depressed.contains("--freetype-outline-thickness=4"))
        assertTrue(depressed.contains("--freetype-outline-opacity=255"))
        assertTrue(depressed.contains("--freetype-shadow-opacity=160"))
    }

    @Test
    fun `appearance survives a store round trip`() {
        val node = java.util.prefs.Preferences.userRoot()
            .node("teledrive-tests/subtitle-appearance-${java.util.UUID.randomUUID()}")
        try {
            val appearance = SubtitleAppearance(
                useSystemDefaults = false,
                fontFamily = "Consolas",
                textScale = 1.25f,
                textColor = 0xFFFFFF00.toInt(),
                textOpacity = 0.75f,
                backgroundOpacity = 0.4f,
                edgeType = EDGE_SHADOW,
                verticalPosition = 0.6f
            )
            SubtitleAppearanceStore.save(node, appearance)

            assertEquals(appearance, SubtitleAppearanceStore.load(node))
        } finally {
            runCatching { node.removeNode() }
            runCatching { node.flush() }
        }
    }
}
