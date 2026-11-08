package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
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
