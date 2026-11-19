package com.drdisagree.teledrive.desktop.files

import com.drdisagree.teledrive.presentation.platform.FileOpener
import com.sun.jna.Platform
import java.awt.Desktop
import java.io.File

/**
 * Desktop "Open with…": on Windows this shows the genuine shell Open With
 * chooser (the same dialog Explorer's Open with menu opens) via the shell's
 * OpenAs entry point; if that cannot start, the file opens with its
 * associated application instead. Other desktops open with the default
 * application directly.
 */
class DesktopFileOpener : FileOpener {

    override fun openWith(path: String, displayName: String, mimeType: String?): Boolean {
        val file = File(path)
        if (!file.isFile) return false
        if (Platform.isWindows()) {
            val chooserStarted = runCatching {
                ProcessBuilder(
                    "rundll32.exe",
                    "shell32.dll,OpenAs_RunDLL",
                    file.absolutePath
                ).start()
                true
            }.getOrDefault(false)
            if (chooserStarted) return true
        }
        return runCatching { Desktop.getDesktop().open(file) }.isSuccess
    }
}
