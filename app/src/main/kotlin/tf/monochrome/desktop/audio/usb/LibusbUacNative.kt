package tf.monochrome.desktop.audio.usb

import android.util.Log

/**
 * The JNI the desktop added to the libusb driver, beside the surface
 * [LibusbUacDriver] carries over from Android.
 *
 * Kept in its own object so `LibusbUacDriver.kt` keeps the Android file's
 * `external` list and stays a diff away from it. Both bind into the same
 * native driver instance (`native/usb/usb_jni.cpp`), so everything here acts
 * on the device [LibusbUacDriver.open] opened.
 *
 * What it adds, and why the Android surface did not need it:
 *  - **pause** that holds the queue: Media3 re-presented audio after a pause,
 *    so Android could flush the ring; the desktop engine does not re-present,
 *    and a flush would leave the position up to a ring behind the music;
 *  - **dispatched / queued frames** that leave out the silence the pump pads
 *    with, so the engine's clock cannot run ahead of what was written;
 *  - **writable frames**, so the sink packs only what the ring can take;
 *  - **in-flight frames**, the transfers queued ahead of the ring;
 *  - the **device descriptor** (manufacturer, product, serial, bcdUSB, class),
 *    which Android read off `UsbDevice` and libusb has to ask the device for;
 *  - a **claim probe**, the desktop's answer to "may we have it": Android asked
 *    the user; on Windows the answer is whether the DAC is bound to WinUSB.
 *
 * Every call is a safe no-op when the library is missing ([isAvailable]).
 */
internal object LibusbUacNative {

    private const val TAG = "LibusbUacNative"

    /** Fields in [nativeDeviceInfo]'s answer. */
    const val DEVICE_INFO_FIELDS = 7

    /**
     * False when `monochrome_usb` is missing, or is a build from before these
     * functions existed: JNI resolves a native method on its first call, so
     * the check calls one. A stale library then degrades like a missing one
     * (VID:PID names, the libusb path refused) instead of throwing
     * UnsatisfiedLinkError from the render thread.
     */
    val isAvailable: Boolean by lazy {
        try {
            UsbNativeLoader.ensureLoaded()
            nativeQueuedFrames()
            true
        } catch (e: LinkageError) {
            Log.w(TAG, "monochrome_usb unavailable or out of date (rebuild native/): ${e.message}")
            false
        }
    }

    /**
     * `[manufacturer, product, serial, bcdUSB "x.yz", class, subclass,
     * protocol]` for the attached device matching the ids, or null when none
     * matches or the library is missing. A string descriptor that could not be
     * read is empty. [bus] and [address] may be -1 for any.
     */
    fun deviceInfo(vendorId: Int, productId: Int, bus: Int, address: Int): Array<String>? =
        if (isAvailable) nativeDeviceInfo(vendorId, productId, bus, address) else null

    fun setPaused(paused: Boolean) {
        if (isAvailable) nativeSetPaused(paused)
    }

    /** Written frames the pump has taken from the ring since the stream started or was flushed. */
    fun dispatchedFrames(): Long = if (isAvailable) nativeDispatchedFrames() else 0L

    /** Written frames still waiting in the ring. */
    fun queuedFrames(): Long = if (isAvailable) nativeQueuedFrames() else 0L

    fun writableFrames(): Int = if (isAvailable) nativeWritableFrames() else 0

    fun inFlightFrames(): Int = if (isAvailable) nativeInFlightFrames() else 0

    /**
     * Null when every audio interface of the open device can be claimed (or a
     * stream already holds them); otherwise one line per interface that
     * refused, e.g. `interface 1 (AudioStreaming): Operation not supported`.
     */
    fun probeClaim(): String? {
        if (!isAvailable) return "the libusb driver is not available on this system"
        return nativeProbeClaim()?.takeIf { it.isNotBlank() }
    }

    @JvmStatic private external fun nativeDeviceInfo(vendorId: Int, productId: Int, bus: Int, address: Int): Array<String>?
    @JvmStatic private external fun nativeSetPaused(paused: Boolean)
    @JvmStatic private external fun nativeDispatchedFrames(): Long
    @JvmStatic private external fun nativeQueuedFrames(): Long
    @JvmStatic private external fun nativeWritableFrames(): Int
    @JvmStatic private external fun nativeInFlightFrames(): Int
    @JvmStatic private external fun nativeProbeClaim(): String?
}
