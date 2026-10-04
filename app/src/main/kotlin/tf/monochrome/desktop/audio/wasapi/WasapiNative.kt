package tf.monochrome.desktop.audio.wasapi

import tf.monochrome.desktop.platform.NativeLibraries
import java.nio.ByteBuffer

/**
 * JNI surface of native/wasapi/wasapi_jni.cpp: an event-driven WASAPI render
 * stream in shared or exclusive mode. Exclusive mode is the bit-perfect path
 * on Windows (the equivalent of Android's libusb bypass): the stream opens at
 * the file's sample rate and the DAC's own PCM width, past the Windows mixer.
 */
object WasapiNative {
    const val FORMAT_PCM16 = 0
    const val FORMAT_PCM24 = 1       // packed 3-byte samples
    const val FORMAT_PCM24_IN_32 = 2 // 24 valid bits, left-justified in 4 bytes
    const val FORMAT_PCM32 = 3
    const val FORMAT_FLOAT32 = 4

    /** Whether the library loaded; false on Linux and when the DLL is missing. */
    val isAvailable: Boolean by lazy {
        try {
            NativeLibraries.load("monochrome_wasapi")
            true
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    /** Each entry: "id\u0001friendly name\u0001<1 if default>". */
    @JvmStatic external fun nativeListDevices(): Array<String>?
    @JvmStatic external fun nativeDefaultDeviceId(): String?
    @JvmStatic external fun nativeIsFormatSupported(deviceId: String?, sampleRate: Int, channels: Int, format: Int, exclusive: Boolean): Boolean
    /** Returns a stream handle, or 0 with nativeLastError() set. deviceId null = default endpoint. */
    @JvmStatic external fun nativeOpen(deviceId: String?, sampleRate: Int, channels: Int, format: Int, exclusive: Boolean, bufferMillis: Int): Long
    /** [sampleRate, channels, format, bufferFrames, periodFrames, exclusive, bytesPerFrame]. */
    @JvmStatic external fun nativeDescribe(handle: Long): IntArray
    @JvmStatic external fun nativeStart(handle: Long): Boolean
    @JvmStatic external fun nativeStop(handle: Long): Boolean
    @JvmStatic external fun nativeReset(handle: Long): Boolean
    @JvmStatic external fun nativeFlush(handle: Long): Boolean
    /** Blocking; returns frames written or a negative error. The buffer must be direct. */
    @JvmStatic external fun nativeWrite(handle: Long, buffer: ByteBuffer, offsetBytes: Int, frames: Int): Int
    @JvmStatic external fun nativeAvailableFrames(handle: Long): Int
    @JvmStatic external fun nativeWrittenFrames(handle: Long): Long
    @JvmStatic external fun nativePlayedFrames(handle: Long): Long
    @JvmStatic external fun nativeLatencyFrames(handle: Long): Int
    @JvmStatic external fun nativeClose(handle: Long)
    @JvmStatic external fun nativeLastError(): String?
    /** Moves whenever an endpoint appears, disappears or the default changes. */
    @JvmStatic external fun nativeDeviceGeneration(): Long
    @JvmStatic external fun nativeEnterProAudio(): Boolean
    @JvmStatic external fun nativeLeaveProAudio()

    data class Device(val id: String, val name: String, val isDefault: Boolean)

    fun listDevices(): List<Device> {
        if (!isAvailable) return emptyList()
        return (nativeListDevices() ?: emptyArray()).mapNotNull { row ->
            val parts = row.split('\u0001')
            if (parts.size < 3) null else Device(parts[0], parts[1], parts[2] == "1")
        }
    }
}
