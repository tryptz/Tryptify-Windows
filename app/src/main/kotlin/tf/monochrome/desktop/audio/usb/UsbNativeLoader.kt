package tf.monochrome.desktop.audio.usb

import tf.monochrome.desktop.platform.NativeLibraries

/**
 * Single entry point for loading libmonochrome_usb.so. Mirrors
 * DspNativeLoader so MonochromeApp can warm both linkers off the UI
 * thread.
 */
internal object UsbNativeLoader {
    init { NativeLibraries.load("monochrome_usb") }

    @JvmStatic
    fun ensureLoaded() { /* class-init runs the dlopen */ }
}
