package tf.monochrome.desktop.audio.usb

/**
 * Who the DAC actually is, known the moment the driver owns it.
 *
 * On Android everything here came off the framework's cached descriptors
 * (`UsbDevice`). The desktop asks the device through libusb
 * ([LibusbUacNative.deviceInfo]): the device descriptor (bcdUSB, class
 * triple) is always readable, but the manufacturer, product and serial are
 * string descriptors that need the device open, and on Windows a DAC still
 * bound to the Windows audio driver cannot be opened -- so before the WinUSB
 * binding those three are null and the display falls back to VID:PID, as it
 * always did for DACs that ship empty strings.
 *
 * It is available the instant [LibusbUacDriver.open] succeeds and is safe to
 * show before any stream is negotiated. The negotiated stream itself
 * (rate/bits/UAC version/clock) lives in [BypassDiagnostics]; this answers the
 * question one row before that: *which* DAC are we talking to.
 */
data class DacInfo(
    val manufacturer: String?,
    val product: String?,
    val serialNumber: String?,
    val vendorId: Int,
    val productId: Int,
    val deviceId: Int,
    /** USB standard version the device advertises (bcdUSB), e.g. "2.00" / "1.10"; null when not read. */
    val usbVersion: String?,
    /** bDeviceClass, or [UNKNOWN] when not read. */
    val deviceClass: Int,
    val deviceSubClass: Int,
    val deviceProtocol: Int,
) {
    /**
     * The name to show. "Focal Bathys" from manufacturer + product, with
     * graceful fallbacks for DACs that ship empty string descriptors (they
     * exist -- some cheap dongles report neither, in which case VID:PID is
     * the only honest identity we have), and for a DAC whose strings could
     * not be read (see the class KDoc).
     */
    val displayName: String
        get() = listOf(manufacturer?.takeIf { it.isNotBlank() },
                       product?.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(" ")
            .ifBlank { "USB DAC $idHex" }

    /** "1d6b:0100"-style identity for support requests and the log. */
    val idHex: String
        get() = "%04x:%04x".format(vendorId, productId)

    /** Compact descriptor line: identity + USB version, e.g.
     *  "1d6b:0100 · USB 2.00 · class 0/0/0". Parts not read are left out. */
    val descriptorLine: String
        get() = buildString {
            append(idHex)
            usbVersion?.takeIf { it.isNotBlank() }?.let {
                append(" · USB ")
                append(it)
            }
            if (deviceClass != UNKNOWN) {
                append(" · class ")
                append(deviceClass)
                append("/")
                append(deviceSubClass)
                append("/")
                append(deviceProtocol)
            }
        }

    companion object {
        /** A descriptor field the native side has not read. */
        const val UNKNOWN = -1

        /**
         * Everything libusb will tell about [device]: the enumeration's
         * VID/PID/bus address plus the descriptor read. Any part that cannot
         * be read degrades the display rather than failing (class KDoc).
         * Reads the device, so call it off the UI thread.
         */
        fun fromAttached(device: UsbAttachedDevice): DacInfo =
            fromDescriptor(
                device,
                LibusbUacNative.deviceInfo(device.vendorId, device.productId, device.bus, device.address),
            )

        /**
         * [device] with `nativeDeviceInfo`'s seven fields (manufacturer,
         * product, serial, bcdUSB "x.yz", class, subclass, protocol) laid
         * over it. Blank strings become null, an unparseable number
         * [UNKNOWN]; null or short [fields] -- no such device, no library --
         * leave the enumeration's identity alone.
         */
        fun fromDescriptor(device: UsbAttachedDevice, fields: Array<String>?): DacInfo {
            val f = fields?.takeIf { it.size >= LibusbUacNative.DEVICE_INFO_FIELDS }
            fun text(i: Int): String? = f?.get(i)?.trim()?.takeIf { it.isNotEmpty() }
            fun number(i: Int): Int = f?.get(i)?.trim()?.toIntOrNull() ?: UNKNOWN
            return DacInfo(
                manufacturer = text(0),
                product = text(1),
                serialNumber = text(2),
                vendorId = device.vendorId,
                productId = device.productId,
                deviceId = device.deviceId,
                usbVersion = text(3),
                deviceClass = number(4),
                deviceSubClass = number(5),
                deviceProtocol = number(6),
            )
        }
    }
}
