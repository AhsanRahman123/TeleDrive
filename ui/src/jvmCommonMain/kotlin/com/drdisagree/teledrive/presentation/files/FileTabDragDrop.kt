package com.drdisagree.teledrive.presentation.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Minimal, versioned payload used only for in-app tab-to-tab file moves/copies.
 * IDs are UUID-like database identifiers; names and absolute paths are never transferred.
 */
data class FileTabDragData(
    val fileIds: List<String>,
    val folderIds: List<String>
) {
    fun encode(): String = buildString {
        append(PREFIX).append('\n')
        append(fileIds.size).append('\n')
        fileIds.forEach { append(it).append('\n') }
        append(folderIds.size).append('\n')
        folderIds.forEach { append(it).append('\n') }
    }

    companion object {
        private const val PREFIX = "teledrive-file-tabs-v1"

        fun decode(value: String): FileTabDragData? = runCatching {
            val lines = value.lineSequence().toList()
            require(lines.firstOrNull() == PREFIX)
            var index = 1
            val fileCount = lines[index++].toInt().also { require(it in 0..10_000) }
            val files = List(fileCount) { lines[index++] }
            val folderCount = lines[index++].toInt().also { require(it in 0..10_000) }
            val folders = List(folderCount) { lines[index++] }
            require(files.none(String::isBlank) && folders.none(String::isBlank))
            require(index == lines.size || lines.drop(index).all(String::isEmpty))
            FileTabDragData(files.distinct(), folders.distinct())
        }.getOrNull()
    }
}

@Composable
expect fun Modifier.fileTabDragSource(payload: () -> String): Modifier

@Composable
expect fun FileTabDropTarget(
    modifier: Modifier = Modifier,
    onDrop: (payload: String, copy: Boolean) -> Boolean,
    content: @Composable () -> Unit
)
