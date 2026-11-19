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
                val process = ProcessBuilder(
                    "rundll32.exe",
                    "shell32.dll,OpenAs_RunDLL",
                    file.absolutePath
                ).start()
                // A chooser that failed to appear exits immediately with an
                // error; one still running after a short wait is the dialog.
                if (process.waitFor(750, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.exitValue() == 0
                } else {
                    true
                }
            }.getOrDefault(false)
            if (chooserStarted) return true
        }
        return runCatching { Desktop.getDesktop().open(file) }.isSuccess
    }
}
