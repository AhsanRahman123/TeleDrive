package com.drdisagree.teledrive.core.transfer

import com.drdisagree.teledrive.domain.model.TransferState
import com.drdisagree.teledrive.domain.model.UserPreferences
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PC-39: the per-direction limits are LIVE. These tests change the
 * setting in the middle of a gated storm — the user's headline use
 * case — and pin the two rules that make it safe:
 *
 *  - Raising starts queued transfers promptly, without restarting the
 *    ones already in flight.
 *  - Lowering never kills an in-flight transfer, and the lane makes NO
 *    new start until its active count is below the new limit (an
 *    active-count gate, not a slot index — a freed low slot must not
 *    start work while five siblings still run above a limit of 1).
 */
class LiveConcurrencySwitchTest {

    @Test
    fun `raising the upload limit mid storm starts queued transfers without restarting in-flight ones`() =
        runBlocking {
            StormHarness(
                UserPreferences(uploadConcurrency = 2, downloadConcurrency = 2, transferRetryCount = 3)
            ).use { harness ->
                harness.telegram.gated = true
                val names = (1..8).map { i ->
                    harness.seedUpload("up-$i", "live-$i.bin", ByteArray(32) { 1 }, i.toLong())
                    "live-$i.bin"
                }
                val drainer = harness.drainer()
                val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

                // At limit 2 exactly two uploads start, and no more.
                awaitStarted(harness, 2)
                delay(600)
                assertEquals(2, harness.telegram.startedUploads.size)
                assertEquals(2, harness.telegram.inFlightUploads.get())
                val firstTwo = harness.telegram.startedUploads.toList()

                // Raise 2 -> 6 live: the four waiting slots claim at once.
                harness.settings.update { it.copy(uploadConcurrency = 6) }
                awaitStarted(harness, 6)

                // The original two were never restarted: same flows, still
                // parked at their gates, and the started list only grew.
                assertEquals(firstTwo, harness.telegram.startedUploads.take(2))
                assertEquals(6, harness.telegram.inFlightUploads.get())
                assertTrue(harness.telegram.uploads.isEmpty())

                harness.telegram.releaseAll()
                val result = withTimeout(60.seconds) { drain.await() }
                assertEquals(TransferDrainResult.COMPLETED, result)
                names.forEachIndexed { index, _ ->
                    assertEquals(
                        TransferState.COMPLETED,
                        harness.transfers.byId("t-up-${index + 1}")!!.state
                    )
                }
            }
        }

    @Test
    fun `lowering the upload limit mid storm lets in-flight finish and starts none until below the limit`() =
        runBlocking {
            StormHarness(
                UserPreferences(uploadConcurrency = 6, downloadConcurrency = 6, transferRetryCount = 3)
            ).use { harness ->
                harness.telegram.gated = true
                (1..8).forEach { i ->
                    harness.seedUpload("up-$i", "lower-$i.bin", ByteArray(32) { 2 }, i.toLong())
                }
                val drainer = harness.drainer()
                val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

                awaitStarted(harness, 6)
                val started = harness.telegram.startedUploads.toList()
                assertEquals(6, started.size)

                // Lower 6 -> 1 live. Nothing in flight is killed...
                harness.settings.update { it.copy(uploadConcurrency = 1) }
                delay(400)
                assertEquals(6, harness.telegram.inFlightUploads.get())

                // ...and as in-flight transfers finish one by one, NO new
                // start happens while the active count is still >= 1.
                // started[] is in claim order (priority, then createdAt),
                // so releasing by that order drains the first six.
                for (released in 1..5) {
                    harness.telegram.releaseUpload(started[released - 1])
                    awaitInFlight(harness, 6 - released)
                    delay(700)
                    assertEquals(
                        "no new start after $released completion(s) at limit 1",
                        6,
                        harness.telegram.startedUploads.size
                    )
                }

                // The last in-flight transfer finishes: active hits 0, and
                // only now may the lane start again — exactly one at a time.
                harness.telegram.releaseUpload(started[5])
                awaitStarted(harness, 7)
                delay(700)
                assertEquals(7, harness.telegram.startedUploads.size)
                assertEquals(1, harness.telegram.inFlightUploads.get())

                harness.telegram.releaseUpload(harness.telegram.startedUploads[6])
                awaitStarted(harness, 8)
                harness.telegram.releaseUpload(harness.telegram.startedUploads[7])

                val result = withTimeout(60.seconds) { drain.await() }
                assertEquals(TransferDrainResult.COMPLETED, result)
                (1..8).forEach { i ->
                    assertEquals(
                        TransferState.COMPLETED,
                        harness.transfers.byId("t-up-$i")!!.state
                    )
                }
            }
        }

    @Test
    fun `raising the download limit mid storm does not disturb uploads running beside it`() =
        runBlocking {
            StormHarness(
                UserPreferences(uploadConcurrency = 2, downloadConcurrency = 1, transferRetryCount = 3)
            ).use { harness ->
                harness.telegram.gated = true
                (1..3).forEach { i ->
                    harness.seedUpload("up-$i", "mix-up-$i.bin", ByteArray(32) { 3 }, i.toLong())
                }
                (1..4).forEach { i ->
                    harness.seedDownload("down-$i", "mix-down-$i.bin", ByteArray(32) { 4 }, 100L + i, i)
                }
                val drainer = harness.drainer()
                val drain = async { drainer.drain(isStopped = { false }, onTerminalFailure = {}) }

                // Upload lane runs at its 2; download lane at its 1 —
                // simultaneously, each under its own number.
                awaitStarted(harness, 2)
                withTimeout(20.seconds) {
                    while (harness.telegram.startedDownloads.size < 1) delay(25)
                }
                delay(500)
                assertEquals(1, harness.telegram.startedDownloads.size)

                harness.settings.update { it.copy(downloadConcurrency = 4) }
                withTimeout(20.seconds) {
                    while (harness.telegram.startedDownloads.size < 4) delay(25)
                }
                // Uploads untouched by the download change.
                assertEquals(2, harness.telegram.inFlightUploads.get())

                harness.telegram.releaseAll()
                val result = withTimeout(60.seconds) { drain.await() }
                assertEquals(TransferDrainResult.COMPLETED, result)
            }
        }

    private suspend fun awaitStarted(harness: StormHarness, count: Int) {
        withTimeout(20.seconds) {
            while (harness.telegram.startedUploads.size < count) delay(25)
        }
    }

    private suspend fun awaitInFlight(harness: StormHarness, count: Int) {
        withTimeout(20.seconds) {
            while (harness.telegram.inFlightUploads.get() != count) delay(25)
        }
    }
}
