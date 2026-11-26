package com.drdisagree.teledrive.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.drdisagree.teledrive.presentation.platform.PlatformScreens

object DesktopPlatformScreens : PlatformScreens {

    @Composable
    override fun Preview(onBack: () -> Unit, onEditNote: (String, String) -> Unit) {
        DesktopPreviewScreen(onBack = onBack)
    }

    /**
     * Desktop stub for the vc181 local preview: the feature is Android's
     * (Transfers archive rows), so desktop only shows the file name with a
     * back action instead of an in-app viewer. Present so the shared
     * [PlatformScreens] contract compiles on both targets.
     */
    @Composable
    override fun LocalPreview(localPath: String, displayName: String, onBack: () -> Unit) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = localPath,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onBack) {
                Text("Back")
            }
        }
    }
}
