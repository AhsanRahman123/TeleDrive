package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_content_description_selected
import com.drdisagree.teledrive.resources.common_select
import org.jetbrains.compose.resources.stringResource

/**
 * A persistent, high-contrast selection control with a 48 dp touch target.
 * Android uses a lighter single-node implementation to reduce the cost of drawing
 * a checkbox over every visible tile. Desktop retains the original Material surface.
 */
@Composable
fun SelectionCheckbox(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highContrast: Boolean = false
) {
    val emphasized = highContrast && checked
    val shape = RoundedCornerShape(12.dp)
    val background = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    }
    val borderColor = if (emphasized) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val iconTint = if (emphasized) MaterialTheme.colorScheme.onPrimary
    else if (checked) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurface
    val icon = when {
        emphasized -> Icons.Filled.Check
        checked -> Icons.Filled.CheckBox
        else -> Icons.Filled.CheckBoxOutlineBlank
    }
    val description = stringResource(
        if (checked) Res.string.common_content_description_selected else Res.string.common_select
    )

    if (LocalAndroidLayout.current) {
        Box(
            modifier = modifier
                .size(48.dp)
                .clip(shape)
                .background(background, shape)
                .border(1.dp, borderColor, shape)
                .toggleable(
                    value = checked,
                    role = Role.Checkbox,
                    onValueChange = { onClick() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(28.dp),
                tint = iconTint
            )
        }
    } else {
        Surface(
            modifier = modifier.size(48.dp),
            shape = shape,
            color = background,
            contentColor = if (emphasized) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = if (emphasized) 4.dp else 2.dp
        ) {
            IconButton(
                onClick = onClick,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = description,
                    modifier = Modifier.size(28.dp),
                    tint = iconTint
                )
            }
        }
    }
}
