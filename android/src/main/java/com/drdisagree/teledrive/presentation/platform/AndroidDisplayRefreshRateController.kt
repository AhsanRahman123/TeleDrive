package com.drdisagree.teledrive.presentation.platform

import android.app.Activity
import android.os.Build
import android.view.Display
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Uses WindowManager.LayoutParams.preferredDisplayModeId so Android can switch the Activity
 * to a real display mode. This is intentionally not Surface.setFrameRate(), which only asks
 * a surface to present at a cadence and does not by itself select the panel refresh mode.
 */
class AndroidDisplayRefreshRateController(
    private val activity: Activity
) : DisplayRefreshRateController {

    override fun supportedRates(): Set<Int> {
        val display = display()
        val currentMode = display.mode
        return display.supportedModes
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight
            }
            .map { it.refreshRate.roundToInt() }
            .filter { it in selectableRates }
            .toSet()
    }

    override fun requestRefreshRate(refreshRate: Int): Boolean {
        if (refreshRate !in selectableRates) return false

        val display = display()
        val currentMode = display.mode
        // Never trade resolution for refresh rate: only request a mode matching the current
        // physical resolution, so the app's layout and density remain unchanged.
        val matchingMode = display.supportedModes
            .filter {
                it.physicalWidth == currentMode.physicalWidth &&
                    it.physicalHeight == currentMode.physicalHeight &&
                    abs(it.refreshRate - refreshRate.toFloat()) <= RATE_TOLERANCE
            }
            .minByOrNull { abs(it.refreshRate - refreshRate.toFloat()) }
            .firstOrNull()
            ?: return false

        return runCatching {
            val attributes = activity.window.attributes
            if (attributes.preferredDisplayModeId != matchingMode.modeId) {
                attributes.preferredDisplayModeId = matchingMode.modeId
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
