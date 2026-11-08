package com.drdisagree.teledrive.desktop.media.player

import com.sun.jna.Native
import com.sun.jna.platform.win32.Dxva2
import com.sun.jna.platform.win32.LowLevelMonitorConfigurationAPI.MC_VCP_CODE_TYPE
import com.sun.jna.platform.win32.PhysicalMonitorEnumerationAPI
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.KeyboardFocusManager
import java.awt.Window
import kotlin.math.roundToInt

/**
 * Reads and writes the physical display brightness through the same two
 * Windows DDC/CI paths used by Monitorian:
 *
 * 1. High-level GetMonitorBrightness / SetMonitorBrightness.
 * 2. Low-level VCP luminance (0x10) fallback for monitors that expose only
 *    low-level DDC/CI brightness.
 *
 * This never applies a brightness filter to the video image.
 */
internal object WindowsMonitorBrightness {

    data class Reading(
        val percent: Float,
        val monitorDescription: String
    )

    private data class RawBrightness(
        val minimum: Int,
        val current: Int,
        val maximum: Int,
        val highLevel: Boolean
    )

    private const val MAX_PHYSICAL_MONITORS = 16
    private const val NEAREST_MONITOR = WinUser.MONITOR_DEFAULTTONEAREST
    private const val VCP_LUMINANCE_CODE = 0x10

    @Synchronized
    fun read(): Reading? {
        if (!isWindows()) return null

        return runCatching {
            withPhysicalMonitors { monitors ->
                monitors.firstNotNullOfOrNull { monitor ->
                    val raw = readRawBrightness(monitor.hPhysicalMonitor)
                        ?: return@firstNotNullOfOrNull null
                    if (raw.maximum <= raw.minimum) return@firstNotNullOfOrNull null

                    Reading(
                        percent = ((raw.current - raw.minimum).toFloat() /
                            (raw.maximum - raw.minimum) * 100f).coerceIn(0f, 100f),
                        monitorDescription = String(monitor.szPhysicalMonitorDescription)
                            .trimEnd('\u0000')
                            .trim()
                    )
                }
            }
        }.getOrNull()
    }

    /**
     * [percent] is normalized to 0..100 and mapped to the monitor's actual
     * reported minimum/maximum. Every brightness-capable physical panel
     * belonging to the active logical display is updated.
     */
    @Synchronized
    fun setPercent(percent: Float): Boolean {
        if (!isWindows()) return false
        val normalized = percent.coerceIn(0f, 100f)

        return runCatching {
            withPhysicalMonitors { monitors ->
                var changedAtLeastOne = false

                monitors.forEach { monitor ->
                    val handle = monitor.hPhysicalMonitor
                    val raw = readRawBrightness(handle) ?: return@forEach
                    if (raw.maximum <= raw.minimum) return@forEach

                    val target = raw.minimum +
                        ((raw.maximum - raw.minimum) * (normalized / 100f)).roundToInt()

                    val applied = if (raw.highLevel) {
                        Dxva2.INSTANCE.SetMonitorBrightness(handle, target).booleanValue()
                    } else {
                        setVcpBrightness(handle, target)
                    }

                    // Some displays advertise high-level support but reject
                    // writes there. Give the VCP luminance path one fallback.
                    val fallbackApplied = if (!applied && raw.highLevel) {
                        val lowLevel = readVcpBrightness(handle)
                        lowLevel != null &&
                            lowLevel.maximum > lowLevel.minimum &&
                            setVcpBrightness(
                                handle,
                                lowLevel.minimum +
                                    ((lowLevel.maximum - lowLevel.minimum) *
                                        (normalized / 100f)).roundToInt()
                            )
                    } else {
                        false
                    }

                    changedAtLeastOne = changedAtLeastOne || applied || fallbackApplied
                }

                changedAtLeastOne
            } ?: false
        }.getOrDefault(false)
    }

    private fun readRawBrightness(handle: WinDef.HANDLE): RawBrightness? {
        val minimum = WinDef.DWORDByReference()
        val current = WinDef.DWORDByReference()
        val maximum = WinDef.DWORDByReference()

        val highLevelSupported = Dxva2.INSTANCE.GetMonitorBrightness(
            handle,
            minimum,
            current,
            maximum
        ).booleanValue()

        if (highLevelSupported) {
            val minValue = minimum.value.toInt()
            val currentValue = current.value.toInt()
            val maxValue = maximum.value.toInt()
            if (maxValue > minValue) {
                return RawBrightness(
                    minimum = minValue,
                    current = currentValue,
                    maximum = maxValue,
                    highLevel = true
                )
            }
        }

        return readVcpBrightness(handle)
    }

    private fun readVcpBrightness(handle: WinDef.HANDLE): RawBrightness? {
        val codeType = MC_VCP_CODE_TYPE.ByReference()
        val current = WinDef.DWORDByReference()
        val maximum = WinDef.DWORDByReference()

        val succeeded = Dxva2.INSTANCE.GetVCPFeatureAndVCPFeatureReply(
            handle,
            WinDef.BYTE(VCP_LUMINANCE_CODE.toLong()),
            codeType,
            current,
            maximum
        ).booleanValue()

        if (!succeeded) return null

        val currentValue = current.value.toInt()
        val maxValue = maximum.value.toInt()
        if (maxValue <= 0) return null

        return RawBrightness(
            minimum = 0,
            current = currentValue,
            maximum = maxValue,
            highLevel = false
        )
    }

    private fun setVcpBrightness(handle: WinDef.HANDLE, target: Int): Boolean =
        Dxva2.INSTANCE.SetVCPFeature(
            handle,
            WinDef.BYTE(VCP_LUMINANCE_CODE.toLong()),
            WinDef.DWORD(target.toLong())
        ).booleanValue()

    private fun isWindows(): Boolean =
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

    private fun targetMonitor(): WinUser.HMONITOR? {
        // Use the display intersecting the active TeleDrive window. If there
        // is no active window, do not risk changing an unrelated monitor.
        val activeWindow = runCatching {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
                ?: Window.getWindows().firstOrNull { it.isVisible && it.isActive }
        }.getOrNull() ?: return null

        return runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(activeWindow))
            User32.INSTANCE.MonitorFromWindow(hwnd, NEAREST_MONITOR)
        }.getOrNull()
    }

    private fun <T> withPhysicalMonitors(
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
            // Always release the handles opened by GetPhysicalMonitorsFromHMONITOR.
            Dxva2.INSTANCE.DestroyPhysicalMonitors(count, physicalMonitors)
        }
    }
}
