package com.drdisagree.teledrive.presentation.platform

import androidx.compose.runtime.staticCompositionLocalOf

/** The transfer widget's shell (round 6): the floating bubble, or the bottom strip. */
enum class TransferWidgetStyle { BUBBLE, BOTTOM_STRIP }

/**
 * The transfer widget's colour preset (round 6). [APP_ACCENT] follows
 * the app's Material accent; the rest are fixed colours applied to both
 * shells' surfaces, rings and accents.
 */
enum class TransferWidgetColor { APP_ACCENT, ORANGE, BLUE, GREEN, PURPLE, GRAPHITE }

/**
 * Master switch and appearance for the floating transfer widget.
 *
 * The widget itself is a desktop-only overlay (an in-app chat-head or
 * bottom strip with live transfer progress and controls). This hook
 * exists so the shared Settings screen can offer its controls without
 * knowing about the desktop store behind it. Desktop provides an
 * implementation backed by its java.util.prefs store family; other
 * platforms do not provide one (the local stays null) and never render
 * the rows — Android behaviour is unchanged, exactly like
 * [TransfersSortPreference].
 */
interface TransferBubblePreference {
    fun isEnabled(): Boolean
    fun setEnabled(value: Boolean)

    /** Widget shell choice; defaults keep non-desktop platforms inert. */
    fun widgetStyle(): TransferWidgetStyle = TransferWidgetStyle.BUBBLE
    fun setWidgetStyle(style: TransferWidgetStyle) {}

    /** Widget colour preset; defaults keep non-desktop platforms inert. */
    fun widgetColor(): TransferWidgetColor = TransferWidgetColor.APP_ACCENT
    fun setWidgetColor(color: TransferWidgetColor) {}
}

val LocalTransferBubblePreference = staticCompositionLocalOf<TransferBubblePreference?> { null }
