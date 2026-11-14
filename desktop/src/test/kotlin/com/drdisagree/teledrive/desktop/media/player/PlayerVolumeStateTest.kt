package com.drdisagree.teledrive.desktop.media.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Test

class PlayerVolumeStateTest {

    @Test
    fun `rapid volume requests enqueue one VLC command with only the latest value`() {
        val queued = mutableListOf<() -> Unit>()
        val applied = mutableListOf<Pair<Int, Boolean>>()
        val dispatcher = LatestAudioStateDispatcher(
            enqueue = { task -> queued += task },
            apply = { volume, muted -> applied += volume to muted }
        )

        dispatcher.submit(35, false)
        dispatcher.submit(70, false)
        dispatcher.submit(125, false)
        dispatcher.submit(180, false)

        assertEquals(1, queued.size)
        queued.removeAt(0).invoke()

        assertEquals(listOf(180 to false), applied)
    }

    @Test
    fun `zero volume is dispatched as muted`() {
        val queued = mutableListOf<() -> Unit>()
        val applied = mutableListOf<Pair<Int, Boolean>>()
        val dispatcher = LatestAudioStateDispatcher(
            enqueue = { task -> queued += task },
            apply = { volume, muted -> applied += volume to muted }
        )

        dispatcher.submit(0, false)
        queued.removeAt(0).invoke()

        assertEquals(listOf(0 to true), applied)
    }

    @Test
    fun `new request during VLC application is drained without queueing another task`() {
        val queued = mutableListOf<() -> Unit>()
        val applied = mutableListOf<Int>()
        lateinit var dispatcher: LatestAudioStateDispatcher
        dispatcher = LatestAudioStateDispatcher(
            enqueue = { task -> queued += task },
            apply = { volume, _ ->
                applied += volume
                if (volume == 50) dispatcher.submit(95, false)
            }
        )

        dispatcher.submit(50, false)
        queued.removeAt(0).invoke()

        assertEquals(listOf(50, 95), applied)
        assertEquals(0, queued.size)
    }

    @Test
    fun `debounced persistence saves only the final volume after input settles`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val saved = CopyOnWriteArrayList<Int>()
        try {
            val writer = DebouncedIntWriter(
                scope = scope,
                debounceMillis = 80L,
                save = saved::add
            )

            writer.schedule(40)
            delay(20L)
            writer.schedule(85)
            delay(180L)

            assertEquals(listOf(85), saved.toList())
        } finally {
            scope.cancel()
        }
    }
}
