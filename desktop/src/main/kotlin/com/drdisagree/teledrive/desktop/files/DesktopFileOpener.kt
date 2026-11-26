package com.drdisagree.teledrive.desktop.files

import com.drdisagree.teledrive.presentation.platform.FileOpener
import com.sun.jna.Platform
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Shell32
import com.sun.jna.platform.win32.WinUser
import java.awt.Desktop
import java.io.File

/**
 * Desktop "Open with…".
 *
 * Windows: the genuine shell Open With chooser, opened the way the shell
 * itself opens it — ShellExecute with the "openas" verb. ShellExecute
 * reports a REAL result (a value above 32 means the shell accepted the
 * request; 32 and below are error codes), unlike the previous
 * `rundll32 shell32.dll,OpenAs_RunDLL` route, whose process exit code is 0
 * whether or not any dialog appeared — the old code read that 0 as success,
 * skipped its fallback and showed nothing at all (1.6.24, field note 6).
 * If the chooser cannot be started, the file opens with its associated
 * application instead. Other desktops open with the default application
 * directly.
 *
 * Returns false only when nothing could be opened, so the caller can show
 * the user a message — a failure is never silent.
 *
 * The two open routes are constructor seams so unit tests can pin the
 * dispatch without a Windows shell.
 */
class DesktopFileOpener(
    private val shellOpenAs: (File) -> Boolean = { file -> shellExecuteOpenAs(file) },
    private val defaultOpen: (File) -> Boolean = { file ->
        runCatching { Desktop.getDesktop().open(file) }.isSuccess
    }
) : FileOpener {

    override fun openWith(path: String, displayName: String, mimeType: String?): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        if (Platform.isWindows() && shellOpenAs(file)) return true
        return defaultOpen(file)
    }

    companion object {

        /** ShellExecute's contract: a return value above 32 is success, 32 and below an error code. */
        internal fun shellExecuteSucceeded(resultValue: Long): Boolean = resultValue > 32L

        private fun shellExecuteOpenAs(file: File): Boolean = runCatching {
            val instance = Shell32.INSTANCE.ShellExecute(
                null,
                "openas",
                file.absolutePath,
                null,
                file.parent,
                WinUser.SW_SHOWNORMAL
            )
            shellExecuteSucceeded(Pointer.nativeValue(instance.pointer))
        }.getOrDefault(false)
    }
}
