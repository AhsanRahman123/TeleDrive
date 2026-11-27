package com.drdisagree.teledrive.presentation.gallery

import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.components.sortFieldLabelRes
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryPanelSortTest {

    @Test
    fun `panel offers the same full field set as the media sort menu`() {
        assertEquals(FileSortField.entries.toList(), GALLERY_PANEL_SORT_FIELDS)
    }

    @Test
    fun `choosing a field keeps the current direction`() {
        assertEquals(
            FileSortField.SIZE to SortDirection.DESCENDING,
            galleryPanelFieldSelection(SortDirection.DESCENDING, FileSortField.SIZE)
        )
        assertEquals(
            FileSortField.NAME to SortDirection.ASCENDING,
            galleryPanelFieldSelection(SortDirection.ASCENDING, FileSortField.NAME)
        )
    }

    @Test
    fun `choosing a direction keeps the current field`() {
        assertEquals(
            FileSortField.DATE_ADDED to SortDirection.ASCENDING,
            galleryPanelDirectionSelection(FileSortField.DATE_ADDED, SortDirection.ASCENDING)
        )
        assertEquals(
            FileSortField.TYPE to SortDirection.DESCENDING,
            galleryPanelDirectionSelection(FileSortField.TYPE, SortDirection.DESCENDING)
        )
    }

    @Test
    fun `every sort field has a distinct label`() {
        val labels = FileSortField.entries.map(::sortFieldLabelRes)
        assertEquals(FileSortField.entries.size, labels.toSet().size)
    }
}
