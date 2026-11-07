package com.drdisagree.teledrive.presentation.preview

import com.drdisagree.teledrive.domain.model.FileCategory
import com.drdisagree.teledrive.domain.model.FileSortField
import com.drdisagree.teledrive.domain.model.SortDirection
import com.drdisagree.teledrive.presentation.navigation.Route

/** So paging sideways walks the same files in the same order, not a freshly guessed query. */
data class PreviewSequence(
    val folderId: String? = null,
    val filterByFolder: Boolean = false,
    val nameQuery: String? = null,
    val categories: List<FileCategory> = emptyList(),
    val favoritesOnly: Boolean = false,
    val availableOfflineOnly: Boolean = false,
    val backedUpOnly: Boolean = false,
    val notBackedUpOnly: Boolean = false,
    val hiddenOnly: Boolean = false,
    val archivedOnly: Boolean = false,
    val sortField: FileSortField = FileSortField.NAME,
    val sortDirection: SortDirection = SortDirection.ASCENDING
) {

    fun routeFor(fileId: String): Route.Preview = Route.Preview(
        fileId = fileId,
        folderId = folderId,
        filterByFolder = filterByFolder,
        nameQuery = nameQuery,
        categories = categories.takeIf { it.isNotEmpty() }
            ?.joinToString(CATEGORY_SEPARATOR) { it.name },
        favoritesOnly = favoritesOnly,
        availableOfflineOnly = availableOfflineOnly,
        backedUpOnly = backedUpOnly,
        notBackedUpOnly = notBackedUpOnly,
        hiddenOnly = hiddenOnly,
        archivedOnly = archivedOnly,
        sortField = sortField.name,
        sortDescending = sortDirection == SortDirection.DESCENDING
    )
}

const val CATEGORY_SEPARATOR = ","


/** The viewer destination after a successful move, kept pure so the navigation rule is testable. */
internal data class PreviewMoveResult(
    val files: List<com.drdisagree.teledrive.domain.model.DriveFile>,
    val initialIndex: Int,
    val closeViewer: Boolean
)

internal fun previewAfterMove(
    currentFiles: List<com.drdisagree.teledrive.domain.model.DriveFile>,
    movedFile: com.drdisagree.teledrive.domain.model.DriveFile,
    currentIndex: Int
): PreviewMoveResult {
    val actualIndex = currentFiles.indexOfFirst { it.id == movedFile.id }
        .takeIf { it >= 0 }
        ?: currentIndex.coerceIn(0, (currentFiles.size - 1).coerceAtLeast(0))
    val remaining = currentFiles.filterNot { it.id == movedFile.id }
    val movedMedia = movedFile.category == FileCategory.IMAGE ||
        movedFile.category == FileCategory.VIDEO
    val nextFiles = if (movedMedia) {
        remaining.filter { it.category == FileCategory.IMAGE || it.category == FileCategory.VIDEO }
    } else {
        remaining
    }

    if (nextFiles.isEmpty()) {
        return PreviewMoveResult(emptyList(), 0, closeViewer = true)
    }

    val originalPositions = currentFiles.mapIndexed { index, file -> file.id to index }.toMap()
    val nextIndex = if (movedMedia) {
        nextFiles.indexOfFirst { (originalPositions[it.id] ?: -1) > actualIndex }
            .takeIf { it >= 0 }
            ?: nextFiles.indexOfLast { (originalPositions[it.id] ?: Int.MAX_VALUE) < actualIndex }
                .coerceAtLeast(0)
    } else {
        actualIndex.coerceIn(0, nextFiles.lastIndex)
    }

    return PreviewMoveResult(nextFiles, nextIndex, closeViewer = false)
}
