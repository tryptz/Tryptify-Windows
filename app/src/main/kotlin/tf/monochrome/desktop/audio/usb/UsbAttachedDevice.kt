package tf.monochrome.desktop.audio.usb

/**
 * A USB device as libusb enumerates it -- what [LibusbUacDriver.refreshDevices]
 * reports and what [LibusbUacDriver.open] takes.
 *
 * Desktop stand-in for Android's `UsbDevice`: the framework handed the app a
 * device object and a file descriptor; here the identity is the four numbers
 * libusb needs to open the device itself. [bus] and [address] tell two
 * identical DACs apart and change on every re-plug, so they are never
 * persisted -- a saved preference remembers [vendorId]:[productId] and takes
 * the first match.
 *
 * [hasAudioStreaming] is true when the device has an interface of class 0x01
 * (Audio) / subclass 0x02 (AudioStreaming): a DAC, as opposed to a hub, a
 * keyboard or a controller-only audio device.
 */
data class UsbAttachedDevice(
    val vendorId: Int,
    val productId: Int,
    val bus: Int,
    val address: Int,
    val hasAudioStreaming: Boolean,
) {
    /** "1d6b:0100"-style identity for logs and support requests. */
    val idHex: String get() = "%04x:%04x".format(vendorId, productId)

    /**
     * One number for the plug-in, the way Android's `UsbDevice.deviceId` was;
     * stable until the device is re-plugged.
     */
    val deviceId: Int get() = (bus shl 8) or (address and 0xFF)

    /** Same physical device, as far as the bus can tell. */
    fun sameDevice(other: UsbAttachedDevice): Boolean =
        vendorId == other.vendorId && productId == other.productId && bus == other.bus && address == other.address

    override fun toString(): String =
        "UsbAttachedDevice($idHex bus=$bus addr=$address${if (hasAudioStreaming) " audio" else ""})"

    companion object {
        /** Ints per device in the native flattening. */
        private const val STRIDE = 5

        /**
         * Decodes `nativeListDevices()`: `[vid, pid, bus, address,
         * hasAudioStreaming] x N`, as `native/usb/usb_jni.cpp` packs it. A
         * trailing partial record is ignored.
         */
        fun decodeAll(flat: IntArray?): List<UsbAttachedDevice> {
            if (flat == null || flat.size < STRIDE) return emptyList()
            val out = ArrayList<UsbAttachedDevice>(flat.size / STRIDE)
            var i = 0
            while (i + STRIDE <= flat.size) {
                out.add(
                    UsbAttachedDevice(
                        vendorId = flat[i],
                        productId = flat[i + 1],
                        bus = flat[i + 2],
                        address = flat[i + 3],
                        hasAudioStreaming = flat[i + 4] != 0,
                    )
                )
                i += STRIDE
            }
            return out
        }
    }
}
