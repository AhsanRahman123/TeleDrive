package com.drdisagree.teledrive.presentation.platform

import androidx.compose.runtime.Composable
import com.drdisagree.teledrive.presentation.preview.LocalPreviewScreen
import com.drdisagree.teledrive.presentation.preview.PreviewScreen

object AndroidPlatformScreens : PlatformScreens {

    @Composable
    override fun Preview(onBack: () -> Unit, onEditNote: (String, String) -> Unit) {
        PreviewScreen(onBack = onBack, onEditNote = onEditNote)
    }

    @Composable
    override fun LocalPreview(localPath: String, displayName: String, onBack: () -> Unit) {
        LocalPreviewScreen(localPath = localPath, displayName = displayName, onBack = onBack)
    }
}
