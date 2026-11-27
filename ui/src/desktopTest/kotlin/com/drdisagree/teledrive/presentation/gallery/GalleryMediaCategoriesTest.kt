package com.drdisagree.teledrive.presentation.gallery

import com.drdisagree.teledrive.domain.model.FileCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryMediaCategoriesTest {

    @Test
    fun `root gallery defaults are byte-identical to the pre-filter behaviour`() {
        assertEquals(
            listOf(FileCategory.IMAGE, FileCategory.VIDEO),
            mediaCategories(GalleryTab.ALL, GalleryMediaType.ALL)
        )
        assertEquals(
            listOf(FileCategory.IMAGE),
            mediaCategories(GalleryTab.PHOTOS, GalleryMediaType.ALL)
        )
        assertEquals(
            listOf(FileCategory.VIDEO),
            mediaCategories(GalleryTab.VIDEOS, GalleryMediaType.ALL)
        )
        assertEquals(
            listOf(FileCategory.IMAGE, FileCategory.VIDEO),
            mediaCategories(GalleryTab.ALBUMS, GalleryMediaType.ALL)
        )
    }

    @Test
    fun `album view media filter narrows the all tab to one type`() {
        assertEquals(
            listOf(FileCategory.IMAGE, FileCategory.VIDEO),
            mediaCategories(GalleryTab.ALL, GalleryMediaType.ALL)
        )
        assertEquals(
            listOf(FileCategory.IMAGE),
            mediaCategories(GalleryTab.ALL, GalleryMediaType.PHOTOS)
        )
        assertEquals(
            listOf(FileCategory.VIDEO),
            mediaCategories(GalleryTab.ALL, GalleryMediaType.VIDEOS)
        )
    }

    @Test
    fun `media filter intersects with a typed tab instead of overriding it`() {
        assertEquals(
            listOf(FileCategory.IMAGE),
            mediaCategories(GalleryTab.PHOTOS, GalleryMediaType.PHOTOS)
        )
        assertEquals(
            listOf(FileCategory.VIDEO),
            mediaCategories(GalleryTab.VIDEOS, GalleryMediaType.VIDEOS)
        )
        assertEquals(
            listOf(FileCategory.IMAGE),
            mediaCategories(GalleryTab.ALBUMS, GalleryMediaType.PHOTOS)
        )
        assertEquals(
            listOf(FileCategory.VIDEO),
            mediaCategories(GalleryTab.ALBUMS, GalleryMediaType.VIDEOS)
        )
    }

    @Test
    fun `conflicting tab and media filter intersect to nothing`() {
        assertEquals(
            emptyList<FileCategory>(),
            mediaCategories(GalleryTab.PHOTOS, GalleryMediaType.VIDEOS)
        )
        assertEquals(
            emptyList<FileCategory>(),
            mediaCategories(GalleryTab.VIDEOS, GalleryMediaType.PHOTOS)
        )
    }

    @Test
    fun `category order is stable with images before videos`() {
        GalleryTab.entries.forEach { tab ->
            val categories = mediaCategories(tab, GalleryMediaType.ALL)
            assertEquals(categories.sortedBy { it.ordinal }, categories)
        }
    }

    @Test
    fun `media type filter counts as an active filter unless it is all`() {
        assertFalse(GalleryUiState().hasActiveFilters)
        assertFalse(
            GalleryUiState(filters = GalleryFilters(mediaType = GalleryMediaType.ALL))
                .hasActiveFilters
        )
        assertTrue(
            GalleryUiState(filters = GalleryFilters(mediaType = GalleryMediaType.PHOTOS))
                .hasActiveFilters
        )
        assertTrue(
            GalleryUiState(filters = GalleryFilters(mediaType = GalleryMediaType.VIDEOS))
                .hasActiveFilters
        )
    }

    @Test
    fun `clearing filters resets the media type to all`() {
        assertEquals(GalleryMediaType.ALL, GalleryFilters().mediaType)
    }
}
