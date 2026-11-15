package com.drdisagree.teledrive.presentation.theme

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import com.drdisagree.teledrive.domain.model.AccentColor
import com.drdisagree.teledrive.domain.model.AccentLevel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TeleDriveTheme(
    darkTheme: Boolean = systemDarkTheme(),
    dynamicColor: Boolean = true,
    accentColor: AccentColor = AccentColor.BLUE,
    accentLevel: AccentLevel = AccentLevel.MEDIUM,
    content: @Composable () -> Unit
) {
    val colorScheme = platformColorScheme(darkTheme, dynamicColor)
        ?: appAccentColorScheme(darkTheme, accentColor, accentLevel)

    PlatformThemeSideEffects(darkTheme)

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = MotionScheme.expressive(),
        typography = TeleDriveTypography,
        shapes = TeleDriveShapes,
        content = content
    )
}
