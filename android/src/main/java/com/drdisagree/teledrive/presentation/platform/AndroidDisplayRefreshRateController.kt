package com.drdisagree.teledrive.presentation.platform

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Uses WindowManager.LayoutParams.preferredRefreshRate, Android's window-level preference
 * for apps that want to request only a refresh rate without changing display resolution.
 * Rates are matched against the display's supported modes before the preference is applied.
 */
class AndroidDisplayRefreshRateController(
    private val activity: Activity
) : DisplayRefreshRateController {

    override fun supportedRates(): Set<Int> = runCatching {
        val display = display()
        val currentMode = display.mode
        display.supportedModes
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .map { it.refreshRate.roundToInt() }
            .filter { it in selectableRates }
            .toSet()
    }.getOrDefault(emptySet())

    override fun requestRefreshRate(refreshRate: Int): Boolean {
        if (refreshRate !in selectableRates) return false

        return runCatching {
            val display = display()
            val currentMode = display.mode
            // Never trade resolution for refresh rate: only request a mode matching the current
            // physical resolution, so the app's layout and density remain unchanged.
            val matchingRate = display.supportedModes
                .asSequence()
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight &&
                        abs(it.refreshRate - refreshRate.toFloat()) <= RATE_TOLERANCE
                }
                .minByOrNull { abs(it.refreshRate - refreshRate.toFloat()) }
                ?.refreshRate
                ?: return@runCatching false

            val attributes = activity.window.attributes
            if (abs(attributes.preferredRefreshRate - matchingRate) > RATE_TOLERANCE) {
                attributes.preferredRefreshRate = matchingRate
                activity.window.attributes = attributes
            }
            true
        }.getOrDefault(false)
    }

    @Suppress("DEPRECATION")
    private fun display(): Display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display ?: activity.windowManager.defaultDisplay
        } else {
            activity.windowManager.defaultDisplay
        }

    private companion object {
        val selectableRates = setOf(60, 90, 120)
        const val RATE_TOLERANCE = 1.25f
    }
}
