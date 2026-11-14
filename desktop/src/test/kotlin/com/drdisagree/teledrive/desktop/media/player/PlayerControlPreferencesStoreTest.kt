package com.drdisagree.teledrive.desktop.media.player

import java.util.UUID
import java.util.prefs.Preferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerControlPreferencesStoreTest {

    @Test
    fun `remember volume defaults to enabled at maximum volume`() = withTemporaryPreferences { node ->
        val loaded = PlayerControlPreferencesStore.load(node)

        assertTrue(loaded.rememberVolume)
        assertEquals(200, loaded.volume)
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
