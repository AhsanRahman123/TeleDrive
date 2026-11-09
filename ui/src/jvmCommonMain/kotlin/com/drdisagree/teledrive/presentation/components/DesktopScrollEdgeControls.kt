package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.background
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch

/**
 * Small, consistent jump controls for long desktop content lists.
 * The arrows jump to the first or last item, not just one viewport.
 */
@Composable
fun DesktopScrollEdgeControls(
    state: LazyListState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    ScrollEdgeControlRow(
        modifier = modifier,
        onTop = {
            scope.launch {
                if (state.layoutInfo.totalItemsCount > 0) state.animateScrollToItem(0)
            }
        },
        onBottom = {
            scope.launch {
                val last = state.layoutInfo.totalItemsCount - 1
                if (last >= 0) state.animateScrollToItem(last)
            }
        }
    )
}

@Composable
fun DesktopScrollEdgeControls(
    state: LazyGridState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    ScrollEdgeControlRow(
        modifier = modifier,
        onTop = {
            scope.launch {
                if (state.layoutInfo.totalItemsCount > 0) state.animateScrollToItem(0)
            }
        },
        onBottom = {
            scope.launch {
                val last = state.layoutInfo.totalItemsCount - 1
                if (last >= 0) state.animateScrollToItem(last)
            }
        }
    )
}

@Composable
fun DesktopScrollEdgeControls(
    state: ScrollState,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    ScrollEdgeControlRow(
        modifier = modifier,
        onTop = { scope.launch { state.animateScrollTo(0) } },
        onBottom = { scope.launch { state.animateScrollTo(state.maxValue) } }
    )
}

@Composable
private fun ScrollEdgeControlRow(
    modifier: Modifier,
    onTop: () -> Unit,
    onBottom: () -> Unit
) {
    Row(
        modifier = modifier
            .zIndex(2f)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onTop) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowUp,
                contentDescription = "Go to first item"
            )
        }
        IconButton(onClick = onBottom) {
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = "Go to last item"
            )
        }
    }
}
