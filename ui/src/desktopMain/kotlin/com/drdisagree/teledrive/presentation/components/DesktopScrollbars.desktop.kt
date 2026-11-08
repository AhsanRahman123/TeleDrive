package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.ScrollbarAdapter
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun DesktopVerticalScrollbar(
    state: ScrollableState,
    modifier: Modifier
) {
    VerticalScrollbar(
        adapter = remember(state) { ScrollableStateScrollbarAdapter(state) },
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun DesktopHorizontalScrollbar(
    state: ScrollableState,
    modifier: Modifier
) {
    HorizontalScrollbar(
        adapter = remember(state) { ScrollableStateScrollbarAdapter(state) },
        modifier = modifier
    )
}

@OptIn(ExperimentalFoundationApi::class)
private class ScrollableStateScrollbarAdapter(
    private val state: ScrollableState
) : ScrollbarAdapter {

    override val scrollOffset: Float
        get() = state.scrollIndicatorState?.scrollOffset?.toFloat() ?: 0f

    override fun maxScrollOffset(containerSize: Int): Float {
        val indicator = state.scrollIndicatorState ?: return 0f
        return (indicator.contentSize - indicator.viewportSize)
            .coerceAtLeast(0)
            .toFloat()
    }

    override suspend fun scrollTo(containerSize: Int, scrollOffset: Float) {
        val indicator = state.scrollIndicatorState ?: return
        val maximum = (indicator.contentSize - indicator.viewportSize)
            .coerceAtLeast(0)
            .toFloat()
        val target = scrollOffset.coerceIn(0f, maximum)
        val delta = target - indicator.scrollOffset
        if (delta != 0f) {
            state.scroll {
                scrollBy(delta)
            }
        }
    }
}
