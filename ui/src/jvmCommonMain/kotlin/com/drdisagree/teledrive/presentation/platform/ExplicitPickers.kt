package com.drdisagree.teledrive.presentation.platform

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Explicit picker variants for platforms that have two pickers
 * ([PlatformCapabilities.supportsExplicitPickerChoice]): the create menu
 * can then offer "Upload files" (built-in picker) and "Upload files
 * (Windows)" (native picker) as separate actions instead of hiding the
 * choice behind a settings toggle.
 *
 * All four default to null: platforms with a single picker (Android)
 * provide none of them and their UI never reads them. The classic
 * [LocalFilePicker], [LocalMultiFilePicker] and [LocalFolderPicker] are
 * unaffected and keep following the platform default.
 */
val LocalInAppMultiFilePicker = staticCompositionLocalOf<MultiFilePicker?> { null }

val LocalInAppFolderPicker = staticCompositionLocalOf<FolderPicker?> { null }

val LocalNativeMultiFilePicker = staticCompositionLocalOf<MultiFilePicker?> { null }

val LocalNativeFolderPicker = staticCompositionLocalOf<FolderPicker?> { null }
