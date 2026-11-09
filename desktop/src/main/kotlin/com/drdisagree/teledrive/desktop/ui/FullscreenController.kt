package com.drdisagree.teledrive.desktop.ui

import androidx.compose.runtime.staticCompositionLocalOf

class FullscreenController(
    val isFullscreen: () -> Boolean,
    val toggle: () -> Unit,
    val setFullscreen: (Boolean) -> Unit
)

val LocalFullscreenController = staticCompositionLocalOf<FullscreenController?> { null }
