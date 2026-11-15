package com.drdisagree.teledrive.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.drdisagree.teledrive.domain.model.AccentColor
import com.drdisagree.teledrive.domain.model.AccentLevel

val BlueLightColorScheme = lightColorScheme(
    primary = Color(0xFF1B6EF3),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E2FF),
    onPrimaryContainer = Color(0xFF001945),
    secondary = Color(0xFF575E71),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDBE2F9),
    onSecondaryContainer = Color(0xFF141B2C),
    tertiary = Color(0xFF00677E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFB4EBFF),
    onTertiaryContainer = Color(0xFF001F28),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF9F9FF),
    onBackground = Color(0xFF1A1B20),
    surface = Color(0xFFF9F9FF),
    onSurface = Color(0xFF1A1B20),
    surfaceVariant = Color(0xFFE1E2EC),
    onSurfaceVariant = Color(0xFF44464F),
    outline = Color(0xFF757780),
    outlineVariant = Color(0xFFC5C6D0),
    inverseSurface = Color(0xFF2F3036),
    inverseOnSurface = Color(0xFFF1F0F7),
    inversePrimary = Color(0xFFB0C6FF),
    surfaceDim = Color(0xFFD9D9E0),
    surfaceBright = Color(0xFFF9F9FF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF3F3FA),
    surfaceContainer = Color(0xFFEDEDF4),
    surfaceContainerHigh = Color(0xFFE8E7EE),
    surfaceContainerHighest = Color(0xFFE2E2E9)
)

val BlueDarkColorScheme = darkColorScheme(
    primary = Color(0xFFB0C6FF),
    onPrimary = Color(0xFF002D6E),
    primaryContainer = Color(0xFF00429B),
    onPrimaryContainer = Color(0xFFD9E2FF),
    secondary = Color(0xFFBFC6DC),
    onSecondary = Color(0xFF293042),
    secondaryContainer = Color(0xFF3F4759),
    onSecondaryContainer = Color(0xFFDBE2F9),
    tertiary = Color(0xFF5CD5F9),
    onTertiary = Color(0xFF003642),
    tertiaryContainer = Color(0xFF004E5F),
    onTertiaryContainer = Color(0xFFB4EBFF),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF111318),
    onBackground = Color(0xFFE2E2E9),
    surface = Color(0xFF111318),
    onSurface = Color(0xFFE2E2E9),
    surfaceVariant = Color(0xFF44464F),
    onSurfaceVariant = Color(0xFFC5C6D0),
    outline = Color(0xFF8F9099),
    outlineVariant = Color(0xFF44464F),
    inverseSurface = Color(0xFFE2E2E9),
    inverseOnSurface = Color(0xFF2F3036),
    inversePrimary = Color(0xFF1B6EF3),
    surfaceDim = Color(0xFF111318),
    surfaceBright = Color(0xFF37393E),
    surfaceContainerLowest = Color(0xFF0C0E13),
    surfaceContainerLow = Color(0xFF1A1B20),
    surfaceContainer = Color(0xFF1E1F25),
    surfaceContainerHigh = Color(0xFF282A2F),
    surfaceContainerHighest = Color(0xFF33353A)
)


/**
 * Uses the native light/dark surface palettes, changing only the accent roles.
 * Blue/Medium intentionally returns the existing scheme unchanged.
 */
