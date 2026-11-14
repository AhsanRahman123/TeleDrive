package com.drdisagree.teledrive.desktop.media.player

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SingleActivePlaybackCoordinatorTest {

    private data class FakePlayer(val name: String)

    @Test
    fun `new player starts only after previous player has been released`() = runBlocking {
        val activePlayers = mutableSetOf<FakePlayer>()
        val events = mutableListOf<String>()
        val coordinator = SingleActivePlaybackCoordinator<FakePlayer> { player ->
            events += "stop:${player.name}"
            // Model a native stop/release that completes asynchronously.
            delay(25)
            assertTrue("previous player must still be active until release completes", activePlayers.remove(player))
        }
        val first = FakePlayer("first")
        val second = FakePlayer("second")
        coordinator.register(first)
        coordinator.register(second)

        coordinator.activate(first) {
            assertTrue("first player must be the only active player", activePlayers.isEmpty())
            activePlayers += first
            events += "start:first"
        }
        coordinator.activate(second) {
            assertTrue("second player must wait for first player teardown", activePlayers.isEmpty())
            activePlayers += second
            events += "start:second"
        }

        assertEquals(
            listOf("start:first", "stop:first", "start:second"),
            events
        )

        coordinator.dispose(second)
        assertTrue(activePlayers.isEmpty())
    }

    @Test
    fun `disposing replaced player does not release it twice`() = runBlocking {
        val released = mutableListOf<FakePlayer>()
        val coordinator = SingleActivePlaybackCoordinator<FakePlayer> { player ->
            released += player
        }
        val first = FakePlayer("first")
        val second = FakePlayer("second")
        coordinator.register(first)
        coordinator.register(second)

        coordinator.activate(first) { }
        coordinator.activate(second) { }
        coordinator.dispose(first)

        assertEquals(listOf(first), released)
    }

    @Test
    fun `unstarted player is still released when its composition is disposed`() = runBlocking {
        val released = mutableListOf<FakePlayer>()
        val coordinator = SingleActivePlaybackCoordinator<FakePlayer> { player ->
            released += player
        }
        val neverStarted = FakePlayer("never-started")
        coordinator.register(neverStarted)

        coordinator.dispose(neverStarted)

        assertEquals(listOf(neverStarted), released)
    }
    @Test
    fun `native release runs outside the player's submit executor`() = runBlocking {
        val playerExecutor = Executors.newSingleThreadExecutor()
        val playerDispatcher = playerExecutor.asCoroutineDispatcher()
        var stopThread: Thread? = null
        var releaseThread: Thread? = null
        val events = mutableListOf<String>()

        try {
            stopThenReleaseOutsidePlayerExecutor(
                stopOnPlayerExecutor = {
                    withContext(playerDispatcher) {
                        stopThread = Thread.currentThread()
                        events += "stop"
                    }
                },
                releasePlayer = {
                    releaseThread = Thread.currentThread()
                    events += "release"
                }
            )
        } finally {
            playerDispatcher.close()
            playerExecutor.shutdown()
        }

        assertEquals(listOf("stop", "release"), events)
        assertNotSame(
            "MediaPlayer.release() must not run on the player's own submit executor",
            stopThread,
            releaseThread
        )
    }

}
