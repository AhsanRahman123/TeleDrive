package com.drdisagree.teledrive.presentation.common

import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.NavigationEventDispatcherOwner
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * P2 (1.6.26) mechanism pin for every AppBackHandler-based Esc ladder
 * (Files, Gallery, Collection, Search, Trash, Transfers, Channels,
 * Settings sections, the local preview, …).
 *
 * In the real desktop window, an unconsumed Escape key press is turned
 * into a completed back navigation event by the window's scene layer
 * (the route a Dialog's dismiss-on-back takes — PC-30), and
 * [AppBackHandler] is what receives it. The headless test scene has no
 * window layer to perform that conversion, so this test supplies the
 * dispatcher owner itself and dispatches the completed back event
 * directly: it pins that the production wrapper registers, fires when
 * enabled, and stays silent when disabled.
 */
@OptIn(ExperimentalTestApi::class)
class AppBackHandlerEscapeTest {

    @get:Rule
    val rule = createComposeRule()

    private class TestOwner : NavigationEventDispatcherOwner {
        override val navigationEventDispatcher = NavigationEventDispatcher()
    }

    @Test
    fun `completed back event fires the registered back handler`() {
        val backs = AtomicInteger(0)
        val owner = TestOwner()
        rule.setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                AppBackHandler { backs.incrementAndGet() }
            }
        }
        rule.runOnIdle { owner.navigationEventDispatcher.dispatchOnBackCompleted() }
        assertEquals(1, backs.get())
    }

    @Test
    fun `disabled handler does not fire on a completed back event`() {
        val backs = AtomicInteger(0)
        val owner = TestOwner()
        rule.setContent {
            CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                AppBackHandler(enabled = false) { backs.incrementAndGet() }
            }
        }
        rule.runOnIdle { owner.navigationEventDispatcher.dispatchOnBackCompleted() }
        assertEquals(0, backs.get())
    }
}
