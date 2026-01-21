package com.example.usbcam.utils

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.example.usbcam.Config

object UsbHelper {
    private const val TAG = "UsbHelper"

    /**
     * Finds all connected USB devices that are NOT cameras.
     * @return List of device names/descriptions that might conflict.
     */
    fun findConflictingUsbDevices(context: Context): List<String> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val deviceList = usbManager.deviceList
        val conflicts = mutableListOf<String>()

        for (device in deviceList.values) {
            if (!isCameraDevice(device)) {
                val description =
                        "Device ID: ${device.deviceId}, VID: ${device.vendorId}, PID: ${device.productId} (${getDeviceType(device)})"
                conflicts.add(description)
                Log.d(TAG, "Conflicting device found: $description")
            }
        }
        return conflicts
    }

    /** Checks if a device is likely a USB Camera (UVC). */
    fun isCameraDevice(device: UsbDevice): Boolean {
        // UVC devices have class 0x0E (Video)
        if (device.deviceClass == Config.USB_CLASS_VIDEO) return true

        // Some cameras define class at interface level
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == Config.USB_CLASS_VIDEO) return true
        }
        return false
    }

    private fun getDeviceType(device: UsbDevice): String {
        return when (device.deviceClass) {
            UsbConstants.USB_CLASS_HID -> "HID (Scanner/Keyboard)"
            UsbConstants.USB_CLASS_COMM -> "CDC (Serial)"
            UsbConstants.USB_CLASS_PER_INTERFACE -> {
                // Check interfaces
                for (i in 0 until device.interfaceCount) {
                    val iface = device.getInterface(i)
                    when (iface.interfaceClass) {
                        UsbConstants.USB_CLASS_HID -> return "HID"
                        10 -> return "Serial (Data)" // USB_CLASS_CDC_DATA
                        0xFF -> return "Vendor Specific (RFID?)"
                    }
                }
                "Interface Defined"
            }
            0xFF -> "Vendor Specific"
            else -> "Unknown (Class: ${device.deviceClass})"
        }
    }
}
