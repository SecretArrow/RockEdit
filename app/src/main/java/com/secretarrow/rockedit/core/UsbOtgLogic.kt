package com.secretarrow.rockedit.core

/**
 * Pure helpers for the USB OTG feature (v0.15.0). The Android/libaums side
 * extracts the raw facts (class codes, device names) and this object makes
 * every decision, so the logic is unit testable without hardware.
 */
object UsbOtgLogic {
    /** Sentinel connection id used by the browser/provider for USB OTG. */
    const val SENTINEL_CONNECTION_ID = -2L

    /** USB mass-storage class code (0x08). */
    const val MSC_CLASS = 0x08

    /**
     * True when a device is treated as USB mass storage: either the device
     * declares the MSC class itself or one of its interfaces does (common
     * for composite devices). Both inputs may be any value incl. 0/-1.
     */
    fun isMassStorageDevice(
        deviceClass: Int,
        hasMassStorageInterface: Boolean,
    ): Boolean = deviceClass == MSC_CLASS || hasMassStorageInterface

    /**
     * Builds a stable display name from vendor/product; every null/blank
     * part collapses into [fallback], so the label is never blank.
     */
    fun displayName(
        vendor: String?,
        product: String?,
        fallback: String,
    ): String {
        val v = vendor?.trim().orEmpty()
        val p = product?.trim().orEmpty()
        val combined = listOf(v, p).filter { it.isNotEmpty() }.joinToString(" ").trim()
        return combined.ifEmpty { fallback.ifBlank { "USB storage" } }
    }

    /** Normalizes a virtual path to the form used inside the volume (no leading slash). */
    fun volumePath(path: String): String = RemotePath.normalize(path).trim('/')

    /** Wraps any throwable into a short, user-presentable reason string. */
    fun describeError(error: Throwable?): String =
        when (error) {
            null -> "unknown USB error"
            is java.io.IOException -> "USB I/O error: ${error.message ?: "device rejected the request"}"
            is SecurityException -> "USB permission was not granted"
            else -> "${error.javaClass.simpleName}: ${error.message ?: "unexpected error"}"
        }
}
