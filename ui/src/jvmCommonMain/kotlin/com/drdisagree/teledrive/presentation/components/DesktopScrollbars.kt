package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Desktop mouse scrollbar. Android supplies a no-op actual; desktop renders a draggable bar.
 */
@Composable
expect fun DesktopVerticalScrollbar(
    state: ScrollableState,
    modifier: Modifier = Modifier
)

@Composable
expect fun DesktopHorizontalScrollbar(
    state: ScrollableState,
    modifier: Modifier = Modifier
)


/** Wrap any vertical scrollable so the desktop mouse scrollbar is always available. */
@Composable
fun DesktopVerticalScrollContainer(
    state: ScrollableState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        content()
        DesktopVerticalScrollbar(
            state = state,
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
        )
    }
}

/** Wrap any horizontal scrollable so the desktop mouse scrollbar is always available. */
@Composable
fun DesktopHorizontalScrollContainer(
    state: ScrollableState,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(modifier = modifier) {
        content()
        DesktopHorizontalScrollbar(
            state = state,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
        )
    }
}
