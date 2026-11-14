package com.drdisagree.teledrive.presentation.components

import androidx.compose.runtime.compositionLocalOf

/**
 * True only inside the Android app. Shared screens can opt into phone-safe layouts without
 * changing the Windows desktop presentation.
 */
val LocalAndroidLayout = compositionLocalOf { false }
