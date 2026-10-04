package tf.monochrome.desktop.audio.usb

/**
 * Single entry point for loading libmonochrome_usb.so. Mirrors
 * DspNativeLoader so MonochromeApp can warm both linkers off the UI
 * thread.
 */
internal object UsbNativeLoader {
    init { System.loadLibrary("monochrome_usb") }

    @JvmStatic
    fun ensureLoaded() { /* class-init runs the dlopen */ }
}
