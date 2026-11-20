package com.drdisagree.teledrive.core.files

import java.io.File

/**
 * The full contents of a picked directory: every file to upload, plus
 * every directory in the tree.
 *
 * [folders] holds each directory's path relative to the picked folder's
 * parent (so the picked folder itself is the first segment), in walk
 * order — a parent always precedes its children. It exists because
 * [files] cannot represent directories that contain no files: without
 * it, an empty folder in a picked tree silently never reaches the
 * library (PC-22).
 */
data class DirectoryExpansion(
    val files: List<ImportSource>,
    val folders: List<String>
)

/** Full expansion of a picked directory; null when [reference] is not a readable directory. */
fun expandDirectoryTree(reference: String): DirectoryExpansion? {
    val root = runCatching { File(reference) }.getOrNull() ?: return null
    if (!root.isDirectory) return null
    val base = root.parentFile ?: return null
    val files = mutableListOf<ImportSource>()
    val folders = mutableListOf<String>()
    // One top-down walk collects both: a directory is visited before
    // anything inside it, so folders come out parent-before-child.
    root.walkTopDown().forEach { entry ->
        when {
            entry.isDirectory ->
                entry.relativeToOrNull(base)?.let { folders += it.invariantSeparatorsPath }

            entry.isFile && entry.length() > 0 ->
                files += ImportSource(
                    reference = entry.absolutePath,
                    relativeFolder = entry.parentFile
                        ?.relativeToOrNull(base)
                        ?.invariantSeparatorsPath
                        .orEmpty()
                )
        }
    }
    return DirectoryExpansion(files = files, folders = folders)
}

/**
 * Paths stay relative to the directory's parent, so the picked folder is the first segment; null
 * when unreadable.
 */
fun expandDirectory(reference: String): List<ImportSource>? =
    expandDirectoryTree(reference)?.files

/**
 * Orders relative folder paths so every parent is created before its
 * children (stable among equal depths), ready to be materialised one by
 * one through the library's folder-creation mechanism.
 */
fun parentBeforeChild(folders: List<String>): List<String> =
    folders.distinct().sortedBy { path -> path.count { it == '/' } }
