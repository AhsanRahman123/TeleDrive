package com.drdisagree.teledrive.desktop.ui

import androidx.compose.ui.graphics.Color
import com.drdisagree.teledrive.presentation.platform.TransferWidgetColor

/**
 * The transfer widget's colour presets (round 6), resolved to the actual
 * accent the shells draw with. Pure values so the mapping is pinned by
 * unit tests: [TransferWidgetColor.APP_ACCENT] resolves to null, which
 * the shells read as "use the app's Material accent" — the default, and
 * the only preset that follows the app's own accent settings.
 */
data class WidgetAccent(val accent: Color, val onAccent: Color)

fun widgetAccent(preset: TransferWidgetColor): WidgetAccent? = when (preset) {
    TransferWidgetColor.APP_ACCENT -> null
    // Pranto's brand orange — the Pranto Drive logo colour.
    TransferWidgetColor.ORANGE -> WidgetAccent(Color(0xFFFF7000), Color.White)
    TransferWidgetColor.BLUE -> WidgetAccent(Color(0xFF2563EB), Color.White)
    TransferWidgetColor.GREEN -> WidgetAccent(Color(0xFF16A34A), Color.White)
    TransferWidgetColor.PURPLE -> WidgetAccent(Color(0xFF7C3AED), Color.White)
    TransferWidgetColor.GRAPHITE -> WidgetAccent(Color(0xFF4B5563), Color.White)
}
