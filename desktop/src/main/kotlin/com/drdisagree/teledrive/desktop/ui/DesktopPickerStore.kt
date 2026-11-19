package com.drdisagree.teledrive.desktop.ui

import java.io.File
import java.util.prefs.Preferences

/**
 * Remembers where the desktop pickers were last looking, so every picker —
 * the built-in dialog and the native Windows dialogs alike — reopens where
 * the user left off instead of back at the home folder. File picking and
 * folder picking remember separate positions: the two flows browse
 * different places. A remembered directory that no longer exists is
 * ignored (see [resolveStartDirectory]).
 */
internal object DesktopPickerStore {

    private val preferences: Preferences by lazy {
        Preferences.userRoot().node("com/drdisagree/teledrive/file-picker")
    }

    fun lastFileDirectory(): File? = lastFileDirectory(preferences)

    fun lastFolderDirectory(): File? = lastFolderDirectory(preferences)

    fun saveFileDirectory(directory: File) = saveFileDirectory(preferences, directory)

    fun saveFolderDirectory(directory: File) = saveFolderDirectory(preferences, directory)

    internal fun lastFileDirectory(source: Preferences): File? =
        lastDirectory(source, KEY_FILE_DIRECTORY)

    internal fun lastFolderDirectory(source: Preferences): File? =
        lastDirectory(source, KEY_FOLDER_DIRECTORY)

    internal fun saveFileDirectory(target: Preferences, directory: File) =
        saveDirectory(target, KEY_FILE_DIRECTORY, directory)

    internal fun saveFolderDirectory(target: Preferences, directory: File) =
        saveDirectory(target, KEY_FOLDER_DIRECTORY, directory)

    private fun lastDirectory(source: Preferences, key: String): File? = runCatching {
        source.get(key, null)?.let { File(it) }?.takeIf { it.isDirectory }
    }.getOrNull()

    private fun saveDirectory(target: Preferences, key: String, directory: File) {
        runCatching {
            target.put(key, directory.absolutePath)
            target.flush()
        }
    }

    /**
     * The directory a picker opens at: the remembered one when it still
     * exists, else the user's home folder, else the first usable root.
     */
    internal fun resolveStartDirectory(
        remembered: File?,
        home: File?,
        roots: List<File>
    ): File? =
        remembered?.takeIf { it.isDirectory }
            ?: home?.takeIf { it.isDirectory }
            ?: roots.firstOrNull { it.isDirectory }

    private const val KEY_FILE_DIRECTORY = "file_directory"
    private const val KEY_FOLDER_DIRECTORY = "folder_directory"
}
