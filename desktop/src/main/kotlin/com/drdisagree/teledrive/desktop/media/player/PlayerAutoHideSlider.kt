package com.drdisagree.teledrive.desktop.media.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Compact video-player level control. The slider appears on hover or keyboard
 * focus and remains open while being dragged. Auto-hide can be disabled
 * independently for each control.
 */
@Composable
internal fun PlayerAutoHideSlider(
    icon: ImageVector,
    contentDescription: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    autoHide: Boolean,
    hideDelayMillis: Long,
    onButtonClick: () -> Unit,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit = {},
    onDraggingChange: (Boolean) -> Unit = {},
    onActiveChange: (Boolean) -> Unit = {},
    expandOnClick: Boolean = false
) {
    val groupInteraction = remember { MutableInteractionSource() }
    val isHovered by groupInteraction.collectIsHoveredAsState()

    val sliderInteraction = remember { MutableInteractionSource() }
    val isDragging by sliderInteraction.collectIsDraggedAsState()

    var hasFocus by remember { mutableStateOf(false) }
    var sliderVisible by remember { mutableStateOf(!autoHide) }
    var manuallyExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(autoHide, isHovered, isDragging, hasFocus, manuallyExpanded, hideDelayMillis) {
        if (!autoHide || isHovered || isDragging || hasFocus || manuallyExpanded) {
            sliderVisible = true
        } else if (sliderVisible) {
            delay(hideDelayMillis.coerceIn(500L, 10_000L))
            sliderVisible = false
        }
    }

    LaunchedEffect(isDragging) {
        onDraggingChange(isDragging)
    }

    LaunchedEffect(isHovered, isDragging, hasFocus, manuallyExpanded) {
        onActiveChange(isHovered || isDragging || hasFocus || manuallyExpanded)
    }

    DisposableEffect(Unit) {
        onDispose { onActiveChange(false) }
    }

    Row(
        modifier = Modifier.hoverable(groupInteraction),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (expandOnClick) manuallyExpanded = !manuallyExpanded
                onButtonClick()
            },
            modifier = Modifier
                .size(38.dp)
                .onFocusChanged { hasFocus = it.isFocused }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = Color.White
            )
        }

        AnimatedVisibility(
            visible = sliderVisible,
            enter = expandHorizontally(expandFrom = Alignment.Start) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + fadeOut()
        ) {
            Slider(
                value = value.coerceIn(valueRange.start, valueRange.endInclusive),
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                interactionSource = sliderInteraction,
                modifier = Modifier
                    .width(108.dp)
                    .height(40.dp)
                    .onFocusChanged { hasFocus = it.isFocused },
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                )
            )
        }
    }
}
