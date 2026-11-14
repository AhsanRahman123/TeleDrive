package com.drdisagree.teledrive.presentation.files

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop tab-to-tab drag transfer is intentionally not wired into Android.
 *
 * The desktop implementation depends on Compose's desktop drag-transfer action API,
 * which is not exposed by the Android target used by this project. Android keeps its
 * native touch selection and file operations; tab-to-tab drag transfer can be added
 * later with a dedicated Android gesture implementation.
 */
@Composable
actual fun Modifier.fileTabDragSource(payload: () -> String): Modifier = this

@Composable
actual fun FileTabDropTarget(
    modifier: Modifier,
    onDrop: (payload: String, copy: Boolean) -> Boolean,
    content: @Composable () -> Unit
) {
    Box(modifier = modifier) {
        content()
    }
}
