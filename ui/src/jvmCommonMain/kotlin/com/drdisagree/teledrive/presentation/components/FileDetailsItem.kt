package com.drdisagree.teledrive.presentation.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.model.DriveFile
import com.drdisagree.teledrive.presentation.common.Formatters
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.common_open
import com.drdisagree.teledrive.resources.common_select
import org.jetbrains.compose.resources.stringResource

/**
 * A compact, information-first file row for the Files Details view.
 * This only displays existing metadata; it does not read or transform file contents.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FileDetailsItem(
    file: DriveFile,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    prominentSelectionCheckbox: Boolean = false
) {
    val compact = LocalCompactLayout.current
    val details = buildList {
        add(file.extension.takeIf { it.isNotBlank() }?.uppercase()
            ?: file.mimeType.substringAfter('/').uppercase())
        add(Formatters.bytes(file.sizeBytes))
        add(Formatters.date(file.modifiedAt))
    }.joinToString("  •  ")

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(
                if (selected) MaterialTheme.colorScheme.secondaryContainer
                else Color.Transparent
            )
            .combinedClickable(
                onClickLabel = stringResource(
                    if (selectionMode) Res.string.common_select else Res.string.common_open
                ),
                onLongClickLabel = stringResource(Res.string.common_select),
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = if (compact) 2.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectionCheckbox(
            checked = selected,
            onClick = onToggleSelection,
            highContrast = prominentSelectionCheckbox,
            modifier = Modifier.padding(end = 6.dp)
        )
        FileThumbnail(
            file = file,
            contentDescription = null,
            modifier = Modifier
                .size(if (compact) 34.dp else 68.dp)
                .clip(MaterialTheme.shapes.medium)
        )
        Spacer(Modifier.width(if (compact) 10.dp else 16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = if (compact) MaterialTheme.typography.bodyMedium else {
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Medium
                    )
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = details,
                style = if (compact) MaterialTheme.typography.bodySmall
                else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        BackupStateBadge(file = file, modifier = Modifier.padding(start = 10.dp))
    }
}
