package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_content_description_selected
import com.drdisagree.teledrive.resources.common_select
import org.jetbrains.compose.resources.stringResource

/**
 * A large, easy-to-hit selection control for mouse, touch, and keyboard users.
 */
@Composable
fun SelectionCheckbox(
    checked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
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
            modifier = Modifier.size(26.dp),
            tint = if (checked) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
