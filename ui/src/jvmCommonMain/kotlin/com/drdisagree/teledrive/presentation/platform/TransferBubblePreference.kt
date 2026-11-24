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
 * The widget itself is a platform overlay (an in-app chat-head or
 * bottom strip with live transfer progress and controls). This hook
 * exists so the shared Settings screen can offer its controls without
 * knowing about the platform store behind it. Desktop provides an
 * implementation backed by its java.util.prefs store family; Android
 * provides one backed by its DataStore settings store. A platform that
 * provides none (the local stays null) never renders the rows.
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
