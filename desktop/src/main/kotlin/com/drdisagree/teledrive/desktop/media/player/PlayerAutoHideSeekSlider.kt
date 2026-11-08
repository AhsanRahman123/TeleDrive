package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import kotlinx.coroutines.delay

/**
 * Auto-hiding full-width seek bar. It is visible while the pointer is over it,
 * while being dragged, or while keyboard-focused; disabling auto-hide keeps it
 * visible whenever the player controls themselves are visible.
 */
@Composable
internal fun PlayerAutoHideSeekSlider(
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    autoHide: Boolean,
    hideDelayMillis: Long,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    onActiveChange: (Boolean) -> Unit,
    thumb: @Composable () -> Unit,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    val isDragging by interaction.collectIsDraggedAsState()
    var hasFocus by remember { mutableStateOf(false) }
    var visible by remember { mutableStateOf(!autoHide) }

    LaunchedEffect(autoHide, isHovered, isDragging, hasFocus, hideDelayMillis) {
        if (!autoHide || isHovered || isDragging || hasFocus) {
            visible = true
        } else if (visible) {
            delay(hideDelayMillis.coerceIn(500L, 10_000L))
            visible = false
        }
    }

    LaunchedEffect(isHovered, isDragging, hasFocus, visible, autoHide) {
        // When auto-hide is enabled, keep the player chrome alive for this
        // slider's full hide delay. When disabled, the player itself can still
        // auto-hide normally.
        onActiveChange(isHovered || isDragging || hasFocus || (autoHide && visible))
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(30.dp)
            .hoverable(interaction)
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = expandVertically(expandFrom = Alignment.CenterVertically) + fadeIn(),
            exit = shrinkVertically(shrinkTowards = Alignment.CenterVertically) + fadeOut()
        ) {
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                interactionSource = interaction,
                thumb = thumb,
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { hasFocus = it.isFocused }
            )
        }
    }
}
