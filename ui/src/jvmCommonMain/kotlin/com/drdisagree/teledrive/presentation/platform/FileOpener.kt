package com.drdisagree.teledrive.presentation.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** Absent on platforms without an external-app chooser, which hides the action. */
fun interface FileOpener {
    fun openWith(path: String, displayName: String, mimeType: String?): Boolean
}

val LocalFileOpener = staticCompositionLocalOf<FileOpener?> { null }
