package tf.monochrome.desktop.audio.usb

/**
 * Who the DAC actually is, known the moment the driver owns it.
 *
 * On Android everything here came off the framework's cached descriptors
 * (`UsbDevice`); the desktop reads the device through libusb, and the JNI
 * surface exports the numeric identity today -- vendor, product, bus address
 * -- but not yet the string descriptors or `bcdUSB` / device class, so those
 * fields are null or [UNKNOWN] until the native side grows a descriptor read.
 * The display degrades gracefully (VID:PID is the fallback it always had).
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
    /** USB standard version the device advertises, e.g. "2.00" / "1.10"; null when not read. */
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
     * the only honest identity we have), and for the desktop until the
     * strings are read at all.
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
         * Identity from the libusb enumeration alone: VID, PID and the bus
         * address. Strings, USB version and class come later, when the JNI
         * exports the device descriptor; see the class KDoc.
         */
        fun fromAttached(device: UsbAttachedDevice): DacInfo = DacInfo(
            manufacturer = null,
            product = null,
            serialNumber = null,
            vendorId = device.vendorId,
            productId = device.productId,
            deviceId = device.deviceId,
            usbVersion = null,
            deviceClass = UNKNOWN,
            deviceSubClass = UNKNOWN,
            deviceProtocol = UNKNOWN,
        )
    }
}