fun appAccentColorScheme(
    darkTheme: Boolean,
    accentColor: AccentColor,
    accentLevel: AccentLevel
): ColorScheme {
    if (accentColor == AccentColor.BLUE && accentLevel == AccentLevel.MEDIUM) {
        return if (darkTheme) BlueDarkColorScheme else BlueLightColorScheme
    }

    val base = when (accentColor) {
        AccentColor.BLUE -> Color(0xFF1B6EF3)
        AccentColor.INDIGO -> Color(0xFF4F5FCB)
        AccentColor.PURPLE -> Color(0xFF7952B8)
        AccentColor.VIOLET -> Color(0xFF7654C8)
        AccentColor.MAGENTA -> Color(0xFFB03A9C)
        AccentColor.PINK -> Color(0xFFD94F98)
        AccentColor.ROSE -> Color(0xFFC43F69)
        AccentColor.CRIMSON -> Color(0xFFB52D4A)
        AccentColor.RED -> Color(0xFFC62828)
        AccentColor.CORAL -> Color(0xFFD65C4C)
        AccentColor.ORANGE -> Color(0xFFD16A10)
        AccentColor.AMBER -> Color(0xFFB97900)
        AccentColor.YELLOW -> Color(0xFFA18B00)
        AccentColor.LIME -> Color(0xFF758E00)
        AccentColor.OLIVE -> Color(0xFF657C36)
        AccentColor.GREEN -> Color(0xFF2F7D3A)
        AccentColor.EMERALD -> Color(0xFF078F65)
        AccentColor.MINT -> Color(0xFF218C78)
        AccentColor.TEAL -> Color(0xFF007F83)
        AccentColor.TURQUOISE -> Color(0xFF008A91)
        AccentColor.CYAN -> Color(0xFF00839A)
        AccentColor.SKY -> Color(0xFF2D83B8)
        AccentColor.SLATE -> Color(0xFF657185)
        AccentColor.BROWN -> Color(0xFF795548)
        AccentColor.GRAPHITE -> Color(0xFF50545D)
    }

    val seed = when (accentLevel) {
        AccentLevel.LIGHT -> mixAccent(base, Color.White, 0.22f)
        AccentLevel.MEDIUM -> base
        AccentLevel.DEEP -> mixAccent(base, Color.Black, 0.18f)
    }

    return if (darkTheme) {
        val primary = mixAccent(seed, Color.White, 0.30f)
        val primaryContainer = mixAccent(seed, Color.Black, 0.30f)
        val secondary = mixAccent(primary, Color(0xFFBEC6D8), 0.38f)
        val tertiary = mixAccent(primary, Color.White, 0.12f)
        BlueDarkColorScheme.copy(
            primary = primary,
            onPrimary = accentForeground(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = mixAccent(seed, Color.White, 0.82f),
            secondary = secondary,
            onSecondary = accentForeground(secondary),
            secondaryContainer = mixAccent(primaryContainer, Color.Black, 0.08f),
            onSecondaryContainer = mixAccent(secondary, Color.White, 0.82f),
            tertiary = tertiary,
            onTertiary = accentForeground(tertiary),
            tertiaryContainer = mixAccent(primaryContainer, Color.White, 0.06f),
            onTertiaryContainer = mixAccent(tertiary, Color.White, 0.82f),
            inversePrimary = seed
        )
    } else {
        val primary = seed
        val primaryContainer = mixAccent(seed, Color.White, 0.84f)
        val secondary = mixAccent(seed, Color(0xFF677080), 0.42f)
        val tertiary = mixAccent(seed, Color.White, 0.14f)
        BlueLightColorScheme.copy(
            primary = primary,
            onPrimary = accentForeground(primary),
            primaryContainer = primaryContainer,
            onPrimaryContainer = mixAccent(seed, Color.Black, 0.76f),
            secondary = secondary,
            onSecondary = accentForeground(secondary),
            secondaryContainer = mixAccent(secondary, Color.White, 0.86f),
            onSecondaryContainer = mixAccent(secondary, Color.Black, 0.76f),
            tertiary = tertiary,
            onTertiary = accentForeground(tertiary),
            tertiaryContainer = mixAccent(tertiary, Color.White, 0.84f),
            onTertiaryContainer = mixAccent(tertiary, Color.Black, 0.76f),
            inversePrimary = mixAccent(seed, Color.White, 0.28f)
        )
    }
}

private fun accentForeground(background: Color): Color =
    if (background.luminance() >= 0.48f) Color(0xFF14151A) else Color.White

private fun mixAccent(from: Color, to: Color, amount: Float): Color {
    val fraction = amount.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * fraction,
        green = from.green + (to.green - from.green) * fraction,
        blue = from.blue + (to.blue - from.blue) * fraction,
        alpha = from.alpha + (to.alpha - from.alpha) * fraction
    )
}
