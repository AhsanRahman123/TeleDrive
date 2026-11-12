package com.drdisagree.teledrive.presentation.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Adds a plain tooltip to an existing icon-only action without changing its click target,
 * enabled state, keyboard behavior, or semantics.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TooltipAnchor(
    tooltip: String?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val label = tooltip?.takeIf { it.isNotBlank() }
    if (label == null) {
        content()
    } else {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                positioning = TooltipAnchorPosition.Above
            ),
            tooltip = { PlainTooltip { Text(label) } },
            state = rememberTooltipState(),
            modifier = modifier
        ) {
            content()
        }
    }
}
