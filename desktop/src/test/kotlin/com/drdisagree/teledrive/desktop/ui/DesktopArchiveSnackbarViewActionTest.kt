package com.drdisagree.teledrive.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.drdisagree.teledrive.presentation.common.CollectSnackbarMessages
import com.drdisagree.teledrive.presentation.common.LocalOpenTransfers
import com.drdisagree.teledrive.presentation.common.UiText
import com.drdisagree.teledrive.presentation.common.isTransferMessage
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.message_download_queued
import com.drdisagree.teledrive.resources.preview_archive_entries_downloading
import com.drdisagree.teledrive.resources.preview_archive_entries_saved
import com.drdisagree.teledrive.resources.preview_archive_entry_downloading
import com.drdisagree.teledrive.resources.preview_archive_entry_saved
import com.drdisagree.teledrive.resources.preview_archive_failed
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * 1.6.22 Item 1 (desktop analogue of Android's ArchiveSnackbarActionTest):
 * after a download from the desktop ZIP viewer, the saved message must carry
 * the same "View" action Files downloads get, and tapping it must invoke the
 * open-Transfers callback.
 *
 * Why this composition level is the honest pin: in the real desktop app the
 * provision DOES reach [DesktopPreviewScreen] — `desktop Main.kt` composes
 * `TeleDriveApp`, whose `MainScaffold` composes `AppNavHost`, and AppNavHost
 * wraps its whole NavHost in
 * `CompositionLocalProvider(LocalOpenTransfers provides { navController.navigateOnce(Route.Transfers) })`.
 * `Route.Preview` reaches the desktop screen through
 * `LocalPlatformScreens.current.Preview` -> `DesktopPlatformScreens.Preview` ->
 * `DesktopPreviewScreen`, which calls the shared [CollectSnackbarMessages]
 * (DesktopPreviewScreen.kt). Composing the full NavHost in a unit test would
 * need the whole Koin/ViewModel graph, so this test composes exactly the two
 * ends that meet in the preview screen — the AppNavHost-shaped provider and
 * the shared collector the desktop preview uses — with the REAL
 * [PreviewViewModel] message types (entry_saved for a single download,
 * entries_saved for Download selected).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopArchiveSnackbarViewActionTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `zip completion messages are transfer messages on desktop too`() {
        assertTrue(UiText.Resource(Res.string.message_download_queued).isTransferMessage())
        assertTrue(
            UiText.Resource(Res.string.preview_archive_entry_saved, "song.mp3").isTransferMessage()
        )
        assertTrue(
            UiText.Resource(Res.string.preview_archive_entries_saved, 3).isTransferMessage()
        )
        assertFalse(UiText.Resource(Res.string.preview_archive_failed).isTransferMessage())
    }

    /**
     * 1.6.24 note 5: the View action must be present IMMEDIATELY when an in-ZIP
     * download starts — the ViewModel now emits a download-start message at
     * acceptance time (single and batch alike, queued ones included), and that
     * message is a transfer message, so it carries the same View action the
     * completion messages always had.
     */
    @Test
    fun `zip download start messages are transfer messages too`() {
        assertTrue(
            UiText.Resource(Res.string.preview_archive_entry_downloading, "song.mp3")
                .isTransferMessage()
        )
        assertTrue(
            UiText.Resource(Res.string.preview_archive_entries_downloading, 3)
                .isTransferMessage()
        )
    }

    @Test
    fun `start messages show View immediately and tapping it opens Transfers`() {
        val opens = AtomicInteger(0)
        val messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
        rule.setContent {
            MaterialTheme {
                CompositionLocalProvider(
                    LocalOpenTransfers provides { opens.incrementAndGet() }
                ) {
                    val hostState = remember { SnackbarHostState() }
                    CollectSnackbarMessages(messages, hostState)
                    SnackbarHost(hostState)
                }
            }
        }
        rule.waitForIdle()

        // Single-entry download START (PreviewViewModel.downloadArchiveEntry
        // acceptance) — shown before any completion message exists.
        messages.tryEmit(
            UiText.Resource(Res.string.preview_archive_entry_downloading, "song.mp3")
        )
        waitForText("Downloading song.mp3…")
        rule.onNodeWithText("View").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { opens.get() == 1 }

        // Download-selected batch START (downloadArchiveSelection acceptance).
        messages.tryEmit(UiText.Resource(Res.string.preview_archive_entries_downloading, 3))
        waitForText("Downloading 3 files…")
        rule.onNodeWithText("View").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { opens.get() == 2 }

        assertEquals(2, opens.get())
    }

    @Test
    fun `single and batch saved messages show View and tapping it opens Transfers`() {
        val opens = AtomicInteger(0)
        val messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
        rule.setContent {
            MaterialTheme {
                // The same provider AppNavHost installs above every destination,
                // including Route.Preview -> DesktopPreviewScreen.
                CompositionLocalProvider(
                    LocalOpenTransfers provides { opens.incrementAndGet() }
                ) {
                    val hostState = remember { SnackbarHostState() }
                    CollectSnackbarMessages(messages, hostState)
                    SnackbarHost(hostState)
                }
            }
        }
        rule.waitForIdle()

        // Single-entry download completion (PreviewViewModel.downloadArchiveEntry).
        messages.tryEmit(UiText.Resource(Res.string.preview_archive_entry_saved, "song.mp3"))
        waitForText("Saved song.mp3 to Downloads")
        rule.onNodeWithText("View").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { opens.get() == 1 }

        // Download-selected batch completion (PreviewViewModel.downloadArchiveSelection).
        messages.tryEmit(UiText.Resource(Res.string.preview_archive_entries_saved, 3))
        waitForText("Saved 3 files to Downloads")
        rule.onNodeWithText("View").assertIsDisplayed().performClick()
        rule.waitUntil(5_000) { opens.get() == 2 }

        assertEquals(2, opens.get())
    }

    @Test
    fun `without the AppNavHost provision the saved message has no View action`() {
        val messages = MutableSharedFlow<UiText>(extraBufferCapacity = 4)
        rule.setContent {
            MaterialTheme {
                // No LocalOpenTransfers provider: the collector must not invent
                // an action it cannot navigate with.
                val hostState = remember { SnackbarHostState() }
                CollectSnackbarMessages(messages, hostState)
                SnackbarHost(hostState)
            }
        }
        rule.waitForIdle()
        messages.tryEmit(UiText.Resource(Res.string.preview_archive_entry_saved, "song.mp3"))
        waitForText("Saved song.mp3 to Downloads")
        assertTrue(
            rule.onAllNodesWithText("View").fetchSemanticsNodes().isEmpty()
        )
    }

    private fun waitForText(text: String) {
        rule.waitUntil(5_000) {
            rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(text).assertIsDisplayed()
    }
}
