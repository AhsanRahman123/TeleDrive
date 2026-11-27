package com.drdisagree.teledrive.presentation.common

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * P2 (1.6.26) mechanism pin: on desktop, an Escape key press reaches
 * [AppBackHandler] — the production wrapper every screen's back ladder
 * runs through (Files, Gallery, Collection, Search, Trash, Transfers,
 * Channels, Settings sections, …). Compose Multiplatform's desktop scene
 * routes an unconsumed Escape through the NavigationEvent dispatcher to
 * the registered back handler, the same route a Dialog's dismiss takes
 * (PC-30). If this ever stops holding, every AppBackHandler-based Esc
 * ladder is dead and this test is the canary.
 */
@OptIn(ExperimentalTestApi::class)
class AppBackHandlerEscapeTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `escape press fires the registered back handler`() {
        val backs = AtomicInteger(0)
        rule.setContent {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            AppBackHandler { backs.incrementAndGet() }
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .testTag("surface")
            )
        }
        rule.onNodeWithTag("surface").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(1, backs.get())
    }

    @Test
    fun `disabled handler does not fire on escape`() {
        val backs = AtomicInteger(0)
        rule.setContent {
            val focusRequester = remember { FocusRequester() }
            LaunchedEffect(Unit) { focusRequester.requestFocus() }
            AppBackHandler(enabled = false) { backs.incrementAndGet() }
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .focusRequester(focusRequester)
                    .focusable()
                    .testTag("surface")
            )
        }
        rule.onNodeWithTag("surface").performKeyInput { pressKey(Key.Escape) }
        rule.waitForIdle()
        assertEquals(0, backs.get())
    }
}
