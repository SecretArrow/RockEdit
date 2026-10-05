package com.secretarrow.rockedit.remote

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.github.mjdev.libaums.UsbMassStorageDevice
import com.secretarrow.rockedit.core.RemoteClient
import com.secretarrow.rockedit.core.UsbOtgLogic

/**
 * USB OTG device handling (v0.15.0). Owns the process-lifetime volume
 * session: the Storage Manager opens a device, the remote browser and the
 * content provider read through [Session.client] via the sentinel
 * connection id.
 *
 * Assumptions documented: only the first readable FAT volume of a device is
 * exposed (matching typical OTG sticks); closing happens when another device
 * is opened or the process dies — an unplugged device surfaces as an
 * IOException on the next operation, reported through the normal error path.
 */
object UsbOtgSupport {
    /** Process-lifetime open volume (null = no device currently opened). */
    object Session {
        @Volatile
        var client: RemoteClient? = null

        @Volatile
        var label: String = ""
    }

    /** Lists attached USB mass-storage devices (empty on any lookup problem). */
    fun attachedDevices(context: Context): List<UsbDevice> =
        try {
            val usbManager =
                context.getSystemService(Context.USB_SERVICE) as? UsbManager
                    ?: return emptyList()
            usbManager.deviceList.values.filter { device ->
                val hasInterface =
                    (0 until device.interfaceCount).any { i -> device.getInterface(i).interfaceClass == UsbOtgLogic.MSC_CLASS }
                UsbOtgLogic.isMassStorageDevice(device.deviceClass, hasInterface)
            }
        } catch (_: Exception) {
            emptyList()
        }

    /** True when the app already may talk to [device]. */
    fun hasPermission(
        context: Context,
        device: UsbDevice,
    ): Boolean =
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
            usbManager.hasPermission(device)
        } catch (_: Exception) {
            false
        }

    /**
     * Initializes [device] and opens its first FAT volume. Any failure
     * throws with an actionable message and leaves the previous session
     * untouched-or-closed deterministically.
     */
    fun open(
        context: Context,
        device: UsbDevice,
    ): RemoteClient {
        close()
        val usbDevice =
            UsbMassStorageDevice.getMassStorageDevices(context).firstOrNull { it.usbDevice.deviceId == device.deviceId }
                ?: throw IllegalStateException("the USB device '${device.deviceName}' is no longer attached")
        try {
            usbDevice.init()
        } catch (e: Exception) {
            throw IllegalStateException("initializing the USB device failed: ${UsbOtgLogic.describeError(e)}")
        }
        val fileSystem =
            usbDevice.volumes.firstOrNull()?.fileSystem
                ?: run {
                    try {
                        usbDevice.close()
                    } catch (_: Exception) {
                    }
                    throw IllegalStateException("no readable FAT partition found on '${device.deviceName}'")
                }
        val label = UsbOtgLogic.displayName(device.manufacturerName, device.productName, "USB storage")
        val client = UsbOtgRemoteClient(LibAumsVolumeFs(fileSystem), label)
        Session.client = client
        Session.label = label
        return client
    }

    /** Closes the current session (safe to call when nothing is open). */
    fun close() {
        val current = Session.client
        Session.client = null
        if (current != null) {
            try {
                current.close()
            } catch (_: Exception) {
                // Device may already be gone; the session reset above is what matters.
            }
        }
    }
}
