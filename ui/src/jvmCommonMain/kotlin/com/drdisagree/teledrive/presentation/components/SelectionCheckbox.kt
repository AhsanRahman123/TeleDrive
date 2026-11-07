package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_content_description_selected
import com.drdisagree.teledrive.resources.common_select
import org.jetbrains.compose.resources.stringResource

/**
 * A persistent, high-contrast selection control with a 48 dp hit target.
 * The filled surface keeps the outline readable over both light and dark thumbnails.
 */
@Composable
fun SelectionCheckbox(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.size(48.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 2.dp
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (checked) {
                    Icons.Filled.CheckBox
                } else {
                    Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = stringResource(
                    if (checked) Res.string.common_content_description_selected else Res.string.common_select
                ),
                modifier = Modifier.size(28.dp),
                tint = if (checked) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}
