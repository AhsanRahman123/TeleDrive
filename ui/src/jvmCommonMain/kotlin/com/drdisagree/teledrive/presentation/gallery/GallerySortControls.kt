package com.drdisagree.teledrive.presentation.gallery

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.components.sortFieldLabelRes
import com.drdisagree.teledrive.resources.Res
import com.drdisagree.teledrive.resources.files_sort
import com.drdisagree.teledrive.resources.files_sort_ascending
import com.drdisagree.teledrive.resources.files_sort_descending
import org.jetbrains.compose.resources.stringResource

/**
 * The sort fields the Gallery advanced-filters panel offers: the same full
 * set the toolbar sort menu offers for media views (the restricted
 * NAME/SIZE/DATE_MODIFIED set is only for the Albums tab, which has no
 * advanced panel).
 */
internal val GALLERY_PANEL_SORT_FIELDS: List<FileSortField> = FileSortField.entries

/**
 * What choosing a field in the panel selects: that field with the direction
 * already in effect — direction is changed only by the direction chips.
 */
internal fun galleryPanelFieldSelection(
    currentDirection: SortDirection,
    field: FileSortField
): Pair<FileSortField, SortDirection> = field to currentDirection

/**
 * What choosing a direction in the panel selects: that direction with the
 * field already in effect.
 */
internal fun galleryPanelDirectionSelection(
    currentField: FileSortField,
    direction: SortDirection
): Pair<FileSortField, SortDirection> = currentField to direction

/**
 * Sort field + direction controls for Gallery's Advanced filters panel.
 * Drives the exact same state and persistence as the toolbar sort menu
 * (GalleryViewModel.setSort -> gallerySortField/gallerySortDirection), so
 * the two surfaces can never disagree.
 */
@Composable
fun GallerySortControls(
    sortField: FileSortField,
    sortDirection: SortDirection,
    onSortSelected: (FileSortField, SortDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(Res.string.files_sort),
            style = MaterialTheme.typography.titleSmall
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            GALLERY_PANEL_SORT_FIELDS.forEach { field ->
                FilterChip(
                    selected = field == sortField,
                    onClick = {
                        val (nextField, nextDirection) =
                            galleryPanelFieldSelection(sortDirection, field)
                        onSortSelected(nextField, nextDirection)
                    },
                    label = { Text(stringResource(sortFieldLabelRes(field))) }
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = sortDirection == SortDirection.ASCENDING,
                onClick = {
                    val (nextField, nextDirection) =
                        galleryPanelDirectionSelection(sortField, SortDirection.ASCENDING)
                    onSortSelected(nextField, nextDirection)
                },
                label = { Text(stringResource(Res.string.files_sort_ascending)) }
            )
            FilterChip(
                selected = sortDirection == SortDirection.DESCENDING,
                onClick = {
                    val (nextField, nextDirection) =
                        galleryPanelDirectionSelection(sortField, SortDirection.DESCENDING)
                    onSortSelected(nextField, nextDirection)
                },
                label = { Text(stringResource(Res.string.files_sort_descending)) }
            )
        }
    }
}
