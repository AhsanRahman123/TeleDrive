package com.drdisagree.teledrive.desktop.media.player

import com.sun.jna.Native
import com.sun.jna.platform.win32.Dxva2
import com.sun.jna.platform.win32.PhysicalMonitorEnumerationAPI
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.KeyboardFocusManager
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Reads and writes the physical display backlight through Windows DDC/CI.
 *
 * This deliberately does not fall back to changing video pixels or applying a
 * colour/brightness overlay. A display that does not expose DDC/CI brightness
 * simply reports no hardware brightness control.
 */
internal object WindowsMonitorBrightness {

    data class Reading(
        val percent: Float,
        val monitorDescription: String
    )

    private const val MAX_PHYSICAL_MONITORS = 16
    private const val PRIMARY_MONITOR_FALLBACK = WinUser.MONITOR_DEFAULTTOPRIMARY
    private const val NEAREST_MONITOR = WinUser.MONITOR_DEFAULTTONEAREST

    @Synchronized
    fun read(): Reading? {
        if (!isWindows()) return null

        return runCatching {
            withPhysicalMonitors { monitors ->
                monitors.firstNotNullOfOrNull { monitor ->
                    val minimum = WinDef.DWORDByReference()
                    val current = WinDef.DWORDByReference()
                    val maximum = WinDef.DWORDByReference()

                    val ok = Dxva2.INSTANCE.GetMonitorBrightness(
                        monitor.hPhysicalMonitor,
                        minimum,
                        current,
                        maximum
                    ).booleanValue()

                    if (!ok) {
                        null
                    } else {
                        val minValue = minimum.value.toInt()
                        val currentValue = current.value.toInt()
                        val maxValue = maximum.value.toInt()
                        if (maxValue <= minValue) {
                            null
                        } else {
                            Reading(
                                percent = ((currentValue - minValue).toFloat() /
                                        (maxValue - minValue) * 100f).coerceIn(0f, 100f),
                                monitorDescription = String(monitor.szPhysicalMonitorDescription)
                                    .trimEnd('\u0000')
                                    .trim()
                            )
                        }
                    }
                }
            }
        }.getOrNull()
    }

    /**
     * [percent] is normalized to 0..100 and mapped to the monitor's reported
     * minimum/maximum. If a logical display exposes multiple physical panels,
     * each brightness-capable panel is updated.
     */
    @Synchronized
    fun setPercent(percent: Float): Boolean {
        if (!isWindows()) return false
        val normalized = percent.coerceIn(0f, 100f)

        return runCatching {
            withPhysicalMonitors { monitors ->
                var changedAtLeastOne = false

                monitors.forEach { monitor ->
                    val minimum = WinDef.DWORDByReference()
                    val current = WinDef.DWORDByReference()
                    val maximum = WinDef.DWORDByReference()

                    val readable = Dxva2.INSTANCE.GetMonitorBrightness(
                        monitor.hPhysicalMonitor,
                        minimum,
                        current,
                        maximum
                    ).booleanValue()

                    if (readable) {
                        val minValue = minimum.value.toInt()
                        val maxValue = maximum.value.toInt()
                        if (maxValue > minValue) {
                            val target = minValue +
                                    ((maxValue - minValue) * (normalized / 100f)).roundToInt()
                            val applied = Dxva2.INSTANCE.SetMonitorBrightness(
                                monitor.hPhysicalMonitor,
                                target
                            ).booleanValue()
                            changedAtLeastOne = changedAtLeastOne || applied
                        }
                    }
                }

                changedAtLeastOne
            } ?: false
        }.getOrDefault(false)
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun targetMonitor(): WinUser.HMONITOR? {
        // Use the monitor with the greatest intersection with the active
        // TeleDrive window. Do not silently change the primary monitor if the
        // app has no active window: that could affect the wrong display.
        val activeWindow = runCatching {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                ?: Window.getWindows().firstOrNull { it.isVisible && it.isActive }
        }.getOrNull() ?: return null

        return runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(activeWindow))
            User32.INSTANCE.MonitorFromWindow(hwnd, NEAREST_MONITOR)
        }.getOrNull()
    }

    private inline fun <T> withPhysicalMonitors(
        action: (Array<PhysicalMonitorEnumerationAPI.PHYSICAL_MONITOR>) -> T
    ): T? {
        val logicalMonitor = targetMonitor() ?: return null
        val countReference = WinDef.DWORDByReference()

        val countRead = Dxva2.INSTANCE.GetNumberOfPhysicalMonitorsFromHMONITOR(
            logicalMonitor,
            countReference
        ).booleanValue()

        if (!countRead) return null

        val count = countReference.value.toInt()
        if (count !in 1..MAX_PHYSICAL_MONITORS) return null

        @Suppress("UNCHECKED_CAST")
        val physicalMonitors =
            PhysicalMonitorEnumerationAPI.PHYSICAL_MONITOR().toArray(count)
                    as Array<PhysicalMonitorEnumerationAPI.PHYSICAL_MONITOR>

        val enumerated = Dxva2.INSTANCE.GetPhysicalMonitorsFromHMONITOR(
            logicalMonitor,
            count,
            physicalMonitors
        ).booleanValue()

        if (!enumerated) return null

        return try {
            action(physicalMonitors)
        } finally {
            // Physical monitor handles are owned by this call and must always
            // be released, including when reading or writing brightness fails.
            Dxva2.INSTANCE.DestroyPhysicalMonitors(count, physicalMonitors)
        }
    }
}
