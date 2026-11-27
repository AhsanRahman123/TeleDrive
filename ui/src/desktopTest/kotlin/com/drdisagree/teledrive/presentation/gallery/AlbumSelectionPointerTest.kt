package com.drdisagree.teledrive.presentation.gallery

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.domain.model.MediaAlbum
import com.drdisagree.teledrive.presentation.components.rememberDragSelect
import com.drdisagree.teledrive.presentation.platform.LocalPlatformCapabilities
import com.drdisagree.teledrive.presentation.platform.PlatformCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A4 regression: a long-press on an album selected it, and the click that a
 * plain `clickable` fired on finger-lift immediately unselected it again.
 * The wiring below replicates GalleryScreen's AlbumGrid + GalleryViewModel
 * album-range semantics and AlbumCard's own click/long-click contract under
 * a TOUCH platform capability (the platform the defect was reported on), so
 * the card's long-click is the guarded no-op Files uses on touch and the
 * container drag-select is the only selection actor during a hold.
 */
@OptIn(ExperimentalTestApi::class)
class AlbumSelectionPointerTest {

    @get:Rule
    val rule = createComposeRule()

    private val touchCapabilities = object : PlatformCapabilities {
        override val supportsAutoBackup: Boolean = false
        override val requiresPermissions: Boolean = false
        override val supportsPullToRefresh: Boolean = false
        override val supportsAppLock: Boolean = false
        override val supportsScreenCaptureBlocking: Boolean = false
        override val supportsDynamicColor: Boolean = false
        override val supportsTouchSelectionActionBar: Boolean = true
    }

    @Test
    fun `touch hold selects the album and it stays selected after lift, tap unselects`() {
        val albums = (0 until 3).map { index ->
            MediaAlbum(
                folderId = "folder-$index",
                name = "Album $index",
                itemCount = index + 1,
                coverFileId = null,
                latestAt = 0L
            )
        }
        var albumSelection by mutableStateOf(emptySet<String>())
        var rangeBase by mutableStateOf<Set<String>?>(null)
        var selectionModeEnabled by mutableStateOf(false)
        val opened = mutableListOf<String>()

        rule.setContent {
            CompositionLocalProvider(LocalPlatformCapabilities provides touchCapabilities) {
                val gridState = rememberLazyGridState()
                // Selection mode exactly as GalleryScreen derives it.
                val selectionMode = albumSelection.isNotEmpty() || selectionModeEnabled
                val toggleAlbum: (String) -> Unit = { folderId ->
                    albumSelection =
                        if (folderId in albumSelection) albumSelection - folderId
                        else albumSelection + folderId
                    selectionModeEnabled = albumSelection.isNotEmpty()
                }
                val dragSelect = rememberDragSelect(
                    gridState = gridState,
                    // GalleryViewModel.startAlbumRangeSelection
                    onStart = {
                        rangeBase = albumSelection
                        selectionModeEnabled = true
                    },
                    isSelected = { index ->
                        albums.getOrNull(index)?.folderId?.let { it in albumSelection } == true
                    },
                    // GalleryViewModel.extendAlbumRangeSelection
                    onRange = { range, shouldSelect ->
                        val base = rangeBase
                        if (base != null) {
                            val ids = range.mapNotNull { albums.getOrNull(it)?.folderId }
                            albumSelection =
                                if (shouldSelect) base + ids else base - ids.toSet()
                            selectionModeEnabled = albumSelection.isNotEmpty()
                        }
                    },
                    // GalleryViewModel.endAlbumRangeSelection
                    onEnd = { rangeBase = null }
                )
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(1),
                    modifier = Modifier
                        .size(width = 200.dp, height = 640.dp)
                        .testTag("albums-grid")
                        .then(dragSelect),
                    contentPadding = PaddingValues(12.dp)
                ) {
                    itemsIndexed(albums, key = { _, album -> album.folderId ?: "" }) { _, album ->
                        AlbumCard(
                            album = album,
                            onClick = {
                                val folderId = album.folderId
                                if (selectionMode && folderId != null) toggleAlbum(folderId)
                                else album.folderId?.let { opened += it }
                            },
                            // AlbumGrid's guard: on touch the drag-select owns
                            // the long-press, so this is a no-op there.
                            onLongClick = {
                                if (!touchCapabilities.supportsTouchSelectionActionBar) {
                                    album.folderId?.let(toggleAlbum)
                                }
                            },
                            selectionMode = selectionMode,
                            selected = album.folderId?.let { it in albumSelection } == true,
                            onSelectionChange = album.folderId?.let { id ->
                                { checked: Boolean ->
                                    if (checked != (id in albumSelection)) toggleAlbum(id)
                                }
                            },
                            modifier = Modifier.testTag("album-card-${album.folderId}")
                        )
                    }
                }
            }
        }

        // Hold on the first album, then lift: the selection must survive the
        // lift (this is the exact gesture that used to unselect it).
        rule.onNodeWithTag("albums-grid").performTouchInput {
            down(Offset(100f, 80f))
            advanceEventTime(1_000)
            up()
        }
        rule.waitForIdle()
        assertEquals(setOf("folder-0"), albumSelection)
        assertTrue(opened.isEmpty())

        // One tap on the selected album unselects it.
        rule.onNodeWithTag("album-card-folder-0").performTouchInput {
            click(center)
        }
        rule.waitForIdle()
        assertEquals(emptySet<String>(), albumSelection)
        assertTrue(opened.isEmpty())

        // A tap with nothing selected opens the album instead.
        rule.onNodeWithTag("album-card-folder-1").performTouchInput {
            click(center)
        }
        rule.waitForIdle()
        assertEquals(listOf("folder-1"), opened)
        assertEquals(emptySet<String>(), albumSelection)
    }
}
