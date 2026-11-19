package com.drdisagree.teledrive.desktop.media.player

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlPreferencesStoreTest {

    @Test
    fun `remember volume defaults to enabled at the standard default volume`() = withTemporaryPreferences { node ->
        val loaded = PlayerControlPreferencesStore.load(node)

        assertTrue(loaded.rememberVolume)
        // The branch originally defaulted to maximum volume (200); the PC build
        // ships the corrected default of 100, matching the player's own default.
        assertEquals(100, loaded.volume)
    }

    @Test
    fun `last chosen volume is restored when remember volume is enabled`() = withTemporaryPreferences { node ->
        PlayerControlPreferencesStore.save(
            node,
            PlayerControlPreferences(rememberVolume = true, volume = 80)
        )

        val loaded = PlayerControlPreferencesStore.load(node)

        assertTrue(loaded.rememberVolume)
        assertEquals(80, loaded.volume)
    }

    @Test
    fun `remember volume setting can be disabled without losing the selected value`() = withTemporaryPreferences { node ->
        PlayerControlPreferencesStore.save(
            node,
            PlayerControlPreferences(rememberVolume = false, volume = 80)
        )

        val loaded = PlayerControlPreferencesStore.load(node)

        assertFalse(loaded.rememberVolume)
        assertEquals(80, loaded.volume)
    }

    @Test
    fun `stored volume is clamped to the supported slider range`() = withTemporaryPreferences { node ->
        node.putInt("last_player_volume", 999)

        val loaded = PlayerControlPreferencesStore.load(node)

        assertEquals(200, loaded.volume)
    }

    @Test
    fun `saveVolume writes only the volume key and leaves the other preferences intact`() =
        withTemporaryPreferences { node ->
            PlayerControlPreferencesStore.save(
                node,
                PlayerControlPreferences(
                    autoHideVolumeSlider = true,
                    showPlayerControls = false,
                    rememberVolume = true,
                    volume = 30
                )
            )

            PlayerControlPreferencesStore.saveVolume(node, 55)

            val loaded = PlayerControlPreferencesStore.load(node)
            assertEquals(55, loaded.volume)
            assertTrue(loaded.autoHideVolumeSlider)
            assertFalse(loaded.showPlayerControls)
            assertTrue(loaded.rememberVolume)
        }

    @Test
    fun `saveVolume clamps to the supported slider range`() = withTemporaryPreferences { node ->
        PlayerControlPreferencesStore.saveVolume(node, 999)
        assertEquals(200, PlayerControlPreferencesStore.load(node).volume)

        PlayerControlPreferencesStore.saveVolume(node, -3)
        assertEquals(0, PlayerControlPreferencesStore.load(node).volume)
    }

    private fun withTemporaryPreferences(block: (Preferences) -> Unit) {
        val node = Preferences.userRoot().node("teledrive-tests/player-volume-${UUID.randomUUID()}")
        try {
            block(node)
        } finally {
            runCatching { node.removeNode() }
            runCatching { node.flush() }
        }
    }
}
